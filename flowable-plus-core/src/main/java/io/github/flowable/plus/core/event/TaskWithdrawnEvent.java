package io.github.flowable.plus.core.event;

import java.util.Date;

/**
 * 任务撤回事件。
 * assignee 为被撤回任务的当前审批人，operator 为执行撤回操作的人（上一节点审批人）。
 *
 * @author flowable-plus
 */
public class TaskWithdrawnEvent implements ProcessEvent {

    private final String taskId;
    private final String processInstanceId;
    private final String taskName;
    private final String nodeId;
    private final String assignee;
    private final String operator;
    private final String reason;
    private final Date withdrawTime;

    private TaskWithdrawnEvent(String taskId, String processInstanceId, String taskName,
                               String nodeId, String assignee, String operator,
                               String reason, Date withdrawTime) {
        this.taskId = taskId;
        this.processInstanceId = processInstanceId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.assignee = assignee;
        this.operator = operator;
        this.reason = reason;
        this.withdrawTime = withdrawTime;
    }

    public static TaskWithdrawnEvent of(String taskId, String processInstanceId, String taskName,
                                         String nodeId, String assignee, String operator,
                                         String reason, Date withdrawTime) {
        return new TaskWithdrawnEvent(taskId, processInstanceId, taskName,
                nodeId, assignee, operator, reason, withdrawTime);
    }

    @Override
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    @Override
    public Date getEventTime() {
        return withdrawTime;
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

    public String getOperator() {
        return operator;
    }

    public String getReason() {
        return reason;
    }

    public Date getWithdrawTime() {
        return withdrawTime;
    }

    @Override
    public String toString() {
        return "TaskWithdrawnEvent{taskId='" + taskId
                + "', processInstanceId='" + processInstanceId
                + "', nodeId='" + nodeId + "'}";
    }
}
