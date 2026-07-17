package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;

/**
 * 可自分发事件的标记接口，继承 {@link ProcessEvent} 并增加分发能力。
 * 框架内部事件类实现此接口，{@code accept()} 方法将自身分发给监听器的对应回调。
 *
 * @author flowable-plus
 */
public interface DispatchableEvent extends ProcessEvent {

    /** 将本事件分发给指定监听器的对应回调方法 */
    void accept(ProcessEventListener listener);
}
