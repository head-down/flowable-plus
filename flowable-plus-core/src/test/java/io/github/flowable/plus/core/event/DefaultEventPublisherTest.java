package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件发布器单元测试。
 *
 * @author flowable-plus
 */
class DefaultEventPublisherTest {

    private static final Date NOW = new Date();

    // ======================== 多监听器注册与分发 ========================

    @Test
    void shouldDispatchToAllRegisteredListeners() {
        TrackingListener l1 = new TrackingListener();
        TrackingListener l2 = new TrackingListener();
        DefaultEventPublisher publisher = new DefaultEventPublisher(Arrays.asList(l1, l2));

        publisher.publish(ProcessStartedEvent.of("leave", "biz-1", "pi-001", "userA", NOW));

        assertThat(l1.startedCount).isEqualTo(1);
        assertThat(l2.startedCount).isEqualTo(1);
    }

    @Test
    void shouldOnlyDispatchMatchingEventType() {
        TrackingListener l1 = new TrackingListener();
        DefaultEventPublisher publisher = new DefaultEventPublisher(Collections.singletonList(l1));

        publisher.publish(TaskCompletedEvent.of("task-1", "pi-1", "审批", "node1", "userA", "同意", NOW));

        assertThat(l1.completedCount).isEqualTo(1);
        assertThat(l1.startedCount).isEqualTo(0);
        assertThat(l1.rejectedCount).isEqualTo(0);
        assertThat(l1.withdrawnCount).isEqualTo(0);
        assertThat(l1.revokedCount).isEqualTo(0);
        assertThat(l1.delegatedCount).isEqualTo(0);
        assertThat(l1.transferredCount).isEqualTo(0);
        assertThat(l1.endedCount).isEqualTo(0);
    }

    @Test
    void shouldDispatchAll8EventTypes() {
        TrackingListener l = new TrackingListener();
        DefaultEventPublisher publisher = new DefaultEventPublisher(Collections.singletonList(l));

        publisher.publish(ProcessStartedEvent.of("leave", "biz-1", "pi-1", "userA", NOW));
        publisher.publish(TaskCompletedEvent.of("t1", "pi-1", "审批", "n1", "userA", null, NOW));
        publisher.publish(TaskRejectedEvent.of("t2", "pi-1", "审批", "n2", "userB", "不同意", NOW));
        publisher.publish(TaskWithdrawnEvent.of("t3", "pi-1", "审批", "n3", "userC", "userD", "撤回", NOW));
        publisher.publish(ProcessRevokedEvent.of("pi-1", "leave", "biz-1", "userA", "撤销", NOW));
        publisher.publish(TaskDelegatedEvent.of("t4", "pi-1", "审批", "n4", "userE", "userF", null, NOW));
        publisher.publish(TaskTransferredEvent.of("t5", "pi-1", "审批", "n5", "userG", "userH", null, NOW));
        publisher.publish(ProcessEndedEvent.of("pi-1", "leave", "biz-1", NOW));

        assertThat(l.startedCount).isEqualTo(1);
        assertThat(l.completedCount).isEqualTo(1);
        assertThat(l.rejectedCount).isEqualTo(1);
        assertThat(l.withdrawnCount).isEqualTo(1);
        assertThat(l.revokedCount).isEqualTo(1);
        assertThat(l.delegatedCount).isEqualTo(1);
        assertThat(l.transferredCount).isEqualTo(1);
        assertThat(l.endedCount).isEqualTo(1);
    }

    // ======================== 异常隔离 ========================

    @Test
    void shouldIsolateListenerException() {
        FailingListener failing = new FailingListener();
        TrackingListener tracking = new TrackingListener();
        DefaultEventPublisher publisher = new DefaultEventPublisher(Arrays.asList(failing, tracking));

        publisher.publish(ProcessStartedEvent.of("leave", "biz-1", "pi-001", "userA", NOW));

        assertThat(tracking.startedCount).isEqualTo(1);
    }

    @Test
    void shouldNotThrowWhenListenerFails() {
        FailingListener failing = new FailingListener();
        DefaultEventPublisher publisher = new DefaultEventPublisher(Collections.singletonList(failing));

        // 不应抛出异常
        publisher.publish(ProcessStartedEvent.of("leave", "biz-1", "pi-001", "userA", NOW));
    }

    // ======================== 空列表 ========================

    @Test
    void shouldNotThrowWithEmptyListenerList() {
        DefaultEventPublisher publisher = new DefaultEventPublisher(Collections.emptyList());

        // 不应抛出异常
        publisher.publish(ProcessStartedEvent.of("leave", "biz-1", "pi-001", "userA", NOW));
    }

    @Test
    void shouldNotThrowWithNullListenerList() {
        DefaultEventPublisher publisher = new DefaultEventPublisher(null);

        // 不应抛出异常
        publisher.publish(ProcessStartedEvent.of("leave", "biz-1", "pi-001", "userA", NOW));
    }

    // ======================== 辅助内部类 ========================

    private static class TrackingListener implements ProcessEventListener {
        int startedCount;
        int completedCount;
        int rejectedCount;
        int withdrawnCount;
        int revokedCount;
        int delegatedCount;
        int transferredCount;
        int endedCount;

        @Override
        public void onProcessStarted(ProcessStartedEvent e) { startedCount++; }

        @Override
        public void onTaskCompleted(TaskCompletedEvent e) { completedCount++; }

        @Override
        public void onTaskRejected(TaskRejectedEvent e) { rejectedCount++; }

        @Override
        public void onTaskWithdrawn(TaskWithdrawnEvent e) { withdrawnCount++; }

        @Override
        public void onProcessRevoked(ProcessRevokedEvent e) { revokedCount++; }

        @Override
        public void onTaskDelegated(TaskDelegatedEvent e) { delegatedCount++; }

        @Override
        public void onTaskTransferred(TaskTransferredEvent e) { transferredCount++; }

        @Override
        public void onProcessEnded(ProcessEndedEvent e) { endedCount++; }
    }

    private static class FailingListener implements ProcessEventListener {
        @Override
        public void onProcessStarted(ProcessStartedEvent e) {
            throw new RuntimeException("模拟异常");
        }
    }
}
