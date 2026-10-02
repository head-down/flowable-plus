package io.github.flowable.plus.core.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionCompleteness;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 决策证据 VO（ADR-0042 第 5 节）：<b>单一类型 + {@code outcome} 判别式</b>，读者用一套字段读三种结局。
 *
 * <p><b>时间不进 JSON</b> —— 由评论行 {@code TIME_} 列填充，落在<b>读侧专属</b>字段
 * {@link #recordedTime}（写入侧恒不填、载荷不含该键）。</p>
 *
 * <p><b>必填 / 可空按「产出路径」四列写死</b>：A = {@code SUGGESTION_PRODUCED}·经出站调用 ·
 * B = {@code SUGGESTION_PRODUCED}·直提 · C = {@code NO_SUGGESTION_BY_POLICY} · D = {@code SUGGESTION_FAILED}。
 * 产出路径是<b>提交路径的结构性事实</b>，不由调用方自述；矩阵内不保留任何「视情形」格（C 列一律按
 * {@link DecisionPolicyReason} 二分）。逐格理由只能引 {@code outcome} 分支、产出路径或 {@code subjectType}，
 * 不得引「因为会调模型」。矩阵与互锁是<b>写入侧契约</b>（框架恒填），本类型只落契约形状。
 * 唯一不在产出路径语义内的字段是读侧专属的 {@link #recordedTime}（矩阵中该字段四列皆为「读侧专属」）。</p>
 *
 * <p><b>直提的判别式</b>：出处组（{@link #provider} ∧ {@link #chainStage} ∧ {@link #degraded}）三者全为
 * {@code null} ⇔ 直提。据此 {@code degraded} 取可空 {@code Boolean} —— 原始 {@code boolean} 永不为 null，
 * 双射将永不成立。「直提性」<b>永不落标识符</b>。</p>
 *
 * <p>{@link #inputSnapshot} 的类型取裸 {@code String}（载荷序列化后的形态，与 {@link #rawOutput} 同构）：
 * 决策载荷类型住 extension，而 core 不得依赖 extension，故此处只承载语义所指，不承载字段同型。</p>
 *
 * <p><b>只读承诺</b>：本类型是读侧值类型，框架不提供任何从 {@link #suggestedAction} 到流程推进的通路；
 * 证据的写入面只允许生产侧写入，不提供 update / delete / 撤回证据的 API。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionEvidenceVO {

    // ======================== 判别 / 承载（四列全必填） ========================

    /** 顶层结局（三叶子态） */
    private DecisionOutcome outcome;

    /** 证据载荷的 schema 版本；读侧遇更高版本尽力读、不做版本门禁 */
    private int schemaVersion;

    /** 幂等身份（不透明、决策源中立；推面与证据 VO 同源同值；四列全必填） */
    private String idempotencyKey;

    // ======================== 建议 ========================

    /** 建议动作 —— 机器读，<b>唯一判定权威</b>；框架不提供从它到流程推进的任何通路 */
    private ApprovalAction suggestedAction;

    /** 建议摘要 —— 给人快速扫视，<b>不得作为判定权威</b> */
    private String actionSummary;

    // ======================== 证据 ========================

    /** 模型标识（provider 契约事实，可空；直提列必须 null） */
    private String modelId;

    /** 输入快照（加工后、clamp 后的最终实际出域载荷；直提列必须 null） */
    private String inputSnapshot;

    /** 原始输出（入站加工后版本；provider 契约事实，可空） */
    private String rawOutput;

    /** 类型化依据（给审计 / 监管核查逻辑链条） */
    private List<DecisionRationaleFact> rationaleFacts;

    /** 文本兜底依据 */
    private String rationaleNarrative;

    /**
     * 直提自述位（三态：null = 位缺失 / 空集合 = 显式空集 / 有值 = 申报了具体来源）。
     *
     * <p><b>序列化时 {@code null} 省略该键</b>（ADR-0042 第 5 节第 4 条形态定稿）—— 三态由此可区分：
     * 键缺席 = 位缺失、{@code []} = 显式空集、有值 = 申报了具体来源。省略一律走<b>字段级</b>注解
     * （本字段，以及读侧专属的 {@link #recordedTime}），<b>不</b>借全局「省略 null 键」：矩阵里多处
     * 「必须 null」格依赖键仍在（或缺席亦语义等价可判），全局配置会顺手牵动其它字段。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<DecisionContextSource> attestedDataSources;

    // ======================== 出处组（三者全 null ⇔ 直提） ========================

    /** 出处：provider 标识（provider 契约事实，可空） */
    private String provider;

    /** 出处：链路阶段 */
    private DecisionChainStage chainStage;

    /** 出处：是否降级（可空 Boolean —— 双射的必要条件） */
    private Boolean degraded;

    // ======================== 主体（判别式 = subjectType） ========================

    /** 主体类型（闭集三值） */
    private DecisionSubjectType subjectType;

    /** 主体 ID（可空） */
    private String subjectId;

    /** 主体显示名（可空） */
    private String subjectName;

    // ======================== 完整度：按方向分记两套 ========================

    /** 出域方向：是否脱敏 / 摘要 */
    private boolean outboundRedacted;

    /** 出域方向：是否截断（含 clamp 丢段） */
    private boolean outboundTruncated;

    /** 出域方向完整度（三值：FULL / PARTIAL / NO_PAYLOAD） */
    private DecisionCompleteness outboundCompleteness;

    /** 入站方向：是否脱敏 / 摘要 */
    private boolean inboundRedacted;

    /** 入站方向：是否截断 */
    private boolean inboundTruncated;

    /** 入站方向完整度（四值） */
    private DecisionCompleteness inboundCompleteness;

    // ======================== 失败 ========================

    /** 失败类别（计错判据 = failureKind != null；INBOUND_PROCESSING_FAILED 为唯一产出态例外值） */
    private DecisionFailureKind failureKind;

    /** 按政策未产出的原因（七值；与 failureKind 互斥） */
    private DecisionPolicyReason policyReason;

    // ======================== 读侧专属（由评论行填充，不进 JSON 载荷） ========================

    /**
     * 记录时间：<b>该条证据所属评论行的 {@code TIME_} 列值</b>，由读侧投影器逐行填充
     * （{@code DecisionEvidenceRowProjector}）—— 消费方按现有顺序拿到「证据 ↔ 时间」的一一对应。
     *
     * <p><b>读侧专属</b>：写入侧恒不填，故 JSON 载荷不含该键（{@code null} 时省略该键）。它是
     * {@code ACT_HI_COMMENT} 行的事实、不是证据载荷的一部分：把时间冗余进载荷会引入「载荷内时间与
     * 评论行 {@code TIME_} 不一致」的新漂移面，而后者已是唯一权威时间来源。</p>
     *
     * <p><b>不进读侧绑定面</b>：本字段是只读属性 —— 反序列化（还原证据载荷）阶段该键<b>一律被忽略、
     * 不参与绑定</b>，值只由读侧从评论行赋值。故它不会成为「载荷里同名键类型不符 ⇒ 载荷损坏 ⇒ 整条证据
     * 被跳过」的新入口（载荷属不可信输入，读侧容错是字段级 / 整条级的既有条款，见投影器）。</p>
     *
     * <p><b>正常非空</b>：评论行恒有 {@code TIME_}（引擎写入评论时赋值），故读侧带出的时间正常非空；
     * 某行 {@code TIME_} 为空时本字段为空，那是<b>行侧异常</b>、不是机制态。</p>
     *
     * <p><b>不是判序输入</b>：「最早在前」由读侧按 {@code TIME_} 升序（同毫秒按数值 {@code ID_} 兜底）
     * 建立的<b>列表序</b>承载；「该锚点是否建序」只由列表的运行时类型（{@link UnorderedDecisionEvidences}）
     * 承载 —— 判定面不得据本字段反推次序是否已建立（同毫秒并列本无客观先后）。</p>
     *
     * <p>类型取 {@link Date}：与同族读侧 VO（{@code ApprovalRecordVO.startTime} / {@code endTime}）同型，
     * 下游原样透传再序列化时无需额外模块（个人规范「新增代码自行选择日期类型须用 java.time」的<b>具名偏离</b>，
     * 取舍理由即此同型一致性）。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Date recordedTime;
}
