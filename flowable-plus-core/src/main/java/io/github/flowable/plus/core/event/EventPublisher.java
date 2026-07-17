package io.github.flowable.plus.core.event;

/**
 * 事件发布器接口。核心层定义接口，允许不同模块提供各自的实现
 * （如同步 {@link DefaultEventPublisher} 或异步 {@code AsyncEventPublisher}）。
 *
 * @author flowable-plus
 */
public interface EventPublisher {

    /**
     * 发布流程事件。所有注册的 {@link io.github.flowable.plus.core.spi.ProcessEventListener}
     * 将按注册顺序收到通知。
     *
     * @param event 流程事件对象，不可为 null
     */
    void publish(ProcessEvent event);
}
