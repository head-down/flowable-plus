package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;

import java.util.Date;

/**
 * 任务完成事件。
 *
 * @author flowable-plus
 */
public class TaskCompletedEvent implements DispatchableEvent {

    private final String taskId;
    private final String processInstanceId;
    private final String taskName;
    private final String nodeId;
    private final String assignee;
    private final String comment;
    private final Date completeTime;

    private TaskCompletedEvent(String taskId, String processInstanceId, String taskName,
                               String nodeId, String assignee, String comment, Date completeTime) {
        this.taskId = taskId;
        this.processInstanceId = processInstanceId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.assignee = assignee;
        this.comment = comment;
        this.completeTime = completeTime;
    }

    public static TaskCompletedEvent of(String taskId, String processInstanceId, String taskName,
                                         String nodeId, String assignee, String comment,
                                         Date completeTime) {
        return new TaskCompletedEvent(taskId, processInstanceId, taskName,
                nodeId, assignee, comment, completeTime);
    }

    @Override
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    @Override
    public Date getEventTime() {
        return completeTime;
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

    public String getAssignee() {
        return assignee;
    }

    public String getComment() {
        return comment;
    }

    public Date getCompleteTime() {
        return completeTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onTaskCompleted(this);
    }

    @Override
    public String toString() {
        return "TaskCompletedEvent{taskId='" + taskId
                + "', processInstanceId='" + processInstanceId
                + "', nodeId='" + nodeId + "'}";
    }
}
