package io.github.flowable.plus.extension.decision;

/**
 * 决策观测事实的可选消费 SPI（ADR-0042 第 10 节「消费三层」之②）。
 *
 * <p><b>普通接口，不是 Spring 事件</b> —— ADR-0042 明确「不把 Spring 事件当契约」。应用实现本接口
 * 即被回调；机制按<b>多实例集合</b>收集，调用<b>顺序不承诺</b>，且为<b>同线程同步调用</b>。</p>
 *
 * <p><b>调用时机</b>：观测事实在结局落定时构造<b>一条</b>，分发次序为「日志 → 指标 → 观测回调」，
 * 每观测一次调用一次；<b>不触发</b>的四类（未激活 / 节点未声明 / 事件面关闭 / 无活锚点）产出零观测，
 * 故不回调。回调抛出的异常由分发点隔离并降级写日志（<b>不再触发回调</b>，避免递归），
 * 不上抛、不改变决策结局与流程状态。</p>
 */
@FunctionalInterface
public interface DecisionObserver {

    /**
     * 消费一条观测事实。
     *
     * @param observation 观测事实，不得为 null
     */
    void onDecision(DecisionObservation observation);
}
