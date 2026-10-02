package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 观测面契约守卫（ADR-0042 第 10 节「可观测面」）。
 *
 * <p>八组：① 信号名与值的<b>前缀 / 格式 / T1</b>；② 维度键无屈折对；③ 闭集值取枚举常量名的小写蛇形；
 * ④ 观测事实字段集恰十六 + 类型白名单；⑤ {@code severity} 必填、{@code outcome} 可空；⑥ 消费隔离
 * （回调抛异常不上抛、不中断其余消费者）；⑦ 观测面禁载（凭据 / 证据载荷 / 身份 / 幂等键字段名）；
 * ⑧ 载荷哨兵不出现于日志与 tag value（运行期）。</p>
 *
 * <p><b>T1 清单的来路</b>：本类只<b>消费</b>契约命名宪章的 T1 绝对禁词清单（宪章为唯一住所），
 * 不另立词表；清单内的中文译名与变体拼写不可机械枚举，故 T1 扫描只覆盖可枚举的 ASCII 词条。
 * 全宇宙的单点 T1 扫描属命名守卫的落点，本类只覆盖观测面的信号名与值。</p>
 */
public class DecisionObservationContractTest {

    /** 观测事实字段数（对账常量） */
    private static final int DECLARED_FIELD_COUNT = 16;

    /** 信号数（对账常量） */
    private static final int DECLARED_SIGNAL_COUNT = 9;

    /** 维度键数（对账常量） */
    private static final int DECLARED_DIMENSION_KEY_COUNT = 10;

    /** 观测面全部公开字符串常量的总数（信号 9 + 维度键 10 + 闭集值 4 + logger 1） */
    private static final int DECLARED_STRING_CONSTANT_COUNT = 24;

    /** 信号值的统一前缀 */
    private static final String SIGNAL_NAME_PREFIX = "flowable.plus.decision.";

    /** 信号常量名的统一前缀 */
    private static final String SIGNAL_CONSTANT_PREFIX = "FLOWABLE_PLUS_DECISION_";

    /** logger 名常量（机制级单一来源） */
    private static final String LOGGER_CONSTANT_NAME = "LOGGER_NAME";

    /** 闭集值常量名的两个前缀（{@code direction} 与 {@code cause}） */
    private static final String DIRECTION_CONSTANT_PREFIX = "DIRECTION_";
    private static final String CAUSE_CONSTANT_PREFIX = "CAUSE_";

    private static final String SCREAMING_SNAKE = "[A-Z][A-Z0-9_]*";
    private static final String LOWER_DOT_SEPARATED = "[a-z][a-z0-9.]*";
    private static final String LOWER_SNAKE = "[a-z][a-z0-9_]*";
    private static final String CAMEL_CASE = "[a-z][a-zA-Z0-9]*";

    private static final String UNDERSCORE = "_";
    private static final String HYPHEN = "-";
    private static final String DOT = ".";
    private static final String DOUBLE_DOT = "..";

    /** 屈折后缀（宪章 §2.C.1 的三种规则屈折） */
    private static final String PLURAL_SUFFIX = "s";
    private static final String PLURAL_SUFFIX_ES = "es";
    private static final String PLURAL_SUFFIX_IES = "ies";
    private static final String Y_SUFFIX = "y";

    /** 观测事实的十六字段名（对账清单） */
    private static final Set<String> DECLARED_FIELD_NAMES = new LinkedHashSet<>(Arrays.asList(
            "taskId", "nodeId", "processInstanceId",
            "outcome", "failureKind", "policyReason", "severity",
            "subjectType", "modelId", "chainStage",
            "latencyMs", "inputTokens", "outputTokens",
            "writeDegradedCause", "admissionReason", "droppedContextSources"));

    /** 维度键常量名（对账清单） */
    private static final Set<String> DECLARED_DIMENSION_KEY_NAMES = new LinkedHashSet<>(Arrays.asList(
            "FAILURE_KIND", "SEVERITY", "NODE_ID", "SUBJECT_TYPE", "POLICY_REASON",
            "DIRECTION", "MODEL_ID", "CAUSE", "REASON", "CONTEXT_SOURCE"));

