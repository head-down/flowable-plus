package io.github.flowable.plus.core.vo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionCompleteness;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.enums.DecisionSubjectType;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 决策证据面的测试专用 fixture（**非测试类**，core 测试树共用）。
 *
 * <p>提供三样东西：证据行的<b>最深合法样本</b>（Java 常量构造，不引金样本文件）、出域载荷的
 * 四段定壳样本，以及一份共用的 JSON 读写入口。</p>
 *
 * <p><b>样本为何是「直提 / 产出」列</b>：{@code SUGGESTION_PRODUCED} 的直提列要求出处组三者全为
 * {@code null}、{@code modelId} / {@code inputSnapshot} 为 {@code null}，{@code attestedDataSources}
 * 可有三态 —— 这是<b>唯一</b>能让 {@code attestedDataSources} 取到值的同时整条样本仍落在某一产出路径列上的组合；
 * 其它列取非空会与必填 / 可空矩阵直接冲突。</p>
 */
public final class DecisionEvidenceTestFixtures {

    /** 证据载荷的 schema 版本（样本取值） */
    private static final int SCHEMA_VERSION = 1;

    /**
     * 共用 JSON 入口。
     *
     * <p>核心模块单测不起 Spring 容器、无容器实例可注入，故按规范「确有特殊配置 / 无容器时须注释说明」在此
     * 自行构造一次；构造后<b>不再改配置</b>（配置完成的 {@code ObjectMapper} 对读写线程安全），默认配置即满足
     * （不做键名改名、不省略 null 键）。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionEvidenceTestFixtures() {
    }

    /** 序列化为 JSON 文本 */
    public static String toJson(final Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    /** 解析 JSON 文本 */
    public static JsonNode parse(final String json) throws IOException {
        return MAPPER.readTree(json);
    }

    /**
     * 最深合法证据行样本：每个字段都带得住的取值或显式 {@code null}，且整条样本满足
     * {@code outcome = SUGGESTION_PRODUCED} 的直提列。
     *
     * <p>读侧专属字段 {@code recordedTime} 在本样本里<b>恒 {@code null}</b> —— 它由读侧从评论行
     * {@code TIME_} 列填充，写侧不产出（故序列化时省略该键）。</p>
     */
    public static DecisionEvidenceVO maximalDirectSubmission() {
        final List<DecisionRationaleFact> facts = Arrays.stream(DecisionRationaleFactKey.values())
                .map(key -> new DecisionRationaleFact(key, "value-" + key.name()))
                .collect(Collectors.toList());
        return DecisionEvidenceVO.builder()
                .outcome(DecisionOutcome.SUGGESTION_PRODUCED)
                .schemaVersion(SCHEMA_VERSION)
                .idempotencyKey("idem-1")
                .suggestedAction(ApprovalAction.AGREE)
                .actionSummary("同意")
                .rawOutput("{\"action\":\"AGREE\"}")
                .rationaleFacts(facts)
                .rationaleNarrative("依据")
                .attestedDataSources(Arrays.asList(DecisionContextSource.values()))
                .subjectType(DecisionSubjectType.USER)
                .subjectId("subject-1")
                .subjectName("主体")
                .outboundCompleteness(DecisionCompleteness.NO_PAYLOAD)
                .inboundCompleteness(DecisionCompleteness.FULL)
                .build();
    }

    /**
     * 最深合法出域载荷样本：四段定壳 + 应用变量命名空间。
     *
     * <p>注意读侧只解析证据行本身的 JSON —— 该载荷在证据行里是 {@code inputSnapshot} 的一个<b>字符串</b>，
     * 其内部嵌套不参与读侧解析；此处一并度量是为了覆盖「若按载荷计深度」的另一种读法。</p>
     */
    public static Map<String, Object> deepestLegalPayload() {
        final Map<String, Object> namespace = new LinkedHashMap<>();
        namespace.put("amount", 1);
        final Map<String, Object> processVariables = new LinkedHashMap<>();
        processVariables.put("appNamespace", namespace);

        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("processVariables", processVariables);
        payload.put("taskVariables", new LinkedHashMap<String, Object>());
        payload.put("taskMetadata", new LinkedHashMap<String, Object>());
        payload.put("processInstanceMetadata", new LinkedHashMap<String, Object>());
        return payload;
    }

    /**
     * 用流式解析器量出 JSON 文本的最大嵌套深度（容器层数）。
     *
     * <p>借 Jackson 的词法 / 语法层计数，不自行扫描括号 —— 字符串与转义由解析器负责。</p>
     *
     * @param json JSON 文本
     * @return 最大嵌套深度（顶层为 1）
     * @throws IOException 文本不可解析时抛出（样本必须合法）
     */
    public static int maxNestingDepth(final String json) throws IOException {
        int depth = 0;
        int max = 0;
        try (JsonParser parser = MAPPER.getFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                final JsonToken token = parser.currentToken();
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                    max = Math.max(max, depth);
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
            }
        }
        return max;
    }
}
