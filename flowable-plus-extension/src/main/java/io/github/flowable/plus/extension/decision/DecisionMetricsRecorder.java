package io.github.flowable.plus.extension.decision;

/**
 * 决策观测事实的指标记录器（ADR-0042 第 10 节「消费三层」之③）。
 *
 * <p><b>机制专属替换点</b>：实现整体替换（默认实现住 starter，只在有 Micrometer 时装配，
 * 故「无指标后端」是合法装配态，本接口在机制内的引用一律<b>可选</b>）。默认实现<b>懒注册</b> ——
 * 未发生任何决策时不得创建本机制前缀的 meter。</p>
 *
 * <p><b>契约要点</b>：① 只收「未产出 / 失败」入错误计数（计错判据 = {@code failureKind != null}）；
 * ② 按政策未产出与重放各自独立计数；③ 维度基数受控，<b>不</b>得把 {@code processInstanceId}
 * 用作维度；④ 信号名 / 维度键 / tag value 一律取 {@link DecisionMetrics} 的常量。</p>
 *
 * <p>本接口的实现须是 best-effort：调用方（观测分发点）已按消费者隔离兜底，实现自身抛出的异常
 * 不会改变决策结局与流程状态。</p>
 */
public interface DecisionMetricsRecorder {

    /**
     * 记录一条观测事实。
     *
     * @param observation 观测事实，不得为 null
     */
    void record(DecisionObservation observation);
}