    /** 观测面字段的类型白名单（不得出现证据 VO / 载荷 / 凭据类型） */
    private static final Set<Class<?>> ALLOWED_FIELD_TYPES = new LinkedHashSet<>(Arrays.asList(
            String.class, Long.class, List.class,
            DecisionOutcome.class, DecisionFailureKind.class, DecisionPolicyReason.class,
            DecisionSubjectType.class, DecisionChainStage.class,
            DecisionSeverity.class, WriteDegradedCause.class, SuggestionAdmissionReason.class));

    /**
     * T1 绝对禁词的可枚举子集（消费命名宪章 §4.1：厂商 / 公司名 · 具体模型家族与型号名 · 具体商业产品名）。
     *
     * <p>比较取<b>归一小写</b>后的子串匹配（与宪章「切词后与清单匹配」同向）。</p>
     */
    private static final List<String> T1_FORBIDDEN_WORDS = Arrays.asList(
            "openai", "anthropic", "google", "meta", "microsoft", "nvidia", "mistral", "cohere",
            "alibaba", "baidu", "deepseek",
            "gpt", "claude", "gemini", "llama", "qwen", "ernie", "palm",
            "bedrock", "azureopenai", "vertexai");

    /** 观测面禁载的字段名（证据载荷 / 摘要 / 身份 / 幂等键；均为语料内已冻结名） */
    private static final List<String> FORBIDDEN_FIELD_NAMES = Arrays.asList(
            "inputSnapshot", "rawOutput", "rationaleNarrative", "rationaleFacts",
            "actionSummary", "subjectId", "subjectName", "idempotencyKey");

    /** 观测面禁载的字段名片段（凭据 / 异常材料） */
    private static final List<String> FORBIDDEN_FIELD_NAME_FRAGMENTS = Arrays.asList(
            "credential", "exception", "throwable", "stacktrace", "stack");

    /** 维度受控：不得作为维度的标识 */
    private static final List<String> FORBIDDEN_DIMENSION_VALUES = Arrays.asList(
            "processInstanceId", "instanceId", "decisionId");

    @Test
    void signalNamesAndValuesArePrefixedFormattedAndT1Clean() {
        final Map<String, String> constants = decisionMetricsStringConstants();
        final List<String> signalNames = namesWithPrefix(constants, SIGNAL_CONSTANT_PREFIX);

        assertThat(signalNames)
                .as("信号恰九个（清单见 ADR-0042 第 10 节「可观测面」）")
                .hasSize(DECLARED_SIGNAL_COUNT);

        for (final String constantName : signalNames) {
            final String signalName = constants.get(constantName);

            assertThat(constantName)
                    .as("信号常量名必须 SCREAMING_SNAKE")
                    .matches(SCREAMING_SNAKE);
            assertThat(constantName)
                    .as("信号常量名不带 METRIC / COUNTER 一类后缀")
                    .doesNotContain("METRIC")
                    .doesNotContain("COUNTER");
            assertThat(signalName)
                    .as("%s 的信号值必须带统一前缀 %s", constantName, SIGNAL_NAME_PREFIX)
                    .startsWith(SIGNAL_NAME_PREFIX);
            assertThat(signalName)
                    .as("%s 的信号值必须全小写、点分隔", constantName)
                    .matches(LOWER_DOT_SEPARATED);
            assertThat(signalName)
                    .as("%s 的信号值不得出现空段或尾点", constantName)
                    .doesNotContain(DOUBLE_DOT)
                    .doesNotEndWith(DOT);
            assertT1Clean(constantName);
            assertT1Clean(signalName);
        }

        // 「名与值两侧 T1」覆盖观测面全部公开常量：维度键、闭集值与 logger 名与信号同批受检
        for (final Map.Entry<String, String> constant : constants.entrySet()) {
            assertT1Clean(constant.getKey());
            assertT1Clean(constant.getValue());
        }
    }

