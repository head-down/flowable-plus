package io.github.flowable.plus.core.vo;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionCompleteness;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DecisionEvidenceVO} 的契约面守卫（ADR-0042 第 5 节）。
 *
 * <p>三件事：<b>必填 / 可空矩阵写死</b>（矩阵表覆盖字段集 == VO 字段集，逐格给出必填理由）；
 * <b>JSON 字段名与 VO 字段名同源</b>；<b>证据面闭集穷举</b>（决策源中立性 ②：取值域不绑 AI）。</p>
 *
 * <p>必填理由只能引 {@code outcome} 分支、产出路径或 {@code subjectType}，不得引「因为会调模型」。</p>
 */
public class DecisionEvidenceVOContractTest {

    // ======================== 矩阵：列与格 ========================

    /** 产出路径四列（A 经出站调用 / B 直提 / C 按政策未产出 / D 失败） */
    private enum Column {
        A, B, C, D
    }

    private enum Requirement {
        /** 该列必填 */
        REQUIRED,
        /** 该列条件必填（条件见 ADR-0042 第 5 节，不进本表） */
        CONDITIONAL,
        /** 该列可空 */
        NULLABLE,
        /** 该列必须为 null */
        MUST_BE_NULL,
        /** 该列必须为 null，唯一例外为 INBOUND_PROCESSING_FAILED */
        MUST_BE_NULL_EXCEPT_EXCEPTION
    }

    private static final class Cell {

        private final Requirement requirement;
        private final String reason;

        private Cell(final Requirement requirement, final String reason) {
            this.requirement = requirement;
            this.reason = reason;
        }
    }

    /** 必填理由的唯一允许来源之一：产出路径的结构性事实 */
    private static final String BY_PATH = "产出路径的结构性事实";

    /** 必填理由的唯一允许来源之一：outcome 分支 */
    private static final String BY_OUTCOME = "outcome 分支";

    /** 必填理由的唯一允许来源之一：subjectType 分支 */
    private static final String BY_SUBJECT = "subjectType 分支";

