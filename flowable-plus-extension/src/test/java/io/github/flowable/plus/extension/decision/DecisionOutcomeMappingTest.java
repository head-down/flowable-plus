package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结局映射表的落位守卫（ADR-0042 第 10 节「计错判据」；行集来源 = 拉管线与出站缝决议 §13）。
 *
 * <p>三条：① 行数逐一对账且<b>每行可达</b> —— 每行都能落成一条符合其落位的观测事实；
 * ② <b>计错判据 = {@code failureKind != null}</b>（不挂在 {@code outcome} 上）；③ 「按政策未产出」
 * 永不带 {@code failureKind} ⇒ <b>绝不入错误率</b>。</p>
 *
 * <p><b>「可达」的机械形态</b>：本票不做管线，故「该行的产生点可到达」由决议表具名保证；此处的
 * 可判形态 = <b>该行的落位能构造出一条观测事实</b>（未物质化三行同样是「可落成的观测」，只是
 * 结局三字段皆为 null）。</p>
 */
public class DecisionOutcomeMappingTest {

    /** 结局映射表行数（对账常量：与决议 §13 的产出观测行逐行相等，含 Provider 缝本地短路两行） */
    private static final int DECLARED_ROW_COUNT = 20;

    /** 「按政策未产出」的行数（七值闭集） */
    private static final int DECLARED_POLICY_ROW_COUNT = 7;

    /** 计入错误的行（失败八行 + 产出态的唯一例外值一行） */
    private static final List<DecisionOutcomeMapping> COUNTED_AS_ERROR = Arrays.asList(
            DecisionOutcomeMapping.INBOUND_PROCESSING_FAILED,
            DecisionOutcomeMapping.POLICY_EVALUATION_FAILED,
            DecisionOutcomeMapping.ASSEMBLY_FAILED,
            DecisionOutcomeMapping.OUTBOUND_TIMEOUT,
            DecisionOutcomeMapping.OUTBOUND_HTTP_ERROR,
            DecisionOutcomeMapping.CREDENTIAL_REJECTED,
            DecisionOutcomeMapping.CREDENTIAL_RESOLUTION_FAILED,
            DecisionOutcomeMapping.RESPONSE_UNPARSEABLE,
            DecisionOutcomeMapping.SITE_ADMISSION_REJECTED);

    /** 绝不入错误率的行（成功路径一行 + 按政策未产出七行 + 未物质化三行） */
    private static final List<DecisionOutcomeMapping> NEVER_COUNTED_AS_ERROR = Arrays.asList(
            DecisionOutcomeMapping.SUGGESTION_DELIVERED,
            DecisionOutcomeMapping.NO_SOURCE_DECLARED,
            DecisionOutcomeMapping.POLICY_REJECTED,
            DecisionOutcomeMapping.MODEL_DECLINED,
            DecisionOutcomeMapping.CONTEXT_UNAVAILABLE,
            DecisionOutcomeMapping.CREDENTIAL_UNAVAILABLE,
            DecisionOutcomeMapping.SUSPENDED,
            DecisionOutcomeMapping.OVERLOADED,
            DecisionOutcomeMapping.ANCHOR_LOST,
            DecisionOutcomeMapping.INSTANCE_ENDED,
            DecisionOutcomeMapping.IDEMPOTENCY_KEY_MISSING);

    @Test
    void everyMappingRowIsReachable() {
        assertThat(DecisionOutcomeMapping.ROW_COUNT)
                .as("行数常量必须与决议表的产出观测行对数（恒定 20，不随表长推导）")
                .isEqualTo(DECLARED_ROW_COUNT);
        assertThat(DecisionOutcomeMapping.values())
                .as("枚举常量数必须等于行数常量")
                .hasSize(DecisionOutcomeMapping.ROW_COUNT);

        for (final DecisionOutcomeMapping row : DecisionOutcomeMapping.values()) {
            final DecisionObservation materialized = materialize(row);

            assertThat(materialized.getSeverity())
                    .as("%s 行必须能落成带严重度的观测（可达）", row)
                    .isSameAs(row.getSeverity());
            assertThat(materialized.getOutcome())
                    .as("%s 行的结局落位必须如实进入观测事实", row)
                    .isSameAs(row.getOutcome());
            assertThat(materialized.getFailureKind())
                    .as("%s 行的失败类别落位必须如实进入观测事实", row)
                    .isSameAs(row.getFailureKind());
            assertThat(materialized.getPolicyReason())
                    .as("%s 行的政策原因落位必须如实进入观测事实", row)
                    .isSameAs(row.getPolicyReason());

            assertThat(row.getPolicyReason() == null || row.getOutcome() == DecisionOutcome.NO_SUGGESTION_BY_POLICY)
                    .as("%s 行的政策原因只许出现在「按政策未产出」行", row)
                    .isTrue();
            assertThat(row.getFailureKind() == null
                    || row.getOutcome() == DecisionOutcome.SUGGESTION_FAILED
                    || row.getFailureKind() == DecisionFailureKind.INBOUND_PROCESSING_FAILED)
                    .as("%s 行的失败类别只许出现在失败行，或取产出态的唯一例外值", row)
                    .isTrue();
        }

        // 满射：三个闭集的每个取值都至少落在一行（防「闭集有值而表无行」的静默漏行）
        assertThat(outcomesOfRows())
                .as("顶层结局三叶子态必须逐值落行（不得有闭集取值无行可达）")
                .contains(DecisionOutcome.SUGGESTION_PRODUCED, DecisionOutcome.NO_SUGGESTION_BY_POLICY,
                        DecisionOutcome.SUGGESTION_FAILED);
        assertThat(failureKindsOfRows())
                .as("失败类别七值必须逐值落行")
                .contains(DecisionFailureKind.values());
        assertThat(policyReasonsOfRows())
                .as("政策原因七值必须逐值落行")
                .contains(DecisionPolicyReason.values());

        // 未物质化三情形：三字段皆 null、仍留可区分结局（产生点即行身份，恰三行）
        final List<DecisionOutcomeMapping> unmaterialized = rowsMatching(row -> row.getOutcome() == null);
        assertThat(unmaterialized)
                .as("未物质化三情形（缺身份 / 锚点失效 / 实例已结束）各占一行")
                .hasSize(3);
        assertThat(unmaterialized)
                .as("未物质化行三字段皆 null、严重度必填（留下可区分的结局，不新增第四态）")
                .allSatisfy(row -> {
                    assertThat(row.getFailureKind()).as("%s 行不得带失败类别", row).isNull();
                    assertThat(row.getPolicyReason()).as("%s 行不得带政策原因", row).isNull();
                    assertThat(row.getSeverity()).as("%s 行必须带严重度", row).isSameAs(DecisionSeverity.ERROR);
                });

        // 派生两位：可重试集合与「是否落证据行」的落位
        assertThat(rowsMatching(DecisionOutcomeMapping::isRetryable))
                .as("可重试集合恰三行：超时 / 非 2xx / 响应不可解析")
                .containsExactlyInAnyOrder(DecisionOutcomeMapping.OUTBOUND_TIMEOUT,
                        DecisionOutcomeMapping.OUTBOUND_HTTP_ERROR, DecisionOutcomeMapping.RESPONSE_UNPARSEABLE);
        assertThat(rowsMatching(row -> !row.isEvidenceRow()))
                .as("不落证据行的恰四行：池满（日志 + 独立计数）与未物质化三情形（不成行）")
                .containsExactlyInAnyOrder(DecisionOutcomeMapping.OVERLOADED, DecisionOutcomeMapping.ANCHOR_LOST,
                        DecisionOutcomeMapping.INSTANCE_ENDED, DecisionOutcomeMapping.IDEMPOTENCY_KEY_MISSING);
    }

