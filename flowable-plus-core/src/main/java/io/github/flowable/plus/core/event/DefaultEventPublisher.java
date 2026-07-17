package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 默认事件发布器，同步遍历所有 {@link ProcessEventListener} 并分发事件。
 * 每个监听器的异常被独立捕获，通过 SLF4J 记录警告日志。
 *
 * @author flowable-plus
 */
public class DefaultEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventPublisher.class);

    private final List<ProcessEventListener> listeners;

    public DefaultEventPublisher(List<ProcessEventListener> listeners) {
        this.listeners = listeners != null ? listeners : Collections.emptyList();
    }

    @Override
    public void publish(ProcessEvent event) {
        if (listeners.isEmpty()) {
            return;
        }
        for (ProcessEventListener listener : listeners) {
            try {
                dispatch(listener, event);
            } catch (Exception e) {
                log.warn("ProcessEventListener {} 回调异常: event={}",
                        listener.getClass().getName(), event.getClass().getSimpleName(), e);
            }
        }
    }

    private void dispatch(ProcessEventListener listener, ProcessEvent event) {
        if (event instanceof ProcessStartedEvent) {
            listener.onProcessStarted((ProcessStartedEvent) event);
        } else if (event instanceof TaskCompletedEvent) {
            listener.onTaskCompleted((TaskCompletedEvent) event);
        } else if (event instanceof TaskRejectedEvent) {
            listener.onTaskRejected((TaskRejectedEvent) event);
        } else if (event instanceof TaskWithdrawnEvent) {
            listener.onTaskWithdrawn((TaskWithdrawnEvent) event);
        } else if (event instanceof ProcessRevokedEvent) {
            listener.onProcessRevoked((ProcessRevokedEvent) event);
        } else if (event instanceof TaskDelegatedEvent) {
            listener.onTaskDelegated((TaskDelegatedEvent) event);
        } else if (event instanceof TaskTransferredEvent) {
            listener.onTaskTransferred((TaskTransferredEvent) event);
        } else if (event instanceof ProcessEndedEvent) {
            listener.onProcessEnded((ProcessEndedEvent) event);
        }
    }
}
