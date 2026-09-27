package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;

/**
 * 结局映射表（ADR-0042 第 10 节「计错判据」；行集来源 = 拉管线与出站缝的实现形态决议 §13）。
 *
 * <p><b>键 = 产生点</b>，一行一常量；每行落位三件事实：{@code outcome} × {@code failureKind} /
 * {@code policyReason} × {@code severity}，外加两个派生位（是否可重试 / 是否落证据行）。</p>
 *
 * <p><b>行集边界</b>：本表收「<b>产出观测</b>的全部结局行」共 {@link #ROW_COUNT} 行
 * （成功路径一行 + 决议 §13 表的编号 2–18 行，其中两处「同一格、两个产生点」按产生点分行，
 * 未物质化三情形按三处独立事实分行）。<b>不触发</b>的四类（未激活 / 节点未声明 / 事件面关闭 /
 * 无活锚点）<b>产出零观测</b> —— 它们不是「结局为零」，而是<b>无结局</b>，故不占本表行，
 * 也不新增第四叶子态。这一点是「观测条数 ≠ 证据行数」的另一面。</p>
 *
 * <p><b>两条判据</b>（承重）：① <b>计错判据 = {@code failureKind != null}</b> —— 不挂在
 * {@code outcome} 上，故「入站加工失败」出现在产出态这一破例被自然计入，而「按政策未产出」
 * 永不带 {@code failureKind} ⇒ <b>绝不入错误率</b>；② {@code severity} 必填、可空性由各行的
 * 落位决定（未物质化三行三字段皆 null、{@code severity = ERROR}，留下可区分的结局）。</p>
 *
 * <p><b>可达性</b>：本表是「产生点 → 结局」的满射，每行的产生点在机制内均可到达（由决议 §13
 * 表具名）；本类型只承载落位，产生点的到达路径属管线侧实现（与观测构造点同处）。</p>
 */
public enum DecisionOutcomeMapping {

    // ======================== 已产出建议（2 行） ========================

    /** 产生点：出站调用成功 ∧ 入站加工成功 ∧ 位点提交成功（成功路径） */
    SUGGESTION_DELIVERED(DecisionOutcome.SUGGESTION_PRODUCED, null, null, DecisionSeverity.INFO, false, true),

    /** 产生点：入站加工失败 / 入站 clamp 超限（含直提）—— <b>唯一的产出态失败</b> */
    INBOUND_PROCESSING_FAILED(DecisionOutcome.SUGGESTION_PRODUCED,
            DecisionFailureKind.INBOUND_PROCESSING_FAILED, null, DecisionSeverity.ERROR, false, true),

    // ======================== 按政策未产出（5 行，均不计错误） ========================

    /** 产生点：零 token / 空装配（建模漏配，声明面） */
    NO_SOURCE_DECLARED(DecisionOutcome.NO_SUGGESTION_BY_POLICY,
            null, DecisionPolicyReason.NO_SOURCE_DECLARED, DecisionSeverity.INFO, false, true),

    /** 产生点：出域策略按设计拦下（内容面；与空装配必须独立） */
    POLICY_REJECTED(DecisionOutcome.NO_SUGGESTION_BY_POLICY,
            null, DecisionPolicyReason.POLICY_REJECTED, DecisionSeverity.INFO, false, true),

    /** 产生点：生产者主动不产出（走过一次出站调用） */
    MODEL_DECLINED(DecisionOutcome.NO_SUGGESTION_BY_POLICY,
            null, DecisionPolicyReason.MODEL_DECLINED, DecisionSeverity.INFO, false, true),

    /** 产生点：运行暂停（机制已激活、运行期被暂止） */
    SUSPENDED(DecisionOutcome.NO_SUGGESTION_BY_POLICY,
            null, DecisionPolicyReason.SUSPENDED, DecisionSeverity.INFO, false, true),

    /** 产生点：专属有界线程池池满 —— 承载面 = 结构化日志 + 独立计数，<b>不落证据行</b> */
    OVERLOADED(DecisionOutcome.NO_SUGGESTION_BY_POLICY,
            null, DecisionPolicyReason.OVERLOADED, DecisionSeverity.INFO, false, false),

    // ======================== 失败（8 行） ========================

    /** 产生点：策略抛异常 / 运行期不自洽（最后防御阻断式不出域） */
    POLICY_EVALUATION_FAILED(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.INTERNAL_ERROR, null, DecisionSeverity.ERROR, false, true),