    @Test
    void dimensionKeysHaveNoInflectionPairs() {
        final Map<String, String> constants = decisionMetricsStringConstants();
        final List<String> dimensionKeyNames = namesIn(constants, DECLARED_DIMENSION_KEY_NAMES);

        assertThat(dimensionKeyNames)
                .as("维度键常量名必须与清单逐个对上")
                .hasSize(DECLARED_DIMENSION_KEY_COUNT)
                .containsExactlyInAnyOrderElementsOf(DECLARED_DIMENSION_KEY_NAMES);

        for (final String constantName : dimensionKeyNames) {
            final String tagKey = constants.get(constantName);

            assertThat(tagKey)
                    .as("%s 的维度键值必须是小驼峰（与证据面冻结字段名对齐）", constantName)
                    .matches(CAMEL_CASE);
            assertThat(tagKey)
                    .as("%s 的维度键值不得成为受控维度的禁用标识", constantName)
                    .isNotIn(FORBIDDEN_DIMENSION_VALUES);
        }

        final List<String> normalizedNames = dimensionKeyNames.stream()
                .map(DecisionObservationContractTest::normalize)
                .collect(Collectors.toList());
        final List<String> normalizedValues = dimensionKeyNames.stream()
                .map(constantName -> normalize(constants.get(constantName)))
                .collect(Collectors.toList());

        assertThat(normalizedNames)
                .as("维度键常量名归一后不得同名")
                .doesNotHaveDuplicates();
        assertThat(normalizedNames)
                .as("维度键常量名归一后不得构成单复数形近对")
                .noneMatch(left -> normalizedNames.stream().anyMatch(right -> isInflectionPair(left, right)));
        assertThat(normalizedValues)
                .as("维度键的值归一后不得构成单复数形近对")
                .noneMatch(left -> normalizedValues.stream().anyMatch(right -> isInflectionPair(left, right)));

        // 硬域无屈折对覆盖同类型的全部公开常量：信号名与闭集值常量与维度键同批受检（logger 名单值、不成对）
        final List<String> otherConstantNames = constants.keySet().stream()
                .filter(constantName -> !DECLARED_DIMENSION_KEY_NAMES.contains(constantName))
                .collect(Collectors.toList());
        final List<String> normalizedOtherNames = otherConstantNames.stream()
                .map(DecisionObservationContractTest::normalize)
                .collect(Collectors.toList());
        final List<String> normalizedOtherValues = otherConstantNames.stream()
                .map(constantName -> normalize(constants.get(constantName)))
                .collect(Collectors.toList());

        assertThat(normalizedOtherNames)
                .as("信号名与闭集值常量归一后不得同名")
                .doesNotHaveDuplicates();
        assertThat(normalizedOtherNames)
                .as("信号名与闭集值常量归一后不得构成单复数形近对")
                .noneMatch(left -> normalizedOtherNames.stream().anyMatch(right -> isInflectionPair(left, right)));
        assertThat(normalizedOtherValues)
                .as("信号名与闭集值的值归一后不得构成单复数形近对")
                .noneMatch(left -> normalizedOtherValues.stream().anyMatch(right -> isInflectionPair(left, right)));
    }

    @Test
    void closedSetValuesAreLowerSnakeOfEnumNames() {
        assertThat(DecisionMetrics.DIRECTION_INPUT).as("direction 取值面").isEqualTo("input");
        assertThat(DecisionMetrics.DIRECTION_OUTPUT).as("direction 取值面").isEqualTo("output");
        assertThat(DecisionMetrics.CAUSE_ANCHOR_LOST).as("cause 取值面").isEqualTo("anchor_lost");
        assertThat(DecisionMetrics.CAUSE_INSTANCE_ENDED).as("cause 取值面").isEqualTo("instance_ended");

        assertTagValuesAreLowerSnakeOfEnumNames(Arrays.asList(SuggestionAdmissionReason.values()));
        assertTagValuesAreLowerSnakeOfEnumNames(Arrays.asList(DecisionContextSource.values()));

        assertThat(Arrays.stream(SuggestionAdmissionReason.values())
                .map(DecisionMetrics::tagValue)
                .collect(Collectors.toList()))
                .as("准入失败原因不得压成重复的 tag value")
                .doesNotHaveDuplicates();

        assertThat(allTagValueSources())
                .as("占位形态 unknown 已废弃：它永不作为 tag value 出现")
                .allSatisfy(source -> assertThat(source).doesNotContain("unknown"));
    }

