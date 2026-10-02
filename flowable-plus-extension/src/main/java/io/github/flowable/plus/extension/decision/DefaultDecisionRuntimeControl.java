package io.github.flowable.plus.extension.decision;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行暂停控制面的默认实现（ADR-0042 第 10 节）：<b>由应用持有的进程内内存态</b>。
 *
 * <p>框架<b>不持久化</b>该状态、<b>不带管理端点</b>、<b>不读 Spring {@code Environment}</b> ——
 * 本类只把「应用怎么持有它」落成一个线程安全的开关位；应用可整体替换本 Bean 表达自己的持有方式
 * （如与自建熔断器联动）。</p>
 *
 * <p><b>线程安全</b>：拉管线的决策线程读、应用的管理线程写，故取 {@link AtomicBoolean} 而非裸布尔。</p>
 */
public final class DefaultDecisionRuntimeControl implements DecisionRuntimeControl {

    /** 暂停位（进程内内存态；不持久化、不暴露） */
    private final AtomicBoolean paused = new AtomicBoolean(false);

    @Override
    public void pause() {
        paused.set(true);
    }

    @Override
    public void resume() {
        paused.set(false);
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }
}
