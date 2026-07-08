package io.github.flowable.plus.core;

import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@link HistoricRepository} 的 Flowable 默认适配器实现。
 *
 * @author flowable-plus
 */
public class FlowableHistoricRepository implements HistoricRepository {

    private final HistoryService historyService;

    public FlowableHistoricRepository(HistoryService historyService) {
        this.historyService = historyService;
    }

    @Override
    public PlusHistoricTask findTaskById(String taskId) {
        HistoricTaskInstance hti = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
        return hti != null ? PlusHistoricTask.from(hti) : null;
    }

    @Override
    public long countFinishedTasks(String processInstanceId, String taskDefinitionKey, String userId) {
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .taskAssignee(userId)
                .finished()
                .count();
    }

    @Override
    public PlusHistoricTask findLatestFinishedTask(String processInstanceId, String taskDefinitionKey) {
        HistoricTaskInstance hti = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1)
                .stream().findFirst().orElse(null);
        return hti != null ? PlusHistoricTask.from(hti) : null;
    }

    @Override
    public PlusHistoricProcessInstance findProcessInstance(String processInstanceId) {
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return hpi != null ? PlusHistoricProcessInstance.from(hpi) : null;
    }

    @Override
    public List<HistoricActivityInstance> findFinishedHistoricActivityInstances(String processInstanceId) {
        if (processInstanceId == null) {
            return Collections.emptyList();
        }
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .orderByHistoricActivityInstanceEndTime().desc()
                .list();
    }

    @Override
    public List<PlusHistoricProcessInstance> findProcessInstancesByIds(Set<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<HistoricProcessInstance> instances = historyService.createHistoricProcessInstanceQuery()
                .processInstanceIds(processInstanceIds)
                .list();
        return instances.stream().map(PlusHistoricProcessInstance::from).collect(java.util.stream.Collectors.toList());
    }
}
