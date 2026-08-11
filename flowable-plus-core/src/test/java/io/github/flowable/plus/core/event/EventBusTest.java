package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.domain.PlusTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * EventBus 单元测试：覆盖 null 发布者短路与语义方法的字段映射、时间戳生成。
 *
 * @author flowable-plus
 */
class EventBusTest {

    private static final Date CREATE_TIME = new Date(1000L);

    private PlusTask createTask() {
        return new PlusTask("task-001", "leave:1:abc", "node1", "pi-001",
                "userA", null, "审批", "exec-1", CREATE_TIME);
    }

    // ======================== null 发布者 ========================

    @Test
    void shouldBeDisabledWithoutPublisher() {
        EventBus eventBus = new EventBus(null);

        assertThat(eventBus.isEnabled()).isFalse();
    }

    @Test
    void allSemanticMethodsShouldNoopWithoutPublisher() {
        EventBus eventBus = new EventBus(null);
        PlusTask task = createTask();

        eventBus.publish(ProcessStartedEvent.of("leave", "biz-1", "pi-1", "userA", new Date()));
        eventBus.taskCompleted(task, "userA", "同意");
        eventBus.taskRejected(task, "不同意");
        eventBus.taskWithdrawn(task, "userB", "撤回");
        eventBus.taskTransferred(task, "userA", "userC", "转办");
        eventBus.taskJumped(task, "node0", "跳转", "REJECT");
        eventBus.taskDelegated(task, "userA", "userD", "委派");
        eventBus.processStarted("leave", "biz-1", "pi-1", "userA");
        eventBus.processInvalidated("pi-1", "leave", "biz-1", "userA", "作废");
        eventBus.processEnded("pi-1", "leave", "biz-1", new Date());

        // 不抛异常即通过
    }

    // ======================== 有发布者：委托与字段映射 ========================

    @Test
    void shouldBeEnabledWithPublisher() {
        EventBus eventBus = new EventBus(mock(EventPublisher.class));

        assertThat(eventBus.isEnabled()).isTrue();
    }

