package io.github.flowable.plus.core;

import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;

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
    public HistoricTaskInstance findTaskById(String taskId) {
        return historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
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
    public HistoricTaskInstance findLatestFinishedTask(String processInstanceId, String taskDefinitionKey) {
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1)
                .stream().findFirst().orElse(null);
    }

    @Override
    public HistoricProcessInstance findProcessInstance(String processInstanceId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
    }
}
