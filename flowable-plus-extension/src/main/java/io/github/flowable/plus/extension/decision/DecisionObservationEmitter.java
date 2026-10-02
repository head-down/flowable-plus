package io.github.flowable.plus.extension.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 决策观测事实的<b>单一构造点</b>：一条观测喂三面（ADR-0042 第 10 节「消费三层」）。
 *
 * <p><b>分发次序</b>：① 结构化日志（下限，不可弱化）→ ② 指标（有 Micrometer 才有消费者）
 * → ③ 观测回调。三个消费者<b>各自独立</b>：任一失败<b>不上抛</b>、不改变决策结局与流程状态；
 * 回调异常降级为一条警告日志，且<b>不再触发回调</b>（避免递归）。</p>
 *
 * <p><b>观测条数 ≠ 证据行数</b>：本类型只负责把已经落定的观测事实喂出去；「是否构造观测」由产生点
 * 决定 —— 不触发的四类（未激活 / 节点未声明 / 事件面关闭 / 无活锚点）产出<b>零观测</b>，
 * 根本不进入本类型。</p>
 *
 * <p><b>日志面</b>：SLF4J 参数化 + 固定字段名、单一 logger（{@link DecisionMetrics#LOGGER_NAME}）、
 * 每条观测一行、级别 = {@link DecisionSeverity} 直映。<b>不引</b> JSON encoder、<b>不用</b> MDC
 * （专属有界池复用线程，漏清即串）。</p>
 *
 * <p><b>观测面禁载</b>：日志与指标信号一律只载 {@link DecisionObservation} 的十六字段 —— 凭据材料、
 * 证据载荷内容、出域载荷内容、{@code actionSummary}、{@code subjectId} / {@code subjectName}、
 * {@code idempotencyKey}、异常 message 与堆栈、变量名与变量值都不在十六字段内，故结构上无从落面。</p>
 */
public class DecisionObservationEmitter {

    /** 观测面唯一 logger（名取自 {@link DecisionMetrics#LOGGER_NAME} 单一来源） */
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionMetrics.LOGGER_NAME);

    /**
     * 日志格式：固定字段名 + SLF4J 占位符，与 {@link DecisionObservation} 的十六字段逐一同名。
     *
     * <p>包内可见，供观测契约守卫断言「字段名固定」与「观测面禁载」。</p>
     */
    static final String LOG_FORMAT = "决策观测：taskId={} nodeId={} processInstanceId={} outcome={} failureKind={} "
            + "policyReason={} severity={} subjectType={} modelId={} chainStage={} latencyMs={} inputTokens={} "
            + "outputTokens={} writeDegradedCause={} admissionReason={} droppedContextSources={}";

    /** 指标记录器；<b>可空</b> —— 无 Micrometer 时缺省即无指标消费者（日志与回调不受影响） */
    private final DecisionMetricsRecorder metricsRecorder;

    /** 观测回调；顺序不承诺，调用为同线程同步 */
    private final List<DecisionObserver> observers;

    /**
     * 构造分发点。
     *
     * @param metricsRecorder 指标记录器，可空（无指标后端时传 null）
     * @param observers       观测回调，可空（等价于空集合）
     */
    public DecisionObservationEmitter(final DecisionMetricsRecorder metricsRecorder,
                                      final List<DecisionObserver> observers) {
        this.metricsRecorder = metricsRecorder;
        this.observers = observers == null
                ? Collections.<DecisionObserver>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(observers));
    }

    /**
     * 喂三面：日志 → 指标 → 观测回调。
     *
     * @param observation 观测事实，不得为 null
     */
    public void emit(final DecisionObservation observation) {
        Objects.requireNonNull(observation, "观测事实不得为 null：本类型是单一构造点的出口，空观测说明构造点未构造");
        writeLog(observation);
        recordMetrics(observation);
        notifyObservers(observation);
    }

    /**
     * 日志面的实参：与 {@link #LOG_FORMAT} 的占位符一一对应。
     *
     * <p>包内可见，供观测契约守卫做<b>可运行期</b>断言（载荷哨兵不出现于日志面）。</p>
     *
     * @param observation 观测事实
     * @return 十六个实参（顺序与 {@link #LOG_FORMAT} 一致），交给 SLF4J 可变参数
     */
    static List<Object> logArguments(final DecisionObservation observation) {
        return Arrays.asList(
                observation.getTaskId(),
                observation.getNodeId(),
                observation.getProcessInstanceId(),
                observation.getOutcome(),
                observation.getFailureKind(),
                observation.getPolicyReason(),
                observation.getSeverity(),
                observation.getSubjectType(),
                observation.getModelId(),
                observation.getChainStage(),
                observation.getLatencyMs(),
                observation.getInputTokens(),
                observation.getOutputTokens(),
                observation.getWriteDegradedCause(),
                observation.getAdmissionReason(),
                observation.getDroppedContextSources());
    }

    /**
     * 写结构化日志：每条观测一行，级别 = 严重度直映。
     *
     * <p><b>异常策略</b>：三个消费者一律 {@code catch (Exception)} 后降级 —— 这是 ADR-0042 第 10 节
     * 「观测面自身故障只降级、绝不上抛、绝不改变决策结局或流程状态」的直接实现（同族先例 =
     * core 的 {@code DefaultEventPublisher}），<b>不是</b>笼统吞异常。</p>
     */
    private void writeLog(final DecisionObservation observation) {
        try {
            final Object[] arguments = logArguments(observation).toArray();
            final DecisionSeverity severity = observation.getSeverity();
            if (DecisionSeverity.ERROR == severity) {
                LOGGER.error(LOG_FORMAT, arguments);
            } else if (DecisionSeverity.WARN == severity) {
                LOGGER.warn(LOG_FORMAT, arguments);
            } else {
                LOGGER.info(LOG_FORMAT, arguments);
            }
        } catch (Exception e) {
            // 日志面是观测面的最外层可见面：它自身写不下去时已无处可退（失败记录自身写不下去，
            // 只能退到更外层 —— 而更外层就是它），故此处吞掉异常、不上抛。
        }
    }

    /**
     * 指标面：有消费者才记；消费者失败只降级写日志。
     */
    private void recordMetrics(final DecisionObservation observation) {
        if (metricsRecorder == null) {
            return;
        }
        try {
            metricsRecorder.record(observation);
        } catch (Exception e) {
            LOGGER.warn("决策指标记录器回调异常：severity={}", observation.getSeverity(), e);
        }
    }

    /**
     * 观测回调面：多实例逐个同步调用；单个回调失败只降级写日志，不中断其余回调、不上抛。
     */
    private void notifyObservers(final DecisionObservation observation) {
        for (final DecisionObserver observer : observers) {
            try {
                observer.onDecision(observation);
            } catch (Exception e) {
                LOGGER.warn("DecisionObserver {} 回调异常：severity={}",
                        observer.getClass().getName(), observation.getSeverity(), e);
            }
        }
    }
}
