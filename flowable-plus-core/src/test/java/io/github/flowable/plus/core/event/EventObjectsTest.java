package io.github.flowable.plus.core.event;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件对象构造测试。
 *
 * @author flowable-plus
 */
class EventObjectsTest {

    private static final String PID = "pi-001";
    private static final String TASK_ID = "task-001";
    private static final Date NOW = new Date();

    // ======================== ProcessStartedEvent ========================

    @Test
    void processStartedEvent_shouldExposeAllFields() {
        ProcessStartedEvent e = ProcessStartedEvent.of("leave", "biz-1", PID, "userA", NOW);

        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(e.getBusinessKey()).isEqualTo("biz-1");
        assertThat(e.getStartUserId()).isEqualTo("userA");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getStartTime()).isEqualTo(NOW);
        assertThat(e instanceof ProcessEvent).isTrue();
    }

    // ======================== TaskCompletedEvent ========================

    @Test
    void taskCompletedEvent_shouldExposeAllFields() {
        TaskCompletedEvent e = TaskCompletedEvent.of(TASK_ID, PID, "审批", "node1", "userA", "同意", NOW);

        assertThat(e.getTaskId()).isEqualTo(TASK_ID);
        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getTaskName()).isEqualTo("审批");
        assertThat(e.getNodeId()).isEqualTo("node1");
        assertThat(e.getAssignee()).isEqualTo("userA");
        assertThat(e.getComment()).isEqualTo("同意");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getCompleteTime()).isEqualTo(NOW);
    }

    @Test
    void taskCompletedEvent_commentCanBeNull() {
        TaskCompletedEvent e = TaskCompletedEvent.of(TASK_ID, PID, "审批", "node1", "userA", null, NOW);

        assertThat(e.getComment()).isNull();
    }

    // ======================== TaskRejectedEvent ========================

    @Test
    void taskRejectedEvent_shouldExposeAllFields() {
        TaskRejectedEvent e = TaskRejectedEvent.of(TASK_ID, PID, "审批", "node1", "userA", "不同意", NOW);

        assertThat(e.getTaskId()).isEqualTo(TASK_ID);
        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getTaskName()).isEqualTo("审批");
        assertThat(e.getNodeId()).isEqualTo("node1");
        assertThat(e.getAssignee()).isEqualTo("userA");
        assertThat(e.getReason()).isEqualTo("不同意");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getRejectTime()).isEqualTo(NOW);
    }

    @Test
    void taskRejectedEvent_reasonCanBeNull() {
        TaskRejectedEvent e = TaskRejectedEvent.of(TASK_ID, PID, "审批", "node1", "userA", null, NOW);

        assertThat(e.getReason()).isNull();
    }

    // ======================== TaskWithdrawnEvent ========================

    @Test
    void taskWithdrawnEvent_shouldExposeAllFields() {
        TaskWithdrawnEvent e = TaskWithdrawnEvent.of(TASK_ID, PID, "审批", "node1", "userA", "prevUser", "撤回原因", NOW);

        assertThat(e.getTaskId()).isEqualTo(TASK_ID);
        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getTaskName()).isEqualTo("审批");
        assertThat(e.getNodeId()).isEqualTo("node1");
        assertThat(e.getAssignee()).isEqualTo("userA");
        assertThat(e.getOperator()).isEqualTo("prevUser");
        assertThat(e.getReason()).isEqualTo("撤回原因");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getWithdrawTime()).isEqualTo(NOW);
    }

    // ======================== ProcessRevokedEvent ========================

    @Test
    void processRevokedEvent_shouldExposeAllFields() {
        ProcessRevokedEvent e = ProcessRevokedEvent.of(PID, "leave", "biz-1", "userA", "撤销原因", NOW);

        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(e.getBusinessKey()).isEqualTo("biz-1");
        assertThat(e.getOperator()).isEqualTo("userA");
        assertThat(e.getReason()).isEqualTo("撤销原因");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getRevokeTime()).isEqualTo(NOW);
    }

    // ======================== TaskDelegatedEvent ========================

    @Test
    void taskDelegatedEvent_shouldExposeAllFields() {
        TaskDelegatedEvent e = TaskDelegatedEvent.of(TASK_ID, PID, "审批", "node1", "userA", "userB", "委派原因", NOW);

        assertThat(e.getTaskId()).isEqualTo(TASK_ID);
        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getTaskName()).isEqualTo("审批");
        assertThat(e.getNodeId()).isEqualTo("node1");
        assertThat(e.getDelegator()).isEqualTo("userA");
        assertThat(e.getDelegatee()).isEqualTo("userB");
        assertThat(e.getReason()).isEqualTo("委派原因");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getDelegateTime()).isEqualTo(NOW);
    }

    // ======================== TaskTransferredEvent ========================

    @Test
    void taskTransferredEvent_shouldExposeAllFields() {
        TaskTransferredEvent e = TaskTransferredEvent.of(TASK_ID, PID, "审批", "node1", "userA", "userB", "转办原因", NOW);

        assertThat(e.getTaskId()).isEqualTo(TASK_ID);
        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getTaskName()).isEqualTo("审批");
        assertThat(e.getNodeId()).isEqualTo("node1");
        assertThat(e.getFromAssignee()).isEqualTo("userA");
        assertThat(e.getToAssignee()).isEqualTo("userB");
        assertThat(e.getReason()).isEqualTo("转办原因");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getTransferTime()).isEqualTo(NOW);
    }

    // ======================== ProcessEndedEvent ========================

    @Test
    void processEndedEvent_shouldExposeAllFields() {
        ProcessEndedEvent e = ProcessEndedEvent.of(PID, "leave", "biz-1", NOW);

        assertThat(e.getProcessInstanceId()).isEqualTo(PID);
        assertThat(e.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(e.getBusinessKey()).isEqualTo("biz-1");
        assertThat(e.getEventTime()).isEqualTo(NOW);
        assertThat(e.getEndTime()).isEqualTo(NOW);
    }
}
