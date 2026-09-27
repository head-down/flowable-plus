package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Provider 缝的默认实现（ADR-0042 第 7 节「5.3 默认 Provider 的字段级线上契约」）：
 * <b>Apache HttpClient + Jackson</b> 的默认方言 —— 只服务「直接吃框架 JSON 的目标」。
 *
 * <p><b>已知边界</b>：默认方言<b>适用面窄</b>；真实厂商端点靠<b>替换 Provider 缝</b>。</p>
 *
 * <p><b>请求体无信封</b>：顶层即决策载荷四段。三态可判性由「段取 {@code null}」与「段取空集合」承担
 * （与 clamp 的计量口径共用同一份载荷序列化，不造第二份形态）。</p>
 *
 * <p><b>响应体平铺</b>：字段名与证据 VO 同源；{@code provider} <b>不进响应体</b>（由本类按决策目标 key 填）；
 * {@code declined} 与 {@code suggestedAction} 的互斥性、{@code chainStage} 的必填性在此判定，
 * 违规一律归 {@link DecisionFailureKind#RESPONSE_UNPARSEABLE}（<b>可重试</b>）。</p>
 *
 * <p><b>HTTP 状态 → 失败类别</b>（本层完成，Transport 层只报状态与字节）：未取得响应 ⇒
 * {@code OUTBOUND_TIMEOUT}；401 / 403 ⇒ {@code OUTBOUND_CREDENTIAL_INVALID}；其它非 2xx ⇒
 * {@code OUTBOUND_HTTP_ERROR}。</p>
 *
 * <p><b>凭据解析侧失败折叠</b>：解析器抛异常时归 {@code OUTBOUND_CREDENTIAL_INVALID}
 * （与 401 / 403 同一取值、同 severity、同重试语义，<b>不新增枚举</b>）；解析器返回 {@code null}
 * 是<b>合法</b>态（视为不需认证材料）。</p>
 *
 * <p><b>{@code modelId} 写侧守卫的判定点在本类</b>：解析到标识即如实带入，本类<b>不制造</b>标识
 * （证据面 A 列 / {@code MODEL_DECLINED} 行的条件必填由写侧矩阵承担）。</p>
 *
 * <p><b>无状态</b>：本类不持任何可变字段，可被多个决策线程并发调用。</p>
 */
public final class DefaultDecisionProvider implements DecisionProvider {

    /** 观测面唯一 logger（名取自 {@link DecisionMetrics#LOGGER_NAME} 单一来源） */
    private static final Logger LOG = LoggerFactory.getLogger(DecisionMetrics.LOGGER_NAME);

    /**
     * 请求体序列化器。
     *
     * <p><b>为何是自有实例、不是容器注入</b>：extension 子包<b>零 Spring 坐标</b>（ADR-0042 第 2 节定案 5 /
     * ADR-0029 结构性约束），注入容器实例在这条依赖方向上不可能；本类的序列化需求就是「载荷四段裸 JSON」，
     * 默认配置即够，无需自定义。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 2xx 区间下界（取协议库的既有常量，不自定义重复定义） */
    private static final int STATUS_SUCCESS_MIN = org.apache.http.HttpStatus.SC_OK;

    /** 2xx 区间上界 */
    private static final int STATUS_SUCCESS_MAX = 299;

    /** 未授权（凭据失效的两入口之一；取协议库的既有常量） */
    private static final int STATUS_UNAUTHORIZED = org.apache.http.HttpStatus.SC_UNAUTHORIZED;

    /** 禁止访问（凭据失效的两入口之二；取协议库的既有常量） */
    private static final int STATUS_FORBIDDEN = org.apache.http.HttpStatus.SC_FORBIDDEN;

    /** 响应字段：模型主动不产出的显式位 */
    private static final String FIELD_DECLINED = "declined";

    /** 响应字段：建议动作 */
    private static final String FIELD_SUGGESTED_ACTION = "suggestedAction";

    /** 响应字段：建议摘要 */
    private static final String FIELD_ACTION_SUMMARY = "actionSummary";

    /** 响应字段：类型化依据 */
    private static final String FIELD_RATIONALE_FACTS = "rationaleFacts";

    /** 响应字段：文本兜底依据 */
    private static final String FIELD_RATIONALE_NARRATIVE = "rationaleNarrative";

    /** 响应字段：模型标识 */
    private static final String FIELD_MODEL_ID = "modelId";

    /** 响应字段：链路阶段（必填） */
    private static final String FIELD_CHAIN_STAGE = "chainStage";

    /** 响应字段：是否降级 */
    private static final String FIELD_DEGRADED = "degraded";

    /** 响应字段：入向 token 用量 */
    private static final String FIELD_INPUT_TOKENS = "inputTokens";

    /** 响应字段：出向 token 用量 */
    private static final String FIELD_OUTPUT_TOKENS = "outputTokens";

    /** 类型化依据的元素字段名：键 */
    private static final String FIELD_FACT_KEY = "key";

    /** 类型化依据的元素字段名：值 */
    private static final String FIELD_FACT_VALUE = "value";

    /** 传输缝（默认为 HTTP 实现；测试以内建固定 fixture 替换） */
    private final DecisionTransport transport;

    /** 凭据解析 SPI；可空（Bean 缺席 = 合法装配态 = 不需认证材料） */
    private final DecisionCredentialResolver credentialResolver;

    /**
     * 构造默认 Provider。
     *
     * @param transport          传输缝实现，不得为 null
     * @param credentialResolver 凭据解析器；{@code null} 等价「不需认证材料」
     */
    public DefaultDecisionProvider(final DecisionTransport transport,
                                   final DecisionCredentialResolver credentialResolver) {
        this.transport = Objects.requireNonNull(transport, "传输缝实现不得为 null");
        this.credentialResolver = credentialResolver;
    }

    @Override
    public DecisionProviderResponse send(final DecisionProviderRequest request) {
        Objects.requireNonNull(request, "出站请求不得为 null");
        final DecisionTarget target = request.getTarget();
        final byte[] requestBody = serializePayload(request.getPayload());
        final CredentialResolution resolution = resolveCredential(target.key());
        if (resolution.failed) {
            return DecisionProviderResponse.failed(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID);
        }
        final DecisionTransportResponse transportResponse;
        try {
            transportResponse = transport.send(
                    new DecisionTransportRequest(target.url(), requestBody, resolution.credential));
        } catch (RuntimeException transportFailure) {
            // 有意的宽捕获：替换传输缝后其失败类型不受框架限定；异常 message 与堆栈不进观测面（禁载），
            // 故此处只记现场定位字段，并把它归为默认方言的内部错误。
            final String providerKey = target.key();
            LOG.warn("决策出站调用的传输缝抛出异常 ⇒ 归 INTERNAL_ERROR：provider={}", providerKey);
            return DecisionProviderResponse.failed(DecisionFailureKind.INTERNAL_ERROR);
        }
        return classify(transportResponse, target.key());
    }

    // ======================== 状态分类 ========================

    /**
     * HTTP 状态 → 失败类别 / 解析。
     *
     * @param transportResponse 传输缝响应
     * @param providerKey       决策目标 key（框架填的 {@code provider} 字段来源）
     * @return 响应
     */
    private static DecisionProviderResponse classify(final DecisionTransportResponse transportResponse,
                                                     final String providerKey) {
        final int status = transportResponse.getStatus();
        if (status == DecisionTransportResponse.STATUS_UNREACHABLE) {
            return DecisionProviderResponse.failed(DecisionFailureKind.OUTBOUND_TIMEOUT);
        }
        if (status == STATUS_UNAUTHORIZED || status == STATUS_FORBIDDEN) {
            return DecisionProviderResponse.failed(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID);
        }
        if (status < STATUS_SUCCESS_MIN || status > STATUS_SUCCESS_MAX) {
            return DecisionProviderResponse.failed(DecisionFailureKind.OUTBOUND_HTTP_ERROR);
        }
        return parseResponse(transportResponse.bodyText(), providerKey);
    }

    // ======================== 凭据解析 ========================

    /** 一次凭据解析的结果（把「失败已折叠」这一事实与「返回 null 合法」区分开）。 */
    private static final class CredentialResolution {

        /** 凭据；无需认证材料时为 null */
        private final DecisionCredential credential;

        /** 解析侧是否失败（失败已折叠进 {@code OUTBOUND_CREDENTIAL_INVALID}） */
        private final boolean failed;

        private CredentialResolution(final DecisionCredential credential, final boolean failed) {
            this.credential = credential;
            this.failed = failed;
        }
    }

    /**
     * 实时解析凭据（绝不缓存）；解析侧失败折叠进 {@code OUTBOUND_CREDENTIAL_INVALID}。
     *
     * @param targetKey 决策目标 key
     * @return 解析结果
     */
    private CredentialResolution resolveCredential(final String targetKey) {
        if (credentialResolver == null) {
            return new CredentialResolution(null, false);
        }
        try {
            return new CredentialResolution(credentialResolver.resolve(targetKey), false);
        } catch (RuntimeException resolutionFailure) {
            // 有意的宽捕获：解析侧任何失败都折叠进同一枚举（ADR-0042 第 7 节），不新增取值；
            // 异常 message 与堆栈不得进观测面，故只记现场定位字段。
            LOG.warn("决策凭据解析失败 ⇒ 折叠进 OUTBOUND_CREDENTIAL_INVALID：provider={}", targetKey);
            return new CredentialResolution(null, true);
        }
    }

    // ======================== 请求体 ========================

    /**
     * 载荷 → 请求体字节（四段无信封；未声明段序列化为 {@code null}、声明但空序列化为空集合
     * —— 三态由此可判）。序列化实现取 clamp 的包内共用入口（计量口径与本处同一份形态）。
     *
     * @param payload 决策载荷
     * @return 请求体字节（UTF-8）
     */
    private static byte[] serializePayload(final DecisionPayload payload) {
        return DecisionClamp.serialize(payload, MAPPER);
    }

    // ======================== 响应解析 ========================

    /**
     * 解析响应体（平铺）。
     *
     * <p><b>互斥性的读法（一处必须写明）</b>：判据只看 <b>{@code declined = true}</b> 这一个信号 ——
     * {@code declined = false} 是「未主动不产出」的<b>中性位</b>，可与 {@code suggestedAction} 并见
     * （否则任何「总是发 {@code declined}」的方言都会被判违规）。故「必居其一」读作
     * 「{@code declined = true} 与 {@code suggestedAction} 恰有其一」。</p>
     *
     * @param rawOutput   响应体原文裸串
     * @param providerKey 决策目标 key（框架填的 {@code provider} 字段来源）
     * @return 解析结果；任一契约违规归 {@code RESPONSE_UNPARSEABLE}
     */
    private static DecisionProviderResponse parseResponse(final String rawOutput, final String providerKey) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(rawOutput);
        } catch (IOException unparseable) {
            return DecisionProviderResponse.failed(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        if (root == null || !root.isObject()) {
            return DecisionProviderResponse.failed(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        final JsonNode declinedNode = root.get(FIELD_DECLINED);
        final JsonNode actionNode = root.get(FIELD_SUGGESTED_ACTION);
        if (present(declinedNode) && !declinedNode.isBoolean()) {
            return DecisionProviderResponse.failed(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        if (present(actionNode) && enumCell(actionNode, ApprovalAction.class) == null) {
            return DecisionProviderResponse.failed(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        final Boolean declined = present(declinedNode) ? Boolean.valueOf(declinedNode.booleanValue()) : null;
        final ApprovalAction suggestedAction = enumCell(actionNode, ApprovalAction.class);
        if (Boolean.TRUE.equals(declined) == (suggestedAction != null)) {
            // 皆缺（无 declined=true 且无建议动作）或皆在（declined=true 与 suggestedAction 并存）
            return DecisionProviderResponse.failed(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        final DecisionChainStage chainStage = enumCell(root.get(FIELD_CHAIN_STAGE), DecisionChainStage.class);
        if (chainStage == null) {
            // 缺失与未知取值同判（响应契约把 chainStage 定为必填）
            return DecisionProviderResponse.failed(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        final String actionSummary = textCell(root.get(FIELD_ACTION_SUMMARY));
        final List<DecisionRationaleFact> rationaleFacts = factsCell(root.get(FIELD_RATIONALE_FACTS));
        final String rationaleNarrative = textCell(root.get(FIELD_RATIONALE_NARRATIVE));
        if (suggestedAction != null
                && (StringUtils.isBlank(actionSummary) || rationaleFacts == null
                        || StringUtils.isBlank(rationaleNarrative))) {
            // 产出时建议面必填；缺任一即不合契约（写侧矩阵同样要求，此处前移到方言面判定）
            return DecisionProviderResponse.failed(DecisionFailureKind.RESPONSE_UNPARSEABLE);
        }
        return DecisionProviderResponse.builder()
                .declined(declined)
                .suggestedAction(suggestedAction)
                .actionSummary(actionSummary)
                .rationaleFacts(rationaleFacts)
                .rationaleNarrative(rationaleNarrative)
                .modelId(textCell(root.get(FIELD_MODEL_ID)))
                .provider(providerKey)
                .chainStage(chainStage)
                .degraded(Boolean.valueOf(booleanCell(root.get(FIELD_DEGRADED))))
                .rawOutput(rawOutput)
                .inputTokens(numberCell(root.get(FIELD_INPUT_TOKENS)))
                .outputTokens(numberCell(root.get(FIELD_OUTPUT_TOKENS)))
                .build();
    }

    /** JSON 节点是否「在场且有值」（键在场但为 {@code null} 视为缺席）。 */
    private static boolean present(final JsonNode node) {
        return node != null && !node.isNull();
    }

    /** 文本单元格：非文本或空白统一收敛为 {@code null}。 */
    private static String textCell(final JsonNode node) {
        return present(node) && node.isTextual() ? node.textValue() : null;
    }

    /** 布尔单元格：非布尔收敛为 {@code false}（{@code degraded} 是可空位，未自报即未降级）。 */
    private static boolean booleanCell(final JsonNode node) {
        return present(node) && node.isBoolean() && node.booleanValue();
    }

    /** 数值单元格：非数值保持 {@code null}（<b>缺失不猜</b>）。 */
    private static Long numberCell(final JsonNode node) {
        return present(node) && node.isNumber() ? Long.valueOf(node.longValue()) : null;
    }

    /**
     * 枚举单元格：按<b>枚举常量名原文</b>匹配（大小写敏感、不引映射表），未知取值回 {@code null}。
     *
     * <p>遍历取 {@code EnumSet.allOf(...)} 而非 {@code values()}（不复制数组）；单个枚举内常量名唯一，
     * 故取首个命中即是唯一命中（{@code findFirst} 的理由）。</p>
     */
    private static <E extends Enum<E>> E enumCell(final JsonNode node, final Class<E> type) {
        final String constantName = textCell(node);
        if (StringUtils.isEmpty(constantName)) {
            return null;
        }
        return EnumSet.allOf(type).stream()
                .filter(constant -> StringUtils.equals(constant.name(), constantName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 类型化依据单元格：键须属闭集；任一元素非法即整格回 {@code null}（由调用方判违规）。
     */
    private static List<DecisionRationaleFact> factsCell(final JsonNode node) {
        if (!present(node) || !node.isArray()) {
            return null;
        }
        final List<DecisionRationaleFact> facts = new ArrayList<>();
        for (final JsonNode element : node) {
            if (!element.isObject()) {
                return null;
            }
            final DecisionRationaleFactKey key = enumCell(element.get(FIELD_FACT_KEY), DecisionRationaleFactKey.class);
            if (key == null) {
                return null;
            }
            facts.add(new DecisionRationaleFact(key, textCell(element.get(FIELD_FACT_VALUE))));
        }
        return facts;
    }
}
