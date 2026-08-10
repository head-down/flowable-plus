package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import io.github.flowable.plus.core.domain.PlusHistoricProcessInstance;
import io.github.flowable.plus.core.domain.PlusTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程查询工作流模块，封装批量流程实例摘要查询。
 *
 * <p><b>历史说明（ADR-0028）</b>：本类原含任务级审批轨迹查询
 * {@code getApprovalTrace}（返回 {@code ApprovalTraceVO}），与
 * {@link HistoryWorkflow#getApprovalHistory} 构成双胞胎重复模块，已于 ADR-0028
 * 删除；审批轨迹统一由 {@link HistoryWorkflow} 承载。对应地，
 * {@code MultiInstanceDetector} 与 {@code ActionInferenceStrategy} 两个构造器
 * 依赖随之移除。</p>
 *
 * @author flowable-plus
 */
public class ProcessQueryWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ProcessQueryWorkflow.class);
    private static final int BATCH_SIZE = 500;

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;

    public ProcessQueryWorkflow(RuntimeService runtimeService,
                                TaskService taskService,
                                HistoryService historyService) {
        if (runtimeService == null) {
            throw new IllegalArgumentException("RuntimeService 不可为 null");
        }
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
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
