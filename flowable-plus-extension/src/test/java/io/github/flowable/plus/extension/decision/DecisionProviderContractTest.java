package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E15 —— 出站响应契约与请求体契约（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E15}）。
 *
 * <p><b>承哪些推入项</b>：{@code #36} 出站响应契约<b>互斥性</b>（{@code declined} 与 {@code suggestedAction}
 * 必居其一；皆缺或皆在 ⇒ {@code RESPONSE_UNPARSEABLE}；{@code chainStage} 缺失 ⇒ 同判）+ <b>幂等键不上线</b>
 * （请求体不含任何身份字段）+ <b>跨硬域同源</b>（响应契约字段与证据 VO 同名字段两端同名同源）+
 * 默认方言四段<b>无信封</b> + <b>凭据从不是载荷的一部分</b>。</p>
 *
 * <p><b>互斥性的读法（与实现同源）</b>：判据只看 {@code declined = true} 这一个信号；
 * {@code declined = false} 是「未主动不产出」的中性位，可与 {@code suggestedAction} 并见 —— 否则任何
 * 「总是发 {@code declined}」的方言都会被判违规。本类把这条读法钉成可判事实（正例与反例各一格）。</p>
 */
class DecisionProviderContractTest {

    /** 请求体的顶层字段 = 载荷四段（无信封） */
    private static final Set<String> PAYLOAD_SEGMENT_NAMES = new LinkedHashSet<>(Arrays.asList(
            "processVariables", "taskVariables", "taskMetadata", "processInstanceMetadata"));

    /** 身份类字段名（幂等键不上线：请求体不得出现它们） */
    private static final List<String> IDENTITY_FIELD_NAMES =
            Arrays.asList("taskId", "nodeId", "idempotencyKey", "processInstanceId");

    /** 响应契约里与证据 VO 同源的字段名（跨硬域同名的靶心） */
    private static final List<String> SHARED_FIELD_NAMES = Arrays.asList("suggestedAction", "actionSummary",
            "rationaleFacts", "rationaleNarrative", "modelId", "provider", "chainStage", "degraded", "rawOutput",
            "failureKind");

    /** 机制自有的缝字段（非证据面字段：显式不产出位 / 用量） */
    private static final List<String> SEAM_ONLY_FIELD_NAMES =
            Arrays.asList("declined", "inputTokens", "outputTokens");

    /** 凭据材料哨兵（只出现在凭据侧） */
    private static final String CREDENTIAL_SENTINEL = "CREDENTIAL-MATERIAL-MUST-NOT-LEAK-2";

    /** 读 JSON 的只读映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认方言：模型产出建议（含 chainStage 与全部产出面必填字段） */
    private static final String PRODUCED_BODY = "{\"suggestedAction\":\"AGREE\",\"actionSummary\":\"建议同意\","
            + "\"rationaleFacts\":[{\"key\":\"SCORE\",\"value\":\"0.9\"}],"
            + "\"rationaleNarrative\":\"依据：分值高于阈值\","
            + "\"modelId\":\"" + DecisionFixtures.MODEL_ID + "\",\"chainStage\":\"PRIMARY\"}";

    @Test
    @DisplayName("declined 与 suggestedAction 必居其一：皆在 / 皆缺 ⇒ 违约，declined=false 是中性位")
    void declinedAndSuggestedActionAreMutuallyExclusive() {
        assertThat(send("{\"declined\":true,\"suggestedAction\":\"AGREE\",\"chainStage\":\"PRIMARY\"}")
                .getFailureKind())
                .as("皆在 ⇒ 互斥性被违反")
                .isEqualTo(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        assertThat(send("{\"chainStage\":\"PRIMARY\"}").getFailureKind())
                .as("皆缺 ⇒ 互斥性被违反（不得由空字段反推）")
                .isEqualTo(DecisionFailureKind.RESPONSE_UNPARSEABLE);

        final DecisionProviderResponse declined = send("{\"declined\":true,\"chainStage\":\"PRIMARY\"}");
        assertThat(declined.getFailureKind()).as("declined = true 单独在场是合法的不产出位").isNull();
        assertThat(declined.getDeclined()).isTrue();
        assertThat(declined.getSuggestedAction()).as("不产出位下不得有建议动作").isNull();

        final DecisionProviderResponse produced = send("{\"declined\":false,\"suggestedAction\":\"AGREE\","
                + "\"actionSummary\":\"建议同意\",\"rationaleFacts\":[],"
                + "\"rationaleNarrative\":\"依据：中性位下仍可产出\",\"chainStage\":\"PRIMARY\"}");
        assertThat(produced.getFailureKind()).as("declined = false 是中性位，可与建议动作并见").isNull();
        assertThat(produced.getSuggestedAction()).isEqualTo(ApprovalAction.AGREE);
    }

    @Test
    @DisplayName("三处违约同判 RESPONSE_UNPARSEABLE：皆在 / 皆缺 / chainStage 缺失")
    void unparseableJudgementMatchesAllThreeViolations() {
        final List<String> violations = Arrays.asList(
                "{\"declined\":true,\"suggestedAction\":\"AGREE\",\"chainStage\":\"PRIMARY\"}",
                "{\"chainStage\":\"PRIMARY\"}",
                "{\"suggestedAction\":\"AGREE\",\"actionSummary\":\"建议同意\","
                        + "\"rationaleFacts\":[],\"rationaleNarrative\":\"依据\"}");
        for (final String violation : violations) {
            assertThat(send(violation).getFailureKind())
                    .as("违约体同判：%s", violation)
                    .isEqualTo(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        assertThat(send("{\"suggestedAction\":\"AGREE\",\"actionSummary\":\"建议同意\","
                + "\"rationaleFacts\":[],\"rationaleNarrative\":\"依据\",\"chainStage\":\"FALLBACK\"}")
                .getFailureKind())
                .as("防空转：同形但合法的体不得被判违约")
                .isNull();
    }

    @Test
    @DisplayName("幂等键不上线：请求体顶层只有载荷四段，未声明的身份不出现")
    void requestBodyCarriesNoIdentityField() throws IOException {
        // 有效数据源声明只含流程变量与实例元数据 ⇒ 任务身份（taskId / nodeId）不在声明面内
        final StubDecisionTransport transport = driveWithPayload(sources(DecisionContextSource.PROCESS_VARIABLES,
                DecisionContextSource.PROCESS_INSTANCE_METADATA));

        final JsonNode requestBody = MAPPER.readTree(transport.lastRequestBodyText());
        assertThat(keysOf(requestBody))
                .as("请求体顶层 = 载荷四段（无信封、无身份字段、无幂等键）")
                .containsExactlyElementsOf(PAYLOAD_SEGMENT_NAMES);
        assertThat(transport.lastRequestBodyText())
                .as("未在有效数据源声明内的身份字段一律不出现")
                .doesNotContain(DecisionFixtures.TASK_ID)
                .doesNotContain(DecisionFixtures.NODE_ID)
                .doesNotContain(DecisionFixtures.IDEMPOTENCY_KEY);
        assertThat(keysOf(requestBody))
                .as("任一身份字段名都不得成为请求体的键")
                .doesNotContainAnyElementsOf(IDENTITY_FIELD_NAMES);
    }

    @Test
    @DisplayName("响应契约字段与证据 VO 同名同源：跨硬域四字段逐一同名，缝字段有据可查")
    void responseFieldNamesMatchEvidenceVOExactly() {
        final Set<String> evidenceFields = DecisionFixtures.fieldNames(DecisionEvidenceVO.class);
        assertThat(evidenceFields)
                .as("跨硬域同源：响应契约的证据面字段必须与证据 VO 逐字同名")
                .containsAll(SHARED_FIELD_NAMES);
        final Set<String> responseFields = DecisionFixtures.fieldNames(DecisionProviderResponse.class);
        assertThat(responseFields)
                .as("响应契约 = 证据面同源字段 + 机制自有的缝字段（无第三个来源）")
                .containsExactlyInAnyOrderElementsOf(
                        union(SHARED_FIELD_NAMES, SEAM_ONLY_FIELD_NAMES));
        assertThat(SEAM_ONLY_FIELD_NAMES)
                .as("缝字段自身不得是证据 VO 字段（显式不产出位 / 用量只住缝内）")
                .noneMatch(evidenceFields::contains);
    }

    @Test
    @DisplayName("载荷四段无信封：顶层即四段、未声明段取 null、声明但空取空集合")
    void payloadCarriesNoEnvelope() throws IOException {
        final StubDecisionTransport transport = driveWithPayload(
                sources(DecisionContextSource.PROCESS_VARIABLES, DecisionContextSource.TASK_METADATA));

        final JsonNode requestBody = MAPPER.readTree(transport.lastRequestBodyText());
        assertThat(keysOf(requestBody))
                .as("顶层即四段：不得有 payload / envelope / request 一类外层壳")
                .containsExactlyElementsOf(PAYLOAD_SEGMENT_NAMES);
        assertThat(requestBody.get("processVariables").isObject())
                .as("声明但空 ⇒ 空集合（与「未声明」两态可分）")
                .isTrue();
        assertThat(requestBody.get("taskVariables").isNull())
                .as("未声明 ⇒ 取 null")
                .isTrue();
        assertThat(requestBody.get("taskMetadata").isObject())
                .as("声明且取值 ⇒ 定型小对象在场")
                .isTrue();
    }

    @Test
    @DisplayName("凭据从不是载荷的一部分：材料只经 Transport 缝原样转交，不进请求体与响应字段")
    void credentialIsNeverPartOfPayload() {
        final StubDecisionTransport transport = new StubDecisionTransport(StubDecisionTransport.PRODUCED_FIXTURE);
        final DecisionProvider provider = new DefaultDecisionProvider(transport,
                targetKey -> DecisionCredential.of(CREDENTIAL_SENTINEL));

        final DecisionProviderResponse response = provider.send(request(null));
        assertThat(response.getFailureKind()).as("凭据解析成功 ⇒ 调用照常").isNull();
        assertThat(transport.lastRequestBodyText())
                .as("凭据附着发生在装配与 clamp 之后 ⇒ 材料绝不进载荷")
                .doesNotContain(CREDENTIAL_SENTINEL);
        assertThat(transport.getLastRequest().getCredential())
                .as("凭据作为请求装饰载体原样转交 Transport 缝")
                .isNotNull();
        assertThat(response.getRawOutput())
                .as("响应面字段不得夹带材料")
                .doesNotContain(CREDENTIAL_SENTINEL);
    }

    // ======================== 驱动辅助 ========================

    /** 驱动一次出站调用（固定产出响应），交回响应。 */
    private static DecisionProviderResponse send(final String responseBody) {
        final StubDecisionTransport transport = new StubDecisionTransport(responseBody);
        return new DefaultDecisionProvider(transport, targetKey -> null).send(request(null));
    }

    /**
     * 按「有效数据源声明」装配载荷（未声明段取 null、声明但空取空集合、元数据段取定型小对象），
     * 驱动一次出站调用并交回留痕的 stub。
     */
    private static StubDecisionTransport driveWithPayload(final Set<DecisionContextSource> sources) {
        final StubDecisionTransport transport = new StubDecisionTransport(StubDecisionTransport.PRODUCED_FIXTURE);
        new DefaultDecisionProvider(transport, targetKey -> null).send(request(sources));
        return transport;
    }

    /** 构造出站请求（按声明面装配四段）。 */
    private static DecisionProviderRequest request(final Set<DecisionContextSource> sources) {
        final Set<DecisionContextSource> declared = sources == null
                ? sources(DecisionContextSource.PROCESS_VARIABLES, DecisionContextSource.TASK_VARIABLES,
                        DecisionContextSource.TASK_METADATA, DecisionContextSource.PROCESS_INSTANCE_METADATA)
                : sources;
        final DecisionPayload payload = new DecisionPayload(
                declared.contains(DecisionContextSource.PROCESS_VARIABLES)
                        ? Collections.singletonMap("amount", 1) : null,
                declared.contains(DecisionContextSource.TASK_VARIABLES) ? Collections.emptyMap() : null,
                declared.contains(DecisionContextSource.TASK_METADATA)
                        ? new TaskMetadata(DecisionFixtures.TASK_ID, "任务", DecisionFixtures.NODE_ID, null, null)
                        : null,
                declared.contains(DecisionContextSource.PROCESS_INSTANCE_METADATA)
                        ? new ProcessInstanceMetadata(DecisionFixtures.PROCESS_INSTANCE_ID, "definition-key-static",
                                null, null, null) : null);
        return new DecisionProviderRequest(new FixedTarget(), payload);
    }

    /** 数据源集（顺序无关）。 */
    private static Set<DecisionContextSource> sources(final DecisionContextSource... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    /** JSON 对象的键集（保持在场序）。 */
    private static Set<String> keysOf(final JsonNode node) {
        final Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** 两个名字集合的并集。 */
    private static Set<String> union(final List<String> first, final List<String> second) {
        final Set<String> union = new LinkedHashSet<>(first);
        union.addAll(second);
        return union;
    }

    /** 固定决策目标（key + 接入信息）。 */
    private static final class FixedTarget implements DecisionTarget {

        @Override
        public String key() {
            return DecisionFixtures.PROVIDER;
        }

        @Override
        public String url() {
            return "https://decision.example.invalid/v1/suggest";
        }
    }
}