    @Test
    void failureOnlyCountsErrorsWhenFailureKindNotNull() {
        assertThat(rowsCountedAsError())
                .as("计错判据 = failureKind != null：失败行与产出态的唯一例外值都计入错误")
                .containsExactlyInAnyOrderElementsOf(COUNTED_AS_ERROR);
        assertThat(rowsNotCountedAsError())
                .as("「按政策未产出」永不带 failureKind ⇒ 绝不入错误率；成功路径与未物质化三情形同理")
                .containsExactlyInAnyOrderElementsOf(NEVER_COUNTED_AS_ERROR);
        assertThat(DecisionOutcomeMapping.INBOUND_PROCESSING_FAILED.getOutcome())
                .as("产出态的唯一例外值仍计入错误（计错判据不挂在 outcome 上）")
                .isSameAs(DecisionOutcome.SUGGESTION_PRODUCED);
    }

    @Test
    void noSuggestionByPolicyNeverCarriesFailureKind() {
        final List<DecisionOutcomeMapping> policyRows = rowsWithOutcome(DecisionOutcome.NO_SUGGESTION_BY_POLICY);

        assertThat(policyRows)
                .as("「按政策未产出」七值各占一行")
                .hasSize(DECLARED_POLICY_ROW_COUNT);
        assertThat(policyRows)
                .as("每一行都必须带政策原因")
                .allSatisfy(row -> assertThat(row.getPolicyReason()).as("%s 行必须带政策原因", row).isNotNull());
        assertThat(policyRows)
                .as("每一行都不得带失败类别（否则污染错误率）")
                .allSatisfy(row -> assertThat(row.getFailureKind()).as("%s 行不得带失败类别", row).isNull());
    }

    /** 用该行的落位构造一条观测事实（「可达」的机械形态）。 */
    private static DecisionObservation materialize(final DecisionOutcomeMapping row) {
        return new DecisionObservation(DecisionFixtures.TASK_ID, DecisionFixtures.NODE_ID,
                DecisionFixtures.PROCESS_INSTANCE_ID, row.getOutcome(), row.getFailureKind(), row.getPolicyReason(),
                row.getSeverity(), null, null, null, null, null, null, null, null, null);
    }

    private static List<DecisionOutcomeMapping> rowsCountedAsError() {
        return rowsMatching(row -> row.getFailureKind() != null);
    }

    private static List<DecisionOutcomeMapping> rowsNotCountedAsError() {
        return rowsMatching(row -> row.getFailureKind() == null);
    }

    private static List<DecisionOutcomeMapping> rowsWithOutcome(final DecisionOutcome outcome) {
        return rowsMatching(row -> row.getOutcome() == outcome);
    }

    private static List<DecisionOutcome> outcomesOfRows() {
        return Arrays.stream(DecisionOutcomeMapping.values())
                .map(DecisionOutcomeMapping::getOutcome)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<DecisionFailureKind> failureKindsOfRows() {
        return Arrays.stream(DecisionOutcomeMapping.values())
                .map(DecisionOutcomeMapping::getFailureKind)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<DecisionPolicyReason> policyReasonsOfRows() {
        return Arrays.stream(DecisionOutcomeMapping.values())
                .map(DecisionOutcomeMapping::getPolicyReason)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<DecisionOutcomeMapping> rowsMatching(final Predicate<DecisionOutcomeMapping> predicate) {
        return Arrays.stream(DecisionOutcomeMapping.values())
                .filter(predicate)
                .collect(Collectors.toList());
    }
}
