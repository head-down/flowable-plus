package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.event.ProcessEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步事件发布器，将事件发布提交到独立线程池执行。
 * 包装同步的 {@link io.github.flowable.plus.core.event.DefaultEventPublisher}。
 *
 * @author flowable-plus
 */
public class AsyncEventPublisher implements EventPublisher {

    private final EventPublisher delegate;
    private final ThreadPoolTaskExecutor executor;

    public AsyncEventPublisher(EventPublisher delegate, ThreadPoolTaskExecutor executor) {
        this.delegate = delegate;
        this.executor = executor;
    }

    @Override
    public void publish(ProcessEvent event) {
        executor.execute(() -> delegate.publish(event));
    }
}