    @Test
    void fieldSetEqualsDeclaredSixteenWithAllowedTypes() throws Exception {
        final List<Field> fields = Arrays.asList(DecisionObservation.class.getDeclaredFields());

        assertThat(fields)
                .as("观测事实字段集恰十六")
                .hasSize(DECLARED_FIELD_COUNT);
        assertThat(fields.stream().map(Field::getName).collect(Collectors.toList()))
                .as("字段名必须与十六字段清单逐个对上")
                .containsExactlyInAnyOrderElementsOf(DECLARED_FIELD_NAMES);

        for (final Field field : fields) {
            assertThat(ALLOWED_FIELD_TYPES)
                    .as("%s 的类型必须在观测面类型白名单内（不得承载载荷 / 证据 VO / 凭据）", field.getName())
                    .contains(field.getType());
        }

        final Field droppedContextSources = DecisionObservation.class.getDeclaredField("droppedContextSources");
        assertThat(((ParameterizedType) droppedContextSources.getGenericType()).getActualTypeArguments())
                .as("装配面字段的元素类型必须是数据源闭集")
                .containsExactly(DecisionContextSource.class);
    }

    @Test
    void severityIsRequiredAndOutcomeNullable() {
        assertThatThrownBy(() -> new DecisionObservation(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null))
                .as("severity 必填：构造期即校验")
                .isInstanceOf(NullPointerException.class);

        final DecisionObservation onlySeverity = new DecisionObservation(null, null, null, null, null, null,
                DecisionSeverity.ERROR, null, null, null, null, null, null, null, null, null);
        assertThat(onlySeverity.getSeverity()).as("severity 必填但足以构成一条观测").isSameAs(DecisionSeverity.ERROR);
        assertThat(onlySeverity.getOutcome()).as("outcome 可空（未物质化的三情形无结局、不新增第四态）").isNull();

        final DecisionObservation unmaterialized = DecisionFixtures.unmaterializedObservation();
        assertThat(unmaterialized.getOutcome()).isNull();
        assertThat(unmaterialized.getFailureKind()).isNull();
        assertThat(unmaterialized.getPolicyReason()).isNull();
        assertThat(unmaterialized.getSeverity()).as("未物质化三情形仍须留下可区分的结局").isSameAs(DecisionSeverity.ERROR);
    }

    @Test
    void observerFailureIsIsolatedAndNeverThrows() {
        final DecisionMetricsRecorder metricsRecorder = mock(DecisionMetricsRecorder.class);
        final List<DecisionObservation> notified = new ArrayList<>();
        final DecisionObserver failingObserver = observation -> {
            throw new IllegalStateException("观测回调故意失败：用于验证消费隔离");
        };
        final DecisionObservation observation = DecisionFixtures.deliveredObservation();
        final DecisionObservationEmitter emitter = new DecisionObservationEmitter(metricsRecorder,
                Arrays.asList(failingObserver, notified::add));

        assertThatCode(() -> emitter.emit(observation))
                .as("任一消费者失败都不得上抛（观测面是 best-effort）")
                .doesNotThrowAnyException();

        verify(metricsRecorder, times(1)).record(observation);
        assertThat(notified)
                .as("前一个回调抛异常不得中断其余回调")
                .containsExactly(observation);
        assertThat(DecisionObservationEmitter.logArguments(observation))
                .as("日志面实参仍逐字段可构造（回调失败不改变日志面）")
                .hasSize(DECLARED_FIELD_COUNT);
    }