    @Test
    void publishShouldDelegateAsIs() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);
        ProcessStartedEvent event = ProcessStartedEvent.of("leave", "biz-1", "pi-1", "userA", new Date());

        eventBus.publish(event);

        verify(mockEp).publish(event);
    }

    @Test
    void taskCompletedShouldMapFieldsAndGenerateTime() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.taskCompleted(createTask(), "userA", "同意");

        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(mockEp).publish(captor.capture());
        TaskCompletedEvent event = captor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-001");
        assertThat(event.getProcessInstanceId()).isEqualTo("pi-001");
        assertThat(event.getTaskName()).isEqualTo("审批");
        assertThat(event.getNodeId()).isEqualTo("node1");
        assertThat(event.getAssignee()).isEqualTo("userA");
        assertThat(event.getComment()).isEqualTo("同意");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void taskRejectedShouldTakeAssigneeFromTask() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.taskRejected(createTask(), "不同意");

        ArgumentCaptor<TaskRejectedEvent> captor = ArgumentCaptor.forClass(TaskRejectedEvent.class);
        verify(mockEp).publish(captor.capture());
        TaskRejectedEvent event = captor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-001");
        assertThat(event.getAssignee()).isEqualTo("userA");
        assertThat(event.getReason()).isEqualTo("不同意");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void taskWithdrawnShouldMapOperator() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.taskWithdrawn(createTask(), "userB", "撤回");

        ArgumentCaptor<TaskWithdrawnEvent> captor = ArgumentCaptor.forClass(TaskWithdrawnEvent.class);
        verify(mockEp).publish(captor.capture());
        TaskWithdrawnEvent event = captor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-001");
        assertThat(event.getAssignee()).isEqualTo("userA");
        assertThat(event.getOperator()).isEqualTo("userB");
        assertThat(event.getReason()).isEqualTo("撤回");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void taskTransferredShouldMapFromAndTo() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.taskTransferred(createTask(), "userA", "userC", "转办");

        ArgumentCaptor<TaskTransferredEvent> captor = ArgumentCaptor.forClass(TaskTransferredEvent.class);
        verify(mockEp).publish(captor.capture());
        TaskTransferredEvent event = captor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-001");
        assertThat(event.getFromAssignee()).isEqualTo("userA");
        assertThat(event.getToAssignee()).isEqualTo("userC");
        assertThat(event.getReason()).isEqualTo("转办");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void taskJumpedShouldMapTargetNodeAndCommentType() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.taskJumped(createTask(), "node0", "跳转", "REJECT");

        ArgumentCaptor<TaskJumpedEvent> captor = ArgumentCaptor.forClass(TaskJumpedEvent.class);
        verify(mockEp).publish(captor.capture());
        TaskJumpedEvent event = captor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-001");
        assertThat(event.getAssignee()).isEqualTo("userA");
        assertThat(event.getTargetNodeId()).isEqualTo("node0");
        assertThat(event.getReason()).isEqualTo("跳转");
        assertThat(event.getCommentType()).isEqualTo("REJECT");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void taskDelegatedShouldMapDelegatorAndDelegatee() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.taskDelegated(createTask(), "userA", "userD", "委派");

        ArgumentCaptor<TaskDelegatedEvent> captor = ArgumentCaptor.forClass(TaskDelegatedEvent.class);
        verify(mockEp).publish(captor.capture());
        TaskDelegatedEvent event = captor.getValue();
        assertThat(event.getTaskId()).isEqualTo("task-001");
        assertThat(event.getDelegator()).isEqualTo("userA");
        assertThat(event.getDelegatee()).isEqualTo("userD");
        assertThat(event.getReason()).isEqualTo("委派");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void processStartedShouldMapAllParams() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.processStarted("leave", "biz-1", "pi-1", "userA");

        ArgumentCaptor<ProcessStartedEvent> captor = ArgumentCaptor.forClass(ProcessStartedEvent.class);
        verify(mockEp).publish(captor.capture());
        ProcessStartedEvent event = captor.getValue();
        assertThat(event.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(event.getBusinessKey()).isEqualTo("biz-1");
        assertThat(event.getProcessInstanceId()).isEqualTo("pi-1");
        assertThat(event.getStartUserId()).isEqualTo("userA");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void processInvalidatedShouldMapOperatorAndReason() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);

        eventBus.processInvalidated("pi-1", "leave", "biz-1", "userA", "作废");

        ArgumentCaptor<ProcessInvalidatedEvent> captor = ArgumentCaptor.forClass(ProcessInvalidatedEvent.class);
        verify(mockEp).publish(captor.capture());
        ProcessInvalidatedEvent event = captor.getValue();
        assertThat(event.getProcessInstanceId()).isEqualTo("pi-1");
        assertThat(event.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(event.getBusinessKey()).isEqualTo("biz-1");
        assertThat(event.getOperator()).isEqualTo("userA");
        assertThat(event.getReason()).isEqualTo("作废");
        assertThat(event.getEventTime()).isNotNull();
    }

    @Test
    void processEndedShouldPassThroughEndTime() {
        EventPublisher mockEp = mock(EventPublisher.class);
        EventBus eventBus = new EventBus(mockEp);
        Date endTime = new Date(2000L);

        eventBus.processEnded("pi-1", "leave", "biz-1", endTime);

        ArgumentCaptor<ProcessEndedEvent> captor = ArgumentCaptor.forClass(ProcessEndedEvent.class);
        verify(mockEp).publish(captor.capture());
        ProcessEndedEvent event = captor.getValue();
        assertThat(event.getProcessInstanceId()).isEqualTo("pi-1");
        assertThat(event.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(event.getBusinessKey()).isEqualTo("biz-1");
        assertThat(event.getEndTime()).isSameAs(endTime);
    }
}
