package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 决策观测事实（ADR-0042 第 10 节「可观测面」）：一次「已触发的决策尝试」或一次「建议提交」
 * 在结局落定时构造的<b>瞬时观测单元</b>，同时喂给结构化日志、可选观测回调与指标三个消费者，
 * 使三面字段同值。
 *
 * <p><b>瞬时不落盘</b>：本类型不进审批轨迹、不持久化、无 JSON 形态 —— 它与「决策证据」是两件事
 * （观测条数 ≠ 证据行数：池满 / 缺身份 / 锚点失效 / 实例已结束<b>不落证据行但有观测</b>）。</p>
 *
 * <p><b>字段集恰十六</b>，按观测用途分列：</p>
 *
 * <ul>
 *   <li>定位：{@link #taskId} / {@link #nodeId} / {@link #processInstanceId}；</li>
 *   <li>结局：{@link #outcome}（<b>可空</b> —— 三叶子态是闭集，未物质化的三情形<b>无结局</b>、
 *       不新增第四态）；</li>
 *   <li>分类：{@link #failureKind} / {@link #policyReason} / {@link #severity}；</li>
 *   <li>归因：{@link #subjectType} / {@link #modelId}；</li>
 *   <li>链路：{@link #chainStage}；</li>
 *   <li>计量：{@link #latencyMs} / {@link #inputTokens} / {@link #outputTokens}；</li>
 *   <li>未物质化：{@link #writeDegradedCause} / {@link #admissionReason}；</li>
 *   <li>装配：{@link #droppedContextSources}。</li>
 * </ul>
 *
 * <p><b>必填性</b>：{@code severity} <b>必填</b>（构造期校验，任何观测都有严重度）；
 * 其余十五字段均可空 —— 可空不是「没填」，而是该观测确实取不到该事实（例如未被模型调用时
 * 两个 token 字段无值：<b>「不适用」与「未知」必须可分</b>）。</p>
 *
 * <p><b>观测面禁载</b>（含由其派生的任何信号）：凭据材料 / 证据载荷内容（{@code inputSnapshot} ·
 * {@code rawOutput} · {@code rationaleNarrative} · {@code rationaleFacts} 的<b>值</b>）/
 * 出域载荷内容 / {@code actionSummary} / {@code subjectId} · {@code subjectName} /
 * {@code idempotencyKey} / 异常 message 与堆栈 / 变量名与变量值。<b>日志可记
 * {@code processInstanceId}，指标不可</b>（维度受控，有意不对称）。</p>
 *
 * <p><b>单一构造点</b>：一次尝试 / 提交在结局落定时构造<b>一条</b>本类型实例，由
 * {@link DecisionObservationEmitter} 喂三面；不触发的四类（未激活 / 节点未声明 / 事件面关闭 /
 * 无活锚点）产出<b>零观测</b>。唯一例外是重放计数 —— 它是读侧派生事实、按「派生概念不得落字段」
 * 不得落本类型，故由写入点直发。</p>
 */
@Getter
public final class DecisionObservation {

    // ======================== 定位 ========================

    /** 锚点任务标识（可空：缺身份的提交没有锚点） */
    private final String taskId;

    /** 节点标识 */
    private final String nodeId;

    /** 流程实例标识（日志可记、指标不可作维度） */
    private final String processInstanceId;

    // ======================== 结局 ========================

    /** 顶层结局（三叶子态；<b>可空</b>） */
    private final DecisionOutcome outcome;

    // ======================== 分类 ========================

    /** 失败类别（计错判据 = 本字段非 null） */
    private final DecisionFailureKind failureKind;

    /** 按政策未产出的原因（与 failureKind 互斥） */
    private final DecisionPolicyReason policyReason;

    /** 严重度（<b>必填</b>，同时是日志级别的映射源） */
    private final DecisionSeverity severity;

    // ======================== 归因 ========================

    /** 主体类型 */
    private final DecisionSubjectType subjectType;

    /** 模型标识（provider 契约事实；未经模型调用时为空） */
    private final String modelId;

    // ======================== 链路 ========================

    /** 链路阶段 */
    private final DecisionChainStage chainStage;

    // ======================== 计量 ========================

    /** 本次尝试的耗时（毫秒） */
    private final Long latencyMs;

    /** 入向 token 用量（未经模型调用时为空） */
    private final Long inputTokens;

    /** 出向 token 用量（未经模型调用时为空） */
    private final Long outputTokens;

    // ======================== 未物质化 ========================

    /** 写入期降级原因（锚点失效 / 实例已结束） */
    private final WriteDegradedCause writeDegradedCause;

    /** 准入失败原因（未物质化的准入失败下只取 {@code IDEMPOTENCY_KEY_REQUIRED}） */
    private final SuggestionAdmissionReason admissionReason;

    // ======================== 装配 ========================

    /** 装配器丢弃的数据源（与 clamp 丢弃分列；构造期收为不可修改集合） */
    private final List<DecisionContextSource> droppedContextSources;

    /**
     * 构造一条观测事实。
     *
     * @param taskId                锚点任务标识，可空
     * @param nodeId                节点标识，可空
     * @param processInstanceId     流程实例标识，可空
     * @param outcome               顶层结局，可空
     * @param failureKind           失败类别，可空
     * @param policyReason          按政策未产出的原因，可空
     * @param severity              严重度，<b>不得为 null</b>
     * @param subjectType           主体类型，可空
     * @param modelId               模型标识，可空
     * @param chainStage            链路阶段，可空
     * @param latencyMs             耗时（毫秒），可空
     * @param inputTokens           入向 token 用量，可空
     * @param outputTokens          出向 token 用量，可空
     * @param writeDegradedCause    写入期降级原因，可空
     * @param admissionReason       准入失败原因，可空
     * @param droppedContextSources 装配器丢弃的数据源，可空
     */
    public DecisionObservation(final String taskId,
                               final String nodeId,
                               final String processInstanceId,
                               final DecisionOutcome outcome,
                               final DecisionFailureKind failureKind,
                               final DecisionPolicyReason policyReason,
                               final DecisionSeverity severity,
                               final DecisionSubjectType subjectType,
                               final String modelId,
                               final DecisionChainStage chainStage,
                               final Long latencyMs,
                               final Long inputTokens,
                               final Long outputTokens,
                               final WriteDegradedCause writeDegradedCause,
                               final SuggestionAdmissionReason admissionReason,
                               final List<DecisionContextSource> droppedContextSources) {
        this.severity = Objects.requireNonNull(severity,
                "严重度必填：任何观测事实都必须带严重度（缺 severity 说明构造点未按错误分类两轴填值）");
        this.taskId = taskId;
        this.nodeId = nodeId;
        this.processInstanceId = processInstanceId;
        this.outcome = outcome;
        this.failureKind = failureKind;
        this.policyReason = policyReason;
        this.subjectType = subjectType;
        this.modelId = modelId;
        this.chainStage = chainStage;
        this.latencyMs = latencyMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.writeDegradedCause = writeDegradedCause;
        this.admissionReason = admissionReason;
        this.droppedContextSources = droppedContextSources == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(droppedContextSources));
    }
}
