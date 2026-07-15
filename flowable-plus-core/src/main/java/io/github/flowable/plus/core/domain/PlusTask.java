package io.github.flowable.plus.core.domain;

import org.flowable.task.api.Task;

import java.util.Date;

/**
 * 运行时任务领域对象，封装 Flowable 原生 {@link Task} 的核心属性。
 *
 * <p>作为 {@link TaskRepository} 接口的返回值，封口 Flowable 类型泄漏。
 * 仅包含业务层需要的字段，不追求完整映射。</p>
 *
 * @author flowable-plus
 */
public class PlusTask {

    private final String id;
    private final String processDefinitionId;
    private final String taskDefinitionKey;
    private final String processInstanceId;
    private final String assignee;
    private final String owner;
    private final String name;
    private final String executionId;
    private final Date createTime;

    public PlusTask(String id, String processDefinitionId, String taskDefinitionKey,
             String processInstanceId, String assignee, String owner, String name,
             String executionId, Date createTime) {
        this.id = id;
        this.processDefinitionId = processDefinitionId;
        this.taskDefinitionKey = taskDefinitionKey;
        this.processInstanceId = processInstanceId;
        this.assignee = assignee;
        this.owner = owner;
        this.name = name;
        this.executionId = executionId;
        this.createTime = createTime;
    }

    /**
     * 从 Flowable 原生 Task 构建领域对象。
     */
    public static PlusTask from(Task task) {
        return new PlusTask(
                task.getId(),
                task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(),
                task.getProcessInstanceId(),
                task.getAssignee(),
                task.getOwner(),
                task.getName(),
                task.getExecutionId(),
                task.getCreateTime());
    }

    public String getId() {
        return id;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    public String getTaskDefinitionKey() {
        return taskDefinitionKey;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public String getAssignee() {
        return assignee;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getExecutionId() {
        return executionId;
    }

    public Date getCreateTime() {
        return createTime;
    }

    @Override
    public String toString() {
        return "PlusTask{"
                + "id='" + id + '\''
                + ", taskDefinitionKey='" + taskDefinitionKey + '\''
                + ", processInstanceId='" + processInstanceId + '\''
                + ", assignee='" + assignee + '\''
                + '}';
    }
}
