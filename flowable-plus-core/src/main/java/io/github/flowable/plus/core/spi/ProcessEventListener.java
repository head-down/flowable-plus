package io.github.flowable.plus.core.spi;

import io.github.flowable.plus.core.event.ProcessEndedEvent;
import io.github.flowable.plus.core.event.ProcessRevokedEvent;
import io.github.flowable.plus.core.event.ProcessStartedEvent;
import io.github.flowable.plus.core.event.TaskCompletedEvent;
import io.github.flowable.plus.core.event.TaskDelegatedEvent;
import io.github.flowable.plus.core.event.TaskRejectedEvent;
import io.github.flowable.plus.core.event.TaskTransferredEvent;
import io.github.flowable.plus.core.event.TaskWithdrawnEvent;

/**
 * 流程事件监听器 SPI，业务系统实现此接口以在流程关键节点接收通知。
 * 所有方法默认空实现，业务系统按需覆盖。
 *
 * <p>回调异常被 try-catch 隔离，不会影响主业务流程。</p>
 *
 * @author flowable-plus
 */
public interface ProcessEventListener {

    default void onProcessStarted(ProcessStartedEvent event) {}

    default void onTaskCompleted(TaskCompletedEvent event) {}

    default void onTaskRejected(TaskRejectedEvent event) {}

    default void onTaskWithdrawn(TaskWithdrawnEvent event) {}

    default void onProcessRevoked(ProcessRevokedEvent event) {}

    default void onTaskDelegated(TaskDelegatedEvent event) {}

    default void onTaskTransferred(TaskTransferredEvent event) {}

    default void onProcessEnded(ProcessEndedEvent event) {}
}
