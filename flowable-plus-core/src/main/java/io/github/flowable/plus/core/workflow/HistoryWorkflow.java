package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.enums.CommentTypeConverter;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.spi.IdentityResolver;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.CountersignSubRecord;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审批历史查询工作流模块，实现 ADR-0009 三级 Comment→Action 推断策略，
 * 从三张历史表聚合完整审批时间线。
 *
 * @author flowable-plus
 */
public class HistoryWorkflow {

    private static final Logger log = LoggerFactory.getLogger(HistoryWorkflow.class);

    /** 活动类型白名单：仅保留这些类型的历史活动实例 */
    private static final Set<String> INCLUDED_ACTIVITY_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("startEvent", "userTask")));

    /** multiInstanceBody 模式匹配，用于识别多实例体活动类型 */
    private static final String MULTI_INSTANCE_BODY_PATTERN = "multiinstance";

    private final HistoryService historyService;
    private final TaskService taskService;
    private final BpmnModelCache bpmnModelCache;
    private final MultiInstanceDetector multiInstanceDetector;
    private final IdentityResolver identityResolver;

    public HistoryWorkflow(HistoryService historyService, TaskService taskService,
                           BpmnModelCache bpmnModelCache, MultiInstanceDetector multiInstanceDetector,
                           IdentityResolver identityResolver) {
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        if (multiInstanceDetector == null) {
            throw new IllegalArgumentException("MultiInstanceDetector 不可为 null");
        }
        if (identityResolver == null) {
            throw new IllegalArgumentException("IdentityResolver 不可为 null");
        }
        this.historyService = historyService;
        this.taskService = taskService;
        this.bpmnModelCache = bpmnModelCache;
        this.multiInstanceDetector = multiInstanceDetector;
        this.identityResolver = identityResolver;
    }

    // ======================== 主方法 ========================

    /**
     * 获取指定流程实例的完整审批历史时间线。
     *
     * @param processInstanceId 流程实例 ID
     * @return 审批历史记录列表，按活动开始时间升序排列
     */
    public List<ApprovalRecordVO> getApprovalHistory(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }

        // 1. 验证流程实例存在，获取 startUserId 和 processDefinitionId
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi == null) {
            throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
        }
        String startUserId = hpi.getStartUserId();
        String processDefinitionId = hpi.getProcessDefinitionId();

        // 2. 查询并过滤 HistoricActivityInstance（三次批量查询之一）
        List<HistoricActivityInstance> allActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list();
        List<HistoricActivityInstance> filteredActivities = filterActivities(allActivities);

        // 3. 查询 HistoricTaskInstance（三次批量查询之二）
        List<HistoricTaskInstance> historicTasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime().asc()
                .list();
        Map<String, HistoricTaskInstance> taskMap = new HashMap<>();
        for (HistoricTaskInstance task : historicTasks) {
            taskMap.put(task.getId(), task);
        }

        // 4. 查询 Comment（三次批量查询之三），按 taskId 分组，时间倒序
        List<Comment> comments = taskService.getProcessInstanceComments(processInstanceId);
        Map<String, List<Comment>> commentsByTaskId = groupCommentsByTaskIdDesc(comments);

        // 5. 构建审批记录列表，贪心归组会签
        List<ApprovalRecordVO> records = new ArrayList<>();
        int i = 0;
        while (i < filteredActivities.size()) {
            HistoricActivityInstance activity = filteredActivities.get(i);

            if (isStartEvent(activity)) {
                // START 特殊处理（ADR-0009 三级）
                records.add(buildStartRecord(activity, startUserId));
                i++;
            } else {
                String activityId = activity.getActivityId();
                String baseId = baseActivityId(activityId);

                boolean isMultiInstance = activity.getProcessDefinitionId() != null
                        && multiInstanceDetector.isMultiInstanceNode(
                        activity.getProcessDefinitionId(), baseId);

                if (isMultiInstance) {
                    // 贪心吞噬会签归组
                    List<HistoricActivityInstance> miGroup = new ArrayList<>();
                    while (i < filteredActivities.size()
                            && isSameMultiInstanceGroup(
                            filteredActivities.get(i).getActivityId(),
                            baseId, processDefinitionId)) {
                        miGroup.add(filteredActivities.get(i));
                        i++;
                    }
                    records.add(buildMultiInstanceRecord(miGroup, taskMap, commentsByTaskId));
                } else {
                    // 普通用户任务节点
                    records.add(buildNormalRecord(activity, taskMap, commentsByTaskId));
                    i++;
                }
            }
        }

        // 6. 全局排序：按 startTime 升序
        records.sort(Comparator.comparing(ApprovalRecordVO::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return records;
    }

    // ======================== 活动类型过滤 ========================

    /**
     * 过滤 HistoricActivityInstance，仅保留 startEvent、userTask 和 multiInstanceBody 类型。
     */
    private List<HistoricActivityInstance> filterActivities(
            List<HistoricActivityInstance> activities) {
        return activities.stream()
                .filter(a -> isIncludedActivityType(a.getActivityType()))
                .collect(Collectors.toList());
    }

    private boolean isIncludedActivityType(String activityType) {
        if (activityType == null) {
            return false;
        }
        return INCLUDED_ACTIVITY_TYPES.contains(activityType)
                || activityType.toLowerCase().contains(MULTI_INSTANCE_BODY_PATTERN);
    }

    // ======================== 多实例子组成员判断 ========================

    /**
     * 判断当前活动是否与前一个活动属于同一个多实例组。
     * 比较去除了 #multiInstance 后缀的 activityId。
     */
    private boolean isSameMultiInstanceGroup(String currentActivityId,
                                              String baseId,
                                              String processDefinitionId) {
        // 先检查是否为多实例节点
        String currentBaseId = baseActivityId(currentActivityId);
        if (!baseId.equals(currentBaseId)) {
            return false;
        }
        // 再次确认 BPMN 模型中确实有多实例配置
        return multiInstanceDetector.isMultiInstanceNode(processDefinitionId, currentBaseId);
    }

    /**
     * 去除 activityId 中的多实例后缀（# 及之后的内容）。
     */
    private String baseActivityId(String activityId) {
        if (activityId == null) {
            return null;
        }
        int idx = activityId.indexOf('#');
        return idx > 0 ? activityId.substring(0, idx) : activityId;
    }

    // ======================== Comment 分组 ========================

    /**
     * 按 taskId 对 Comment 进行分组，每组按时间倒序排列（用于特征提取）。
     */
    private Map<String, List<Comment>> groupCommentsByTaskIdDesc(List<Comment> comments) {
        Map<String, List<Comment>> result = new HashMap<>();
        if (comments == null || comments.isEmpty()) {
            return result;
        }
        // getProcessInstanceComments 返回按时间升序的结果
        for (Comment comment : comments) {
            String taskId = comment.getTaskId();
            if (taskId != null) {
                result.computeIfAbsent(taskId, k -> new ArrayList<>()).add(comment);
            }
        }
        // 每组内部按时间倒序
        for (List<Comment> list : result.values()) {
            list.sort((a, b) -> b.getTime().compareTo(a.getTime()));
        }
        return result;
    }

    // ======================== Comment → ApprovalAction 三级推断 (ADR-0009) ========================

    /**
     * 三级 Comment→Action 推断策略（ADR-0009）：
     * 1. 特征提取：按 Comment 时间倒序扫描，取第一个匹配 CommentType 的值
     * 2. DeleteReason 兜底：无匹配 Comment 时，读取 HistoricTaskInstance.deleteReason
     * 3. START 特殊处理：由 startEvent + startUserId 构造，不经过此方法
     */
    private ApprovalAction inferAction(HistoricTaskInstance task,
                                        Map<String, List<Comment>> commentsByTaskId) {
        // 一级：特征提取（时间倒序扫描 Comment）
        List<Comment> taskComments = commentsByTaskId.getOrDefault(task.getId(), Collections.emptyList());
        for (Comment comment : taskComments) {
            String typeStr = comment.getType();
            if (typeStr != null) {
                try {
                    CommentType ct = CommentType.valueOf(typeStr);
                    return CommentTypeConverter.toApprovalAction(ct);
                } catch (IllegalArgumentException ignored) {
                    // 非业务类型 Comment（如普通留言），跳过继续尝试
                }
            }
        }

        // 二级：DeleteReason 兜底
        String deleteReason = task.getDeleteReason();
        if ("completed".equals(deleteReason)) {
            return ApprovalAction.AGREE;
        }
        if ("deleted".equals(deleteReason)) {
            // deleted 场景：需结合上下文判断（撤回/撤销/驳回）
            // 这里做简单判断：若 task 已被 delete，优先返回 REJECT
            return ApprovalAction.REJECT;
        }
        if (deleteReason != null && !deleteReason.isEmpty()) {
            // 其他非标准 deleteReason（如管理员强杀），标记为终止
            log.warn("未知 deleteReason: taskId={}, deleteReason={}", task.getId(), deleteReason);
            return ApprovalAction.TERMINATE;
        }

        // 三级默认：活跃节点（无 deleteReason，无结束时间），action 为 null
        return null;
    }

    /**
     * 从 Comment 中提取审批意见文本。
     * 取时间倒序第一个匹配 CommentType 的业务 Comment 的 fullMessage。
     */
    private String extractCommentText(HistoricTaskInstance task,
                                       Map<String, List<Comment>> commentsByTaskId) {
        List<Comment> taskComments = commentsByTaskId.getOrDefault(task.getId(), Collections.emptyList());
        for (Comment comment : taskComments) {
            String typeStr = comment.getType();
            if (typeStr != null) {
                try {
                    CommentType.valueOf(typeStr);
                    return comment.getFullMessage();
                } catch (IllegalArgumentException ignored) {
                    // 非业务类型，跳过
                }
            }
        }
        return null;
    }

    // ======================== 记录构建 ========================

    /**
     * 构建 START 记录（ADR-0009 三级特殊处理）。
     * 从 startEvent 活动实例 + HistoricProcessInstance.startUserId 构造。
     */
    private ApprovalRecordVO buildStartRecord(HistoricActivityInstance startActivity,
                                               String startUserId) {
        String actorName = identityResolver.resolve(startUserId);
        return ApprovalRecordVO.builder()
                .taskId(null)
                .nodeId(startActivity.getActivityId())
                .nodeName(startActivity.getActivityName())
                .action(ApprovalAction.START)
                .actorId(startUserId)
                .actorName(actorName)
                .comment(null)
                .startTime(startActivity.getStartTime())
                .endTime(startActivity.getEndTime())
                .duration(calcDuration(startActivity.getStartTime(), startActivity.getEndTime()))
                .countersignRecords(null)
                .build();
    }

    /**
     * 构建普通用户任务节点记录。
     */
    private ApprovalRecordVO buildNormalRecord(HistoricActivityInstance activity,
                                                Map<String, HistoricTaskInstance> taskMap,
                                                Map<String, List<Comment>> commentsByTaskId) {
        String taskId = activity.getTaskId();
        HistoricTaskInstance task = taskId != null ? taskMap.get(taskId) : null;

        if (task == null) {
            return buildRecordWithoutTask(activity);
        }

        ApprovalAction action = inferAction(task, commentsByTaskId);
        String comment = extractCommentText(task, commentsByTaskId);
        String actorName = identityResolver.resolve(task.getAssignee());

        return ApprovalRecordVO.builder()
                .taskId(task.getId())
                .nodeId(activity.getActivityId())
                .nodeName(activity.getActivityName())
                .action(action)
                .actorId(task.getAssignee())
                .actorName(actorName)
                .comment(comment)
                .startTime(activity.getStartTime())
                .endTime(task.getEndTime())
                .duration(calcDuration(task.getCreateTime(), task.getEndTime()))
                .countersignRecords(null)
                .build();
    }

    /**
     * 构建无关联 HistoricTaskInstance 的记录（异常情况兜底）。
     */
    private ApprovalRecordVO buildRecordWithoutTask(HistoricActivityInstance activity) {
        return ApprovalRecordVO.builder()
                .taskId(null)
                .nodeId(activity.getActivityId())
                .nodeName(activity.getActivityName())
                .action(null)
                .actorId(null)
                .actorName(null)
                .comment(null)
                .startTime(activity.getStartTime())
                .endTime(activity.getEndTime())
                .duration(calcDuration(activity.getStartTime(), activity.getEndTime()))
                .countersignRecords(null)
                .build();
    }

    /**
     * 构建多实例（会签）父记录 + 子记录。
     * 贪心吞噬算法：第一个活动为体（body），后续活动为子实例。
     */
    private ApprovalRecordVO buildMultiInstanceRecord(
            List<HistoricActivityInstance> miGroup,
            Map<String, HistoricTaskInstance> taskMap,
            Map<String, List<Comment>> commentsByTaskId) {

        // 构建子记录列表
        List<CountersignSubRecord> subRecords = new ArrayList<>();
        for (HistoricActivityInstance activity : miGroup) {
            String taskId = activity.getTaskId();
            if (taskId == null) {
                continue; // 体活动没有关联任务，跳过
            }
            HistoricTaskInstance task = taskMap.get(taskId);

            ApprovalAction action = task != null ? inferAction(task, commentsByTaskId) : null;
            String comment = task != null ? extractCommentText(task, commentsByTaskId) : null;
            String actorName = task != null ? identityResolver.resolve(task.getAssignee()) : null;

            CountersignSubRecord subRecord = CountersignSubRecord.builder()
                    .taskId(taskId)
                    .nodeId(activity.getActivityId())
                    .nodeName(activity.getActivityName())
                    .action(action)
                    .actorId(task != null ? task.getAssignee() : null)
                    .actorName(actorName)
                    .comment(comment)
                    .startTime(task != null ? task.getCreateTime() : activity.getStartTime())
                    .endTime(task != null ? task.getEndTime() : activity.getEndTime())
                    .duration(task != null
                            ? calcDuration(task.getCreateTime(), task.getEndTime())
                            : calcDuration(activity.getStartTime(), activity.getEndTime()))
                    .build();
            subRecords.add(subRecord);
        }

        // 父节点聚合信息
        HistoricActivityInstance firstActivity = miGroup.get(0);
        Date parentStartTime = miGroup.stream()
                .map(HistoricActivityInstance::getStartTime)
                .filter(d -> d != null)
                .min(Comparator.naturalOrder())
                .orElse(firstActivity.getStartTime());

        boolean allFinished = subRecords.stream().allMatch(s -> s.getEndTime() != null);
        Date parentEndTime = allFinished
                ? subRecords.stream()
                    .map(CountersignSubRecord::getEndTime)
                    .max(Comparator.naturalOrder())
                    .orElse(null)
                : null;

        return ApprovalRecordVO.builder()
                .taskId(null)
                .nodeId(baseActivityId(firstActivity.getActivityId()))
                .nodeName(firstActivity.getActivityName())
                .action(null)
                .actorId(null)
                .actorName(null)
                .comment(null)
                .startTime(parentStartTime)
                .endTime(parentEndTime)
                .duration(calcDuration(parentStartTime, parentEndTime))
                .countersignRecords(subRecords)
                .build();
    }

    // ======================== 工具方法 ========================

    private boolean isStartEvent(HistoricActivityInstance activity) {
        return "startEvent".equals(activity.getActivityType());
    }

    private Long calcDuration(Date start, Date end) {
        if (start == null || end == null) {
            return null;
        }
        return end.getTime() - start.getTime();
    }
}
