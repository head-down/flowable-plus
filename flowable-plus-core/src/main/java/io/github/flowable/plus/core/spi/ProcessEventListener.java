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
 * <p><b>进阶用法 — CQRS 审批宽表：</b>当待办/已办列表需要 JOIN 业务表
 * 做条件过滤，或面临大数据量精确分页时，可实现此接口，在
 * {@link #onTaskCompleted(TaskCompletedEvent)} 中将审批记录异步写入
 * 业务侧审批宽表（含流程摘要 + 业务字段），查询"待办/已办"直接走业务表，
 * 彻底解耦 Flowable 引擎表。参见
 * {@link io.github.flowable.plus.core.api.QueryOperations}。</p>
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
