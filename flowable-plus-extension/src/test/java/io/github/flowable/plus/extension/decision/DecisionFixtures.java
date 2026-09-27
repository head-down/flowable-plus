package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 观测面与证据面的固定 fixture（<b>测试专用类型，非测试类</b>）。
 *
 * <p>形态 = <b>Java 常量</b>；<b>不落资源文件</b> —— 本模块的测试树<b>不建</b>
 * {@code src/test/resources}，故 recorded fixture 无栖身处（「recorded 不进 v1」的结构保证）。</p>
 */
final class DecisionFixtures {

    /**
     * 载荷哨兵：只出现在<b>载荷侧</b> fixture 里。
     *
     * <p>观测面（结构化日志与指标 tag value）一律不得出现它 —— 它是「观测面禁载」的运行期可判形态：
     * 哨兵真的存在于载荷侧，故「未出现于观测面」的断言不是真空成立。</p>
     */
    static final String PAYLOAD_SENTINEL = "SENTINEL-PAYLOAD-CONTENT-MUST-NOT-BE-OBSERVED";

    /** 锚点任务标识 */
    static final String TASK_ID = "task-20260927-0001";

    /** 节点标识 */
    static final String NODE_ID = "userTask-decide";

    /** 流程实例标识 */
    static final String PROCESS_INSTANCE_ID = "process-20260927-0001";

    /** 模型标识 */
    static final String MODEL_ID = "decision-model-1";

    /** 单次尝试耗时（毫秒） */
    static final long LATENCY_MS = 128L;

    /** 入向 token 用量 */
    static final long INPUT_TOKENS = 320L;

    /** 出向 token 用量 */
    static final long OUTPUT_TOKENS = 96L;

    /** 装配器丢弃的数据源 */
    private static final List<DecisionContextSource> DROPPED_SOURCES =
            Collections.unmodifiableList(Collections.singletonList(DecisionContextSource.PROCESS_VARIABLES));

    private DecisionFixtures() {
    }

    /**
     * 载荷侧 fixture：一段含哨兵的载荷文本（观测面禁载材料的替身；本票无载荷类型，故取裸串形态）。
     *
     * @return 含 {@link #PAYLOAD_SENTINEL} 的载荷文本
     */
    static String payloadCarryingSentinel() {
        return "{\"processVariables\":{\"note\":\"" + PAYLOAD_SENTINEL + "\"}}";
    }

    /**
     * 成功路径的观测 fixture（{@code SUGGESTION_DELIVERED} 行）。
     *
     * @return 观测事实
     */
    static DecisionObservation deliveredObservation() {
        return new DecisionObservation(TASK_ID, NODE_ID, PROCESS_INSTANCE_ID,
                DecisionOutcome.SUGGESTION_PRODUCED, null, null, DecisionSeverity.INFO,
                DecisionSubjectType.AI, MODEL_ID, DecisionChainStage.PRIMARY,
                LATENCY_MS, INPUT_TOKENS, OUTPUT_TOKENS, null, null, DROPPED_SOURCES);
    }

    /**
     * 失败行的观测 fixture（{@code OUTBOUND_TIMEOUT} 行：可重试、落证据行）。
     *
     * @return 观测事实
     */
    static DecisionObservation failedObservation() {
        return new DecisionObservation(TASK_ID, NODE_ID, PROCESS_INSTANCE_ID,
                DecisionOutcome.SUGGESTION_FAILED, DecisionFailureKind.OUTBOUND_TIMEOUT, null,
                DecisionSeverity.WARN, DecisionSubjectType.AI, MODEL_ID, DecisionChainStage.PRIMARY,
                LATENCY_MS, null, null, null, null, null);
    }

    /**
     * 未物质化行的观测 fixture（{@code ANCHOR_LOST} 行：结局三字段皆 null、严重度必填）。
     *
     * @return 观测事实
     */
    static DecisionObservation unmaterializedObservation() {
        return new DecisionObservation(TASK_ID, NODE_ID, PROCESS_INSTANCE_ID,
                null, null, null, DecisionSeverity.ERROR,
                DecisionSubjectType.USER, null, null,
                null, null, null, WriteDegradedCause.ANCHOR_LOST, null, null);
    }

    // ======================== 证据面 fixture（写入侧） ========================

    /** 幂等身份（不透明串；与证据 VO 同源同值的那个值） */
    static final String IDEMPOTENCY_KEY = "idem-20260927-0001";

