package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.DecisionCompleteness;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionEvidenceWriteGuard;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 决策证据的<b>写入器</b>（ADR-0042 第 5 / 6 / 9 节）：把「一次书写的显式事实」物质化为 core 的
 * {@link DecisionEvidenceVO}，并产出可持久化的<b>证据行整行文本</b>（标记 + ASCII-safe JSON）。
 *
 * <p><b>它物质化四列产出路径</b>（A 经出站调用 / B 直提 / C 按政策未产出 / D 失败）。列由
 * 「{@code outcome} × 出处组是否为空」判别，<b>不由调用方自述</b> —— 产出路径是提交路径的结构性事实
 * （拉管线路径必有决策目标、直提路径必无）。四列<b>互斥且穷举</b>，无第五列（重复到达的同一幂等键
 * 照旧落在它本来的那一列，不另成一列）。</p>
 *
 * <p><b>矩阵逐格在构造期强制</b>（ADR-0042 第 5 节必填 / 可空矩阵）：逐格的理由只引
 * <b>{@code outcome} 分支</b>、<b>产出路径</b>或 <b>{@code subjectType}</b>，<b>不得</b>引「因为会调模型」。
 * 矩阵本身逐格写在 {@code DecisionEvidenceVOContractTest}（core，机器可判）；本类的职责是让任何一格都
 * <b>写不出来</b>。</p>
 *
 * <p><b>标志推导 = 状态驱动</b>（ADR-0042 第 6 节）：输入只有两个显式事实
 * （{@link DecisionEvidenceDraft#isOutboundPayloadPresent()} /
 * {@link DecisionEvidenceDraft#isInboundPayloadPresent()}）加加工事实，<b>不枚举</b>路径。两条对偶勿混：
 * <b>装配器丢的来源不计任何标志</b>（它是声明面最小化的基线，另记可观测计数）；
 * <b>clamp 丢的计 {@code truncated}</b>（它是加工）。</p>
 *
 * <p><b>标志互锁（写入侧契约，框架恒填）</b>：每方向 {@code PARTIAL ⇔ (redacted ∨ truncated)}；
 * {@code (FULL ∨ NO_PAYLOAD) ⇒ (¬redacted ∧ ¬truncated)}；{@code FULL / PARTIAL ⇒ 载荷字段非空}；
 * 以及「载荷字段空 ∧ 未加工 ⇒ {@code NO_PAYLOAD}」。<b>一处读法必须写明</b>：第三条互锁的反向支
 * （载荷空 ∧ 未加工 ⇒ {@code NO_PAYLOAD}）只在<b>三值域内</b>成立 —— {@code RESTRICTED} 的定义性特征正是
 * 「<b>有</b>载荷但按政策不可落盘」，其载荷字段（入站 = {@code rawOutput}）恒空而完整度<b>不是</b>
 * {@code NO_PAYLOAD}。故「无实际载荷」仍由 {@code NO_PAYLOAD} 单一承担，不被布尔兼职、也不被 {@code FULL}
 * 兼职。</p>
 *
 * <p><b>出域方向取不到 {@code RESTRICTED}</b>：{@code inputSnapshot} 是「离开本域的到底是什么」的
 * <b>唯一证据</b>，允许策略屏蔽它等于给「无未留痕出域」开后门；{@code RESTRICTED} 的构造点只在<b>入站</b>
 * 方向，且<b>不在直提列</b> —— 直提的入站加工不经策略（策略 key 属节点声明，推面与声明解耦），
 * 故「有内容但按政策不可落盘」在直提路径上结构性地不可能发生。</p>
 *
 * <p><b>入站两类降级先分类型</b>（标志层即可区分）：<b>政策性不可落盘</b> ⇒
 * {@code inboundCompleteness = RESTRICTED} + {@code failureKind = null}（<b>不计错误指标</b>）；
 * <b>策略执行异常 / clamp 超限</b> ⇒ {@code NO_PAYLOAD} + {@code failureKind = INBOUND_PROCESSING_FAILED}
 * （<b>计入错误指标</b>）。后者是 {@code failureKind} 七值中唯一可在产出态出现者，双向守卫在构造期强制。</p>
 *
 * <p><b>写入侧护栏</b>（{@link DecisionEvidenceWriteGuard#MAX_EVIDENCE_BYTES}）：整行文本（标记 + JSON）
 * <b>写入前</b>按 UTF-8 字节校验，超限<b>拒绝写入</b>该行 ⇒ 物质化为 {@code SUGGESTION_FAILED} /
 * {@code INTERNAL_ERROR} 的 D 列行（<b>零新增枚举值、零新增槽位、零新增写入降级取值</b>，与 ADR-0042 第 6 节
 * clamp 兜底拒绝同型）。被拒的原行内容<b>不落任何内容体</b>。</p>
 *
 * <p><b>主体走 JSON，不走 {@code USER_ID_}</b>：{@code ACT_HI_COMMENT.USER_ID_} ≠ 决策证据的真实产出主体
 * —— {@code addComment} 没有 userId 入参，其值取决于调用线程的认证上下文，框架<b>不能</b>指定（不是「不应」）。
 * 主体唯一落点是 JSON 的 {@code subjectType} / {@code subjectId} / {@code subjectName}，本类不产生、
 * 不读取任何 userId。</p>
 *
 * <p><b>本类是实现细节，不入机制术语表</b>（命名宪章 §4.5）：住所 = extension 包内，消费者只有拉管线与位点
 * 服务；持久化本身（{@code TaskService#addComment}）由调用方完成，本类只交整行文本。</p>
 */
final class DecisionEvidenceWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionEvidenceWriter.class);

    /** 证据载荷的 schema 版本（v1 唯一产出值；读侧遇更高版本尽力读、不做版本门禁） */
    static final int SCHEMA_VERSION = 1;

    /**
     * 证据行序列化器：<b>只做一件事</b> —— {@code ESCAPE_NON_ASCII}，使整行 ASCII-safe。
     *
     * <p>依据 ADR-0042 第 5 节：写读环境字符集不同会 mangle 中文，转义后免疫。除此以外<b>不改配置</b>
     * —— 尤其<b>不</b>打开全局「省略 null 键」：矩阵里多处「必须 null」格依赖键仍在（或缺席亦语义等价可判），
     * 而 {@code attestedDataSources} 的三态（缺席 / 显式空集 / 有值）由 core 该字段上的字段级注解决定，
     * 不借全局配置顺手牵动其它字段。</p>
     */
    private final ObjectMapper mapper;

    DecisionEvidenceWriter() {
        this.mapper = new ObjectMapper().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true);
    }

    /**
     * 把一次书写的显式事实物质化为证据 VO：判列 → 逐格校验矩阵 → 推导方向标志与完整度。
     *
     * @param draft 一次书写的显式事实，不得为 null
     * @return 证据 VO（{@code schemaVersion} 恒填 {@link #SCHEMA_VERSION}）
     * @throws IllegalArgumentException 任一矩阵格 / 守卫被违反（非法态无出生路径）
     */
    DecisionEvidenceVO materialize(final DecisionEvidenceDraft draft) {
        Objects.requireNonNull(draft, "一次书写的显式事实不得为 null");
        requireCarrier(draft);
        requireFailureKindGuards(draft);
        final Column column = columnOf(draft);
        requireColumnCells(draft, column);
        requirePolicyReasonPlacement(draft, column);
        requirePayloadFacts(draft, column);
        requireRationaleFacts(draft, column);
        requireAttestedDataSources(draft, column);
        return build(draft, column);
    }

    /**
     * 产出可持久化的<b>证据行整行文本</b>（标记 + ASCII-safe JSON），并在写入前施加尺寸护栏。
     *
     * <p>超限 ⇒ <b>拒绝写入</b>该行，改交一个 {@code SUGGESTION_FAILED} / {@code INTERNAL_ERROR} 的
     * D 列行的整行文本（被拒的原行内容不落任何内容体）。</p>
     *
     * @param evidence 证据 VO，不得为 null
     * @return 交 {@code TaskService#addComment} 的整行文本（引擎据它填 {@code FULL_MSG_}
     *         ，并自行折叠截断出 {@code MESSAGE_}）
     */
    String row(final DecisionEvidenceVO evidence) {
        Objects.requireNonNull(evidence, "证据 VO 不得为 null");
        final String candidate = marker() + serialize(evidence);
        final int bytes = utf8Length(candidate);
        if (bytes <= DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES) {
            return candidate;
        }
        LOG.warn("证据行超写入侧尺寸上限，拒绝写入并归失败行：上限={} 字节，实际={} 字节",
                DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES, bytes);
        return marker() + serialize(internalErrorRow(evidence));
    }

    // ======================== 判列 ========================

    /** 四列产出路径（A 经出站调用 / B 直提 / C 按政策未产出 / D 失败）；互斥且穷举，无第五列 */
    private enum Column {

        /** A：{@code SUGGESTION_PRODUCED} ∧ 出处组非空（本次产出经过框架的一次出站调用） */
        OUTBOUND,

        /** B：{@code SUGGESTION_PRODUCED} ∧ 出处组全 null（直提，无出域位点） */
        DIRECT,

        /** C：{@code NO_SUGGESTION_BY_POLICY} */
        POLICY,

        /** D：{@code SUGGESTION_FAILED} */
        FAILURE
    }

    /**
     * 判列：{@code outcome} 定三叶子态，产出态内再按出处组是否为空分 A / B。
     *
     * <p>出处组「同 null 或同非 null」在此先行强制 —— 半填的出处组既不是直提也不是非直提，属于写不出来的态。</p>
     */
    private static Column columnOf(final DecisionEvidenceDraft draft) {
        final DecisionOutcome outcome = draft.getOutcome();
        if (outcome == null) {
            throw new IllegalArgumentException("outcome（判别式，三叶子态）四列全必填");
        }
        requireProvenanceGroupConsistent(draft);
        switch (outcome) {
            case SUGGESTION_PRODUCED:
                return isDirect(draft) ? Column.DIRECT : Column.OUTBOUND;
            case NO_SUGGESTION_BY_POLICY:
                return Column.POLICY;
            case SUGGESTION_FAILED:
                return Column.FAILURE;
            default:
                throw new IllegalArgumentException("outcome 只有三个叶子态（未产出是上位词、不承载取值）：" + outcome);
        }
    }

    /** 直提的判别式 = 出处组三字段全为 {@code null} */
    private static boolean isDirect(final DecisionEvidenceDraft draft) {
        return draft.getProvider() == null && draft.getChainStage() == null && draft.getDegraded() == null;
    }

    private static void requireProvenanceGroupConsistent(final DecisionEvidenceDraft draft) {
        int present = 0;
        if (draft.getProvider() != null) {
            present++;
        }
        if (draft.getChainStage() != null) {
            present++;
        }
        if (draft.getDegraded() != null) {
            present++;
        }
        if (present != 0 && present != 3) {
            throw new IllegalArgumentException(
                    "出处组（provider / chainStage / degraded）必须同 null 或同非 null：半填即非法态");
        }
    }

    // ======================== 逐列矩阵 ========================

    private static void requireColumnCells(final DecisionEvidenceDraft draft, final Column column) {
        switch (column) {
            case OUTBOUND:
                requireProducedCells(draft, false);
                break;
            case DIRECT:
                requireProducedCells(draft, true);
                break;
            case POLICY:
                requirePolicyCells(draft);
                break;
            case FAILURE:
                requireFailureCells(draft);
                break;
            default:
                throw new IllegalStateException("未知的产出路径列：" + column);
        }
    }

    /**
     * 产出列（A / B）的格：建议面必填、证据面必填、自述位只许直提。
     *
     * @param direct 是否直提列
     */
    private static void requireProducedCells(final DecisionEvidenceDraft draft, final boolean direct) {
        if (draft.getSuggestedAction() == null) {
            throw new IllegalArgumentException("产出路径：产出列必须带建议动作（不允许无动作、仅证据提交）");
        }
        requireText(draft.getActionSummary(), "产出路径：产出列必须带建议摘要（拒空摘要）");
        requireFactsPresent(draft, "产出路径：产出列的类型化依据必填（不得为 null，可为空集合）");
        requireText(draft.getRationaleNarrative(), "产出路径：产出列的文本兜底依据必填");
        if (direct) {
            if (draft.getModelId() != null) {
                throw new IllegalArgumentException("产出路径：直提无出域位点，modelId 必须为 null");
            }
            if (draft.getInputSnapshot() != null) {
                throw new IllegalArgumentException("产出路径：直提无出域位点，inputSnapshot 必须为 null");
            }
            if (!containsFactKey(draft.getRationaleFacts(), DecisionRationaleFactKey.BASIS_CODE)) {
                throw new IllegalArgumentException(
                        "产出路径：直提列的类型化依据须至少含一条 BASIS_CODE（准入规则的写侧守卫）");
            }
            if (draft.isInboundRestricted()) {
                throw new IllegalArgumentException(
                        "产出路径：直提的入站加工不经策略（策略 key 属节点声明，推面与声明解耦）"
                                + "⇒ 直提列取不到 RESTRICTED");
            }
        }
    }

    /** C 列的格：建议面必须 null；`MODEL_DECLINED` ⇒ 出处组与 `modelId` 必填，其余四值 ⇒ 必须 null。 */
    private static void requirePolicyCells(final DecisionEvidenceDraft draft) {
        if (draft.getSuggestedAction() != null || draft.getActionSummary() != null) {
            throw new IllegalArgumentException("outcome 分支：按政策未产出列的建议面必须为 null");
        }
        final DecisionPolicyReason policyReason = draft.getPolicyReason();
        if (policyReason == null) {
            throw new IllegalArgumentException("outcome 分支：按政策未产出列的 policyReason 必填（五值）");
        }
        requireFactsPresent(draft, "outcome 分支：按政策未产出列的类型化依据必填");
        requireText(draft.getRationaleNarrative(), "outcome 分支：按政策未产出列的文本兜底依据必填");
        if (draft.getInputSnapshot() != null) {
            throw new IllegalArgumentException("outcome 分支：按政策未产出列的 inputSnapshot 必须为 null");
        }
        if (draft.getRawOutput() != null) {
            throw new IllegalArgumentException("outcome 分支：按政策未产出列的 rawOutput 必须为 null");
        }
        final boolean declined = policyReason == DecisionPolicyReason.MODEL_DECLINED;
        if (declined) {
            if (isDirect(draft)) {
                throw new IllegalArgumentException(
                        "outcome 分支：MODEL_DECLINED 走过一次出站调用，出处组必填");
            }
            requireText(draft.getModelId(), "outcome 分支：MODEL_DECLINED 走过一次出站调用，modelId 必填");
        } else if (!isDirect(draft) || draft.getModelId() != null) {
            throw new IllegalArgumentException(
                    "outcome 分支：除 MODEL_DECLINED 外的四值结构上未发生出站调用，出处组与 modelId 必须为 null");
        }
    }

    /** D 列的格：建议面 / 依据面 / 自述位一律 null，失败类别必填，政策原因必须 null。 */
    private static void requireFailureCells(final DecisionEvidenceDraft draft) {
        if (draft.getFailureKind() == null) {
            throw new IllegalArgumentException("outcome 分支：失败列的 failureKind 必填（七值）");
        }
        if (draft.getSuggestedAction() != null || draft.getActionSummary() != null) {
            throw new IllegalArgumentException("outcome 分支：失败列的建议面必须为 null");
        }
        if (draft.getRationaleFacts() != null || draft.getRationaleNarrative() != null) {
            throw new IllegalArgumentException("outcome 分支：失败列的依据面必须为 null");
        }
        if (draft.getAttestedDataSources() != null) {
            throw new IllegalArgumentException("outcome 分支：失败列的自述位必须为 null");
        }
        if (draft.getPolicyReason() != null) {
            throw new IllegalArgumentException("outcome 分支：policyReason 只属按政策未产出列，失败列必须为 null");
        }
    }

    // ======================== 跨列守卫 ========================

    /** 判别 / 承载两格（四列全必填）与出处组一致性。 */
    private static void requireCarrier(final DecisionEvidenceDraft draft) {
        requireText(draft.getIdempotencyKey(), "产出路径：幂等身份四列全必填（「空」= null 或去空白空串）");
        if (draft.getSubjectType() == null) {
            throw new IllegalArgumentException("subjectType：判别式，四列全必填");
        }
    }

    /**
     * {@code failureKind} 双向守卫三条（ADR-0042 第 5 节）：产出态只允许 {@code null} 或
     * {@code INBOUND_PROCESSING_FAILED}；该例外值反向只许落在产出态；按政策未产出恒不得带失败类别。
     */
    private static void requireFailureKindGuards(final DecisionEvidenceDraft draft) {
        final DecisionOutcome outcome = draft.getOutcome();
        final DecisionFailureKind failureKind = draft.getFailureKind();
        if (outcome == DecisionOutcome.SUGGESTION_PRODUCED && failureKind != null
                && failureKind != DecisionFailureKind.INBOUND_PROCESSING_FAILED) {
            throw new IllegalArgumentException(
                    "outcome 分支：产出态只允许 failureKind 为 null 或 INBOUND_PROCESSING_FAILED：" + failureKind);
        }
        if (failureKind == DecisionFailureKind.INBOUND_PROCESSING_FAILED
                && outcome != DecisionOutcome.SUGGESTION_PRODUCED) {
            throw new IllegalArgumentException(
                    "outcome 分支：INBOUND_PROCESSING_FAILED 是本枚举唯一的产出态例外值，不得落在未产出态：" + outcome);
        }
        if (outcome == DecisionOutcome.NO_SUGGESTION_BY_POLICY && failureKind != null) {
            throw new IllegalArgumentException("outcome 分支：按政策未产出是机制的结论、不是异常，failureKind 必须为 null");
        }
        if (outcome == DecisionOutcome.SUGGESTION_FAILED && failureKind == null) {
            throw new IllegalArgumentException("outcome 分支：失败列的 failureKind 必填（七值）");
        }
    }

    /** {@code policyReason} 只属 C 列：其余三列必须为 null（与 {@code failureKind} 互斥）。 */
    private static void requirePolicyReasonPlacement(final DecisionEvidenceDraft draft, final Column column) {
        if (column != Column.POLICY && draft.getPolicyReason() != null) {
            throw new IllegalArgumentException("outcome 分支：policyReason 只属按政策未产出列，其余列必须为 null");
        }
    }

    /**
     * 两个显式事实与载荷字段的<b>锁</b>：该方向有载荷 ⇔ 该方向的载荷字段非空。
     *
     * <p>这就是互锁中「{@code FULL / PARTIAL} ⇒ 载荷非空」与「载荷空 ∧ 未加工 ⇒ {@code NO_PAYLOAD}」的
     * 写入侧落实；「{@code RESTRICTED} / 入站降级」时载荷字段被清空是它们各自的定义（见类 javadoc）。</p>
     */
    private static void requirePayloadFacts(final DecisionEvidenceDraft draft, final Column column) {
        if (column == Column.DIRECT && draft.isOutboundPayloadPresent()) {
            throw new IllegalArgumentException("产出路径：直提无出域位点，出域方向恒无载荷");
        }
        if (draft.isOutboundPayloadPresent() != StringUtils.isNotEmpty(draft.getInputSnapshot())) {
            throw new IllegalArgumentException("互锁：出域方向的载荷事实与 inputSnapshot 必须同向（有载荷 ⇔ 载荷非空）");
        }
        if (draft.isInboundRestricted() && draft.isInboundPayloadPresent()) {
            throw new IllegalArgumentException(
                    "互锁：入站「政策性不可落盘」与「有落盘载荷」互斥（前者恰是 RESTRICTED 的定义）");
        }
        final boolean inboundFailed = draft.getFailureKind() == DecisionFailureKind.INBOUND_PROCESSING_FAILED;
        if (draft.isInboundRestricted() && inboundFailed) {
            throw new IllegalArgumentException(
                    "互锁：入站两降级必须先分类型、不得同时声明 —— 政策性不可落盘（RESTRICTED、不计错误）"
                            + "与入站加工失败（NO_PAYLOAD + INBOUND_PROCESSING_FAILED、计错误）是两件事");
        }
        final boolean inboundHasPayload = draft.isInboundPayloadPresent() && !draft.isInboundRestricted() && !inboundFailed;
        if (inboundHasPayload != StringUtils.isNotEmpty(draft.getRawOutput())) {
            throw new IllegalArgumentException(
                    "互锁：入站方向的载荷事实与 rawOutput 必须同向（有落盘载荷 ⇔ rawOutput 非空）；"
                            + "政策性不可落盘与入站降级两种形态的 rawOutput 恒空");
        }
    }

    /**
     * 依据面的内容义务（{@code outcome} 分支驱动）：直提列须 ≥1 条 {@code BASIS_CODE}（在
     * {@link #requireProducedCells} 内强制）；C 列按 {@code policyReason} 分支 ——
     * {@code NO_SOURCE_DECLARED} ⇒ ≥1 条 {@code MISSING_INPUT}，其余四值 ⇒ ≥1 条 {@code POLICY_RULE}。
     */
    private static void requireRationaleFacts(final DecisionEvidenceDraft draft, final Column column) {
        if (column != Column.POLICY) {
            return;
        }
        final DecisionRationaleFactKey requiredKey =
                draft.getPolicyReason() == DecisionPolicyReason.NO_SOURCE_DECLARED
                        ? DecisionRationaleFactKey.MISSING_INPUT
                        : DecisionRationaleFactKey.POLICY_RULE;
        if (!containsFactKey(draft.getRationaleFacts(), requiredKey)) {
            throw new IllegalArgumentException(
                    "outcome 分支：按政策未产出列的类型化依据须至少含一条 " + requiredKey + "（现场值须可核）");
        }
    }

    /**
     * 自述位的三态与内容约束（<b>只有直提列可携带</b>）：三态可区分（{@code null} / 空集合 / 有值），
     * 有值态禁 null 元素、禁重复元素。
     */
    private static void requireAttestedDataSources(final DecisionEvidenceDraft draft, final Column column) {
        final List<?> sources = draft.getAttestedDataSources();
        if (sources == null) {
            return;
        }
        if (column != Column.DIRECT) {
            throw new IllegalArgumentException("产出路径：自述位只属直提列，其余三列必须为 null");
        }
        final Set<Object> seen = new HashSet<>();
        for (final Object source : sources) {
            if (source == null) {
                throw new IllegalArgumentException("自述位有值态禁止 null 元素");
            }
            if (!seen.add(source)) {
                throw new IllegalArgumentException("自述位有值态禁止重复元素：" + source);
            }
        }
    }

    /** 类型化依据的元素合法性（键属闭集、值非空白）。 */
    private static void requireFactsPresent(final DecisionEvidenceDraft draft, final String message) {
        if (draft.getRationaleFacts() == null) {
            throw new IllegalArgumentException(message);
        }
        for (final DecisionRationaleFact fact : draft.getRationaleFacts()) {
            if (fact == null || fact.getKey() == null || StringUtils.isBlank(fact.getValue())) {
                throw new IllegalArgumentException("类型化依据的元素非法（键须属闭集、值非空白）");
            }
        }
    }

    private static boolean containsFactKey(final List<DecisionRationaleFact> facts, final DecisionRationaleFactKey key) {
        if (facts == null) {
            return false;
        }
        return facts.stream().anyMatch(fact -> fact != null && fact.getKey() == key);
    }

    private static void requireText(final String value, final String message) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    // ======================== 标志推导与装配 ========================

    /**
     * 由两个显式事实 + 加工事实推出方向标志与完整度，并装配最终 VO。
     *
     * <p>「无载荷」时两布尔取 {@code false} —— 这是<b>诚实的真空真</b>（无载荷即无加工），
     * {@code NO_PAYLOAD} 由完整度独立承担，不被布尔兼职。</p>
     */
    private DecisionEvidenceVO build(final DecisionEvidenceDraft draft, final Column column) {
        final boolean outboundPayload = column != Column.DIRECT && draft.isOutboundPayloadPresent();
        final boolean outboundTruncated = outboundPayload
                && (draft.isOutboundTruncated() || draft.isOutboundClampDropped());
        final boolean outboundRedacted = outboundPayload && draft.isOutboundRedacted();

        final boolean inboundFailed = draft.getFailureKind() == DecisionFailureKind.INBOUND_PROCESSING_FAILED;
        final boolean inboundPayload = draft.isInboundPayloadPresent()
                && !draft.isInboundRestricted() && !inboundFailed;
        final boolean inboundTruncated = inboundPayload && draft.isInboundTruncated();
        final boolean inboundRedacted = inboundPayload && draft.isInboundRedacted();

        return DecisionEvidenceVO.builder()
                .outcome(draft.getOutcome())
                .schemaVersion(SCHEMA_VERSION)
                .idempotencyKey(draft.getIdempotencyKey())
                .suggestedAction(draft.getSuggestedAction())
                .actionSummary(draft.getActionSummary())
                .modelId(draft.getModelId())
                .inputSnapshot(outboundPayload ? draft.getInputSnapshot() : null)
                .rawOutput(inboundPayload ? draft.getRawOutput() : null)
                .rationaleFacts(draft.getRationaleFacts())
                .rationaleNarrative(draft.getRationaleNarrative())
                .attestedDataSources(column == Column.DIRECT ? draft.getAttestedDataSources() : null)
                .provider(draft.getProvider())
                .chainStage(draft.getChainStage())
                .degraded(draft.getDegraded())
                .subjectType(draft.getSubjectType())
                .subjectId(draft.getSubjectId())
                .subjectName(draft.getSubjectName())
                .outboundRedacted(outboundRedacted)
                .outboundTruncated(outboundTruncated)
                .outboundCompleteness(completenessOf(outboundPayload, outboundRedacted, outboundTruncated))
                .inboundRedacted(inboundRedacted)
                .inboundTruncated(inboundTruncated)
                .inboundCompleteness(inboundCompletenessOf(draft, inboundPayload, inboundRedacted, inboundTruncated))
                .failureKind(draft.getFailureKind())
                .policyReason(draft.getPolicyReason())
                .build();
    }

    /** 出域方向的推导域恒三值（{@code RESTRICTED} 的构造点只在入站）。 */
    private static DecisionCompleteness completenessOf(final boolean hasPayload,
                                                       final boolean redacted,
                                                       final boolean truncated) {
        if (!hasPayload) {
            return DecisionCompleteness.NO_PAYLOAD;
        }
        return redacted || truncated ? DecisionCompleteness.PARTIAL : DecisionCompleteness.FULL;
    }

    /** 入站方向的推导域四值：{@code RESTRICTED} = 有内容但策略判政策性不可落盘（不计错误）。 */
    private static DecisionCompleteness inboundCompletenessOf(final DecisionEvidenceDraft draft,
                                                              final boolean hasPayload,
                                                              final boolean redacted,
                                                              final boolean truncated) {
        if (draft.getFailureKind() == DecisionFailureKind.INBOUND_PROCESSING_FAILED) {
            return DecisionCompleteness.NO_PAYLOAD;
        }
        if (draft.isInboundRestricted()) {
            return DecisionCompleteness.RESTRICTED;
        }
        return completenessOf(hasPayload, redacted, truncated);
    }

    /**
     * 尺寸护栏拒写后的替代行：{@code SUGGESTION_FAILED} / {@code INTERNAL_ERROR} 的 D 列行
     * （<b>零新增枚举值 / 零新增槽位</b>）。
     *
     * <p>幂等身份与主体原样保留（D 列四列全必填），出处组保留以维持直提双射（直提失败行全 null、
     * 拉面失败行必带 provider）；两个方向皆无载荷 —— 被拒的原行内容<b>不落任何内容体</b>。</p>
     */
    private DecisionEvidenceVO internalErrorRow(final DecisionEvidenceVO refused) {
        return materialize(DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .failureKind(DecisionFailureKind.INTERNAL_ERROR)
                .idempotencyKey(refused.getIdempotencyKey())
                .subjectType(refused.getSubjectType())
                .subjectId(refused.getSubjectId())
                .subjectName(refused.getSubjectName())
                .provider(refused.getProvider())
                .chainStage(refused.getChainStage())
                .degraded(refused.getDegraded())
                .build());
    }

    // ======================== 序列化 ========================

    /** 证据行标记（与 {@code TYPE_} 同源，构造来源唯一）。 */
    private static String marker() {
        return DecisionEvidenceComment.marker();
    }

    private String serialize(final DecisionEvidenceVO evidence) {
        try {
            return mapper.writeValueAsString(evidence);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("证据行序列化失败：证据 VO 只有闭集取值与裸串，序列化不该失败", broken);
        }
    }

    private static int utf8Length(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
