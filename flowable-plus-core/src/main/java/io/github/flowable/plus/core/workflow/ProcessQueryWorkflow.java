package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.support.ActionInferenceStrategy;
import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import io.github.flowable.plus.core.domain.PlusHistoricProcessInstance;
import io.github.flowable.plus.core.domain.PlusHistoricTask;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流程查询工作流模块，封装批量流程实例摘要查询与审批轨迹查询。
 *
 * @author flowable-plus
 */
public class ProcessQueryWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ProcessQueryWorkflow.class);
    private static final int BATCH_SIZE = 500;

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final MultiInstanceDetector multiInstanceDetector;
    private final ActionInferenceStrategy actionInferenceStrategy;

    public ProcessQueryWorkflow(RuntimeService runtimeService,
                                TaskService taskService,
                                HistoryService historyService,
                                MultiInstanceDetector multiInstanceDetector,
                                ActionInferenceStrategy actionInferenceStrategy) {
        if (runtimeService == null) {
            throw new IllegalArgumentException("RuntimeService 不可为 null");
        }
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (actionInferenceStrategy == null) {
            throw new IllegalArgumentException("ActionInferenceStrategy 不可为 null");
        }
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.multiInstanceDetector = multiInstanceDetector;
        this.actionInferenceStrategy = actionInferenceStrategy;
    }

    // ======================== 批量流程摘要查询 ========================

    public Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            throw new IllegalArgumentException("processInstanceIds 不可为 null 或空");
        }

        Map<String, ProcessSummaryVO> result = new LinkedHashMap<>();
        boolean foundAny = false;

        for (int i = 0; i < processInstanceIds.size(); i += BATCH_SIZE) {
            List<String> batch = processInstanceIds.subList(i, Math.min(i + BATCH_SIZE, processInstanceIds.size()));
            Set<String> batchSet = new LinkedHashSet<>(batch);

            // 1. 通过 RuntimeService 查询运行时实例
            List<ProcessInstance> runtimeInstances = runtimeService
                    .createProcessInstanceQuery()
                    .processInstanceIds(batchSet)
                    .list();
            Set<String> runtimeIds = new HashSet<>();
            Map<String, ProcessInstance> runtimeMap = new HashMap<>();
            for (ProcessInstance pi : runtimeInstances) {
                runtimeIds.add(pi.getProcessInstanceId());
                runtimeMap.put(pi.getProcessInstanceId(), pi);
            }

            // 2. 通过 TaskService 查询运行时活跃任务
            Map<String, List<PlusTask>> tasksByInstance = new HashMap<>();
            if (!runtimeIds.isEmpty()) {
                List<Task> activeTasks = taskService.createTaskQuery()
                        .processInstanceIdIn(runtimeIds).active().list();
                for (Task task : activeTasks) {
                    tasksByInstance.computeIfAbsent(task.getProcessInstanceId(), k -> new ArrayList<>())
                            .add(PlusTask.from(task));
                }
            }

            // 3. 通过 HistoryService 查询历史实例（已结束的）
            List<String> deadIds = new ArrayList<>(batchSet);
            deadIds.removeAll(runtimeIds);
            Map<String, PlusHistoricProcessInstance> histMap = new HashMap<>();
            if (!deadIds.isEmpty()) {
                List<HistoricProcessInstance> histInstances = historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceIds(new HashSet<>(deadIds))
                        .list();
                for (HistoricProcessInstance hpi : histInstances) {
                    histMap.put(hpi.getId(), PlusHistoricProcessInstance.from(hpi));
                }
            }

            // 4. 按输入顺序构建 VO
            for (String instanceId : batch) {
                ProcessSummaryVO vo;
                if (runtimeMap.containsKey(instanceId)) {
                    vo = buildRunningSummary(runtimeMap.get(instanceId),
                            tasksByInstance.getOrDefault(instanceId, Collections.emptyList()));
                } else if (histMap.containsKey(instanceId)) {
                    vo = buildEndedSummary(histMap.get(instanceId));
                } else {
                    continue;
                }
                result.put(instanceId, vo);
                foundAny = true;
            }
        }

        if (!foundAny) {
            log.warn("batchQueryProcessSummaries: 所有 processInstanceId 均不存在，共 {} 个", processInstanceIds.size());
        }

        return result;
    }

    // ======================== 单条流程摘要查询 ========================

    /**
     * 获取单个流程实例的运行时摘要信息。
     *
     * <p>内部委托给 {@link #batchQueryProcessSummaries(List)}，封装单条语义。
     * 流程实例不存在时返回 null。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null 或空
     * @return 流程摘要信息，流程实例不存在时返回 null
     * @throws IllegalArgumentException 如果 processInstanceId 为 null 或空
     */
    public ProcessSummaryVO getProcessSummary(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }
        return batchQueryProcessSummaries(
                Collections.singletonList(processInstanceId)).get(processInstanceId);
    }

    // ======================== businessKey 查询 ========================

    /**
     * 根据流程实例 ID 获取 businessKey，先查运行时再查历史。
     *
     * @param processInstanceId 流程实例 ID，不可为 null 或空
     * @return businessKey，未设置或流程实例不存在时返回 null
     */
    public String getBusinessKeyByProcessInstanceId(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }

        // 1. 先查运行时（多数场景是运行中的流程）
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (pi != null) {
            return pi.getBusinessKey();
        }

        // 2. 回退查历史（已结束的流程）
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (hpi != null) {
            return hpi.getBusinessKey();
        }

        return null;
    }

    // ======================== 审批轨迹查询 ========================

    public List<ApprovalTraceVO> getApprovalTrace(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }

        // 1. 查询活跃运行时任务
        List<Task> activeTaskObjs = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        List<PlusTask> activeTasks = activeTaskObjs.stream()
                .map(PlusTask::from).collect(Collectors.toList());

        // 2. 查询已结束的历史任务
        List<HistoricTaskInstance> historicTaskObjs = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .orderByHistoricTaskInstanceStartTime().asc()
                .list();
        List<PlusHistoricTask> historicTasks = historicTaskObjs.stream()
                .map(PlusHistoricTask::from).collect(Collectors.toList());

        // 3. 若都为空，验证流程实例是否存在
        if (activeTasks.isEmpty() && historicTasks.isEmpty()) {
            HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            if (hpi == null) {
                throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
            }
            return Collections.emptyList();
        }

        // 4. 查询审批意见，按 taskId 分组（时间倒序）
        List<Comment> comments = taskService.getProcessInstanceComments(processInstanceId);
        Map<String, List<Comment>> commentsByTaskId = groupCommentsByTaskId(comments);

        // 5. 按 nodeId 分组
        Map<String, List<PlusHistoricTask>> historicByNode = historicTasks.stream()
                .collect(Collectors.groupingBy(PlusHistoricTask::getTaskDefinitionKey, LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, List<PlusTask>> activeByNode = activeTasks.stream()
                .collect(Collectors.groupingBy(PlusTask::getTaskDefinitionKey, LinkedHashMap::new,
                        Collectors.toList()));

        // 6. 收集所有 nodeId
        Set<String> allNodeIds = new LinkedHashSet<>();
        allNodeIds.addAll(historicByNode.keySet());
        allNodeIds.addAll(activeByNode.keySet());

        // 7. 构建最终结果
        List<ApprovalTraceVO> result = new ArrayList<>();
        for (String nodeId : allNodeIds) {
            List<PlusHistoricTask> nodeHistTasks = historicByNode.getOrDefault(nodeId, Collections.emptyList());
            List<PlusTask> nodeActiveTasks = activeByNode.getOrDefault(nodeId, Collections.emptyList());

            // 获取 processDefinitionId（历史任务或活跃任务中取第一个有效的）
            String processDefinitionId = resolveProcessDefinitionId(nodeHistTasks, nodeActiveTasks);
            boolean isMultiInstance = processDefinitionId != null
                    && multiInstanceDetector != null
                    && multiInstanceDetector.isMultiInstanceNode(processDefinitionId, nodeId);

            if (isMultiInstance) {
                // 会签：聚合展示
                result.add(buildCounterSignParent(nodeId, nodeHistTasks, nodeActiveTasks, commentsByTaskId));
            } else {
                // 普通节点：逐一展示
                for (PlusHistoricTask ht : nodeHistTasks) {
                    result.add(buildHistoricTraceVO(ht, commentsByTaskId));
                }
                for (PlusTask at : nodeActiveTasks) {
                    result.add(buildActiveTraceVO(at, commentsByTaskId));
                }
            }
        }

        // 8. 按 startTime 升序排序（会签父节点以子节点最早时间为准）
        result.sort(Comparator.comparing(ApprovalTraceVO::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return result;
    }

    // ======================== 私有构建方法 ========================

    private ApprovalTraceVO buildHistoricTraceVO(PlusHistoricTask ht, Map<String, List<Comment>> commentsByTaskId) {
        Long durationMillis = null;
        if (ht.getEndTime() != null && ht.getCreateTime() != null) {
            durationMillis = ht.getEndTime().getTime() - ht.getCreateTime().getTime();
        }

        List<Comment> taskComments = commentsByTaskId.getOrDefault(ht.getId(), Collections.emptyList());
        ApprovalAction action = actionInferenceStrategy.inferAction(
                ht.getId(), ht.getDeleteReason(), taskComments);
        String comment = extractCommentText(taskComments);
        String operationComment = extractOperationCommentText(taskComments);

        return ApprovalTraceVO.builder()
                .taskId(ht.getId())
                .taskName(ht.getName())
                .nodeId(ht.getTaskDefinitionKey())
                .assignee(ht.getAssignee())
                .startTime(ht.getCreateTime())
                .endTime(ht.getEndTime())
                .durationMillis(durationMillis)
                .comment(comment)
                .operationComment(operationComment)
                .approved(toApproved(action))
                .isRejected(toRejected(action))
                .countersignDetails(null)
                .build();
    }

    private ApprovalTraceVO buildActiveTraceVO(PlusTask at, Map<String, List<Comment>> commentsByTaskId) {
        List<Comment> taskComments = commentsByTaskId.getOrDefault(at.getId(), Collections.emptyList());
        String comment = extractCommentText(taskComments);
        String operationComment = extractOperationCommentText(taskComments);
        return ApprovalTraceVO.builder()
                .taskId(at.getId())
                .taskName(at.getName())
                .nodeId(at.getTaskDefinitionKey())
                .assignee(at.getAssignee())
                .startTime(at.getCreateTime())
                .endTime(null)
                .durationMillis(null)
                .comment(comment)
                .operationComment(operationComment)
                .approved(null)
                .isRejected(null)
                .countersignDetails(null)
                .build();
    }

    private ApprovalTraceVO buildCounterSignParent(String nodeId,
                                                    List<PlusHistoricTask> nodeHistTasks,
                                                    List<PlusTask> nodeActiveTasks,
                                                    Map<String, List<Comment>> commentsByTaskId) {
        // 构建子详情列表
        List<ApprovalTraceVO> details = new ArrayList<>();
        for (PlusHistoricTask ht : nodeHistTasks) {
            details.add(buildHistoricTraceVO(ht, commentsByTaskId));
        }
        for (PlusTask at : nodeActiveTasks) {
            details.add(buildActiveTraceVO(at, commentsByTaskId));
        }
        details.sort(Comparator.comparing(ApprovalTraceVO::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // 父级汇总
        Date parentStartTime = details.stream()
                .map(ApprovalTraceVO::getStartTime)
                .filter(d -> d != null)
                .min(Comparator.naturalOrder())
                .orElse(null);

        Date parentEndTime = details.stream()
                .map(ApprovalTraceVO::getEndTime)
                .allMatch(d -> d != null)
                ? details.stream()
                    .map(ApprovalTraceVO::getEndTime)
                    .max(Comparator.naturalOrder())
                    .orElse(null)
                : null;

        Long parentDurationMillis = null;
        if (parentStartTime != null && parentEndTime != null) {
            parentDurationMillis = parentEndTime.getTime() - parentStartTime.getTime();
        }

        // 状态汇总：全部同意 → approved=true；任一驳回 → isRejected=true
        boolean allApproved = details.stream().allMatch(d -> Boolean.TRUE.equals(d.getApproved()));
        boolean anyRejected = details.stream().anyMatch(d -> Boolean.TRUE.equals(d.getIsRejected()));

        // 获取节点名称（从第一个有名称的任务中取）
        String taskName = null;
        for (PlusHistoricTask ht : nodeHistTasks) {
            if (ht.getName() != null) {
                taskName = ht.getName();
                break;
            }
        }
        if (taskName == null) {
            for (PlusTask at : nodeActiveTasks) {
                if (at.getName() != null) {
                    taskName = at.getName();
                    break;
                }
            }
        }

        return ApprovalTraceVO.builder()
                .taskId(null)
                .taskName(taskName)
                .nodeId(nodeId)
                .assignee(null)
                .startTime(parentStartTime)
                .endTime(parentEndTime)
                .durationMillis(parentDurationMillis)
                .comment(null)
                .approved(allApproved)
                .isRejected(anyRejected)
                .countersignDetails(details)
                .build();
    }

    // ======================== Comment 工具方法 ========================

    /**
     * 按 taskId 对 Comment 进行分组，每组按时间倒序排列。
     */
    private Map<String, List<Comment>> groupCommentsByTaskId(List<Comment> comments) {
        Map<String, List<Comment>> result = new HashMap<>();
        if (comments == null || comments.isEmpty()) {
            return result;
        }
        // getProcessInstanceComments 返回按时���升序的结果
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

    /**
     * 从 Comment 列表中提取审批意见文本（取第一个业务 Comment 的 fullMessage，ADR-0025：跳过操作注释组）。
     */
    private String extractCommentText(List<Comment> taskComments) {
        Comment businessComment = actionInferenceStrategy.findFirstBusinessComment(taskComments);
        return businessComment != null ? businessComment.getFullMessage() : null;
    }

    /**
     * 从 Comment 列表中提取操作注释文本（ADR-0025），与业务审批意见 {@code comment} 语义解耦。
     */
    private String extractOperationCommentText(List<Comment> taskComments) {
        Comment operationComment = actionInferenceStrategy.findFirstOperationComment(taskComments);
        return operationComment != null ? operationComment.getFullMessage() : null;
    }

    /**
     * 从 ApprovalAction 派生 approved 字段。
     */
    private static Boolean toApproved(ApprovalAction action) {
        if (action == ApprovalAction.AGREE || action == ApprovalAction.COUNTER_SIGN_AGREE) {
            return true;
        }
        return null;
    }

    /**
     * 从 ApprovalAction 派生 isRejected 字段。
     */
    private static Boolean toRejected(ApprovalAction action) {
        if (action == ApprovalAction.REJECT || action == ApprovalAction.COUNTER_SIGN_REJECT) {
            return true;
        }
        return false;
    }

    private String resolveProcessDefinitionId(List<PlusHistoricTask> nodeHistTasks, List<PlusTask> nodeActiveTasks) {
        if (!nodeHistTasks.isEmpty()) {
            return nodeHistTasks.get(0).getProcessDefinitionId();
        }
        if (!nodeActiveTasks.isEmpty()) {
            return nodeActiveTasks.get(0).getProcessDefinitionId();
        }
        return null;
    }

    // ======================== 流程摘要构建 ========================

    private ProcessSummaryVO buildRunningSummary(ProcessInstance pi, List<PlusTask> tasks) {
        ProcessSummaryVO.ProcessSummaryVOBuilder builder = ProcessSummaryVO.builder()
                .instanceId(pi.getProcessInstanceId())
                .businessKey(pi.getBusinessKey())
                .processDefinitionKey(pi.getProcessDefinitionKey())
                .processDefinitionName(pi.getProcessDefinitionName())
                .startUserId(pi.getStartUserId())
                .createTime(pi.getStartTime())
                .endTime(null)
                .suspendState(pi.isSuspended() ? 2 : 1)
                .isEnded(false)
                .endReason(null);

        if (tasks.isEmpty()) {
            builder.currentTaskId(null)
                    .currentTaskName(null)
                    .currentNodeId(null)
                    .activeAssignees(Collections.emptyList());
        } else {
            PlusTask firstTask = tasks.get(0);
            builder.currentTaskId(firstTask.getId())
                    .currentTaskName(firstTask.getName())
                    .currentNodeId(firstTask.getTaskDefinitionKey());

            List<AssigneeInfo> assignees = new ArrayList<>();
            for (PlusTask t : tasks) {
                assignees.add(new AssigneeInfo(t.getAssignee(), t.getId(), null));
            }
            builder.activeAssignees(assignees);
        }

        return builder.build();
    }

    private ProcessSummaryVO buildEndedSummary(PlusHistoricProcessInstance hpi) {
        return ProcessSummaryVO.builder()
                .instanceId(hpi.getId())
                .businessKey(hpi.getBusinessKey())
                .processDefinitionKey(hpi.getProcessDefinitionKey())
                .processDefinitionName(hpi.getProcessDefinitionName())
                .startUserId(hpi.getStartUserId())
                .createTime(hpi.getStartTime())
                .endTime(hpi.getEndTime())
                .currentTaskId(null)
                .currentTaskName(null)
                .currentNodeId(null)
                .suspendState(1)
                .isEnded(true)
                .endReason(hpi.getDeleteReason())
                .activeAssignees(Collections.emptyList())
                .build();
    }
}
