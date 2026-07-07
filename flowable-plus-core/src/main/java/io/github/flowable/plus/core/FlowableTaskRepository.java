package io.github.flowable.plus.core;

import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link TaskRepository} 的 Flowable 默认适配器实现。
 *
 * @author flowable-plus
 */
public class FlowableTaskRepository implements TaskRepository {

    private final TaskService taskService;

    public FlowableTaskRepository(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public Task findById(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult();
    }

    @Override
    public List<Task> listActiveTasks(String processInstanceId, String taskDefinitionKey) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .list();
    }

    @Override
    public long countActiveTasks(String processInstanceId, String taskDefinitionKey) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .count();
    }

    @Override
    public Task findActiveByProcessInstance(String processInstanceId) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult();
    }

    @Override
    public Task findActiveTask(String processInstanceId, String taskDefinitionKey, String assignee) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .taskAssignee(assignee)
                .active()
                .singleResult();
    }

    @Override
    public void claim(String taskId, String userId) {
        taskService.claim(taskId, userId);
    }

    @Override
    public void addComment(String taskId, String processInstanceId, String type, String message) {
        taskService.addComment(taskId, processInstanceId, type, message);
    }

    @Override
    public void complete(String taskId, Map<String, Object> variables) {
        taskService.complete(taskId, variables);
    }

    @Override
    public List<Task> findActiveTasksByProcessInstanceIds(Collection<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Collections.emptyList();
        }
        return taskService.createTaskQuery()
                .processInstanceIdIn(new ArrayList<>(processInstanceIds))
                .active()
                .list();
    }
}
