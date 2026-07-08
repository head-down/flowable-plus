package io.github.flowable.plus.core;

import org.flowable.task.api.history.HistoricTaskInstance;

import java.util.Date;

/**
 * 历史任务领域对象，封装 Flowable 原生 {@link HistoricTaskInstance} 的核心属性。
 *
 * @author flowable-plus
 */
public class PlusHistoricTask {

    private final String id;
    private final String processDefinitionId;
    private final String taskDefinitionKey;
    private final String processInstanceId;
    private final String assignee;
    private final String name;
    private final Date createTime;
    private final Date endTime;
    private final String deleteReason;

    public PlusHistoricTask(String id, String processDefinitionId, String taskDefinitionKey,
                     String processInstanceId, String assignee, String name,
                     Date createTime, Date endTime, String deleteReason) {
        this.id = id;
        this.processDefinitionId = processDefinitionId;
        this.taskDefinitionKey = taskDefinitionKey;
        this.processInstanceId = processInstanceId;
        this.assignee = assignee;
        this.name = name;
        this.createTime = createTime;
        this.endTime = endTime;
        this.deleteReason = deleteReason;
    }

    static PlusHistoricTask from(HistoricTaskInstance hti) {
        return new PlusHistoricTask(
                hti.getId(),
                hti.getProcessDefinitionId(),
                hti.getTaskDefinitionKey(),
                hti.getProcessInstanceId(),
                hti.getAssignee(),
                hti.getName(),
                hti.getCreateTime(),
                hti.getEndTime(),
                hti.getDeleteReason());
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

    public String getName() {
        return name;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public String getDeleteReason() {
        return deleteReason;
    }

    @Override
    public String toString() {
        return "PlusHistoricTask{"
                + "id='" + id + '\''
                + ", taskDefinitionKey='" + taskDefinitionKey + '\''
                + ", processInstanceId='" + processInstanceId + '\''
                + ", assignee='" + assignee + '\''
                + '}';
    }
}
