package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 建议提交模型（ADR-0042 第 2 节定案 3 / 第 3 节派生 1）：<b>扁平的调用方自述写入契约</b>。
 *
 * <p>框架在提交时据此<b>构造</b> core 的 {@code DecisionEvidenceVO}（补 {@code outcome = SUGGESTION_PRODUCED}、
 * 方向标志、{@code completeness}、{@code schemaVersion}、标记）—— 故 {@code outcome} / {@code policyReason} /
 * {@code failureKind} / 方向标志六项 / {@code completeness} / {@code schemaVersion} / 标记
 * <b>不由本模型承载</b>（框架推导 / 恒填）。</p>
 *
 * <p><b>不另立「依据值类型」</b>：依据 = {@link #rationaleFacts}（类型化）+ {@link #rationaleNarrative}
 * （文本兜底），沿用 core 已定名的类型。</p>
 *
 * <p><b>必填性</b>：{@link #taskId} / {@link #suggestedAction} / {@link #idempotencyKey} /
 * {@link #subjectType} / {@link #actionSummary} 必填；其余可空。准入规则集（11 条、同步按序、首个失败即抛）
 * 归位点服务。</p>
 *
 * <p><b>出处组双射</b>：{@link #provider} ∧ {@link #chainStage} ∧ {@link #degraded} 三者
 * <b>同 null 或同非 null</b>（{@code degraded} 取<b>可空</b> {@code Boolean} —— 原始 {@code boolean} 会使
 * 「出处组为空」永不成立）；三者全 null ⇔ 直提。直提下 {@link #modelId} 与 {@link #inputSnapshot}
 * <b>必空</b>（直提无出域位点），且 {@link #rationaleFacts} 须含 ≥1 条
 * {@link io.github.flowable.plus.core.enums.DecisionRationaleFactKey#BASIS_CODE}。</p>
 *
 * <p><b>「空」的判据</b>：{@code null} 或去空白后为空串（{@link #idempotencyKey} /
 * {@link #actionSummary} 两处）；身份值<b>原样存、原样比</b>、不做任何规范化
 * （{@code "A"} 与 {@code " A "} 是两个不同身份 —— 规范化会把不同键并成一次决策，违 ADR-0042 第 9 节第 11 条）。</p>
 *
 * <p><b>锚点</b>：{@link #taskId} 是结构性必备（一条合法准入的提交必然存在当前任务）；v1 不支持无任务锚点的证据。
 * 幂等身份对推面 = 调用方提供；对拉面 = 框架按确定性规则推导（v1 的拉面键 = 一元锚点 {@code taskId}，
 * 框架自造、不透明串、形态不构成契约面）—— 二者<b>同源同值</b>地进入证据 VO 的
 * {@code idempotencyKey}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionSubmission {

    // ======================== 必填 ========================

    /** 锚点：当前任务 ID（结构性必备；v1 不支持无任务锚点的证据） */
    private String taskId;

    /** 建议动作（复用 core {@link ApprovalAction}；值域 = 表态比较面 ∩ 当前任务可用投票动作） */
    private ApprovalAction suggestedAction;

    /** 幂等身份（不透明、决策源中立；与证据 VO 同源同值） */
    private String idempotencyKey;

    /** 主体类型（闭集三值） */
    private DecisionSubjectType subjectType;

    /** 建议摘要（拒空摘要） */
    private String actionSummary;

    // ======================== 可空 ========================

    /** 类型化依据（直提 ⇒ 至少一条 {@code BASIS_CODE}） */
    private List<DecisionRationaleFact> rationaleFacts;

    /** 文本兜底依据 */
    private String rationaleNarrative;

    /** 原始输出（= 提交方交上来的<b>入站</b>载荷，经入站卫生与 clamp） */
    private String rawOutput;

    /** 主体 ID（可空） */
    private String subjectId;

    /** 主体显示名（可空） */
    private String subjectName;

    // ======================== 出处组（同 null 或同非 null） ========================

    /** 出处：provider 标识（直提下必 null） */
    private String provider;

    /** 出处：链路阶段（直提下必 null） */
    private DecisionChainStage chainStage;

    /** 出处：是否降级（直提下必 null；可空 {@code Boolean} 是双射的必要条件） */
    private Boolean degraded;

    // ======================== 直提约束 ========================

    /** 模型标识（provider 契约事实；<b>直提下必须为空</b>） */
    private String modelId;

    /** 输入快照（<b>直提下必须为空</b>；直提无出域位点） */
    private String inputSnapshot;

    /**
     * 直提自述位（可空，<b>三态</b>）：{@code null} = 位缺失（未申报）/
     * 空集合 = 显式空集（声明未使用）/ 有值 = 申报了具体来源。
     *
     * <p>有值态三条约束：禁 null 元素、禁重复元素、元素须符合 {@link DecisionContextSource} 值契约。</p>
     *
     * <p>本类型<b>不参与</b>证据行的 JSON 序列化（它是写入契约、不是证据载荷）；三态在<b>证据行序列化</b>时
     * 才显形 —— 由 core {@code DecisionEvidenceVO.attestedDataSources} 字段上的注解决定「{@code null}
     * 省略该键」，该注解<b>只落那一个字段</b>。</p>
     */
    private List<DecisionContextSource> attestedDataSources;
}