    /** 出处：provider 标识 */
    static final String PROVIDER = "provider-1";

    /** 主体 ID */
    static final String SUBJECT_ID = "subject-1";

    /** 主体显示名 */
    static final String SUBJECT_NAME = "外部决策服务";

    /** 出域载荷序列化形态（「模型实际看到的」那个串） */
    static final String INPUT_SNAPSHOT = "{\"processVariables\":{\"amount\":1}}";

    /** 入站载荷（落盘内容） */
    static final String RAW_OUTPUT = "{\"action\":\"AGREE\"}";

    /**
     * 直提提交（B 列的调用方自述）：出处组三者全 {@code null}、{@code modelId} / {@code inputSnapshot}
     * 必须为 {@code null}、类型化依据含 {@code BASIS_CODE}。
     *
     * @param attested 直提自述位（三态：{@code null} / 空集 / 有值）
     * @return 建议提交
     */
    static SuggestionSubmission directSubmission(final List<DecisionContextSource> attested) {
        return SuggestionSubmission.builder()
                .taskId(TASK_ID)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .subjectType(DecisionSubjectType.SYSTEM)
                .subjectId(SUBJECT_ID)
                .subjectName(SUBJECT_NAME)
                .suggestedAction(ApprovalAction.AGREE)
                .actionSummary("外部服务建议同意")
                .rawOutput(RAW_OUTPUT)
                .rationaleFacts(facts(DecisionRationaleFactKey.BASIS_CODE))
                .rationaleNarrative("依据：外部规则命中")
                .attestedDataSources(attested)
                .build();
    }

    /**
     * 直提草稿（B 列）：{@code of(submission)} 后的两个方向事实 —— 直提出域恒无载荷（由写入器强制）、
     * 入站按提交方是否交载荷取态。
     *
     * @param attested    直提自述位
     * @param inboundPayload 入站是否有落盘载荷
     * @return 草稿
     */
    static DecisionEvidenceDraft directDraft(final List<DecisionContextSource> attested,
                                             final boolean inboundPayload) {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.of(directSubmission(attested));
        draft.setInboundPayloadPresent(inboundPayload);
        draft.setRawOutput(inboundPayload ? RAW_OUTPUT : null);
        return draft;
    }