    @Test
    void noEvidenceCredentialOrIdentityFieldAppears() {
        final List<String> fieldNames = Arrays.stream(DecisionObservation.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toList());

        assertThat(fieldNames)
                .as("观测面禁载：证据载荷 / 摘要 / 身份 / 幂等键字段名一律不得出现")
                .doesNotContainAnyElementsOf(FORBIDDEN_FIELD_NAMES);

        for (final String fieldName : fieldNames) {
            final String lowered = fieldName.toLowerCase(Locale.ROOT);
            for (final String fragment : FORBIDDEN_FIELD_NAME_FRAGMENTS) {
                assertThat(lowered)
                        .as("观测面禁载：字段名 %s 不得含 %s（凭据材料 / 异常材料）", fieldName, fragment)
                        .doesNotContain(fragment);
            }
        }

        assertThat(allDimensionKeyValues())
                .as("维度受控：processInstanceId / instanceId / decisionId 不得成为维度")
                .doesNotContainAnyElementsOf(FORBIDDEN_DIMENSION_VALUES);
    }

    @Test
    void payloadSentinelNeverLeaksIntoLogOrTags() {
        final String sentinel = DecisionFixtures.PAYLOAD_SENTINEL;

        assertThat(DecisionFixtures.payloadCarryingSentinel())
                .as("防空转：哨兵必须真的存在于载荷侧 fixture，否则本断言恒真")
                .contains(sentinel);

        final DecisionObservation observation = DecisionFixtures.deliveredObservation();
        assertThat(DecisionObservationEmitter.LOG_FORMAT)
                .as("日志格式串不得出现载荷哨兵")
                .doesNotContain(sentinel);
        assertThat(DecisionObservationEmitter.logArguments(observation))
                .as("日志面实参不得出现载荷哨兵")
                .doesNotContain(sentinel);
        assertThat(DecisionObservationEmitter.LOG_FORMAT)
                .as("正对照：日志面确实载有定位字段（断言非真空）")
                .contains("taskId={}")
                .contains("processInstanceId={}");

        final DecisionMetricsRecorder metricsRecorder = mock(DecisionMetricsRecorder.class);
        final List<DecisionObservation> notified = new ArrayList<>();
        new DecisionObservationEmitter(metricsRecorder, Collections.singletonList(notified::add)).emit(observation);

        assertThat(notified).as("运行期：真发一条观测").hasSize(1);
        for (final Object argument : DecisionObservationEmitter.logArguments(notified.get(0))) {
            assertThat(String.valueOf(argument))
                    .as("运行期：日志面每一实参都不得出现载荷哨兵")
                    .doesNotContain(sentinel);
        }
        verify(metricsRecorder, times(1)).record(notified.get(0));

        assertThat(allTagValueSources())
                .as("运行期：tag value 来源不得出现载荷哨兵")
                .allSatisfy(source -> assertThat(source).doesNotContain(sentinel));
    }

    // ======================== 私有支撑 ========================

    /**
     * {@link DecisionMetrics} 的全部公开字符串常量（名 → 值）。
     *
     * @return 常量表
     */
    private static Map<String, String> decisionMetricsStringConstants() {
        final Map<String, String> constants = new LinkedHashMap<>();
        for (final Field field : DecisionMetrics.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    constants.put(field.getName(), (String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("读取公开常量失败：" + field.getName(), e);
                }
            }
        }
        assertThat(constants)
                .as("观测面公开字符串常量总数 = 信号 9 + 维度键 10 + 闭集值 4 + logger 1")
                .hasSize(DECLARED_STRING_CONSTANT_COUNT);
        return constants;
    }

