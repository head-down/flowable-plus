package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E10 —— 建议提交的准入契约（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E10}）。
 *
 * <p><b>承哪些推入项</b>：{@code #33} 的准入规则<b>逐条 11 条</b>（同步、按序、首个失败即抛；
 * 正例通过 / 反例抛 {@link SuggestionAdmissionException} 且 {@code reason} 取对应值）+ 原因枚举
 * <b>恰好 13 值</b> + <b>每取值有可达构造点</b>（键集相等）+ {@code null} 与去空白空串<b>同判</b>
 * （{@code idempotencyKey} / {@code actionSummary} 两处）+ 缺身份 = <b>唯一不物质化</b>的准入失败。</p>
 *
 * <p><b>「不物质化」的落行判据是状态驱动</b>（ADR-0042 第 8 节第 4 条 2026-09-26 订正）：
 * 带齐<b>三项前置</b>（{@code taskId} ∧ {@code idempotencyKey} ∧ {@code subjectType} 皆非空）者
 * ── 用写入器物质化 D 列行<b>之后</b>再抛；缺任一者只退日志 + 指标、不落行。故
 * {@code #missingIdentityIsTheOnlyUnmaterializedRejection()} 这一<b>逐字断言名</b>所覆盖的判据，
 * 按订正读作「不物质化 ⇔ 缺三项前置之一」（缺幂等身份是其原判、也是本断言名所指的那一项）。</p>
 *
 * <p><b>引擎侧只有两个读取面</b>：可用动作判定所需的任务读取（规则 ⑥）与写入期降级的原因判别。
 * 二者都以 mock 引擎对象隔离，使本类断言的是<b>准入契约</b>本身，而非引擎行为。</p>
 */
class SuggestionAdmissionContractTest {

    /** 准入原因闭集规模（数值唯一住所 = {@code docs/impl/0042-implementation-plan.md} §3.5） */
    private static final int ADMISSION_REASON_COUNT = 13;

    private TaskService taskService;
    private RuntimeService runtimeService;
    private MultiInstanceDetector multiInstanceDetector;
    private Task anchor;
    private List<DecisionObservation> observations;
    private DefaultSuggestionSubmissionService service;

    @BeforeEach
    void setUp() {
        anchor = mock(Task.class);
        when(anchor.getId()).thenReturn(DecisionFixtures.TASK_ID);
        when(anchor.getProcessInstanceId()).thenReturn(DecisionFixtures.PROCESS_INSTANCE_ID);
        when(anchor.getTaskDefinitionKey()).thenReturn(DecisionFixtures.NODE_ID);

        final TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.taskId(anyString())).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(anchor);
        taskService = mock(TaskService.class);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);

        final ProcessInstanceQuery processInstanceQuery = mock(ProcessInstanceQuery.class);
        when(processInstanceQuery.processInstanceId(anyString())).thenReturn(processInstanceQuery);
        when(processInstanceQuery.count()).thenReturn(1L);
        runtimeService = mock(RuntimeService.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);

        multiInstanceDetector = mock(MultiInstanceDetector.class);
        observations = new ArrayList<>();
        service = newService(true, taskService, runtimeService, multiInstanceDetector, observations);
    }

    // ======================== 四条具名断言 ========================

    @Test
    @DisplayName("反例逐条携带自己的原因：十三个反例 → 与书面原因逐一相等，且失败行归 SITE_ADMISSION_REJECTED")
    void rejectingCaseCarriesItsOwnReason() throws IOException {
        final Set<SuggestionAdmissionReason> observed = new LinkedHashSet<>();
        for (final RejectionCase rejectionCase : rejectionCases()) {
            resetInvocations();
            final SuggestionAdmissionException rejected = assertRejected(rejectionCase);
            observed.add(rejected.getReason());
            assertThat(rejected.getReason())
                    .as("准入规则（%s）：反例必须携带书面原因", rejectionCase.rule)
                    .isEqualTo(rejectionCase.reason);
            assertMaterialization(rejectionCase);
        }
        assertThat(observed)
                .as("防空转：反例集必须真的走遍多个原因")
                .hasSizeGreaterThan(4);
    }

    @Test
    @DisplayName("每取值有可达构造点：反例集观测到的原因键集恰等于 13 值闭集")
    void eachRuleHasAReachableConstructionPoint() {
        final Set<SuggestionAdmissionReason> observed = EnumSet.noneOf(SuggestionAdmissionReason.class);
        for (final RejectionCase rejectionCase : rejectionCases()) {
            resetInvocations();
            observed.add(assertRejected(rejectionCase).getReason());
        }
        assertThat(EnumSet.allOf(SuggestionAdmissionReason.class))
                .as("准入原因闭集恰 13 值（数值唯一住所 = 实施方案 §3.5）")
                .hasSize(ADMISSION_REASON_COUNT);
        assertThat(observed)
                .as("每取值须有可达构造点：观测到的键集必须恰等于 13 值集")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(SuggestionAdmissionReason.class));
    }

    @Test
    @DisplayName("null 与去空白空串同判：idempotencyKey / actionSummary 两处")
    void nullAndBlankAreJudgedIdentical() {
        final SuggestionSubmission nullKey = directBase().idempotencyKey(null).build();
        final SuggestionSubmission blankKey = directBase().idempotencyKey("   ").build();
        assertThat(assertRejected(nullKey).getReason())
                .as("幂等身份：null 与去空白空串同判")
                .isEqualTo(SuggestionAdmissionReason.IDEMPOTENCY_KEY_REQUIRED)
                .isEqualTo(assertRejected(blankKey).getReason());

        final SuggestionSubmission nullSummary = directBase().actionSummary(null).build();
        final SuggestionSubmission blankSummary = directBase().actionSummary(" \t ").build();
        assertThat(assertRejected(nullSummary).getReason())
                .as("建议摘要：null 与去空白空串同判")
                .isEqualTo(SuggestionAdmissionReason.ACTION_SUMMARY_REQUIRED)
                .isEqualTo(assertRejected(blankSummary).getReason());

        // 同判须贯穿到落行判据：身份两形态同归不物质化、摘要两形态同归物质化，无第三态
        assertThat(materialized(nullKey)).as("缺身份 ⇒ 不物质化").isFalse();
        assertThat(materialized(blankKey)).as("去空白空串身份与 null 同判 ⇒ 同样不物质化").isFalse();
        assertThat(materialized(nullSummary)).as("摘要缺失是「三项前置已带齐」的准入失败 ⇒ 物质化").isTrue();
        assertThat(materialized(blankSummary)).as("去空白空串摘要与 null 同判 ⇒ 同样物质化").isTrue();
    }

    @Test
    @DisplayName("缺身份 = 唯一不物质化的准入失败：不物质化集恰为三项前置原因")
    void missingIdentityIsTheOnlyUnmaterializedRejection() {
        final Set<SuggestionAdmissionReason> unmaterialized = EnumSet.noneOf(SuggestionAdmissionReason.class);
        final Set<SuggestionAdmissionReason> materialized = EnumSet.noneOf(SuggestionAdmissionReason.class);
        for (final RejectionCase rejectionCase : rejectionCases()) {
            resetInvocations();
            assertRejected(rejectionCase);
            (materializedNow() ? materialized : unmaterialized).add(rejectionCase.reason);
        }

        assertThat(unmaterialized)
                .as("不物质化集恰为三项前置原因（taskId / idempotencyKey / subjectType 缺一即不落行）；"
                        + "其中「缺幂等身份」是原判、理由不变（无身份即无从判别，落记录会把同一第三方的"
                        + "反复重试变成无法归并的审计噪声）")
                .containsExactlyInAnyOrder(SuggestionAdmissionReason.TASK_ID_REQUIRED,
                        SuggestionAdmissionReason.IDEMPOTENCY_KEY_REQUIRED,
                        SuggestionAdmissionReason.SUBJECT_TYPE_REQUIRED);
        assertThat(materialized)
                .as("其余十值一律物质化（判据是状态驱动，不由原因枚举反推）、且与不物质化集互斥穷举")
                .hasSize(ADMISSION_REASON_COUNT - unmaterialized.size())
                .doesNotContainAnyElementsOf(unmaterialized)
                .containsExactlyInAnyOrderElementsOf(
                        EnumSet.complementOf(EnumSet.copyOf(unmaterialized)));
    }

    // ======================== 正例逐条通过 ========================

    @Test
    @DisplayName("11 条规则的正例逐条通过：直提 / 拉面两类提交各落一条产出列证据行")
    void positiveCasesPassPerRule() {
        final List<SuggestionSubmission> positives = new ArrayList<>();
        positives.add(directBase().build());                                          // ①②③④⑤⑥⑦⑧⑨⑩⑪
        positives.add(directBase().attestedDataSources(new ArrayList<>()).build());    // ⑪ 显式空集
        positives.add(directBase().attestedDataSources(DecisionFixtures.allContextSources()).build());
        positives.add(outboundBase().build());                                         // ⑨ 不适用（非直提）
        positives.add(outboundBase().rationaleFacts(new ArrayList<>()).build());        // ⑩ 空集合合法
        positives.add(directBase().rawOutput(null).build());                           // 入站无载荷
        positives.add(outboundBase().actionSummary("拉面建议").degraded(Boolean.TRUE).build());

        for (final SuggestionSubmission positive : positives) {
            resetInvocations();
            service.submit(positive);
            assertThat(observations)
                    .as("成功路径的观测产生点在管线（latency / tokens 只有管线可得），位点服务只落证据行：%s",
                            positive.getSuggestedAction())
                    .isEmpty();
            verify(taskService, times(1))
                    .addComment(anyString(), anyString(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("直提落 B 列、拉面落 A 列：出处组双射在成功路径上双向成立")
    void producedRowKeepsTheProvenanceBijection() throws IOException {
        resetInvocations();
        service.submit(directBase().build());
        final JsonNode direct = DecisionFixtures.evidenceJson(capturedRow());
        assertThat(DecisionFixtures.enumValue(direct.get("outcome"), DecisionOutcome.class))
                .as("直提落产出态")
                .isEqualTo(DecisionOutcome.SUGGESTION_PRODUCED);
        assertThat(direct.get("provider").isNull() && direct.get("chainStage").isNull()
                && direct.get("degraded").isNull())
                .as("出处组全 null ⇔ 直提（直提列不适用出域位点）")
                .isTrue();

        resetInvocations();
        service.submit(outboundBase().build());
        final JsonNode outbound = DecisionFixtures.evidenceJson(capturedRow());
        assertThat(outbound.get("provider").asText())
                .as("出处组非 null ⇔ 经出站调用")
                .isEqualTo(DecisionFixtures.PROVIDER);
        assertThat(DecisionFixtures.enumValue(outbound.get("chainStage"), DecisionChainStage.class))
                .as("链路阶段如实搬运")
                .isEqualTo(DecisionChainStage.PRIMARY);
    }

    @Test
    @DisplayName("入站载荷超限 ⇒ 产出态 + INBOUND_PROCESSING_FAILED（该枚举唯一的产出态例外值）")
    void oversizedInboundPayloadIsDeclaredAsInboundProcessingFailed() throws IOException {
        resetInvocations();
        service.submit(directBase()
                .rawOutput(StringUtils.repeat('x', DecisionClamp.MAX_PAYLOAD_BYTES + 1)).build());

        final JsonNode payload = DecisionFixtures.evidenceJson(capturedRow());
        assertThat(DecisionFixtures.enumValue(payload.get("outcome"), DecisionOutcome.class))
                .as("入站加工失败不改变顶层结局（产出态照旧）")
                .isEqualTo(DecisionOutcome.SUGGESTION_PRODUCED);
        assertThat(DecisionFixtures.enumValue(payload.get("failureKind"), DecisionFailureKind.class))
                .as("超限（clamp 返回 null）⇒ INBOUND_PROCESSING_FAILED")
                .isEqualTo(DecisionFailureKind.INBOUND_PROCESSING_FAILED);
        assertThat(payload.get("rawOutput").isNull())
                .as("超限 ⇒ rawOutput 恒空（不落内容体）")
                .isTrue();
    }

    // ======================== 分支补齐：关闭态 / 两个降级槽位 / 最后防御 ========================

    @Test
    @DisplayName("全局关时提交为 no-op 非失败：不落记录、不抛准入异常、不触引擎")
    void submitUnderGlobalOffIsInert() {
        final TaskService untouchedTasks = mock(TaskService.class);
        final RuntimeService untouchedRuntime = mock(RuntimeService.class);
        final MultiInstanceDetector untouchedDetector = mock(MultiInstanceDetector.class);
        final List<DecisionObservation> untouchedObservations = new ArrayList<>();
        final DefaultSuggestionSubmissionService disabled = newService(false, untouchedTasks,
                untouchedRuntime, untouchedDetector, untouchedObservations);

        disabled.submit(directBase().build());
        disabled.submit(directBase().taskId(null).build());
        disabled.submit(directBase().suggestedAction(ApprovalAction.RETURN).build());

        verifyNoInteractions(untouchedTasks);
        verifyNoInteractions(untouchedRuntime);
        verifyNoInteractions(untouchedDetector);
        assertThat(untouchedObservations)
                .as("关态不触发、不留记录（也非失败）")
                .isEmpty();
    }

    @Test
    @DisplayName("锚点不存在 ⇒ 走「锚点失效」槽位：不成行、不抛、不产生 SUGGESTION_FAILED")
    void unknownAnchorDegradesWithoutRowOrFailure() {
        when(taskService.createTaskQuery().taskId(anyString()).singleResult()).thenReturn(null);
        resetInvocations();

        service.submit(directBase().build());

        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
        assertThat(singleObservation().getWriteDegradedCause())
                .as("任务不存在 ⇒ 写入期降级槽位（锚点失效）")
                .isEqualTo(WriteDegradedCause.ANCHOR_LOST);
        assertThat(singleObservation().getOutcome())
                .as("结构性写入失败不是结局（不新增第四叶子态）")
                .isNull();
    }

    @Test
    @DisplayName("写入失败 ⇒ 两个写入失败槽位分别可判：实例已终结 vs 锚点已消失")
    void writeFailureTakesOneOfTheTwoDegradedCauses() {
        doThrow(new RuntimeException("引擎拒绝写入")).when(taskService)
                .addComment(anyString(), anyString(), anyString(), anyString());

        resetInvocations();
        service.submit(directBase().build());
        assertThat(singleObservation().getWriteDegradedCause())
                .as("实例仍在而锚点写不进去 ⇒ 锚点失效")
                .isEqualTo(WriteDegradedCause.ANCHOR_LOST);

        when(runtimeService.createProcessInstanceQuery().processInstanceId(anyString()).count())
                .thenReturn(0L);
        resetInvocations();
        service.submit(directBase().build());
        assertThat(singleObservation().getWriteDegradedCause())
                .as("运行期已无该实例 ⇒ 实例已结束")
                .isEqualTo(WriteDegradedCause.INSTANCE_ENDED);
    }

    @Test
    @DisplayName("最后防御：主闸未拦住而写入器拒绝 ⇒ 归 INTERNAL_ERROR 落行，并上抛不静默")
    void writerDefenseYieldsInternalErrorAndRethrows() throws IOException {
        // 两处「主闸放行、写入器拒绝」的形态：拉面带自述位（自述位只属直提列）、拉面缺依据面
        // （core 矩阵 A 列要求依据非空，而准入规则集不含此项 —— 故它们只能由防御面承接）
        final List<SuggestionSubmission> defended = new ArrayList<>();
        defended.add(outboundBase().attestedDataSources(DecisionFixtures.allContextSources()).build());
        defended.add(outboundBase().rationaleFacts(null).build());

        for (final SuggestionSubmission submission : defended) {
            resetInvocations();
            try {
                service.submit(submission);
                throw new AssertionError("最后防御应当上抛（不静默）");
            } catch (IllegalArgumentException expected) {
                assertThat(expected)
                        .as("上抛的是写入器的构造期守卫异常，不是准入异常")
                        .isNotInstanceOf(SuggestionAdmissionException.class);
            }

            final JsonNode payload = DecisionFixtures.evidenceJson(capturedRow());
            assertThat(DecisionFixtures.enumValue(payload.get("outcome"), DecisionOutcome.class))
                    .as("最后防御归失败列")
                    .isEqualTo(DecisionOutcome.SUGGESTION_FAILED);
            assertThat(DecisionFixtures.enumValue(payload.get("failureKind"), DecisionFailureKind.class))
                    .as("最后防御归 INTERNAL_ERROR（零新增枚举值）")
                    .isEqualTo(DecisionFailureKind.INTERNAL_ERROR);
            assertThat(singleObservation().getFailureKind())
                    .as("同一次事实在观测面上同值（按 INTERNAL_ERROR 进告警并计入错误指标）")
                    .isEqualTo(DecisionFailureKind.INTERNAL_ERROR);
        }
    }

    @Test
    @DisplayName("准入失败的行落不下 ⇒ 走写入期降级槽位：不成行、报出的不是 SUGGESTION_FAILED")
    void rejectionWhoseRowCannotLandDegradesInsteadOfFailing() {
        // ① 锚点读取即落空（规则 ③ 失败 ∧ 任务不存在）：不成行、不产生失败结局
        when(taskService.createTaskQuery().taskId(anyString()).singleResult()).thenReturn(null);
        resetInvocations();
        assertThat(assertRejected(directBase().suggestedAction(null).build()).getReason())
                .as("准入失败本身照抛（拒绝与「写得下去写不下去」是两件事）")
                .isEqualTo(SuggestionAdmissionReason.ACTION_REQUIRED);
        assertThat(capturedRows()).as("锚点已失效 ⇒ 不成行").isEmpty();
        assertThat(singleObservation().getWriteDegradedCause())
                .as("前置带齐但锚点已失效 ⇒ 降级槽位")
                .isEqualTo(WriteDegradedCause.ANCHOR_LOST);
        assertThat(singleObservation().getOutcome())
                .as("不成行 ⇒ 不产生 SUGGESTION_FAILED（计错判据不成立）")
                .isNull();
        assertThat(singleObservation().getFailureKind()).isNull();
        assertThat(singleObservation().getAdmissionReason()).isNull();

        // ② 锚点在手、写入失败（同一个准入失败）：同样只降级、不报失败结局
        when(taskService.createTaskQuery().taskId(anyString()).singleResult()).thenReturn(anchor);
        doThrow(new RuntimeException("引擎拒绝写入")).when(taskService)
                .addComment(anyString(), anyString(), anyString(), anyString());
        resetInvocations();
        assertThat(assertRejected(directBase().suggestedAction(null).build()).getReason())
                .isEqualTo(SuggestionAdmissionReason.ACTION_REQUIRED);
        assertThat(singleObservation().getWriteDegradedCause())
                .as("写入期降级（实例仍在 ⇒ 锚点失效）")
                .isEqualTo(WriteDegradedCause.ANCHOR_LOST);
        assertThat(singleObservation().getOutcome())
                .as("行没落下 ⇒ 不报 SUGGESTION_FAILED 结局")
                .isNull();
    }

    // ======================== 反例集 ========================

    /**
     * 十三个反例（规则 ①–⑪ → 闭集十三值）：每条只违反它自己那一条。
     *
     * <p>所有反例都以「{@code AGREE} 可用」为背景（除 ⑥ 显式置「运行时多实例」使 {@code AGREE} 不可用），
     * 故规则 ⑥ 对它们一律放行 —— 任一实现若把某条规则提前 / 后置，会先响出别的原因而打红。</p>
     */
    private static List<RejectionCase> rejectionCases() {
        final List<RejectionCase> cases = new ArrayList<>();
        cases.add(new RejectionCase("① 锚点必填", directBase().taskId(null).build(),
                SuggestionAdmissionReason.TASK_ID_REQUIRED));
        cases.add(new RejectionCase("② 幂等身份必填", directBase().idempotencyKey(" ").build(),
                SuggestionAdmissionReason.IDEMPOTENCY_KEY_REQUIRED));
        cases.add(new RejectionCase("③ 动作必填", directBase().suggestedAction(null).build(),
                SuggestionAdmissionReason.ACTION_REQUIRED));
        cases.add(new RejectionCase("④ 摘要非空", directBase().actionSummary("").build(),
                SuggestionAdmissionReason.ACTION_SUMMARY_REQUIRED));
        cases.add(new RejectionCase("⑤ 动作属表态比较面",
                directBase().suggestedAction(ApprovalAction.RETURN).build(),
                SuggestionAdmissionReason.ACTION_NOT_COMPARABLE));
        cases.add(new RejectionCase("⑥ 动作与可用投票动作匹配", directBase().build(),
                SuggestionAdmissionReason.ACTION_NOT_AVAILABLE_FOR_TASK, true));
        cases.add(new RejectionCase("⑦ 主体类型必填", directBase().subjectType(null).build(),
                SuggestionAdmissionReason.SUBJECT_TYPE_REQUIRED));
        cases.add(new RejectionCase("⑧ 出处组双射", outboundBase().chainStage(null).build(),
                SuggestionAdmissionReason.PROVENANCE_GROUP_INCONSISTENT));
        cases.add(new RejectionCase("⑨a 直提下 modelId 必空",
                directBase().modelId(DecisionFixtures.MODEL_ID).build(),
                SuggestionAdmissionReason.MODEL_ID_NOT_ALLOWED_ON_DIRECT));
        cases.add(new RejectionCase("⑨b 直提下 inputSnapshot 必空",
                directBase().inputSnapshot(DecisionFixtures.INPUT_SNAPSHOT).build(),
                SuggestionAdmissionReason.INPUT_SNAPSHOT_NOT_ALLOWED_ON_DIRECT));
        cases.add(new RejectionCase("⑨c 直提须含 BASIS_CODE",
                directBase().rationaleFacts(DecisionFixtures.facts(
                        DecisionRationaleFactKey.SCORE)).build(),
                SuggestionAdmissionReason.BASIS_CODE_REQUIRED));
        cases.add(new RejectionCase("⑩ 依据元素合法",
                outboundBase().rationaleFacts(Collections.singletonList(null)).build(),
                SuggestionAdmissionReason.RATIONALE_FACT_INVALID));
        cases.add(new RejectionCase("⑪ 自述位三态与元素合法",
                directBase().attestedDataSources(Collections.singletonList(null)).build(),
                SuggestionAdmissionReason.ATTESTED_SOURCES_INVALID));
        return cases;
    }

    /** 反例：规则名 + 提交 + 书面原因 + 检测对象覆盖（⑥ 需要「运行时多实例」才使 {@code AGREE} 不可用）。 */
    private static final class RejectionCase {

        private final String rule;
        private final SuggestionSubmission submission;
        private final SuggestionAdmissionReason reason;
        private final boolean runtimeMultiInstance;

        private RejectionCase(final String rule, final SuggestionSubmission submission,
                             final SuggestionAdmissionReason reason) {
            this(rule, submission, reason, false);
        }

        private RejectionCase(final String rule, final SuggestionSubmission submission,
                             final SuggestionAdmissionReason reason, final boolean runtimeMultiInstance) {
            this.rule = rule;
            this.submission = submission;
            this.reason = reason;
            this.runtimeMultiInstance = runtimeMultiInstance;
        }
    }

    // ======================== 提交样本 ========================

    /** 直提提交（出处组三者全 null；{@code modelId} / {@code inputSnapshot} 必空；依据含 {@code BASIS_CODE}）。 */
    private static SuggestionSubmission.SuggestionSubmissionBuilder directBase() {
        return SuggestionSubmission.builder()
                .taskId(DecisionFixtures.TASK_ID)
                .idempotencyKey(DecisionFixtures.IDEMPOTENCY_KEY)
                .subjectType(DecisionSubjectType.SYSTEM)
                .subjectId(DecisionFixtures.SUBJECT_ID)
                .subjectName(DecisionFixtures.SUBJECT_NAME)
                .suggestedAction(ApprovalAction.AGREE)
                .actionSummary("外部服务建议同意")
                .rationaleFacts(DecisionFixtures.facts(DecisionRationaleFactKey.BASIS_CODE))
                .rationaleNarrative("依据：外部规则命中")
                .rawOutput(DecisionFixtures.RAW_OUTPUT);
    }

    /** 拉面提交（出处组三者非 null + {@code modelId} + {@code inputSnapshot}；动作仍是可用投票动作）。 */
    private static SuggestionSubmission.SuggestionSubmissionBuilder outboundBase() {
        return SuggestionSubmission.builder()
                .taskId(DecisionFixtures.TASK_ID)
                .idempotencyKey(DecisionFixtures.IDEMPOTENCY_KEY)
                .subjectType(DecisionSubjectType.AI)
                .subjectId(DecisionFixtures.SUBJECT_ID)
                .subjectName(DecisionFixtures.SUBJECT_NAME)
                .suggestedAction(ApprovalAction.AGREE)
                .actionSummary("建议同意")
                .rationaleFacts(DecisionFixtures.facts(DecisionRationaleFactKey.SCORE))
                .rationaleNarrative("依据：模型给出分值")
                .rawOutput(DecisionFixtures.RAW_OUTPUT)
                .modelId(DecisionFixtures.MODEL_ID)
                .inputSnapshot(DecisionFixtures.INPUT_SNAPSHOT)
                .provider(DecisionFixtures.PROVIDER)
                .chainStage(DecisionChainStage.PRIMARY)
                .degraded(Boolean.FALSE);
    }

    // ======================== 驱动与断言辅助 ========================

    private static DefaultSuggestionSubmissionService newService(
            final boolean enabled,
            final TaskService tasks,
            final RuntimeService runtime,
            final MultiInstanceDetector detector,
            final List<DecisionObservation> sink) {
        return new DefaultSuggestionSubmissionService(detector, tasks, runtime,
                new DecisionObservationEmitter(null, Collections.singletonList(sink::add)), enabled);
    }

    /** 按反例的检测对象覆盖置位，再驱动一次提交并交回异常。 */
    private SuggestionAdmissionException assertRejected(final RejectionCase rejectionCase) {
        when(multiInstanceDetector.isMultiInstance(any())).thenReturn(false);
        when(multiInstanceDetector.isRuntimeMultiInstance(any()))
                .thenReturn(rejectionCase.runtimeMultiInstance);
        when(multiInstanceDetector.isInitiatorDecisionTask(any())).thenReturn(false);
        return assertRejected(rejectionCase.submission);
    }

    private SuggestionAdmissionException assertRejected(final SuggestionSubmission submission) {
        try {
            service.submit(submission);
        } catch (SuggestionAdmissionException rejected) {
            return rejected;
        }
        throw new AssertionError("准入应当失败并抛出 SuggestionAdmissionException：" + submission);
    }

    /** 驱动一次提交后，本段是否落了一行证据（= 三项前置带齐的物质化）。 */
    private boolean materializedNow() {
        return capturedRows().size() == 1;
    }

    /** 置位后驱动一次提交，交回「是否物质化」。 */
    private boolean materialized(final SuggestionSubmission submission) {
        resetInvocations();
        assertRejected(submission);
        return materializedNow();
    }

    /** 断言本反例的落行行为与失败行内容（不物质化者必不落行，原因落观测面的 {@code admissionReason}）。 */
    private void assertMaterialization(final RejectionCase rejectionCase) throws IOException {
        final List<String> rows = capturedRows();
        if (rows.isEmpty()) {
            assertThat(singleObservation().getAdmissionReason())
                    .as("准入规则（%s）：缺任一前置 ⇒ 不落行（退日志 + 指标），原因落 admissionReason",
                            rejectionCase.rule)
                    .isEqualTo(rejectionCase.reason);
            return;
        }
        assertThat(rows)
                .as("准入规则（%s）：一次准入失败至多落一行", rejectionCase.rule)
                .hasSize(1);
        final JsonNode payload = DecisionFixtures.evidenceJson(rows.get(0));
        assertThat(DecisionFixtures.enumValue(payload.get("outcome"), DecisionOutcome.class))
                .as("准入规则（%s）：带齐三项前置的准入失败物质化为失败行", rejectionCase.rule)
                .isEqualTo(DecisionOutcome.SUGGESTION_FAILED);
        assertThat(DecisionFixtures.enumValue(payload.get("failureKind"), DecisionFailureKind.class))
                .as("准入规则（%s）：失败行归 SITE_ADMISSION_REJECTED", rejectionCase.rule)
                .isEqualTo(DecisionFailureKind.SITE_ADMISSION_REJECTED);
        assertThat(payload.get("idempotencyKey").asText())
                .as("准入规则（%s）：失败行保留幂等身份（可判别性不因拒绝而丢）", rejectionCase.rule)
                .isEqualTo(DecisionFixtures.IDEMPOTENCY_KEY);
        assertThat(singleObservation().getFailureKind())
                .as("准入规则（%s）：已物质化的拒绝在观测面上同值（进告警并计入错误指标）", rejectionCase.rule)
                .isEqualTo(DecisionFailureKind.SITE_ADMISSION_REJECTED);
    }

    private void resetInvocations() {
        clearInvocations(taskService);
        observations.clear();
    }

    /** 本段内交出的 {@code addComment} 的第四个实参（整行文本）。 */
    private List<String> capturedRows() {
        final ArgumentCaptor<String> rows = ArgumentCaptor.forClass(String.class);
        verify(taskService, atLeast(0)).addComment(anyString(), anyString(), anyString(), rows.capture());
        return rows.getAllValues();
    }

    private String capturedRow() {
        final List<String> rows = capturedRows();
        assertThat(rows)
                .as("本段应当恰好落一行证据")
                .hasSize(1);
        return rows.get(0);
    }

    private DecisionObservation singleObservation() {
        assertThat(observations)
                .as("本段应当恰好发一条观测")
                .hasSize(1);
        return observations.get(0);
    }
}
