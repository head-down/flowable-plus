package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
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
 * <p>通过 {@link TaskRepository} 和 {@link HistoricRepository} 接缝访问任务与历史数据，
 * 运行时实例查询直接使用 {@link RuntimeService}。</p>
 *
 * @author flowable-plus
 */
public class ProcessQueryWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ProcessQueryWorkflow.class);
    private static final int BATCH_SIZE = 500;

    private final RuntimeService runtimeService;
    private final TaskRepository taskRepository;
    private final HistoricRepository historicRepository;
    private final BpmnModelCache bpmnModelCache;

    public ProcessQueryWorkflow(RuntimeService runtimeService,
                                TaskRepository taskRepository,
                                HistoricRepository historicRepository,
                                BpmnModelCache bpmnModelCache) {
        if (runtimeService == null) {
            throw new IllegalArgumentException("RuntimeService 不可为 null");
        }
        if (taskRepository == null) {
            throw new IllegalArgumentException("TaskRepository 不可为 null");
        }
        if (historicRepository == null) {
            throw new IllegalArgumentException("HistoricRepository 不可为 null");
        }
        this.runtimeService = runtimeService;
        this.taskRepository = taskRepository;
        this.historicRepository = historicRepository;
        this.bpmnModelCache = bpmnModelCache;
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

            // 2. 通过 TaskRepository 查询运行时活跃任务
            Map<String, List<PlusTask>> tasksByInstance = new HashMap<>();
            if (!runtimeIds.isEmpty()) {
                List<PlusTask> activeTasks = taskRepository.findActiveTasksByProcessInstanceIds(runtimeIds);
                for (PlusTask task : activeTasks) {
                    tasksByInstance.computeIfAbsent(task.getProcessInstanceId(), k -> new ArrayList<>()).add(task);
                }
            }

            // 3. 通过 HistoricRepository 查询历史实例（已结束的）
            List<String> deadIds = new ArrayList<>(batchSet);
            deadIds.removeAll(runtimeIds);
            Map<String, PlusHistoricProcessInstance> histMap = new HashMap<>();
            if (!deadIds.isEmpty()) {
                List<PlusHistoricProcessInstance> histInstances = historicRepository
                        .findProcessInstancesByIds(new HashSet<>(deadIds));
                for (PlusHistoricProcessInstance hpi : histInstances) {
                    histMap.put(hpi.getId(), hpi);
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

    // ======================== 审批轨迹查询 ========================

    public List<ApprovalTraceVO> getApprovalTrace(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }

        // 1. 查询活跃运行时任务
        List<PlusTask> activeTasks = taskRepository.findActiveTasksByProcessInstanceIds(
                Collections.singletonList(processInstanceId));

        // 2. 查询已结束的历史任务
        List<PlusHistoricTask> historicTasks = historicRepository
                .findHistoricTasksByProcessInstanceId(processInstanceId);

        // 3. 若都为空，验证流程实例是否存在
        if (activeTasks.isEmpty() && historicTasks.isEmpty()) {
            PlusHistoricProcessInstance hpi = historicRepository.findProcessInstance(processInstanceId);
            if (hpi == null) {
                throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
            }
            return Collections.emptyList();
        }

        // 4. 查询审批意见，按 taskId 取最后一条
        List<Comment> comments = taskRepository.getProcessInstanceComments(processInstanceId);
        Map<String, String> lastCommentByTaskId = groupLastCommentByTaskId(comments);

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
                    && bpmnModelCache != null
                    && bpmnModelCache.isMultiInstanceNode(processDefinitionId, nodeId);

            if (isMultiInstance) {
                // 会签：聚合展示
                result.add(buildCounterSignParent(nodeId, nodeHistTasks, nodeActiveTasks, lastCommentByTaskId));
            } else {
                // 普通节点：逐一展示
                for (PlusHistoricTask ht : nodeHistTasks) {
                    result.add(buildHistoricTraceVO(ht, lastCommentByTaskId.get(ht.getId())));
                }
                for (PlusTask at : nodeActiveTasks) {
                    result.add(buildActiveTraceVO(at, lastCommentByTaskId.get(at.getId())));
                }
            }
        }

        // 8. 按 startTime 升序排序（会签父节点以子节点最早时间为准）
        result.sort(Comparator.comparing(ApprovalTraceVO::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return result;
    }

    // ======================== 私有构建方法 ========================

    private ApprovalTraceVO buildHistoricTraceVO(PlusHistoricTask ht, String comment) {
        Long durationMillis = null;
        if (ht.getEndTime() != null && ht.getCreateTime() != null) {
            durationMillis = ht.getEndTime().getTime() - ht.getCreateTime().getTime();
        }

        return ApprovalTraceVO.builder()
                .taskId(ht.getId())
                .taskName(ht.getName())
                .nodeId(ht.getTaskDefinitionKey())
                .assignee(ht.getAssignee())
                .startTime(ht.getCreateTime())
                .endTime(ht.getEndTime())
                .durationMillis(durationMillis)
                .comment(comment)
                .approved(inferApproved(ht.getDeleteReason()))
                .isRejected(inferRejected(ht.getDeleteReason()))
                .countersignDetails(null)
                .build();
    }

    private ApprovalTraceVO buildActiveTraceVO(PlusTask at, String comment) {
        return ApprovalTraceVO.builder()
                .taskId(at.getId())
                .taskName(at.getName())
                .nodeId(at.getTaskDefinitionKey())
                .assignee(at.getAssignee())
                .startTime(at.getCreateTime())
                .endTime(null)
                .durationMillis(null)
                .comment(comment)
                .approved(null)
                .isRejected(null)
                .countersignDetails(null)
                .build();
    }

    private ApprovalTraceVO buildCounterSignParent(String nodeId,
                                                    List<PlusHistoricTask> nodeHistTasks,
                                                    List<PlusTask> nodeActiveTasks,
                                                    Map<String, String> lastCommentByTaskId) {
        // 构建子详情列表
        List<ApprovalTraceVO> details = new ArrayList<>();
        for (PlusHistoricTask ht : nodeHistTasks) {
            details.add(buildHistoricTraceVO(ht, lastCommentByTaskId.get(ht.getId())));
        }
        for (PlusTask at : nodeActiveTasks) {
            details.add(buildActiveTraceVO(at, lastCommentByTaskId.get(at.getId())));
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

    private Map<String, String> groupLastCommentByTaskId(List<Comment> comments) {
        Map<String, String> result = new HashMap<>();
        if (comments == null || comments.isEmpty()) {
            return result;
        }
        // getProcessInstanceComments 已按时间升序排列
        for (Comment comment : comments) {
            String taskId = comment.getTaskId();
            if (taskId != null) {
                result.put(taskId, comment.getFullMessage());
            }
        }
        return result;
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

    private Boolean inferApproved(String deleteReason) {
        if (deleteReason == null || deleteReason.isEmpty()) {
            return true;
        }
        return false;
    }

    private Boolean inferRejected(String deleteReason) {
        if (deleteReason == null) {
            return false;
        }
        String reason = deleteReason.toUpperCase();
        return reason.contains("驳回") || reason.contains("REJECT") || reason.contains("撤回") || reason.contains("WITHDRAW");
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