    /**
     * 拉面草稿（A 列）：出处组三者非 {@code null} + {@code modelId} + 出域 / 入站两方向皆有载荷。
     *
     * @return 草稿
     */
    static DecisionEvidenceDraft outboundDraft() {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_PRODUCED)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .subjectType(DecisionSubjectType.AI)
                .subjectId(SUBJECT_ID)
                .subjectName(SUBJECT_NAME)
                .suggestedAction(ApprovalAction.AGREE)
                .actionSummary("建议同意")
                .modelId(MODEL_ID)
                .inputSnapshot(INPUT_SNAPSHOT)
                .rawOutput(RAW_OUTPUT)
                .rationaleFacts(facts(DecisionRationaleFactKey.SCORE))
                .rationaleNarrative("依据：模型给出分值")
                .provider(PROVIDER)
                .chainStage(DecisionChainStage.PRIMARY)
                .degraded(Boolean.FALSE)
                .build();
        draft.setOutboundPayloadPresent(true);
        draft.setInboundPayloadPresent(true);
        return draft;
    }

    /**
     * 按政策未产出草稿（C 列）：{@code MODEL_DECLINED} 给出处组与 {@code modelId}（走过一次出站调用），
     * 其余四值全 {@code null}；类型化依据按分支取 {@code MISSING_INPUT} / {@code POLICY_RULE}。
     *
     * <p><b>两个方向的事实皆为 false</b>：矩阵对 C 列的 {@code inputSnapshot} 与 {@code rawOutput}
     * 两格写的是<b>必须 null</b>，故该列的载荷事实由矩阵钉死为「无载荷」（{@code MODEL_DECLINED}
     * 那次出站调用由出处组与 {@code modelId} 记录，不由载荷字段记录）。详见
     * {@code DecisionEvidenceWriterTest} 的类 javadoc 对该读法的披露。</p>
     *
     * @param reason 政策原因（五值）
     * @return 草稿
     */
    static DecisionEvidenceDraft policyDraft(final DecisionPolicyReason reason) {
        final boolean declined = reason == DecisionPolicyReason.MODEL_DECLINED;
        final DecisionRationaleFactKey key = reason == DecisionPolicyReason.NO_SOURCE_DECLARED
                ? DecisionRationaleFactKey.MISSING_INPUT
                : DecisionRationaleFactKey.POLICY_RULE;
        return DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.NO_SUGGESTION_BY_POLICY)
                .policyReason(reason)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .subjectType(declined ? DecisionSubjectType.AI : DecisionSubjectType.SYSTEM)
                .subjectId(SUBJECT_ID)
                .subjectName(SUBJECT_NAME)
                .modelId(declined ? MODEL_ID : null)
                .provider(declined ? PROVIDER : null)
                .chainStage(declined ? DecisionChainStage.PRIMARY : null)
                .degraded(declined ? Boolean.FALSE : null)
                .rationaleFacts(facts(key))
                .rationaleNarrative("依据：按政策未产出")
                .build();
    }

    /**
     * 失败草稿（D 列）：失败类别必填、建议面 / 依据面 / 自述位一律 {@code null}；方向事实按失败类别取
     * （超时与响应不可解析时出站载荷已离开本域）。
     *
     * @param kind 失败类别（七值）
     * @return 草稿
     */
    static DecisionEvidenceDraft failureDraft(final DecisionFailureKind kind) {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .failureKind(kind)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .subjectType(DecisionSubjectType.AI)
                .subjectId(SUBJECT_ID)
                .subjectName(SUBJECT_NAME)
                .provider(PROVIDER)
                .chainStage(DecisionChainStage.PRIMARY)
                .degraded(Boolean.FALSE)
                .build();
        if (kind == DecisionFailureKind.OUTBOUND_TIMEOUT
                || kind == DecisionFailureKind.RESPONSE_UNPARSEABLE) {
            draft.setOutboundPayloadPresent(true);
            draft.setInputSnapshot(INPUT_SNAPSHOT);
        }
        if (kind == DecisionFailureKind.RESPONSE_UNPARSEABLE) {
            draft.setInboundPayloadPresent(true);
            draft.setRawOutput(RAW_OUTPUT);
        }
        return draft;
    }

    /**
     * 单条类型化依据（值非空白；元素合法性是写入器的守卫对象）。
     *
     * @param key 键（闭集）
     * @return 依据事实列表
     */
    static List<DecisionRationaleFact> facts(final DecisionRationaleFactKey key) {
        return new ArrayList<>(Collections.singletonList(
                new DecisionRationaleFact(key, "证据面 fixture 的依据值")));
    }

    /** 四段有效数据源全量（自述位「有值」态） */
    static List<DecisionContextSource> allContextSources() {
        return new ArrayList<>(EnumSet.allOf(DecisionContextSource.class));
    }

    /**
     * 证据行文本的 UTF-8 字节数（护栏的计量口径；测试专用辅助，事实来源是写入器内同一算式）。
     *
     * @param value 文本
     * @return UTF-8 字节数
     */
    static int utf8Length(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 某类型的声明字段名集合（字段集对账用）。
     *
     * @param owner 类型
     * @return 声明字段名（保持声明序）
     */
    static Set<String> fieldNames(final Class<?> owner) {
        return Arrays.stream(owner.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 证据行的 JSON 入口（标记剥离走 core 的单一来源，不在测试里另行拼装）。
     *
     * <p>默认配置即读侧所要求的形态；本类型只读、写侧序列化由 {@code DecisionEvidenceWriter} 负责。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 证据行文本 → JSON。
     *
     * @param row 证据行整行文本（标记 + JSON）
     * @return JSON 根节点
     * @throws IOException 文本不可解析时抛出（样本必须合法）
     */
    static JsonNode evidenceJson(final String row) throws IOException {
        return MAPPER.readTree(DecisionEvidenceComment.stripMarker(row));
    }

    /**
     * JSON 节点 → 枚举取值（走读侧同一契约：字段值 = 枚举常量名原文；未知取值严格报错）。
     *
     * <p>用它代替在断言里直接调 {@code Enum#name()} —— 前者顺带证明了写入结果**可被契约类型反序列化**。</p>
     *
     * @param node JSON 节点
     * @param type 目标枚举类型
     * @param <E>  枚举类型
     * @return 枚举取值；节点为缺失 / null 时返回 {@code null}
     */
    static <E extends Enum<E>> E enumValue(final JsonNode node, final Class<E> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("证据行的枚举字段取值不属契约闭集：" + node, broken);
        }
    }
}
