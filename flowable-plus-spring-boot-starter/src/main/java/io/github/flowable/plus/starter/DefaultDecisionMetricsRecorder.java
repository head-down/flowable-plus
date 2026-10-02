package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.extension.decision.DecisionMetrics;
import io.github.flowable.plus.extension.decision.DecisionMetricsRecorder;
import io.github.flowable.plus.extension.decision.DecisionObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 决策指标记录器的默认实现（starter <b>包内可见</b>，不进公开命名表；命名取中立词）。
 *
 * <p><b>懒注册</b>：首次观测时才向 {@link MeterRegistry} 取 meter —— 上下文启动后、未发生任何决策时
 * 本机制前缀的 meter 数为 <b>0</b>（不得在启动期急切创建指标名）。无 micrometer 时
 * {@code ObjectProvider} 解析为空 ⇒ 直接返回（缺省即无指标消费者；日志与回调不受影响）。</p>
 *
 * <p><b>信号映射</b>（信号名与维度键取 {@link DecisionMetrics} 常量，单一来源；枚举取值统一映射为
 * 常量名小写蛇形）：</p>
 *
 * <ul>
 *   <li>{@code failureKind != null}（计错判据，不挂 {@code outcome}）⇒ failure 计数，
 *       维度 {@code failureKind} / {@code severity}；</li>
 *   <li>{@code outcome == NO_SUGGESTION_BY_POLICY} ⇒ no.suggestion.by.policy 计数，
 *       维度 {@code policyReason}（绝不入错误率）；</li>
 *   <li>{@code writeDegradedCause != null} ⇒ write.degraded 计数，维度 {@code cause}；</li>
 *   <li>{@code admissionReason != null} ⇒ submission.rejected 计数，维度 {@code reason}；</li>
 *   <li>{@code droppedContextSources} 非空 ⇒ context.source.dropped 计数（逐来源一条），
 *       维度 {@code contextSource}；</li>
 *   <li>{@code latencyMs != null} ⇒ latency 计时，维度 {@code severity}；</li>
 *   <li>{@code inputTokens} / {@code outputTokens} 非空 ⇒ tokens 计数（逐方向），
 *       维度 {@code direction} / {@code modelId}（缺失不猜；直提 / 非模型端点不产生 token 观测）。</li>
 * </ul>
 *
 * <p>replay 与 {@code tokens.usage.missing} 是<b>写入点直发</b>的信号（不经观测构造点、无观测字段可依），
 * 不在本记录器的承载面内。</p>
 */
final class DefaultDecisionMetricsRecorder implements DecisionMetricsRecorder {

    /** 指标注册表提供者（懒解析；无 micrometer 时每次解析为空） */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    DefaultDecisionMetricsRecorder(final ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public void record(final DecisionObservation observation) {
        final MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        if (observation.getFailureKind() != null) {
            registry.counter(DecisionMetrics.FLOWABLE_PLUS_DECISION_FAILURE,
                    DecisionMetrics.FAILURE_KIND, lowerSnake(observation.getFailureKind()),
                    DecisionMetrics.SEVERITY, lowerSnake(observation.getSeverity()))
                    .increment();
        }
        if (observation.getOutcome() == DecisionOutcome.NO_SUGGESTION_BY_POLICY) {
            registry.counter(DecisionMetrics.FLOWABLE_PLUS_DECISION_NO_SUGGESTION_BY_POLICY,
                    DecisionMetrics.POLICY_REASON, lowerSnake(observation.getPolicyReason()))
                    .increment();
        }
        if (observation.getWriteDegradedCause() != null) {
            registry.counter(DecisionMetrics.FLOWABLE_PLUS_DECISION_WRITE_DEGRADED,
                    DecisionMetrics.CAUSE, lowerSnake(observation.getWriteDegradedCause()))
                    .increment();
        }
        if (observation.getAdmissionReason() != null) {
            registry.counter(DecisionMetrics.FLOWABLE_PLUS_DECISION_SUBMISSION_REJECTED,
                    DecisionMetrics.REASON, lowerSnake(observation.getAdmissionReason()))
                    .increment();
        }
        if (observation.getDroppedContextSources() != null) {
            observation.getDroppedContextSources().forEach(dropped ->
                    registry.counter(DecisionMetrics.FLOWABLE_PLUS_DECISION_CONTEXT_SOURCE_DROPPED,
                            DecisionMetrics.CONTEXT_SOURCE, lowerSnake(dropped)).increment());
        }
        if (observation.getLatencyMs() != null) {
            registry.timer(DecisionMetrics.FLOWABLE_PLUS_DECISION_LATENCY,
                            DecisionMetrics.SEVERITY, lowerSnake(observation.getSeverity()))
                    .record(observation.getLatencyMs(), TimeUnit.MILLISECONDS);
        }
        if (observation.getInputTokens() != null) {
            tokenCounter(registry, DecisionMetrics.DIRECTION_INPUT, observation.getModelId())
                    .increment(observation.getInputTokens());
        }
        if (observation.getOutputTokens() != null) {
            tokenCounter(registry, DecisionMetrics.DIRECTION_OUTPUT, observation.getModelId())
                    .increment(observation.getOutputTokens());
        }
    }

    private Counter tokenCounter(final MeterRegistry registry,
                                                               final String direction, final String modelId) {
        return registry.counter(DecisionMetrics.FLOWABLE_PLUS_DECISION_TOKENS,
                DecisionMetrics.DIRECTION, direction,
                DecisionMetrics.MODEL_ID, modelId == null ? "unknown" : modelId);
    }

    /**
     * 枚举取值 → 小写蛇形（维度值统一映射）。
     *
     * <p><b>对个人规范的有意偏离（如实登记，同 {@code E2}/{@code E3} 的 {@code name()} 判例形态）</b>：
     * 维度值契约 = 「枚举常量名小写蛇形」（命名宪章 / 观测面闭集值口径），取值必须派生自
     * {@code name()} —— {@code toString()} 可被子类改写，不构成同一契约。</p>
     */
    private static String lowerSnake(final Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }
}
