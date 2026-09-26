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
 * 建议提交模型（ADR-0042 第 2 节定案 3 / 第 3 节派生 1）：**扁平的调用方自述写入契约**。
 *
 * <p>框架在提交时据此<b>构造</b> core 的 {@code DecisionEvidenceVO}（补 {@code outcome =
 * SUGGESTION_PRODUCED}、方向标志、{@code completeness}、{@code schemaVersion}、标记）——
 * 故 {@code outcome} / {@code policyReason} / {@code failureKind} / 方向标志六项 /
 * {@code completeness} / {@code schemaVersion} / 标记 <b>不由本模型承载</b>。</p>
 *
 * <p><b>不另立「依据值类型」</b>：依据 = {@link #rationaleFacts}（类型化）+
 * {@link #rationaleNarrative}（文本兜底），沿用 core 已定名的类型。</p>
 *
 * <p><b>必填性</b>：{@code taskId} / {@code suggestedAction} / {@code idempotencyKey} /
 * {@code subjectType} / {@code actionSummary} 必填；其余可空。</p>
 *
 * <p><b>出处组双射</b>：{@link #provider} ∧ {@link #chainStage} ∧ {@link #degraded} 三者
 * <b>同 null 或同非 null</b>；全 null ⇔ 直提。直提下 {@code modelId} 与 {@code inputSnapshot}
 * <b>必空</b>，且 {@code rationaleFacts} 须含 ≥1 条 {@code BASIS_CODE}。</p>
 *
 * <p><b>「空」的判据</b>：{@code null} 或去空白后为空串（{@code idempotencyKey} /
 * {@code actionSummary} 两处）；身份值<b>原样存、原样比</b>、不做任何规范化
 * （{@code "A"} 与 {@code " A "} 是两个不同身份）。</p>
 *
 * <p><b>骨架说明</b>：准入规则集（11 条、按序首个失败即抛）与「框架据此构造证据 VO」的转换体
 * 属机制行为，本骨架不实现。{@code inputSnapshot} 取序列化形态（与 {@code rawOutput} 同构）——
 * 见 #43 决议的发现①。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionSubmission {

    // ======================== 必填 ========================

    /** 锚点：当前任务 ID（结构性必备；v1 不支持无任务锚点的证据） */
    private String taskId;

    /** 建议动作（复用 core {@code ApprovalAction}；值域 = 表态比较面 ∩ 当前任务可用投票动作） */
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

    /** 原始输出（= 提交方交上来的入站载荷，经入站卫生与 clamp） */
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

    /** 出处：是否降级（直提下必 null；可空 Boolean 是双射的必要条件） */
    private Boolean degraded;

    // ======================== 直提约束 ========================

    /** 模型标识（provider 契约事实；<b>直提下必须为空</b>） */
    private String modelId;

    /** 输入快照（<b>直提下必须为空</b>；无出域位点） */
    private String inputSnapshot;

    /**
     * 直提自述位（可空，<b>三态</b>）：{@code null} = 位缺失（未申报）/
     * 空集合 = 显式空集（声明未使用）/ 有值 = 申报了具体来源。
     *
     * <p>序列化时 {@code null} <b>省略该键</b>。有值态三条约束：禁 null 元素、禁重复元素、
     * 元素须符合 {@code DecisionContextSource} 值契约。</p>
     */
    private List<DecisionContextSource> attestedDataSources;
}
