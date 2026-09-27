package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 一次证据书写的<b>显式事实</b>载体（ADR-0042 第 5 / 6 节）：调用方（拉管线 / 位点服务）自述子集 +
 * 两个方向上「到底有没有载荷」的显式事实 + 加工事实。
 *
 * <p><b>状态驱动，不路径驱动</b>（ADR-0042 第 6 节「标志推导的形态定稿」）：方向标志与
 * {@code completeness} 只从 {@link #outboundPayloadPresent} / {@link #inboundPayloadPresent}
 * 两个显式事实（映 ADR 的 {@code hasOutboundPayload} / {@code hasInboundPayload}）与加工事实推出，
 * <b>不枚举</b> {@code policyReason} / {@code failureKind} 的取值。故「某条路径下到底有没有载荷」
 * 由提交方（面 4 的知识）如实声明，本类型不替它枚举。</p>
 *
 * <p><b>两个方向各自的降级事实</b>：{@link #inboundRestricted} 承「有内容但策略判政策性不可落盘」
 * （映 {@code RESTRICTED}，<b>不计错误</b>）；入站加工<b>异常 / clamp 超限</b>不另设事实位 ——
 * 它以 {@link #failureKind} = {@link DecisionFailureKind#INBOUND_PROCESSING_FAILED} 声明
 * （该取值在产出态的唯一破例，双向守卫见 {@link DecisionEvidenceWriter}）。</p>
 *
 * <p><b>判别 / 承载与判别式不由本类型承担</b>：{@code schemaVersion} 与方向标志六项、标记、
 * {@code completeness} 皆由 {@link DecisionEvidenceWriter} 恒填；判别式 {@code outcome} 由提交方按
 * 「本次提交的结论」声明（三叶子态）。</p>
 *
 * <p><b>本类型是实现细节，不入机制术语表</b>（命名宪章 §4.5）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionEvidenceDraft {

    // ======================== 判别 / 承载（四列全必填） ========================

    /** 顶层结局（三叶子态；四列产出路径由它与出处组共同判别） */
    private DecisionOutcome outcome;

    /** 按政策未产出的原因（<b>只有</b> {@code NO_SUGGESTION_BY_POLICY} 列可非空） */
    private DecisionPolicyReason policyReason;

    /** 失败类别（{@code SUGGESTION_FAILED} 列必填；产出态只允许 {@code INBOUND_PROCESSING_FAILED}） */
    private DecisionFailureKind failureKind;

    /** 幂等身份（不透明、决策源中立；「空」= null 或去空白空串，值原样存原样比、不做规范化） */
    private String idempotencyKey;

    /** 主体类型（判别式；四列全必填） */
    private DecisionSubjectType subjectType;

    // ======================== 主体描述（可空） ========================

    /** 主体 ID（可空） */
    private String subjectId;

    /** 主体显示名（可空） */
    private String subjectName;

    // ======================== 建议（产出列必填、其余必须 null） ========================

    /** 建议动作（产出列必填） */
    private ApprovalAction suggestedAction;

    /** 建议摘要（产出列必填，拒空摘要） */
    private String actionSummary;

    // ======================== 证据 ========================

    /** 模型标识（provider 契约事实；直提列必须 null） */
    private String modelId;

    /** 输入快照（加工后、clamp 后的最终实际出域载荷序列化形态；直提列必须 null） */
    private String inputSnapshot;

    /** 原始输出（入站加工后版本；直提列 = 提交方交上来的入站载荷经入站卫生与 clamp 后的形态） */
    private String rawOutput;

    /** 类型化依据 */
    private List<DecisionRationaleFact> rationaleFacts;

    /** 文本兜底依据 */
    private String rationaleNarrative;

    /** 直提自述位（三态：null = 位缺失 / 空集合 = 显式空集 / 有值 = 申报了具体来源） */
    private List<DecisionContextSource> attestedDataSources;

    // ======================== 出处组（三者同 null ⇔ 直提） ========================

    /** 出处：provider 标识 */
    private String provider;

    /** 出处：链路阶段 */
    private DecisionChainStage chainStage;

    /** 出处：是否降级（可空 {@code Boolean} 是双射的必要条件） */
    private Boolean degraded;

    // ======================== 出域方向的显式事实与加工事实 ========================

    /** 出域方向显式事实：装配器产出非空载荷 ∧ 策略放行（直提恒 false） */
    private boolean outboundPayloadPresent;

    /** 出域方向加工事实：策略是否发生脱敏 / 摘要（不含截断） */
    private boolean outboundRedacted;

    /** 出域方向加工事实：策略是否发生截断 */
    private boolean outboundTruncated;

    /** 出域方向加工事实：框架 clamp 是否丢弃整段（<b>计</b> {@code truncated}） */
    private boolean outboundClampDropped;

    // ======================== 入站方向的显式事实与加工事实 ========================

    /** 入站方向显式事实：该方向有落盘载荷（有内容，且未被策略政策性拦下） */
    private boolean inboundPayloadPresent;

    /** 入站方向显式事实：有内容但策略判政策性不可落盘（映 {@code RESTRICTED}，不计错误） */
    private boolean inboundRestricted;

    /** 入站方向加工事实：策略是否发生脱敏 / 摘要（不含截断） */
    private boolean inboundRedacted;

    /** 入站方向加工事实：策略是否发生截断 */
    private boolean inboundTruncated;

    /**
     * 由「建议提交」构造一份草稿（<b>框架据此构造 core 证据 VO</b> 的唯一转换点）：
     * 结局取 {@code SUGGESTION_PRODUCED}，调用方自述子集原样搬入。
     *
     * <p>产出路径（经出站调用 / 直提）<b>不由调用方自述</b> —— 它由出处组是否为空这一结构性事实判别，
     * 故本方法不做任何路径判定；出域方向的事实由调用方随后声明（直提恒无载荷）。</p>
     *
     * <p><b>入站方向的事实由内容推出</b>：{@code SuggestionSubmission.rawOutput} 的语义本就是
     * 「经入站卫生与 clamp 后」的形态 ⇒ 非空即「该方向有落盘载荷」。要去声明政策性不可落盘或入站加工失败者，
     * 在拿到草稿后显式改这两处（并同步清空内容位，互锁会把不一致挡在构造期）。</p>
     *
     * @param submission 建议提交（调用方自述），不得为 null
     * @return 草稿
     */
    public static DecisionEvidenceDraft of(final SuggestionSubmission submission) {
        final DecisionEvidenceDraft draft = new DecisionEvidenceDraft();
        draft.outcome = DecisionOutcome.SUGGESTION_PRODUCED;
        draft.idempotencyKey = submission.getIdempotencyKey();
        draft.subjectType = submission.getSubjectType();
        draft.subjectId = submission.getSubjectId();
        draft.subjectName = submission.getSubjectName();
        draft.suggestedAction = submission.getSuggestedAction();
        draft.actionSummary = submission.getActionSummary();
        draft.modelId = submission.getModelId();
        draft.inputSnapshot = submission.getInputSnapshot();
        draft.rawOutput = submission.getRawOutput();
        draft.rationaleFacts = submission.getRationaleFacts();
        draft.rationaleNarrative = submission.getRationaleNarrative();
        draft.attestedDataSources = submission.getAttestedDataSources();
        draft.provider = submission.getProvider();
        draft.chainStage = submission.getChainStage();
        draft.degraded = submission.getDegraded();
        draft.inboundPayloadPresent = StringUtils.isNotEmpty(submission.getRawOutput());
        return draft;
    }
}
