package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
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
 * 流程查询工作流模块，封装批量流程实例摘要查询逻辑。
 *
 * <p>通过 {@link TaskRepository} 和 {@link HistoricRepository} 接缝访问
 * 运行中和已结束的流程实例数据，避免直接操作 Flowable 引擎内部服务。</p>
 *
 * @author flowable-plus
 */
public class ProcessQueryWorkflow implements ProcessQueryOperations {

    private static final Logger log = LoggerFactory.getLogger(ProcessQueryWorkflow.class);
    private static final int BATCH_SIZE = 500;

    private final RuntimeService runtimeService;
    private final TaskRepository taskRepository;
    private final HistoricRepository historicRepository;

    public ProcessQueryWorkflow(RuntimeService runtimeService, TaskRepository taskRepository,
                                HistoricRepository historicRepository) {
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
    }

    @Override
    public Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            throw new IllegalArgumentException("processInstanceIds 不可为 null 或空");
        }

        Map<String, ProcessSummaryVO> result = new LinkedHashMap<>();
        boolean foundAny = false;

        for (int i = 0; i < processInstanceIds.size(); i += BATCH_SIZE) {
            List<String> batch = processInstanceIds.subList(i, Math.min(i + BATCH_SIZE, processInstanceIds.size()));
            Set<String> batchSet = new LinkedHashSet<>(batch);

            // 1. 查询运行时实例
            List<ProcessInstance> runtimeInstances = runtimeService.createProcessInstanceQuery()
                    .processInstanceIds(batchSet)
                    .list();
            Set<String> runtimeIds = new HashSet<>();
            Map<String, ProcessInstance> runtimeMap = new HashMap<>();
            for (ProcessInstance pi : runtimeInstances) {
                runtimeIds.add(pi.getProcessInstanceId());
                runtimeMap.put(pi.getProcessInstanceId(), pi);
            }

            // 2. 通过 TaskRepository 查询运行时活跃任务
            Map<String, List<Task>> tasksByInstance = new HashMap<>();
            if (!runtimeIds.isEmpty()) {
                List<Task> activeTasks = taskRepository.findActiveTasksByProcessInstanceIds(runtimeIds);
                for (Task task : activeTasks) {
                    tasksByInstance.computeIfAbsent(task.getProcessInstanceId(), k -> new ArrayList<>()).add(task);
                }
            }

            // 3. 通过 HistoricRepository 查询历史实例（已结束的）
            List<String> deadIds = new ArrayList<>(batchSet);
            deadIds.removeAll(runtimeIds);
            Map<String, HistoricProcessInstance> histMap = new HashMap<>();
            if (!deadIds.isEmpty()) {
                List<HistoricProcessInstance> histInstances = historicRepository
                        .findProcessInstancesByIds(new HashSet<>(deadIds));
                for (HistoricProcessInstance hpi : histInstances) {
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

    private ProcessSummaryVO buildRunningSummary(ProcessInstance pi, List<Task> tasks) {
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
            Task firstTask = tasks.get(0);
            builder.currentTaskId(firstTask.getId())
                    .currentTaskName(firstTask.getName())
                    .currentNodeId(firstTask.getTaskDefinitionKey());

            List<AssigneeInfo> assignees = new ArrayList<>();
            for (Task t : tasks) {
                assignees.add(new AssigneeInfo(t.getAssignee(), t.getId(), null));
            }
            builder.activeAssignees(assignees);
        }

        return builder.build();
    }

    private ProcessSummaryVO buildEndedSummary(HistoricProcessInstance hpi) {
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