    /** 产生点：装配器异常 / 出域 clamp 兜底拒绝（丢到全空仍超限） */
    ASSEMBLY_FAILED(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.INTERNAL_ERROR, null, DecisionSeverity.ERROR, false, true),

    /** 产生点：出站调用超时（可重试） */
    OUTBOUND_TIMEOUT(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.OUTBOUND_TIMEOUT, null, DecisionSeverity.WARN, true, true),

    /** 产生点：出站调用返回非 2xx（非 401 / 403；可重试） */
    OUTBOUND_HTTP_ERROR(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.OUTBOUND_HTTP_ERROR, null, DecisionSeverity.WARN, true, true),

    /** 产生点：Transport 层返回 401 / 403（凭据失效，不可重试） */
    CREDENTIAL_REJECTED(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID, null, DecisionSeverity.ERROR, false, true),

    /** 产生点：凭据解析侧失败（折叠进同一失败类别，不新增枚举） */
    CREDENTIAL_RESOLUTION_FAILED(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID, null, DecisionSeverity.ERROR, false, true),

    /** 产生点：响应不可解析（含 declined 与 suggestedAction 的互斥性违反 / chainStage 缺失；可重试） */
    RESPONSE_UNPARSEABLE(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.RESPONSE_UNPARSEABLE, null, DecisionSeverity.WARN, true, true),

    /** 产生点：位点准入拒绝（管线接住并物质化） */
    SITE_ADMISSION_REJECTED(DecisionOutcome.SUGGESTION_FAILED,
            DecisionFailureKind.SITE_ADMISSION_REJECTED, null, DecisionSeverity.ERROR, false, true),

    // ======================== 未物质化（3 行，三字段皆 null） ========================

    /** 产生点：写入失败 —— 锚点失效（不成行、不产生失败结局，只降级日志 + 独立计数） */
    ANCHOR_LOST(null, null, null, DecisionSeverity.ERROR, false, false),

    /** 产生点：写入失败 —— 实例已结束（同上） */
    INSTANCE_ENDED(null, null, null, DecisionSeverity.ERROR, false, false),

    /** 产生点：缺幂等身份（唯一不物质化的准入失败） */
    IDEMPOTENCY_KEY_MISSING(null, null, null, DecisionSeverity.ERROR, false, false),
    ;

    /**
     * 结局映射表的行数（<b>对账常量</b>）。
     *
     * <p>取值独立于 {@link #values()} 的长度，供「表被改动」时机械对账打红；本表契约行数 = 18。</p>
     */
    public static final int ROW_COUNT = 18;

    /** 顶层结局（未物质化三行为 null） */
    private final DecisionOutcome outcome;

    /** 失败类别（计错判据 = 非 null） */
    private final DecisionFailureKind failureKind;

    /** 按政策未产出的原因（与失败类别互斥） */
    private final DecisionPolicyReason policyReason;

    /** 严重度（必填；同时是日志级别的映射源） */
    private final DecisionSeverity severity;

    /** 是否可重试（可重试集合 = 超时 / 非 2xx / 响应不可解析） */
    private final boolean retryable;

    /** 是否落证据行（池满与未物质化三情形不落） */
    private final boolean evidenceRow;

    DecisionOutcomeMapping(final DecisionOutcome outcome,
                           final DecisionFailureKind failureKind,
                           final DecisionPolicyReason policyReason,
                           final DecisionSeverity severity,
                           final boolean retryable,
                           final boolean evidenceRow) {
        this.outcome = outcome;
        this.failureKind = failureKind;
        this.policyReason = policyReason;
        this.severity = severity;
        this.retryable = retryable;
        this.evidenceRow = evidenceRow;
    }

    /**
     * 顶层结局。
     *
     * @return 结局；未物质化三行返回 null
     */
    public DecisionOutcome getOutcome() {
        return outcome;
    }

    /**
     * 失败类别。
     *
     * @return 失败类别；无失败时返回 null（即「不计错误」）
     */
    public DecisionFailureKind getFailureKind() {
        return failureKind;
    }

    /**
     * 按政策未产出的原因。
     *
     * @return 政策原因；非「按政策未产出」时返回 null
     */
    public DecisionPolicyReason getPolicyReason() {
        return policyReason;
    }

    /**
     * 严重度。
     *
     * @return 严重度，恒非 null
     */
    public DecisionSeverity getSeverity() {
        return severity;
    }

    /**
     * 是否可重试。
     *
     * @return 可重试返回 true
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * 是否落证据行。
     *
     * @return 落证据行返回 true
     */
    public boolean isEvidenceRow() {
        return evidenceRow;
    }
}
