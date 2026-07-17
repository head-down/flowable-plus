package io.github.flowable.plus.core.event;

import java.util.Date;

/**
 * 任务转办事件。
 *
 * @author flowable-plus
 */
public class TaskTransferredEvent implements ProcessEvent {

    private final String taskId;
    private final String processInstanceId;
    private final String taskName;
    private final String nodeId;
    private final String fromAssignee;
    private final String toAssignee;
    private final String reason;
    private final Date transferTime;

    private TaskTransferredEvent(String taskId, String processInstanceId, String taskName,
                                 String nodeId, String fromAssignee, String toAssignee,
                                 String reason, Date transferTime) {
        this.taskId = taskId;
        this.processInstanceId = processInstanceId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.fromAssignee = fromAssignee;
        this.toAssignee = toAssignee;
        this.reason = reason;
        this.transferTime = transferTime;
    }

    public static TaskTransferredEvent of(String taskId, String processInstanceId, String taskName,
                                           String nodeId, String fromAssignee, String toAssignee,
                                           String reason, Date transferTime) {
        return new TaskTransferredEvent(taskId, processInstanceId, taskName,
                nodeId, fromAssignee, toAssignee, reason, transferTime);
    }

    @Override
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    @Override
    public Date getEventTime() {
        return transferTime;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getFromAssignee() {
        return fromAssignee;
    }

    public String getToAssignee() {
        return toAssignee;
    }

    public String getReason() {
        return reason;
    }

    public Date getTransferTime() {
        return transferTime;
    }

    @Override
    public String toString() {
        return "TaskTransferredEvent{taskId='" + taskId
                + "', processInstanceId='" + processInstanceId
                + "', fromAssignee='" + fromAssignee + "', toAssignee='" + toAssignee + "'}";
    }
}
