package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import lombok.Getter;

import java.util.Date;

/**
 * 任务驳回事件。
 *
 * @author flowable-plus
 */
@Getter
public class TaskRejectedEvent implements DispatchableEvent {

    private final String taskId;
    private final String processInstanceId;
    private final String taskName;
    private final String nodeId;
    private final String assignee;
    private final String reason;
    private final Date rejectTime;

    private TaskRejectedEvent(String taskId, String processInstanceId, String taskName,
                              String nodeId, String assignee, String reason, Date rejectTime) {
        this.taskId = taskId;
        this.processInstanceId = processInstanceId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.assignee = assignee;
        this.reason = reason;
        this.rejectTime = rejectTime;
    }

    public static TaskRejectedEvent of(String taskId, String processInstanceId, String taskName,
                                        String nodeId, String assignee, String reason,
                                        Date rejectTime) {
        return new TaskRejectedEvent(taskId, processInstanceId, taskName,
                nodeId, assignee, reason, rejectTime);
    }

    @Override
    public Date getEventTime() {
        return rejectTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onTaskRejected(this);
    }

    @Override
    public String toString() {
        return "TaskRejectedEvent{taskId='" + taskId
                + "', processInstanceId='" + processInstanceId
                + "', nodeId='" + nodeId + "'}";
    }
}
