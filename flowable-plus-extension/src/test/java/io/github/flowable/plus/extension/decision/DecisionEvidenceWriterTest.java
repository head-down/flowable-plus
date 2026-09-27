package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionCompleteness;
import io.github.flowable.plus.core.enums.DecisionEvidenceWriteGuard;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E9 —— 证据写入器守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E9}）。
 *
 * <p><b>承哪些推入项</b>：{@code #31} 的标志<b>互锁四联</b>（每方向）与 {@code failureKind} 双向守卫三条；
 * {@code #35} 的<b>标志推导状态驱动</b>（两个显式事实、出域三值 / 入站四值、出域取不到 {@code RESTRICTED}、
 * 装配器丢不计标志 vs clamp 丢计 {@code truncated}）、两类未产出物质化、<b>入站两降级先分类型</b>；
 * 以及模块与构建 §4 的写入侧护栏（超限拒绝写入 ⇒ {@code SUGGESTION_FAILED} / {@code INTERNAL_ERROR}，
 * 零新增枚举值 / 槽位）。</p>
 *
 * <p><b>必填 / 可空矩阵逐格写必填理由</b>（{@code verification-landings} §6.3 第 3 条，住所 {@code C6 · E9}）：
 * 逐格理由只能引 <b>{@code outcome} 分支</b>、<b>产出路径</b>或 <b>{@code subjectType}</b>，
 * <b>不得</b>引「因为会调模型」。机器可判的逐格理由表住 core 的
 * {@code DecisionEvidenceVOContractTest}（<b>唯一机器住所</b>，防两处真相）；本类的分工是把矩阵落成
 * <b>行为的必然</b>（写不出来的格），理由来源逐组列在下面，人评审可判：</p>
 *
 * <ul>
 *   <li><b>{@code subjectType}</b>（四列必填）：判别式本身 —— 缺它整条证据没有主体判别面。</li>
 *   <li><b>产出路径</b>（A / B / C / D 的结构性事实）：{@code idempotencyKey} 四列必填（可判别性）；
 *       {@code schemaVersion} 与方向标志六项四列必填（框架恒填，故在写入器里「推导出来」而不是「传进来」）；
 *       建议面两格在产出列必填、在其余两列必须 null；出处组在 A 列必填 / B 列必须 null；{@code inputSnapshot}
 *       在直提列必须 null；{@code attestedDataSources} 只属直提列。</li>
 *   <li><b>{@code outcome} 分支</b>：{@code policyReason} 只属按政策未产出列；{@code failureKind} 在失败列必填、
 *       在产出列必须 null（唯一例外 {@code INBOUND_PROCESSING_FAILED}）；依据面在产出列与按政策未产出列必填、
 *       在失败列必须 null。</li>
 * </ul>
 *
 * <p><b>两处「本层面不真置」的如实登记</b>：① A 列 {@code modelId} 的**条件必填**（矩阵行注「A 列不保证只含
 * 模型端点」）—— 其条件（Provider 缝是否从响应解析到模型标识）只在**出站缝**可得，本层面既不能置真、也不能置假，
 * 故写入器**不设该判据**，判定点归拉管线与出站缝票；② 「矩阵任何一格都写不出来」这句话在 A 列 {@code modelId}
 * 这**一格**上不成立（同上）。两条都**不新增断言**，如实披露而非以弱断言充数。</p>
 *
 * <p><b>纯值，零引擎</b>：断言对象是物质化出来的证据 VO 与证据行文本（持久化由调用方完成）。</p>
 */
class DecisionEvidenceWriterTest {

    /** 出域方向的完整度取值域（三值；{@code RESTRICTED} 的构造点只在入站） */
    private static final Set<DecisionCompleteness> OUTBOUND_DOMAIN = EnumSet.of(
            DecisionCompleteness.FULL, DecisionCompleteness.PARTIAL, DecisionCompleteness.NO_PAYLOAD);

    /** 入站方向的完整度取值域（四值） */
    private static final Set<DecisionCompleteness> INBOUND_DOMAIN = EnumSet.allOf(DecisionCompleteness.class);

    /** 超限样本的文本长度（≈ 80 KiB 上限的 1.5 倍，确保整行必超） */
    private static final int OVERSIZED_TEXT_LENGTH = (int) (DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES * 1.5);

    private final DecisionEvidenceWriter writer = new DecisionEvidenceWriter();

    @Test
    @DisplayName("标志互锁四联逐方向：PARTIAL ⇔ 有加工 / 无载荷 ⇒ NO_PAYLOAD / 有载荷 ⇔ 非 NO_PAYLOAD")
    void flagInterlockHoldsPerDirection() {
        final List<DecisionEvidenceVO> corpus = corpus();
        assertThat(corpus).as("防空转：样本集必须真的产出多条证据").hasSizeGreaterThan(8);

        corpus.forEach(evidence -> {
            final String where = evidence.getOutcome() + "/" + evidence.getIdempotencyKey();

            final boolean outboundProcessed = evidence.isOutboundRedacted() || evidence.isOutboundTruncated();
            assertThat(evidence.getOutboundCompleteness() == DecisionCompleteness.PARTIAL)
                    .as("出域方向的互锁第一联（%s）：PARTIAL ⇔ (redacted ∨ truncated)", where)
                    .isEqualTo(outboundProcessed);
            if (evidence.getOutboundCompleteness() == DecisionCompleteness.FULL
                    || evidence.getOutboundCompleteness() == DecisionCompleteness.NO_PAYLOAD) {
                assertThat(outboundProcessed)
                        .as("出域方向的互锁第二联（%s）：(FULL ∨ NO_PAYLOAD) ⇒ (¬redacted ∧ ¬truncated)", where)
                        .isFalse();
            }
            if (!outboundProcessed) {
                assertThat(evidence.getOutboundCompleteness())
                        .as("出域方向的互锁第二联反支（%s）：无加工 ⇒ 只可能是 FULL / NO_PAYLOAD（出域取不到 RESTRICTED）",
                                where)
                        .isIn(DecisionCompleteness.FULL, DecisionCompleteness.NO_PAYLOAD);
            }
            if (evidence.getOutboundCompleteness() == DecisionCompleteness.NO_PAYLOAD) {
                assertThat(evidence.getInputSnapshot())
                        .as("出域方向的互锁第三联（%s）：NO_PAYLOAD ⇒ 载荷字段为空", where)
                        .isNull();
                assertThat(outboundProcessed)
                        .as("出域方向的互锁第三联（%s）：NO_PAYLOAD ⇒ 未加工", where)
                        .isFalse();
            }
            if (evidence.getInputSnapshot() == null && !outboundProcessed) {
                assertThat(evidence.getOutboundCompleteness())
                        .as("出域方向的互锁第三联反支（%s）：载荷空 ∧ 未加工 ⇒ NO_PAYLOAD", where)
                        .isEqualTo(DecisionCompleteness.NO_PAYLOAD);
            }
            if (evidence.getOutboundCompleteness() == DecisionCompleteness.FULL
                    || evidence.getOutboundCompleteness() == DecisionCompleteness.PARTIAL) {
                assertThat(evidence.getInputSnapshot())
                        .as("出域方向的互锁第四联（%s）：FULL / PARTIAL ⇒ 载荷非空", where)
                        .isNotBlank();
            }

            final boolean inboundProcessed = evidence.isInboundRedacted() || evidence.isInboundTruncated();
            assertThat(evidence.getInboundCompleteness() == DecisionCompleteness.PARTIAL)
                    .as("入站方向的互锁第一联（%s）：PARTIAL ⇔ (redacted ∨ truncated)", where)
                    .isEqualTo(inboundProcessed);
            if (evidence.getInboundCompleteness() == DecisionCompleteness.FULL
                    || evidence.getInboundCompleteness() == DecisionCompleteness.NO_PAYLOAD) {
                assertThat(inboundProcessed)
                        .as("入站方向的互锁第二联（%s）：(FULL ∨ NO_PAYLOAD) ⇒ (¬redacted ∧ ¬truncated)", where)
                        .isFalse();
            }
            if (!inboundProcessed) {
                assertThat(evidence.getInboundCompleteness())
                        .as("入站方向的互锁第二联反支（%s）：无加工 ⇒ FULL / NO_PAYLOAD / RESTRICTED 三者之一"
                                + "（RESTRICTED 是「有载荷但按政策不可落盘」的第四值，不违「不被 FULL 兼职」）", where)
                        .isIn(DecisionCompleteness.FULL, DecisionCompleteness.NO_PAYLOAD,
                                DecisionCompleteness.RESTRICTED);
            }
            if (evidence.getInboundCompleteness() == DecisionCompleteness.NO_PAYLOAD
                    || evidence.getInboundCompleteness() == DecisionCompleteness.RESTRICTED) {
                assertThat(evidence.getRawOutput())
                        .as("入站方向的互锁第三联（%s）：NO_PAYLOAD / RESTRICTED ⇒ 载荷字段为空", where)
                        .isNull();
                assertThat(inboundProcessed)
                        .as("入站方向的互锁第三联（%s）：无落盘载荷 ⇒ 未加工", where)
                        .isFalse();
            }
            if (evidence.getRawOutput() == null && !inboundProcessed
                    && evidence.getInboundCompleteness() != DecisionCompleteness.RESTRICTED) {
                assertThat(evidence.getInboundCompleteness())
                        .as("入站方向的互锁第三联反支（%s）：载荷空 ∧ 未加工 ∧ 非政策性不可落盘 ⇒ NO_PAYLOAD", where)
                        .isEqualTo(DecisionCompleteness.NO_PAYLOAD);
            }
            if (evidence.getInboundCompleteness() == DecisionCompleteness.FULL
                    || evidence.getInboundCompleteness() == DecisionCompleteness.PARTIAL) {
                assertThat(evidence.getRawOutput())
                        .as("入站方向的互锁第四联（%s）：FULL / PARTIAL ⇒ 载荷非空", where)
                        .isNotBlank();
            }
        });

        assertThat(corpus.stream().filter(evidence -> evidence.getInputSnapshot() != null)
                .allMatch(evidence -> evidence.getOutboundCompleteness() == DecisionCompleteness.FULL
                        || evidence.getOutboundCompleteness() == DecisionCompleteness.PARTIAL))
                .as("「无实际载荷」由 NO_PAYLOAD 单一承担：有载荷的样本不存在 NO_PAYLOAD")
                .isTrue();
    }

    @Test
    @DisplayName("failureKind 双向守卫三条：产出态例外、反向落点、按政策未产出恒 null")
    void inboundFailureKindExceptionIsBidirectional() {
        // 第一联：产出态只允许 null 或 INBOUND_PROCESSING_FAILED
        assertThatThrownBy(() -> writer.materialize(inboundFailureDraft(DecisionFailureKind.OUTBOUND_TIMEOUT)))
                .as("产出态带上出站类失败类别 ⇒ 非法态")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(writer.materialize(inboundFailureDraft(DecisionFailureKind.INBOUND_PROCESSING_FAILED))
                .getFailureKind())
                .as("产出态允许的唯一例外值")
                .isEqualTo(DecisionFailureKind.INBOUND_PROCESSING_FAILED);

        // 第二联：反向 —— 例外值只许落在产出态
        assertThatThrownBy(() -> writer.materialize(DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .failureKind(DecisionFailureKind.INBOUND_PROCESSING_FAILED)
                .idempotencyKey(DecisionFixtures.IDEMPOTENCY_KEY)
                .subjectType(DecisionSubjectType.SYSTEM)
                .build()))
                .as("INBOUND_PROCESSING_FAILED 落进失败列 ⇒ 非法态（双向守卫的第二条）")
                .isInstanceOf(IllegalArgumentException.class);

        // 第三联：按政策未产出恒不得带失败类别
        final DecisionEvidenceDraft policyWithFailure = DecisionFixtures.policyDraft(DecisionPolicyReason.SUSPENDED);
        policyWithFailure.setFailureKind(DecisionFailureKind.OUTBOUND_TIMEOUT);
        assertThatThrownBy(() -> writer.materialize(policyWithFailure))
                .as("按政策未产出是机制的结论、不是异常 ⇒ 带失败类别即非法态")
                .isInstanceOf(IllegalArgumentException.class);

        // 失败列的失败类别为 null 亦非法（「失败」必须有类别可判）
        assertThatThrownBy(() -> writer.materialize(DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .idempotencyKey(DecisionFixtures.IDEMPOTENCY_KEY)
                .subjectType(DecisionSubjectType.SYSTEM)
                .build()))
                .as("失败列的 failureKind 必填")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("标志推导状态驱动：同一显式事实组恒得同一组标志，路径不参与推导")
    void flagsAreDerivedFromTwoExplicitFacts() {
        // 推导表逐格：出域方向只由「有载荷 + 两个加工事实（含 clamp 丢段）」决定
        assertDerivedOutbound(true, false, false, false, DecisionCompleteness.FULL, false, false);
        assertDerivedOutbound(true, true, false, false, DecisionCompleteness.PARTIAL, true, false);
        assertDerivedOutbound(true, false, true, false, DecisionCompleteness.PARTIAL, false, true);
        assertDerivedOutbound(true, false, false, true, DecisionCompleteness.PARTIAL, false, true);
        assertDerivedOutbound(false, true, true, true, DecisionCompleteness.NO_PAYLOAD, false, false);

        // 入站方向同构
        assertDerivedInbound(true, false, false, DecisionCompleteness.FULL, false, false);
        assertDerivedInbound(true, true, false, DecisionCompleteness.PARTIAL, true, false);
        assertDerivedInbound(false, false, false, DecisionCompleteness.NO_PAYLOAD, false, false);

        // 路径不参与推导：同样的出域事实 + 同样的加工记录，A 列与 D 列得到同一组标志
        final DecisionEvidenceVO viaOutbound = writer.materialize(DecisionFixtures.outboundDraft());
        final DecisionEvidenceVO failure = writer.materialize(
                DecisionFixtures.failureDraft(DecisionFailureKind.OUTBOUND_TIMEOUT));
        assertThat(outboundTriple(failure))
                .as("出域标志是「载荷事实 + 加工事实」的函数：失败列与产出列同事实 ⇒ 同标志")
                .isEqualTo(outboundTriple(viaOutbound));
        assertThat(failure.getOutcome())
                .as("防空转：两条样本的结局确实不同，否则上一条断言是真空成立")
                .isNotEqualTo(viaOutbound.getOutcome());

        // 路径不参与推导：载荷事实相同、路径不同（按政策未产出 vs 失败）⇒ 同为「无载荷」
        final DecisionEvidenceVO policy = writer.materialize(
                DecisionFixtures.policyDraft(DecisionPolicyReason.SUSPENDED));
        final DecisionEvidenceVO otherFailure = writer.materialize(
                DecisionFixtures.failureDraft(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID));
        assertThat(outboundTriple(policy))
                .as("两条路径的载荷事实相同 ⇒ 出域标志同组（不枚举九种路径）")
                .isEqualTo(outboundTriple(otherFailure));
        assertThat(policy.getPolicyReason())
                .as("防空转：两条样本的路径原因分属两个枚举域")
                .isEqualTo(DecisionPolicyReason.SUSPENDED);

        // 装配器丢的来源不计标志（对偶勿混）：它根本不在写入器的输入面上 —— 声明面最小化只记可观测计数
        assertThat(DecisionFixtures.fieldNames(DecisionEvidenceDraft.class))
                .as("装配器丢弃的来源不得进入写入面（它不计任何标志，只作可观测计数）")
                .doesNotContain("droppedContextSources");
    }

    @Test
    @DisplayName("出域方向取不到 RESTRICTED：该值的构造点只在入站，且不在直提列")
    void outboundNeverTakesRestricted() {
        final List<DecisionEvidenceVO> corpus = corpus();
        final Set<DecisionCompleteness> observedOutbound = new LinkedHashSet<>();
        final Set<DecisionCompleteness> observedInbound = new LinkedHashSet<>();
        corpus.forEach(evidence -> {
            observedOutbound.add(evidence.getOutboundCompleteness());
            observedInbound.add(evidence.getInboundCompleteness());
            assertThat(evidence.getOutboundCompleteness())
                    .as("inputSnapshot 是「离开本域的到底是什么」的唯一证据 ⇒ 出域方向不得取 RESTRICTED")
                    .isNotEqualTo(DecisionCompleteness.RESTRICTED);
            assertThat(OUTBOUND_DOMAIN)
                    .as("出域方向的完整度取值域恰为三值（按方向的取值域必须显式写下）")
                    .contains(evidence.getOutboundCompleteness());
        });
        assertThat(observedOutbound)
                .as("防空转：样本集必须真的取遍出域三值，否则上一条断言接近真空")
                .containsExactlyInAnyOrderElementsOf(OUTBOUND_DOMAIN);
        assertThat(observedInbound)
                .as("RESTRICTED 的构造点在入站 ⇒ 入站四值全可达")
                .containsExactlyInAnyOrderElementsOf(INBOUND_DOMAIN);

        // 直提列取不到 RESTRICTED —— 直提的入站加工不经策略（策略 key 属节点声明、推面与声明解耦）
        final DecisionEvidenceDraft directRejected = DecisionFixtures.directDraft(null, false);
        directRejected.setInboundRestricted(true);
        assertThatThrownBy(() -> writer.materialize(directRejected))
                .as("直提列声明「政策性不可落盘」⇒ 非法态（该列结构上不可能发生策略拒绝）")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("入站两降级先分类型：策略拒绝不计错误、加工失败计错误，标志层即可区分")
    void inboundExceptionIsAnErrorButPolicyRejectionIsNot() {
        final DecisionEvidenceDraft rejection = DecisionFixtures.outboundDraft();
        rejection.setInboundPayloadPresent(false);
        rejection.setRawOutput(null);
        rejection.setInboundRestricted(true);
        final DecisionEvidenceVO rejected = writer.materialize(rejection);

        assertThat(rejected.getInboundCompleteness())
                .as("政策性不可落盘 ⇒ RESTRICTED（落原因、不落内容体）")
                .isEqualTo(DecisionCompleteness.RESTRICTED);
        assertThat(rejected.getRawOutput())
                .as("政策性不可落盘 ⇒ rawOutput 置空")
                .isNull();
        assertThat(rejected.getFailureKind())
                .as("策略拒绝是合规结论 ⇒ 不计错误指标（计错判据 = failureKind != null）")
                .isNull();
        assertThat(rejected.getOutcome())
                .as("策略拒绝不改变顶层结局（产出态照旧）")
                .isEqualTo(DecisionOutcome.SUGGESTION_PRODUCED);

        final DecisionEvidenceVO failed = writer.materialize(
                inboundFailureDraft(DecisionFailureKind.INBOUND_PROCESSING_FAILED));
        assertThat(failed.getInboundCompleteness())
                .as("策略执行异常（或入站 clamp 超限）⇒ NO_PAYLOAD 这一可观察值")
                .isEqualTo(DecisionCompleteness.NO_PAYLOAD);
        assertThat(failed.getRawOutput())
                .as("最小化占位：不落任何内容体")
                .isNull();
        assertThat(failed.getFailureKind())
                .as("入站加工失败 ⇒ 计错误指标")
                .isEqualTo(DecisionFailureKind.INBOUND_PROCESSING_FAILED);
        assertThat(failed.getInboundCompleteness())
                .as("两者在标志层即可区分（不再混用）：RESTRICTED vs NO_PAYLOAD + 失败类别")
                .isNotEqualTo(rejected.getInboundCompleteness());

        // 两类降级不得同时声明（先分类型 = 声明位上互斥）
        final DecisionEvidenceDraft both = DecisionFixtures.outboundDraft();
        both.setInboundPayloadPresent(false);
        both.setRawOutput(null);
        both.setInboundRestricted(true);
        both.setFailureKind(DecisionFailureKind.INBOUND_PROCESSING_FAILED);
        assertThatThrownBy(() -> writer.materialize(both))
                .as("同时声明政策性不可落盘与入站加工失败 ⇒ 非法态（该压成一件事就再也分不出来）")
                .isInstanceOf(IllegalArgumentException.class);

        // 压合登记：策略异常与入站 clamp 超限在写入面同值（两者共用一个声明位，不得据此判因）
        final DecisionEvidenceVO clampOverLimit = writer.materialize(
                inboundFailureDraft(DecisionFailureKind.INBOUND_PROCESSING_FAILED));
        assertThat(inboundObservable(clampOverLimit))
                .as("策略异常与入站 clamp 超限压成同一可观察值（如实登记，不新增字段 / 维度）")
                .isEqualTo(inboundObservable(failed));
    }

    @Test
    @DisplayName("两类未产出都物质化：按政策未产出与失败各成行且互不混用")
    void writerMaterializesBothNonProducedOutcomes() {
        final List<DecisionEvidenceVO> policyRows = EnumSet.allOf(DecisionPolicyReason.class).stream()
                .map(DecisionFixtures::policyDraft)
                .map(writer::materialize)
                .collect(Collectors.toList());
        final List<DecisionEvidenceVO> failureRows = EnumSet.allOf(DecisionFailureKind.class).stream()
                .filter(kind -> kind != DecisionFailureKind.INBOUND_PROCESSING_FAILED)
                .map(DecisionFixtures::failureDraft)
                .map(writer::materialize)
                .collect(Collectors.toList());

        assertThat(policyRows).as("按政策未产出的五值逐值可物质化").hasSize(5);
        assertThat(failureRows).as("失败列的六值逐值可物质化（第七值属产出态例外）").hasSize(6);
        assertThat(policyRows).allSatisfy(row -> {
            assertThat(row.getOutcome())
                    .as("outcome 分支：两类未产出各自成行、不合并")
                    .isEqualTo(DecisionOutcome.NO_SUGGESTION_BY_POLICY);
            assertThat(row.getPolicyReason())
                    .as("outcome 分支：按政策未产出列的 policyReason 必填")
                    .isNotNull();
            assertThat(row.getFailureKind())
                    .as("计错判据：按政策未产出绝不带失败类别")
                    .isNull();
        });
        assertThat(failureRows).allSatisfy(row -> {
            assertThat(row.getOutcome())
                    .as("outcome 分支：两类未产出各自成行、不合并")
                    .isEqualTo(DecisionOutcome.SUGGESTION_FAILED);
            assertThat(row.getFailureKind())
                    .as("计错判据：失败列的 failureKind 必填")
                    .isNotNull();
            assertThat(row.getPolicyReason())
                    .as("outcome 分支：policyReason 只属按政策未产出列")
                    .isNull();
        });

        // 物质化不是「随便落一个结局」：缺原因 / 缺类别写不出对应列
        final DecisionEvidenceDraft noReason = DecisionFixtures.directDraft(null, true);
        noReason.setOutcome(DecisionOutcome.NO_SUGGESTION_BY_POLICY);
        noReason.setSuggestedAction(null);
        noReason.setActionSummary(null);
        assertThatThrownBy(() -> writer.materialize(noReason))
                .as("按政策未产出列缺 policyReason ⇒ 非法态（每个未产出都有可区分结局）")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("写入侧超限拒绝写入：归失败列 INTERNAL_ERROR，零新增枚举值 / 槽位")
    void writeGuardRefusesOversizedRowWithoutNewEnumValue() throws IOException {
        final DecisionEvidenceDraft oversized = DecisionFixtures.directDraft(null, true);
        oversized.setRationaleNarrative(StringUtils.repeat('x', OVERSIZED_TEXT_LENGTH));
        final DecisionEvidenceVO candidate = writer.materialize(oversized);

        final String legalCandidate = writer.row(writer.materialize(DecisionFixtures.outboundDraft()));
        assertThat(DecisionFixtures.utf8Length(legalCandidate))
                .as("防空转：合法行本身不超上限（否则「超限才拒写」无从分辨）")
                .isLessThanOrEqualTo(DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES);

        final String refused = writer.row(candidate);
        final JsonNode payload = DecisionFixtures.evidenceJson(refused);

        assertThat(DecisionFixtures.enumValue(payload.get("outcome"), DecisionOutcome.class))
                .as("超限 ⇒ 拒绝写入该行，归 SUGGESTION_FAILED")
                .isEqualTo(DecisionOutcome.SUGGESTION_FAILED);
        assertThat(DecisionFixtures.enumValue(payload.get("failureKind"), DecisionFailureKind.class))
                .as("超限 ⇒ failureKind = INTERNAL_ERROR（与出域 clamp 兜底拒绝同型）")
                .isEqualTo(DecisionFailureKind.INTERNAL_ERROR);
        assertThat(DecisionFixtures.utf8Length(refused))
                .as("替代行自身必须在写入侧上界之内（否则仍写不进去）")
                .isLessThanOrEqualTo(DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES);
        assertThat(payload.get("rationaleNarrative").isNull())
                .as("被拒的原行内容不落任何内容体")
                .isTrue();
        assertThat(payload.get("suggestedAction").isNull())
                .as("被拒的原行内容不落任何内容体")
                .isTrue();
        assertThat(payload.get("idempotencyKey").asText())
                .as("替代行保留幂等身份（四列全必填，可判别性不因拒写而丢）")
                .isEqualTo(DecisionFixtures.IDEMPOTENCY_KEY);

        // 零新增枚举值 / 槽位：闭集规模一律未动
        assertThat(EnumSet.allOf(DecisionFailureKind.class))
                .as("超限处置零新增枚举值")
                .hasSize(7);
        assertThat(EnumSet.allOf(DecisionPolicyReason.class))
                .as("超限处置零新增枚举值")
                .hasSize(5);
        assertThat(EnumSet.allOf(DecisionOutcome.class))
                .as("超限处置零新增枚举值（未产出仍是上位词、不入枚举）")
                .hasSize(3);
        assertThat(EnumSet.allOf(WriteDegradedCause.class))
                .as("超限处置零新增写入降级槽位（它不成行、不是降级）")
                .hasSize(2);
        assertThat(EnumSet.allOf(DecisionCompleteness.class))
                .as("超限处置零新增完整度取值")
                .hasSize(4);
    }

    @Test
    @DisplayName("主体走 JSON：ACT_HI_COMMENT.USER_ID_ 不承决策证据的真实产出主体")
    void subjectIsCarriedInJsonNotUserId() throws IOException {
        final String row = writer.row(writer.materialize(DecisionFixtures.outboundDraft()));
        final JsonNode payload = DecisionFixtures.evidenceJson(row);

        assertThat(DecisionFixtures.enumValue(payload.get("subjectType"), DecisionSubjectType.class))
                .as("主体唯一落点 = JSON 的 subjectType")
                .isEqualTo(DecisionSubjectType.AI);
        assertThat(payload.get("subjectId").asText())
                .as("主体描述性字段走 JSON")
                .isEqualTo(DecisionFixtures.SUBJECT_ID);
        assertThat(payload.get("subjectName").asText())
                .as("主体显示名走 JSON")
                .isEqualTo(DecisionFixtures.SUBJECT_NAME);

        final List<String> keys = new ArrayList<>();
        payload.fieldNames().forEachRemaining(keys::add);
        assertThat(keys)
                .as("证据面禁载线程认证身份：不产生、不读取任何 userId 类键")
                .doesNotContain("userId", "USER_ID_", "actorId", "author", "authenticatedUserId");
        assertThat(DecisionFixtures.fieldNames(DecisionEvidenceVO.class))
                .as("证据 VO 不得长出一个与 USER_ID_ 同义的字段（主体只走 subjectType / subjectId / subjectName）")
                .doesNotContain("userId", "actorId", "author");
    }

    // ======================== 推导表辅助 ========================

    private void assertDerivedOutbound(final boolean payloadPresent, final boolean redacted,
                                       final boolean truncated, final boolean clampDropped,
                                       final DecisionCompleteness expected, final boolean expectedRedacted,
                                       final boolean expectedTruncated) {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft
                .of(DecisionFixtures.directSubmission(null));
        draft.setProvider(DecisionFixtures.PROVIDER);
        draft.setChainStage(DecisionChainStage.PRIMARY);
        draft.setDegraded(Boolean.FALSE);
        draft.setModelId(DecisionFixtures.MODEL_ID);
        draft.setOutboundPayloadPresent(payloadPresent);
        draft.setInputSnapshot(payloadPresent ? DecisionFixtures.INPUT_SNAPSHOT : null);
        draft.setOutboundRedacted(redacted);
        draft.setOutboundTruncated(truncated);
        draft.setOutboundClampDropped(clampDropped);

        final DecisionEvidenceVO evidence = writer.materialize(draft);
        assertThat(evidence.getOutboundCompleteness())
                .as("出域方向：载荷=%s 脱敏=%s 截断=%s clamp丢段=%s ⇒ %s",
                        payloadPresent, redacted, truncated, clampDropped, expected)
                .isEqualTo(expected);
        assertThat(evidence.isOutboundRedacted()).isEqualTo(expectedRedacted);
        assertThat(evidence.isOutboundTruncated())
                .as("clamp 丢段计 truncated（对偶：装配器丢不计任何标志）")
                .isEqualTo(expectedTruncated);
    }

    private void assertDerivedInbound(final boolean payloadPresent, final boolean redacted,
                                      final boolean truncated, final DecisionCompleteness expected,
                                      final boolean expectedRedacted, final boolean expectedTruncated) {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft
                .of(DecisionFixtures.directSubmission(null));
        draft.setInboundPayloadPresent(payloadPresent);
        draft.setRawOutput(payloadPresent ? DecisionFixtures.RAW_OUTPUT : null);
        draft.setInboundRedacted(redacted);
        draft.setInboundTruncated(truncated);

        final DecisionEvidenceVO evidence = writer.materialize(draft);
        assertThat(evidence.getInboundCompleteness())
                .as("入站方向：载荷=%s 脱敏=%s 截断=%s ⇒ %s", payloadPresent, redacted, truncated, expected)
                .isEqualTo(expected);
        assertThat(evidence.isInboundRedacted()).isEqualTo(expectedRedacted);
        assertThat(evidence.isInboundTruncated()).isEqualTo(expectedTruncated);
    }

    // ======================== 样本集 ========================

    /** 覆盖四列 + 三态 + 两降级的样本集（每组事实都真的被取到）。 */
    private List<DecisionEvidenceVO> corpus() {
        final List<DecisionEvidenceVO> corpus = new ArrayList<>();
        corpus.add(writer.materialize(DecisionFixtures.outboundDraft()));

        final DecisionEvidenceDraft processed = DecisionFixtures.outboundDraft();
        processed.setOutboundRedacted(true);
        processed.setInboundTruncated(true);
        corpus.add(writer.materialize(processed));

        final DecisionEvidenceDraft clampDropped = DecisionFixtures.outboundDraft();
        clampDropped.setOutboundClampDropped(true);
        corpus.add(writer.materialize(clampDropped));

        corpus.add(writer.materialize(DecisionFixtures.directDraft(null, true)));
        corpus.add(writer.materialize(DecisionFixtures.directDraft(new ArrayList<>(), false)));
        corpus.add(writer.materialize(DecisionFixtures.directDraft(DecisionFixtures.allContextSources(), true)));

        corpus.addAll(EnumSet.allOf(DecisionPolicyReason.class).stream()
                .map(DecisionFixtures::policyDraft)
                .map(writer::materialize)
                .collect(Collectors.toList()));
        corpus.addAll(EnumSet.allOf(DecisionFailureKind.class).stream()
                .filter(kind -> kind != DecisionFailureKind.INBOUND_PROCESSING_FAILED)
                .map(DecisionFixtures::failureDraft)
                .map(writer::materialize)
                .collect(Collectors.toList()));

        corpus.add(writer.materialize(inboundFailureDraft(DecisionFailureKind.INBOUND_PROCESSING_FAILED)));

        final DecisionEvidenceDraft restricted = DecisionFixtures.outboundDraft();
        restricted.setInboundPayloadPresent(false);
        restricted.setRawOutput(null);
        restricted.setInboundRestricted(true);
        corpus.add(writer.materialize(restricted));

        return corpus;
    }

    /** 入站加工失败的产出态样本（{@code failureKind} 的唯一产出态例外值；直提列亦可达）。 */
    private static DecisionEvidenceDraft inboundFailureDraft(final DecisionFailureKind kind) {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft
                .of(DecisionFixtures.directSubmission(null));
        draft.setFailureKind(kind);
        draft.setInboundPayloadPresent(false);
        draft.setRawOutput(null);
        return draft;
    }

    // ======================== 辅助 ========================

    private static List<Object> outboundTriple(final DecisionEvidenceVO evidence) {
        return Arrays.asList(evidence.getOutboundCompleteness(), evidence.isOutboundRedacted(),
                evidence.isOutboundTruncated());
    }

    /** 入站方向的可观察值（不比较整条 VO：依据事实类型无 {@code equals}，那会让断言退化成引用比较）。 */
    private static List<Object> inboundObservable(final DecisionEvidenceVO evidence) {
        return Arrays.asList(evidence.getOutcome(), evidence.getFailureKind(),
                evidence.getInboundCompleteness(), evidence.isInboundRedacted(),
                evidence.isInboundTruncated(), evidence.getRawOutput());
    }
}
