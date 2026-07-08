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
    public PlusTask findById(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        return task != null ? PlusTask.from(task) : null;
    }

    @Override
    public List<PlusTask> listActiveTasks(String processInstanceId, String taskDefinitionKey) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .list();
        return tasks.stream().map(PlusTask::from).collect(java.util.stream.Collectors.toList());
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
    public PlusTask findActiveByProcessInstance(String processInstanceId) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult();
        return task != null ? PlusTask.from(task) : null;
    }

    @Override
    public PlusTask findActiveTask(String processInstanceId, String taskDefinitionKey, String assignee) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .taskAssignee(assignee)
                .active()
                .singleResult();
        return task != null ? PlusTask.from(task) : null;
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
    public List<PlusTask> findActiveTasksByProcessInstanceIds(Collection<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceIdIn(new ArrayList<>(processInstanceIds))
                .active()
                .list();
        return tasks.stream().map(PlusTask::from).collect(java.util.stream.Collectors.toList());
    }
}