    private static final Set<String> ALLOWED_REASONS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList(BY_PATH, BY_OUTCOME, BY_SUBJECT)));

    /** 必填理由不得出现的表述（B.5.2：不得引「因为会调模型」） */
    private static final List<String> FORBIDDEN_REASON_PHRASES =
            Collections.unmodifiableList(Arrays.asList("调模型", "模型调用"));

    private static final Map<String, List<Cell>> MATRIX = buildMatrix();

    // ======================== 断言 ========================

    @Test
    void matrixCoversEveryFieldOfVO() {
        assertThat(MATRIX.keySet())
                .as("矩阵表覆盖字段集必须恒等于 VO 字段集（新增字段无处可藏）")
                .containsExactlyInAnyOrderElementsOf(declaredFieldNames(DecisionEvidenceVO.class));

        MATRIX.forEach((field, cells) -> assertThat(cells)
                .as("字段 %s 必须在四个产出路径列上都有格（不得留「视情形」格）", field)
                .hasSize(Column.values().length));

        MATRIX.forEach((field, cells) -> cells.forEach(cell -> assertCellReasonWellFormed(field, cell)));
    }

    @Test
    void jsonKeysEqualFieldNames() throws Exception {
        final String json = DecisionEvidenceTestFixtures.toJson(
                DecisionEvidenceTestFixtures.maximalDirectSubmission());
        final JsonNode node = DecisionEvidenceTestFixtures.parse(json);

        final Set<String> jsonKeys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(jsonKeys::add);

        assertThat(jsonKeys)
                .as("JSON 字段名与 VO 字段名同源（不得借注解改名或漏键）")
                .containsExactlyInAnyOrderElementsOf(declaredFieldNames(DecisionEvidenceVO.class));
    }

    @Test
    void closedValueDomainsAreExhaustiveAndNotBoundToAi() {
        assertThat(DecisionOutcome.values()).containsExactly(
                DecisionOutcome.SUGGESTION_PRODUCED,
                DecisionOutcome.NO_SUGGESTION_BY_POLICY,
                DecisionOutcome.SUGGESTION_FAILED);
        assertThat(DecisionPolicyReason.values()).containsExactly(
                DecisionPolicyReason.NO_SOURCE_DECLARED,
                DecisionPolicyReason.POLICY_REJECTED,
                DecisionPolicyReason.MODEL_DECLINED,
                DecisionPolicyReason.SUSPENDED,
                DecisionPolicyReason.OVERLOADED);
        assertThat(DecisionFailureKind.values()).containsExactly(
                DecisionFailureKind.OUTBOUND_TIMEOUT,
                DecisionFailureKind.OUTBOUND_HTTP_ERROR,
                DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID,
                DecisionFailureKind.RESPONSE_UNPARSEABLE,
                DecisionFailureKind.INBOUND_PROCESSING_FAILED,
                DecisionFailureKind.SITE_ADMISSION_REJECTED,
                DecisionFailureKind.INTERNAL_ERROR);
        assertThat(DecisionChainStage.values()).containsExactly(
                DecisionChainStage.PRIMARY,
                DecisionChainStage.FALLBACK,
                DecisionChainStage.RULE);
        assertThat(DecisionSubjectType.values()).containsExactly(
                DecisionSubjectType.AI,
                DecisionSubjectType.SYSTEM,
                DecisionSubjectType.USER);
        assertThat(DecisionCompleteness.values()).containsExactly(
                DecisionCompleteness.FULL,
                DecisionCompleteness.PARTIAL,
                DecisionCompleteness.NO_PAYLOAD,
                DecisionCompleteness.RESTRICTED);
        assertThat(DecisionRationaleFactKey.values()).containsExactly(
                DecisionRationaleFactKey.BASIS_CODE,
                DecisionRationaleFactKey.POLICY_RULE,
                DecisionRationaleFactKey.MISSING_INPUT,
                DecisionRationaleFactKey.SCORE,
                DecisionRationaleFactKey.SOURCE_REF);

        // 中立性 ②：取值域不绑 AI —— 每个域都必须容纳非模型类决策源 / 非模型专属结局。
        assertThat(DecisionSubjectType.values())
                .as("主体域必须容纳离线批算 / 外部规则服务与人")
                .contains(DecisionSubjectType.SYSTEM, DecisionSubjectType.USER);
        assertThat(DecisionChainStage.values())
                .as("链路阶段必须容纳规则链路")
                .contains(DecisionChainStage.RULE);
        assertThat(DecisionOutcome.values())
                .as("结局域必须容纳非模型专属的未产出与失败")
                .contains(DecisionOutcome.NO_SUGGESTION_BY_POLICY, DecisionOutcome.SUGGESTION_FAILED);
        assertThat(DecisionPolicyReason.values())
                .as("政策原因域必须容纳不经出站调用的那些原因")
                .contains(DecisionPolicyReason.NO_SOURCE_DECLARED, DecisionPolicyReason.POLICY_REJECTED,
                        DecisionPolicyReason.SUSPENDED, DecisionPolicyReason.OVERLOADED);
        assertThat(DecisionFailureKind.values())
                .as("失败类别域必须容纳非出站类失败")
                .contains(DecisionFailureKind.RESPONSE_UNPARSEABLE,
                        DecisionFailureKind.SITE_ADMISSION_REJECTED,
                        DecisionFailureKind.INTERNAL_ERROR);

        // 「AI」只作为主体取值出现一次（取值字面量豁免面），其余域不得混入决策源取值。
        // 断言对象是**取值名**：跨枚举类型比较实例恒不相等，那样的写法永真、抓不到泄漏。
        nonSubjectDomains().forEach(domain -> domain.forEach(value -> assertThat(value.name())
                .as("决策源取值 AI 只允许出现在 subjectType 域内")
                .isNotEqualTo(DecisionSubjectType.AI.name())));
    }

    // ======================== 矩阵定义 ========================

    private static Map<String, List<Cell>> buildMatrix() {
        final Map<String, List<Cell>> matrix = new LinkedHashMap<>();
        row(matrix, "outcome",
                required(BY_OUTCOME), required(BY_OUTCOME), required(BY_OUTCOME), required(BY_OUTCOME));
        row(matrix, "schemaVersion",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "idempotencyKey",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "subjectType",
                required(BY_SUBJECT), required(BY_SUBJECT), required(BY_SUBJECT), required(BY_SUBJECT));
        row(matrix, "subjectId",
                nullable(), nullable(), nullable(), nullable());
        row(matrix, "subjectName",
                nullable(), nullable(), nullable(), nullable());
        row(matrix, "suggestedAction",
                required(BY_OUTCOME), required(BY_OUTCOME), mustBeNull(), mustBeNull());
        row(matrix, "actionSummary",
                required(BY_OUTCOME), required(BY_OUTCOME), mustBeNull(), mustBeNull());
        row(matrix, "modelId",
                conditional(BY_PATH), mustBeNull(), conditional(BY_OUTCOME), nullable());
        row(matrix, "inputSnapshot",
                nullable(), mustBeNull(), mustBeNull(), nullable());
        row(matrix, "rawOutput",
                nullable(), nullable(), mustBeNull(), nullable());
        row(matrix, "rationaleFacts",
                required(BY_OUTCOME), required(BY_SUBJECT), required(BY_OUTCOME), mustBeNull());
        row(matrix, "rationaleNarrative",
                required(BY_OUTCOME), required(BY_SUBJECT), required(BY_OUTCOME), mustBeNull());
        row(matrix, "attestedDataSources",
                mustBeNull(), nullable(), mustBeNull(), mustBeNull());
        row(matrix, "provider",
                required(BY_PATH), mustBeNull(), conditional(BY_OUTCOME), nullable());
        row(matrix, "chainStage",
                required(BY_PATH), mustBeNull(), conditional(BY_OUTCOME), nullable());
        row(matrix, "degraded",
                required(BY_PATH), mustBeNull(), conditional(BY_OUTCOME), nullable());
        row(matrix, "outboundRedacted",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "outboundTruncated",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "outboundCompleteness",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "inboundRedacted",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "inboundTruncated",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "inboundCompleteness",
                required(BY_PATH), required(BY_PATH), required(BY_PATH), required(BY_PATH));
        row(matrix, "failureKind",
                mustBeNullExceptException(BY_OUTCOME), mustBeNullExceptException(BY_OUTCOME),
                mustBeNull(), required(BY_OUTCOME));
        row(matrix, "policyReason",
                mustBeNull(), mustBeNull(), required(BY_OUTCOME), mustBeNull());
        return Collections.unmodifiableMap(matrix);
    }

    private static void row(final Map<String, List<Cell>> matrix, final String field,
                            final Cell a, final Cell b, final Cell c, final Cell d) {
        matrix.put(field, Collections.unmodifiableList(Arrays.asList(a, b, c, d)));
    }

    private static Cell required(final String reason) {
        return new Cell(Requirement.REQUIRED, reason);
    }

    private static Cell conditional(final String reason) {
        return new Cell(Requirement.CONDITIONAL, reason);
    }

    private static Cell nullable() {
        return new Cell(Requirement.NULLABLE, null);
    }

    private static Cell mustBeNull() {
        return new Cell(Requirement.MUST_BE_NULL, null);
    }

    private static Cell mustBeNullExceptException(final String reason) {
        return new Cell(Requirement.MUST_BE_NULL_EXCEPT_EXCEPTION, reason);
    }

    // ======================== 辅助 ========================

    /** 必填格必须有理由、理由只能取自白名单、且任何格的理由都不得引「因为会调模型」 */
    private static void assertCellReasonWellFormed(final String field, final Cell cell) {
        if (cell.requirement == Requirement.REQUIRED || cell.requirement == Requirement.CONDITIONAL) {
            assertThat(cell.reason)
                    .as("字段 %s 的必填格必须写明理由", field)
                    .isNotBlank();
            assertThat(ALLOWED_REASONS)
                    .as("字段 %s 的必填理由只能引 outcome 分支 / 产出路径 / subjectType", field)
                    .contains(cell.reason);
        }
        FORBIDDEN_REASON_PHRASES.forEach(forbidden -> assertThat(StringUtils.defaultString(cell.reason))
                .as("字段 %s 的理由不得引「因为会调模型」", field)
                .doesNotContain(forbidden));
    }

    private static Set<String> declaredFieldNames(final Class<?> owner) {
        return Arrays.stream(owner.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 除 {@code subjectType} 外的全部取值域（「AI 只许出现在主体域」的扫描面） */
    private static List<List<? extends Enum<?>>> nonSubjectDomains() {
        return Arrays.asList(
                Arrays.asList(DecisionOutcome.values()),
                Arrays.asList(DecisionPolicyReason.values()),
                Arrays.asList(DecisionFailureKind.values()),
                Arrays.asList(DecisionChainStage.values()),
                Arrays.asList(DecisionCompleteness.values()),
                Arrays.asList(DecisionRationaleFactKey.values()));
    }
}
