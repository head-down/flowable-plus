package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.spi.IdentityResolver;
import io.github.flowable.plus.core.support.ActionInferenceStrategy;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.CountersignSubRecord;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    /** 活动类型白名单：仅保留这些类型的历史活动实例 */
    private static final Set<String> INCLUDED_ACTIVITY_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("startEvent", "userTask")));

    /**
     * multiInstanceBody 模式匹配，用于识别多实例体活动类型。
     * Flowable 6.8.0 中不存在此类型（多实例节点的历史活动即子实例的 userTask），
     * 保留作未来版本防御，见 {@link #isMultiInstanceBodyActivity}。
     */
    private static final String MULTI_INSTANCE_BODY_PATTERN = "multiinstance";

    /** Task 局部变量名：会签轮次索引 */
    private static final String CS_ROUND_INDEX_VAR = "csRoundIndex";

    private final HistoryService historyService;
    private final TaskService taskService;
    private final BpmnModelCache bpmnModelCache;
    private final MultiInstanceDetector multiInstanceDetector;
    private final IdentityResolver identityResolver;
    private final ActionInferenceStrategy actionInferenceStrategy;

    public HistoryWorkflow(HistoryService historyService, TaskService taskService,
                           BpmnModelCache bpmnModelCache, MultiInstanceDetector multiInstanceDetector,
                           IdentityResolver identityResolver,
                           ActionInferenceStrategy actionInferenceStrategy) {
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
        if (actionInferenceStrategy == null) {
            throw new IllegalArgumentException("ActionInferenceStrategy 不可为 null");
        }
        this.historyService = historyService;
        this.taskService = taskService;
        this.bpmnModelCache = bpmnModelCache;
        this.multiInstanceDetector = multiInstanceDetector;
        this.identityResolver = identityResolver;
        this.actionInferenceStrategy = actionInferenceStrategy;
    }

    // ======================== 主方法 ========================

    /**
     * 获取指定流程实例的完整审批历史时间线。
     *
     * @param processInstanceId 流程实例 ID
     * @return 审批历史记录列表，按活动开始时间升序排列
     * @throws IllegalArgumentException 如果 processInstanceId 为 null 或空字符串
     * @throws NotFoundException 如果指定的流程实例不存在
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
                    // 贪心吞噬会签归组（同一 baseId 的全部活动归入一组；taskId 为 null 的无任务活动在构建时被过滤）
                    List<HistoricActivityInstance> miGroup = new ArrayList<>();
                    do {
                        miGroup.add(filteredActivities.get(i));
                        i++;
                    } while (i < filteredActivities.size()
                            && isSameMultiInstanceGroup(
                            filteredActivities.get(i).getActivityId(),
                            baseId, processDefinitionId));
                    records.addAll(buildMultiInstanceRecords(
                            miGroup, taskMap, commentsByTaskId, processInstanceId));
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
     * 过滤 HistoricActivityInstance，仅保留 startEvent、userTask 类型
     * （以及理论上的 multiInstanceBody 类型——6.8.0 下不存在，见 {@link #isMultiInstanceBodyActivity}）。
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
                || isMultiInstanceBodyActivity(activityType);
    }

    // ======================== 多实例子组成员判断 ========================

    /**
     * 判断当前活动是否与当前多实例组属于同一组。
     * 仅比较去除多实例后缀的 activityId；轮次边界统一由 splitIntoExplicitRounds 按 csRoundIndex 处理（ADR-0020）。
     */
    private boolean isSameMultiInstanceGroup(String currentActivityId,
                                              String baseId,
                                              String processDefinitionId) {
        // ADR-0020: 轮次边界统一由 splitIntoExplicitRounds 按 csRoundIndex 拆分，
        // 不依赖任何"体活动"边界（6.8.0 无 miBody 历史活动，子实例记录即 userTask）。
        String currentBaseId = baseActivityId(currentActivityId);
        if (!baseId.equals(currentBaseId)) {
            return false;
        }
        return multiInstanceDetector.isMultiInstanceNode(processDefinitionId, currentBaseId);
    }

    /**
     * 检查 activityType 是否为 multiInstanceBody 类型。
     *
     * <p><b>死代码说明（2026-08-08）</b>：Flowable 6.8.0 中不存在 multiInstanceBody
     * 历史活动——实测引擎源码 {@code multiInstanceBody} 字符串零出现，多实例节点的
     * {@code ACT_HI_ACTINST} 记录即各子实例的 userTask 活动，此方法在 6.8.0 下恒返回
     * false，{@link #isIncludedActivityType} 的该分支永不命中。<b>保留</b>作为未来
     * Flowable 版本（6.8.1+ 存在 MI 跳转回归，升级时需回归）引入新活动类型时的防御性
     * 过滤，勿删除。</p>
     */
    private boolean isMultiInstanceBodyActivity(String activityType) {
        return activityType != null
                && activityType.toLowerCase().contains(MULTI_INSTANCE_BODY_PATTERN);
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
     * 从 Comment 中提取审批意见文本。
     * 取时间倒序第一个匹配 CommentType 的业务 Comment 的 fullMessage。
     */
    private String extractCommentText(String taskId, Map<String, List<Comment>> commentsByTaskId) {
        List<Comment> taskComments = commentsByTaskId.getOrDefault(taskId, Collections.emptyList());
        Comment businessComment = actionInferenceStrategy.findFirstBusinessComment(taskComments);
        return businessComment != null ? businessComment.getFullMessage() : null;
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

        List<Comment> taskComments = commentsByTaskId.getOrDefault(task.getId(), Collections.emptyList());
        ApprovalAction action = actionInferenceStrategy.inferAction(
                task.getId(), task.getDeleteReason(), taskComments);
        String comment = extractCommentText(task.getId(), commentsByTaskId);
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
     * 构建多实例（会签）记录。
     * 贪心吞噬算法归组一组活动后，按 csRoundIndex Task 局部变量切分轮次。
     *
     * <p>分组策略：
     * <ol>
     *   <li>有 csRoundIndex 显式值 → 直接使用</li>
     *   <li>无显式轮次 → 默认 round = 0（原始审批人隐式轮次）</li>
     * </ol>
     *
     * @return 一轮或多轮会签对应的审批记录列表
     */
    private List<ApprovalRecordVO> buildMultiInstanceRecords(
            List<HistoricActivityInstance> miGroup,
            Map<String, HistoricTaskInstance> taskMap,
            Map<String, List<Comment>> commentsByTaskId,
            String processInstanceId) {

        // 1. 构建所有子记录（排除 taskId 为 null 的无任务活动，如服务任务等）
        List<CountersignSubRecord> allSubRecords = new ArrayList<>();

        for (HistoricActivityInstance activity : miGroup) {
            String taskId = activity.getTaskId();
            if (taskId == null) {
                continue; // 体活动没有关联任务，跳过
            }
            allSubRecords.add(buildSubRecord(activity, taskId, taskMap, commentsByTaskId));
        }

        if (allSubRecords.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查询 csRoundIndex Task 局部变量
        Map<String, Integer> roundByTaskId = queryCsRoundIndex(processInstanceId);

        // 3. 赋值 roundIndex：有显式值直接使用，无则默认 0（原始审批人隐式轮次）
        for (CountersignSubRecord sub : allSubRecords) {
            Integer explicitRound = roundByTaskId.get(sub.getTaskId());
            sub.setRoundIndex(explicitRound != null ? explicitRound : 0);
        }

        return splitIntoExplicitRounds(allSubRecords, miGroup);
    }

    /**
     * 按显式 csRoundIndex 分组为多轮，复用 buildRoundVO 构建每轮父记录。
     */
    private List<ApprovalRecordVO> splitIntoExplicitRounds(
            List<CountersignSubRecord> allSubRecords,
            List<HistoricActivityInstance> miGroup) {
        Map<Integer, List<CountersignSubRecord>> roundMap = new LinkedHashMap<>();
        for (CountersignSubRecord sub : allSubRecords) {
            Integer roundIdx = sub.getRoundIndex();
            if (roundIdx == null) {
                roundIdx = 0;
            }
            roundMap.computeIfAbsent(roundIdx, k -> new ArrayList<>()).add(sub);
        }

        List<ApprovalRecordVO> result = new ArrayList<>();
        for (List<CountersignSubRecord> roundSubRecords : roundMap.values()) {
            result.add(buildRoundVO(roundSubRecords, miGroup));
        }
        return result;
    }

    /**
     * 查询 Task 局部变量 csRoundIndex，返回 taskId → roundIndex 映射。
     * Task 局部变量通过 {@code setVariableLocal(taskId, ...)} 设置，
     * 持久化到 ACT_HI_VARINST（关联 taskId，而非 executionId）。
     */
    private Map<String, Integer> queryCsRoundIndex(String processInstanceId) {
        Map<String, Integer> result = new HashMap<>();

        List<HistoricVariableInstance> vars = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(CS_ROUND_INDEX_VAR)
                .list();

        for (HistoricVariableInstance var : vars) {
            String taskId = var.getTaskId();
            if (taskId != null && var.getValue() instanceof Integer) {
                result.put(taskId, (Integer) var.getValue());
            }
        }
        return result;
    }

    /**
     * 从 miGroup 中提取第一个有关联任务的活动构建单个子记录。
     */
    private CountersignSubRecord buildSubRecord(HistoricActivityInstance activity,
                                                 String taskId,
                                                 Map<String, HistoricTaskInstance> taskMap,
                                                 Map<String, List<Comment>> commentsByTaskId) {
        HistoricTaskInstance task = taskMap.get(taskId);
        List<Comment> taskComments = commentsByTaskId.getOrDefault(taskId, Collections.emptyList());
        ApprovalAction action = task != null
                ? actionInferenceStrategy.inferAction(task.getId(), task.getDeleteReason(), taskComments)
                : null;
        String comment = task != null ? extractCommentText(task.getId(), commentsByTaskId) : null;
        String actorName = task != null ? identityResolver.resolve(task.getAssignee()) : null;

        return CountersignSubRecord.builder()
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
    }

    /**
     * 为一组子记录构建父审批记录，聚合 startTime 和 endTime。
     */
    private ApprovalRecordVO buildRoundVO(List<CountersignSubRecord> subRecords,
                                          List<HistoricActivityInstance> miGroup) {
        HistoricActivityInstance firstActivity = miGroup.get(0);
        Date parentStartTime = subRecords.stream()
                .map(CountersignSubRecord::getStartTime)
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