    /**
     * 断言全部 tag value 都是「枚举常量名的小写蛇形」。
     *
     * @param values 枚举常量
     */
    private static void assertTagValuesAreLowerSnakeOfEnumNames(final List<? extends Enum<?>> values) {
        assertThat(values).as("防空转：被换算的枚举不得为空").isNotEmpty();
        for (final Enum<?> value : values) {
            assertThat(DecisionMetrics.tagValue(value))
                    .as("%s 的 tag value 必须取枚举常量名的小写蛇形", value.name())
                    .isEqualTo(value.name().toLowerCase(Locale.ROOT))
                    .matches(LOWER_SNAKE);
        }
    }

    /**
     * T1 绝对禁词扫描（归一后子串匹配）。
     *
     * @param identifier 待扫标识符
     */
    private static void assertT1Clean(final String identifier) {
        final String normalized = identifier.toLowerCase(Locale.ROOT);
        for (final String forbidden : T1_FORBIDDEN_WORDS) {
            assertThat(normalized)
                    .as("T1 绝对禁词 %s 不得出现在 %s 中", forbidden, identifier)
                    .doesNotContain(forbidden);
        }
    }

    private static List<String> namesWithPrefix(final Map<String, String> constants, final String prefix) {
        return constants.keySet().stream()
                .filter(constantName -> hasPrefix(constantName, prefix))
                .collect(Collectors.toList());
    }

    private static List<String> namesIn(final Map<String, String> constants, final Set<String> expected) {
        return constants.keySet().stream()
                .filter(expected::contains)
                .collect(Collectors.toList());
    }

    /** 全部维度键的值。 */
    private static List<String> allDimensionKeyValues() {
        final Map<String, String> constants = decisionMetricsStringConstants();
        return namesIn(constants, DECLARED_DIMENSION_KEY_NAMES).stream()
                .map(constants::get)
                .collect(Collectors.toList());
    }

    /** 全部 tag value 来源：维度键值 + 闭集值 + 由枚举换算出的值。 */
    private static List<String> allTagValueSources() {
        final List<String> sources = new ArrayList<>(allDimensionKeyValues());
        sources.add(DecisionMetrics.DIRECTION_INPUT);
        sources.add(DecisionMetrics.DIRECTION_OUTPUT);
        sources.add(DecisionMetrics.CAUSE_ANCHOR_LOST);
        sources.add(DecisionMetrics.CAUSE_INSTANCE_ENDED);
        for (final SuggestionAdmissionReason reason : SuggestionAdmissionReason.values()) {
            sources.add(DecisionMetrics.tagValue(reason));
        }
        for (final DecisionContextSource source : DecisionContextSource.values()) {
            sources.add(DecisionMetrics.tagValue(source));
        }
        for (final WriteDegradedCause cause : WriteDegradedCause.values()) {
            sources.add(DecisionMetrics.tagValue(cause));
        }
        return sources;
    }

    /** 前缀判定：extension 不引 commons-lang3（依赖账本裁定），故以私有静态方法承担。 */
    private static boolean hasPrefix(final String value, final String prefix) {
        return value.length() >= prefix.length() && value.regionMatches(0, prefix, 0, prefix.length());
    }

    /** 归一：去分隔符（`_` / `-`）并统一小写（宪章 §2.C.1）。 */
    private static String normalize(final String name) {
        return name.replace(UNDERSCORE, "").replace(HYPHEN, "").toLowerCase(Locale.ROOT);
    }

    /** 词形屈折三种：`+s`、`+es`、`y→ies`（自反比较恒不成立，故两两自比不影响结果）。 */
    private static boolean isInflectionPair(final String left, final String right) {
        return left.concat(PLURAL_SUFFIX).equals(right)
                || right.concat(PLURAL_SUFFIX).equals(left)
                || left.concat(PLURAL_SUFFIX_ES).equals(right)
                || right.concat(PLURAL_SUFFIX_ES).equals(left)
                || (left.endsWith(Y_SUFFIX) && left.substring(0, left.length() - 1).concat(PLURAL_SUFFIX_IES).equals(right))
                || (right.endsWith(Y_SUFFIX) && right.substring(0, right.length() - 1).concat(PLURAL_SUFFIX_IES).equals(left));
    }
}
