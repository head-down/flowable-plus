package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import org.flowable.engine.runtime.ProcessInstance;
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
 * <p>所有数据访问通过仓储接缝（{@link RuntimeProcessRepository}、
 * {@link TaskRepository}、{@link HistoricRepository}），
 * 不直接依赖 Flowable 引擎内部服务。</p>
 *
 * @author flowable-plus
 */
public class ProcessQueryWorkflow implements ProcessQueryOperations {

    private static final Logger log = LoggerFactory.getLogger(ProcessQueryWorkflow.class);
    private static final int BATCH_SIZE = 500;

    private final RuntimeProcessRepository runtimeProcessRepository;
    private final TaskRepository taskRepository;
    private final HistoricRepository historicRepository;

    public ProcessQueryWorkflow(RuntimeProcessRepository runtimeProcessRepository,
                                TaskRepository taskRepository,
                                HistoricRepository historicRepository) {
        if (runtimeProcessRepository == null) {
            throw new IllegalArgumentException("RuntimeProcessRepository 不可为 null");
        }
        if (taskRepository == null) {
            throw new IllegalArgumentException("TaskRepository 不可为 null");
        }
        if (historicRepository == null) {
            throw new IllegalArgumentException("HistoricRepository 不可为 null");
        }
        this.runtimeProcessRepository = runtimeProcessRepository;
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

            // 1. 通过 RuntimeProcessRepository 查询运行时实例
            List<ProcessInstance> runtimeInstances = runtimeProcessRepository
                    .findProcessInstancesByIds(batchSet);
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
