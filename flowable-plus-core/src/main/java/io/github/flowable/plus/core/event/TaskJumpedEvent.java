package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import lombok.Getter;

import java.util.Date;

/**
 * 任务跳转事件。审批人通过跳转操作将当前节点跳转至任一已完成的历史节点。
 *
 * @author flowable-plus
 */
@Getter
public class TaskJumpedEvent implements DispatchableEvent {

    private final String taskId;
    private final String processInstanceId;
    private final String taskName;
    private final String nodeId;
    private final String assignee;
    private final String targetNodeId;
    private final String reason;
    private final String commentType;
    private final Date jumpTime;

    private TaskJumpedEvent(String taskId, String processInstanceId, String taskName,
                            String nodeId, String assignee, String targetNodeId,
                            String reason, String commentType, Date jumpTime) {
        this.taskId = taskId;
        this.processInstanceId = processInstanceId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.assignee = assignee;
        this.targetNodeId = targetNodeId;
        this.reason = reason;
        this.commentType = commentType;
        this.jumpTime = jumpTime;
    }

    public static TaskJumpedEvent of(String taskId, String processInstanceId, String taskName,
                                      String nodeId, String assignee, String targetNodeId,
                                      String reason, String commentType, Date jumpTime) {
        return new TaskJumpedEvent(taskId, processInstanceId, taskName,
                nodeId, assignee, targetNodeId, reason, commentType, jumpTime);
    }

    @Override
    public Date getEventTime() {
        return jumpTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onTaskJumped(this);
    }

    @Override
    public String toString() {
        return "TaskJumpedEvent{taskId='" + taskId
                + "', processInstanceId='" + processInstanceId
                + "', nodeId='" + nodeId
                + "', targetNodeId='" + targetNodeId + "'}";
    }
}
