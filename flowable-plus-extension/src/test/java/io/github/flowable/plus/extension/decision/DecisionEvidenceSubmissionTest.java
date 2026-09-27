package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionCompleteness;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E8 —— 证据提交模型守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E8}）。
 *
 * <p><b>承哪些推入项</b>：ADR-0042 第 11 节第 6 条 (c) 的证据面主落点 ——
 * <b>四列产出路径矩阵逐格</b>（A 经出站调用 / B 直提 / C 按政策未产出 / D 失败）与
 * <b>直提双射</b>（出处组三字段全 {@code null} ⇔ 直提，且直提列无出域位点、其入站加工不经策略
 * ⇒ 取不到 {@code RESTRICTED}）；并承接 {@code #49} 边界推入的「证据行序列化时
 * {@code attestedDataSources} 的 {@code null} <b>省略该键</b>」与三态可区分。</p>
 *
 * <p><b>逐格理由只引三处</b>（ADR-0042 第 5 节第 3 条 / 命名宪章 §2.B.5.2）：{@code outcome} 分支、
 * <b>产出路径</b>（A / B / C / D 的结构性事实）或 {@code subjectType}；<b>不得</b>引「因为会调模型」。
 * 本类的每条矩阵断言都把它引的那一处写在 {@code as(...)} 里，机器可判的逐格理由表住
 * core 的 {@code DecisionEvidenceVOContractTest}（唯一住所，本类只引判据、不复制表）。</p>
 *
 * <p><b>纯值，零引擎</b>：断言对象是物质化出来的证据 VO 与证据行文本。</p>
 */
class DecisionEvidenceSubmissionTest {

    /** 四列产出路径（A 经出站调用 / B 直提 / C 按政策未产出 / D 失败） */
    private enum PathColumn {

        /** A：{@code SUGGESTION_PRODUCED} ∧ 出处组非空 */
        A_VIA_OUTBOUND,

        /** B：{@code SUGGESTION_PRODUCED} ∧ 出处组全 {@code null} */
        B_DIRECT,

        /** C：{@code NO_SUGGESTION_BY_POLICY} */
        C_POLICY,

        /** D：{@code SUGGESTION_FAILED} */
        D_FAILURE
    }

    private final DecisionEvidenceWriter writer = new DecisionEvidenceWriter();

    @Test
    @DisplayName("四列产出路径恰好四列：逐组合驱动，无第五列，重放不另成一列")
    void producesExactlyFourPathColumns() {
        // 组合穷举：(outcome × 出处组是否为空) 共六格，逐格驱动写入器
        final Map<PathColumn, DecisionEvidenceVO> byColumn = new LinkedHashMap<>();
        byColumn.put(PathColumn.A_VIA_OUTBOUND, writer.materialize(DecisionFixtures.outboundDraft()));
        byColumn.put(PathColumn.B_DIRECT,
                writer.materialize(DecisionFixtures.directDraft(null, false)));
        byColumn.put(PathColumn.C_POLICY,
                writer.materialize(DecisionFixtures.policyDraft(DecisionPolicyReason.NO_SOURCE_DECLARED)));
        byColumn.put(PathColumn.D_FAILURE,
                writer.materialize(DecisionFixtures.failureDraft(DecisionFailureKind.OUTBOUND_TIMEOUT)));

        assertThat(EnumSet.allOf(PathColumn.class))
                .as("产出路径只有四列（穷举面：不得出现第五列，例如把重放列成一列）")
                .hasSize(4);
        assertThat(byColumn.keySet())
                .as("四列逐格都有产出（逐格驱动，不靠抽样）")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(PathColumn.class));
        byColumn.forEach((declared, evidence) -> assertThat(columnOf(evidence))
                .as("产出 VO 的列必须与它被构造时声明的那一列一致")
                .isEqualTo(declared));

        // 互斥：每列只见它自己的判别式与出处组形态
        assertThat(columnOf(byColumn.get(PathColumn.A_VIA_OUTBOUND)))
                .as("产出路径的结构性事实：A 与 B 的分叉只在出处组是否为空，不在 outcome")
                .isNotEqualTo(columnOf(byColumn.get(PathColumn.B_DIRECT)));
        assertThat(byColumn.get(PathColumn.A_VIA_OUTBOUND).getOutcome())
                .as("产出路径：A 与 B 同属产出态")
                .isEqualTo(byColumn.get(PathColumn.B_DIRECT).getOutcome());

        // 半填出处组既不是 A 也不是 B ⇒ 写不出来（无出生路径）
        assertThatThrownBy(() -> writer.materialize(DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_PRODUCED)
                .idempotencyKey(DecisionFixtures.IDEMPOTENCY_KEY)
                .subjectType(DecisionFixtures.directSubmission(null).getSubjectType())
                .suggestedAction(DecisionFixtures.directSubmission(null).getSuggestedAction())
                .actionSummary("摘要")
                .rationaleFacts(DecisionFixtures.facts(DecisionRationaleFactKey.BASIS_CODE))
                .rationaleNarrative("依据")
                .provider(DecisionFixtures.PROVIDER)
                .build()))
                .as("出处组半填（只有 provider）⇒ 非法态")
                .isInstanceOf(IllegalArgumentException.class);

        // 重放不是第五列：同一幂等身份重复到达，照旧落在它本来的那一列
        final DecisionEvidenceVO replay = writer.materialize(DecisionFixtures.outboundDraft());
        assertThat(replay.getIdempotencyKey())
                .as("幂等身份不规范化、原样存原样比（重复到达不抑制）")
                .isEqualTo(byColumn.get(PathColumn.A_VIA_OUTBOUND).getIdempotencyKey());
        assertThat(columnOf(replay))
                .as("重复到达不新增列")
                .isEqualTo(PathColumn.A_VIA_OUTBOUND);

        // C 列内部的二分不改列：MODEL_DECLINED 与其余四值同属 C 列
        EnumSet.allOf(DecisionPolicyReason.class).forEach(reason -> assertThat(
                columnOf(writer.materialize(DecisionFixtures.policyDraft(reason))))
                .as("outcome 分支：policyReason 的五值同属按政策未产出列")
                .isEqualTo(PathColumn.C_POLICY));

        // D 列内部的六值亦不改列（第七值 INBOUND_PROCESSING_FAILED 是产出态的唯一例外值，
        // 它的双向守卫与拒绝落点归 E9 的 #inboundFailureKindExceptionIsBidirectional()）
        EnumSet.allOf(DecisionFailureKind.class).stream()
                .filter(kind -> kind != DecisionFailureKind.INBOUND_PROCESSING_FAILED)
                .forEach(kind -> assertThat(columnOf(writer.materialize(DecisionFixtures.failureDraft(kind))))
                        .as("outcome 分支：失败列的 failureKind 取值不改列")
                        .isEqualTo(PathColumn.D_FAILURE));
    }

    @Test
    @DisplayName("四列矩阵逐格成立：必填 / 可空 / 必须 null 逐格断言")
    void matrixCellHoldsForEveryProducedColumn() {
        final Map<PathColumn, DecisionEvidenceVO> samples = new LinkedHashMap<>();
        samples.put(PathColumn.A_VIA_OUTBOUND, writer.materialize(DecisionFixtures.outboundDraft()));
        samples.put(PathColumn.B_DIRECT,
                writer.materialize(DecisionFixtures.directDraft(DecisionFixtures.allContextSources(), true)));
        samples.put(PathColumn.C_POLICY,
                writer.materialize(DecisionFixtures.policyDraft(DecisionPolicyReason.MODEL_DECLINED)));
        samples.put(PathColumn.D_FAILURE,
                writer.materialize(DecisionFixtures.failureDraft(DecisionFailureKind.OUTBOUND_TIMEOUT)));

        assertThat(samples.keySet())
                .as("逐格表驱动：四列一列不漏（漏一列即整列矩阵无断言）")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(PathColumn.class));
        samples.forEach(this::assertCommonCells);

        assertOutboundCells(samples.get(PathColumn.A_VIA_OUTBOUND));
        assertDirectCells(samples.get(PathColumn.B_DIRECT));
        assertPolicyCells(samples.get(PathColumn.C_POLICY));
        assertFailureCells(samples.get(PathColumn.D_FAILURE));

        // C 列内部二分的另一支（未发生出站调用）逐格对账
        assertPolicyWithoutOutboundCallCells(
                writer.materialize(DecisionFixtures.policyDraft(DecisionPolicyReason.NO_SOURCE_DECLARED)));
    }

    @Test
    @DisplayName("直提双射：出处组三字段全 null ⇔ 直提（含入站三态与出域恒无载荷）")
    void directSubmissionBijectionHolds() {
        final DecisionEvidenceVO direct = writer.materialize(
                DecisionFixtures.directDraft(DecisionFixtures.allContextSources(), true));
        final DecisionEvidenceVO outbound = writer.materialize(DecisionFixtures.outboundDraft());

        assertThat(hasProvenance(direct))
                .as("产出路径：直提 ⇒ 出处组三者全 null")
                .isFalse();
        assertThat(hasProvenance(outbound))
                .as("产出路径：经出站调用 ⇒ 出处组三者非 null")
                .isTrue();

        assertThat(direct.getModelId())
                .as("产出路径：直提无出域位点，modelId 必须 null")
                .isNull();
        assertThat(direct.getInputSnapshot())
                .as("产出路径：直提无出域位点，inputSnapshot 必须 null")
                .isNull();
        assertThat(direct.getRationaleFacts())
                .as("产出路径：直提列的类型化依据须至少含一条 BASIS_CODE")
                .anyMatch(fact -> fact.getKey() == DecisionRationaleFactKey.BASIS_CODE);

        assertThat(direct.getOutboundCompleteness())
                .as("产出路径：直提出域方向恒 NO_PAYLOAD（无载荷即无加工）")
                .isEqualTo(DecisionCompleteness.NO_PAYLOAD);
        assertThat(direct.isOutboundRedacted())
                .as("产出路径：直提出域双标志恒 false")
                .isFalse();
        assertThat(direct.isOutboundTruncated())
                .as("产出路径：直提出域双标志恒 false")
                .isFalse();

        assertThat(Boolean.class)
                .as("出处组的降级位取可空 Boolean —— 原始 boolean 会使双射永不成立")
                .isEqualTo(declaredTypeOf("degraded", DecisionEvidenceVO.class));
        assertThat(direct.getDegraded())
                .as("直提双射的「全 null」一侧在类型与取值上真的可达")
                .isNull();

        assertThatThrownBy(() -> {
            final DecisionEvidenceDraft halfFilled = DecisionEvidenceDraft
                    .of(DecisionFixtures.directSubmission(null));
            halfFilled.setProvider(DecisionFixtures.PROVIDER);
            writer.materialize(halfFilled);
        })
                .as("直提双射的另一半：出处组半填写不出来")
                .isInstanceOf(IllegalArgumentException.class);

        // 直提入站按提交方是否交载荷取 FULL / PARTIAL / NO_PAYLOAD，且取不到 RESTRICTED
        assertThat(writer.materialize(DecisionFixtures.directDraft(null, false)).getInboundCompleteness())
                .as("产出路径：提交方没交载荷 ⇒ 入站 NO_PAYLOAD")
                .isEqualTo(DecisionCompleteness.NO_PAYLOAD);

        final DecisionEvidenceDraft full = DecisionEvidenceDraft
                .of(DecisionFixtures.directSubmission(null));
        full.setInboundPayloadPresent(true);
        assertThat(writer.materialize(full).getInboundCompleteness())
                .as("产出路径：交了载荷且无加工 ⇒ 入站 FULL")
                .isEqualTo(DecisionCompleteness.FULL);

        final DecisionEvidenceDraft partial = DecisionEvidenceDraft
                .of(DecisionFixtures.directSubmission(null));
        partial.setInboundPayloadPresent(true);
        partial.setInboundTruncated(true);
        final DecisionEvidenceVO partialEvidence = writer.materialize(partial);
        assertThat(partialEvidence.getInboundCompleteness())
                .as("产出路径：交了载荷且发生加工 ⇒ 入站 PARTIAL")
                .isEqualTo(DecisionCompleteness.PARTIAL);

        // 直提列取不到 RESTRICTED：不是「样本里没出现」这种真空断言，而是该形态在直提列**写不出来**
        final DecisionEvidenceDraft policyRejectedOnDirect = DecisionFixtures.directDraft(null, false);
        policyRejectedOnDirect.setInboundRestricted(true);
        assertThatThrownBy(() -> writer.materialize(policyRejectedOnDirect))
                .as("产出路径：直提的入站加工不经策略（策略 key 属节点声明、推面与声明解耦）"
                        + "⇒ 直提列声明「政策性不可落盘」即非法态，取不到 RESTRICTED")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("自述位三态可区分：位缺失省略键 / 显式空集 / 有值（且注解只落该字段）")
    void attestedDataSourcesThreeStatesAreDistinguishable() throws IOException {
        final JsonNode absent = DecisionFixtures.evidenceJson(
                writer.row(writer.materialize(DecisionFixtures.directDraft(null, true))));
        final JsonNode empty = DecisionFixtures.evidenceJson(
                writer.row(writer.materialize(DecisionFixtures.directDraft(new ArrayList<>(), true))));
        final JsonNode valued = DecisionFixtures.evidenceJson(writer.row(
                writer.materialize(DecisionFixtures.directDraft(DecisionFixtures.allContextSources(), true))));

        assertThat(absent.has("attestedDataSources"))
                .as("位缺失（null）⇒ 序列化时省略该键")
                .isFalse();
        assertThat(empty.get("attestedDataSources"))
                .as("显式空集 ⇒ 键在场且为空数组")
                .isEmpty();
        assertThat(valued.get("attestedDataSources"))
                .as("有值 ⇒ 键在场且逐个落出申报的来源")
                .hasSize(4);

        assertThat(absent.has("modelId"))
                .as("「省略 null 键」只落自述位这一个字段：矩阵里「必须 null」的格依赖键仍在")
                .isTrue();
        assertThat(absent.get("modelId").isNull())
                .as("矩阵「必须 null」的格以 JSON null 在场，与「位缺失」可区分")
                .isTrue();
    }

    // ======================== 逐列矩阵 ========================

    /** 四列公共格：判别 / 承载四格必填、主体描述可空。 */
    private void assertCommonCells(final PathColumn column, final DecisionEvidenceVO evidence) {
        assertThat(evidence.getOutcome())
                .as("outcome 分支：判别式四列全必填")
                .isNotNull();
        assertThat(evidence.getSchemaVersion())
                .as("产出路径：schemaVersion 四列全必填（框架恒填 v1）")
                .isEqualTo(DecisionEvidenceWriter.SCHEMA_VERSION);
        assertThat(evidence.getIdempotencyKey())
                .as("产出路径：幂等身份四列全必填")
                .isEqualTo(DecisionFixtures.IDEMPOTENCY_KEY);
        assertThat(evidence.getSubjectType())
                .as("subjectType 分支：判别式四列全必填")
                .isNotNull();
        assertThat(evidence.getSubjectId())
                .as("subjectType 分支：ID 与显示名是描述性字段，可空（本列有值）")
                .isEqualTo(DecisionFixtures.SUBJECT_ID);
        assertThat(evidence.getSubjectName())
                .as("subjectType 分支：ID 与显示名是描述性字段，可空（本列有值）")
                .isEqualTo(DecisionFixtures.SUBJECT_NAME);
    }

    /** A 列：经出站调用 —— 建议面与证据面必填，自述位必须 null，出处组必填。 */
    private void assertOutboundCells(final DecisionEvidenceVO evidence) {
        assertThat(evidence.getSuggestedAction())
                .as("产出路径：产出列的建议动作必填")
                .isNotNull();
        assertThat(evidence.getActionSummary())
                .as("产出路径：产出列的建议摘要必填")
                .isNotBlank();
        assertThat(evidence.getRationaleFacts())
                .as("产出路径：产出列的类型化依据必填")
                .isNotEmpty();
        assertThat(evidence.getRationaleNarrative())
                .as("产出路径：产出列的文本兜底依据必填")
                .isNotBlank();
        assertThat(evidence.getAttestedDataSources())
                .as("产出路径：自述位只属直提列，经出站调用列必须 null")
                .isNull();
        assertThat(evidence.getProvider())
                .as("产出路径：A 列出处组必填")
                .isNotBlank();
        assertThat(evidence.getChainStage())
                .as("产出路径：A 列出处组必填")
                .isEqualTo(DecisionChainStage.PRIMARY);
        assertThat(evidence.getDegraded())
                .as("产出路径：A 列出处组必填")
                .isFalse();
        assertThat(evidence.getModelId())
                .as("产出路径：A 列不保证只含模型端点，modelId 条件必填（本样本有值；"
                        + "该条件只在出站缝可得，属本层面不真置 —— 见 E9 类 javadoc 的如实登记）")
                .isNotBlank();
        assertThat(evidence.getInputSnapshot())
                .as("产出路径：A 列出域载荷受出域控制，可空（有载荷时非空）")
                .isNotBlank();
        assertThat(evidence.getRawOutput())
                .as("产出路径：A 列入站输出经入站加工，可空（有载荷时非空）")
                .isNotBlank();
        assertThat(evidence.getPolicyReason())
                .as("outcome 分支：policyReason 只属按政策未产出列")
                .isNull();
        assertThat(evidence.getFailureKind())
                .as("outcome 分支：产出态的 failureKind 只允许 null 或 INBOUND_PROCESSING_FAILED")
                .isNull();
    }

    /** B 列：直提 —— 建议面与证据面必填，出处组三者必须 null，自述位三态可空。 */
    private void assertDirectCells(final DecisionEvidenceVO evidence) {
        assertThat(evidence.getSuggestedAction())
                .as("产出路径：产出列的建议动作必填")
                .isNotNull();
        assertThat(evidence.getActionSummary())
                .as("产出路径：产出列的建议摘要必填")
                .isNotBlank();
        assertThat(evidence.getRationaleFacts())
                .as("产出路径：直提列的类型化依据必填且须含 BASIS_CODE")
                .anyMatch(fact -> fact.getKey() == DecisionRationaleFactKey.BASIS_CODE);
        assertThat(evidence.getRationaleNarrative())
                .as("产出路径：产出列的文本兜底依据必填")
                .isNotBlank();
        assertThat(evidence.getAttestedDataSources())
                .as("产出路径：自述位可空，三态（本样本为「有值」态）")
                .hasSize(4);
        assertThat(evidence.getProvider())
                .as("产出路径：B 列出处组三者必须 null")
                .isNull();
        assertThat(evidence.getChainStage())
                .as("产出路径：B 列出处组三者必须 null")
                .isNull();
        assertThat(evidence.getDegraded())
                .as("产出路径：B 列出处组三者必须 null")
                .isNull();
        assertThat(evidence.getModelId())
                .as("产出路径：直提无出域位点，modelId 必须 null")
                .isNull();
        assertThat(evidence.getInputSnapshot())
                .as("产出路径：直提无出域位点，inputSnapshot 必须 null")
                .isNull();
    }

    /** C 列（{@code MODEL_DECLINED} 支）：建议面必须 null，出处组与 {@code modelId} 必填，载荷字段必须 null。 */
    private void assertPolicyCells(final DecisionEvidenceVO evidence) {
        assertThat(evidence.getSuggestedAction())
                .as("outcome 分支：按政策未产出列的建议动作必须 null")
                .isNull();
        assertThat(evidence.getActionSummary())
                .as("outcome 分支：按政策未产出列的建议摘要必须 null")
                .isNull();
        assertThat(evidence.getPolicyReason())
                .as("outcome 分支：按政策未产出列的 policyReason 必填")
                .isEqualTo(DecisionPolicyReason.MODEL_DECLINED);
        assertThat(evidence.getRationaleFacts())
                .as("outcome 分支：按政策未产出列的类型化依据必填（非 NO_SOURCE_DECLARED ⇒ 含 POLICY_RULE）")
                .anyMatch(fact -> fact.getKey() == DecisionRationaleFactKey.POLICY_RULE);
        assertThat(evidence.getRationaleNarrative())
                .as("outcome 分支：按政策未产出列的文本兜底依据必填")
                .isNotBlank();
        assertThat(evidence.getProvider())
                .as("outcome 分支：MODEL_DECLINED 走过一次出站调用，出处组必填")
                .isNotBlank();
        assertThat(evidence.getModelId())
                .as("outcome 分支：MODEL_DECLINED 走过一次出站调用，modelId 必填")
                .isNotBlank();
        assertThat(evidence.getInputSnapshot())
                .as("outcome 分支：按政策未产出列的 inputSnapshot 必须 null")
                .isNull();
        assertThat(evidence.getRawOutput())
                .as("outcome 分支：按政策未产出列的 rawOutput 必须 null")
                .isNull();
        assertThat(evidence.getAttestedDataSources())
                .as("产出路径：自述位只属直提列，按政策未产出列必须 null")
                .isNull();
        assertThat(evidence.getFailureKind())
                .as("outcome 分支：按政策未产出是机制的结论，failureKind 必须 null")
                .isNull();
    }

    /** C 列（其余四值支）：结构上未发生出站调用 ⇒ 出处组与 {@code modelId} 必须 null。 */
    private void assertPolicyWithoutOutboundCallCells(final DecisionEvidenceVO evidence) {
        assertThat(evidence.getPolicyReason())
                .as("outcome 分支：按政策未产出列的 policyReason 必填")
                .isEqualTo(DecisionPolicyReason.NO_SOURCE_DECLARED);
        assertThat(evidence.getProvider())
                .as("outcome 分支：该支结构上未发生出站调用，出处组必须 null")
                .isNull();
        assertThat(evidence.getChainStage())
                .as("outcome 分支：该支结构上未发生出站调用，出处组必须 null")
                .isNull();
        assertThat(evidence.getDegraded())
                .as("outcome 分支：该支结构上未发生出站调用，出处组必须 null")
                .isNull();
        assertThat(evidence.getModelId())
                .as("outcome 分支：该支结构上未发生出站调用，modelId 必须 null")
                .isNull();
        assertThat(evidence.getRationaleFacts())
                .as("outcome 分支：空装配支的类型化依据须至少含一条 MISSING_INPUT")
                .anyMatch(fact -> fact.getKey() == DecisionRationaleFactKey.MISSING_INPUT);
        assertThat(evidence.getSuggestedAction())
                .as("outcome 分支：按政策未产出列的建议动作必须 null")
                .isNull();
    }

    /** D 列：建议面 / 依据面 / 自述位必须 null，失败类别必填。 */
    private void assertFailureCells(final DecisionEvidenceVO evidence) {
        assertThat(evidence.getSuggestedAction())
                .as("outcome 分支：失败列的建议动作必须 null")
                .isNull();
        assertThat(evidence.getActionSummary())
                .as("outcome 分支：失败列的建议摘要必须 null")
                .isNull();
        assertThat(evidence.getRationaleFacts())
                .as("outcome 分支：失败列的类型化依据必须 null")
                .isNull();
        assertThat(evidence.getRationaleNarrative())
                .as("outcome 分支：失败列的文本兜底依据必须 null")
                .isNull();
        assertThat(evidence.getAttestedDataSources())
                .as("产出路径：自述位只属直提列，失败列必须 null")
                .isNull();
        assertThat(evidence.getFailureKind())
                .as("outcome 分支：失败列的 failureKind 必填（七值）")
                .isEqualTo(DecisionFailureKind.OUTBOUND_TIMEOUT);
        assertThat(evidence.getPolicyReason())
                .as("outcome 分支：policyReason 只属按政策未产出列，失败列必须 null")
                .isNull();
    }

    // ======================== 辅助 ========================

    /** 由产出 VO 反推它落在哪一列（判别式定三叶子态，产出态内再按出处组是否为空分叉）。 */
    private static PathColumn columnOf(final DecisionEvidenceVO evidence) {
        switch (evidence.getOutcome()) {
            case SUGGESTION_PRODUCED:
                return hasProvenance(evidence) ? PathColumn.A_VIA_OUTBOUND : PathColumn.B_DIRECT;
            case NO_SUGGESTION_BY_POLICY:
                return PathColumn.C_POLICY;
            case SUGGESTION_FAILED:
                return PathColumn.D_FAILURE;
            default:
                throw new IllegalStateException("未知结局：" + evidence.getOutcome());
        }
    }

    private static boolean hasProvenance(final DecisionEvidenceVO evidence) {
        return provenanceCount(evidence) == 3;
    }

    private static int provenanceCount(final DecisionEvidenceVO evidence) {
        int present = 0;
        if (evidence.getProvider() != null) {
            present++;
        }
        if (evidence.getChainStage() != null) {
            present++;
        }
        if (evidence.getDegraded() != null) {
            present++;
        }
        return present;
    }

    /** 证据行文本 → JSON（读侧同一契约：标记剥离与解析走 core 与测试基座的单一来源）。 */
    private static JsonNode evidenceJson(final DecisionEvidenceVO evidence) throws IOException {
        return DecisionFixtures.evidenceJson(new DecisionEvidenceWriter().row(evidence));
    }

    private static Class<?> declaredTypeOf(final String fieldName, final Class<?> owner) {
        try {
            return owner.getDeclaredField(fieldName).getType();
        } catch (NoSuchFieldException absent) {
            throw new IllegalStateException("证据 VO 的出处组字段不存在：" + fieldName, absent);
        }
    }
}
