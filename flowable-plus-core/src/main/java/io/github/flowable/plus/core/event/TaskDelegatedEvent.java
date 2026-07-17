package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;

import java.util.Date;

/**
 * 任务委派事件。
 *
 * @author flowable-plus
 */
public class TaskDelegatedEvent implements DispatchableEvent {

    private final String taskId;
    private final String processInstanceId;
    private final String taskName;
    private final String nodeId;
    private final String delegator;
    private final String delegatee;
    private final String reason;
    private final Date delegateTime;

    private TaskDelegatedEvent(String taskId, String processInstanceId, String taskName,
                               String nodeId, String delegator, String delegatee,
                               String reason, Date delegateTime) {
        this.taskId = taskId;
        this.processInstanceId = processInstanceId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.delegator = delegator;
        this.delegatee = delegatee;
        this.reason = reason;
        this.delegateTime = delegateTime;
    }

    public static TaskDelegatedEvent of(String taskId, String processInstanceId, String taskName,
                                         String nodeId, String delegator, String delegatee,
                                         String reason, Date delegateTime) {
        return new TaskDelegatedEvent(taskId, processInstanceId, taskName,
                nodeId, delegator, delegatee, reason, delegateTime);
    }

    @Override
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    @Override
    public Date getEventTime() {
        return delegateTime;
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

    public String getDelegator() {
        return delegator;
    }

    public String getDelegatee() {
        return delegatee;
    }

    public String getReason() {
        return reason;
    }

    public Date getDelegateTime() {
        return delegateTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onTaskDelegated(this);
    }

    @Override
    public String toString() {
        return "TaskDelegatedEvent{taskId='" + taskId
                + "', processInstanceId='" + processInstanceId
                + "', delegator='" + delegator + "', delegatee='" + delegatee + "'}";
    }
}
