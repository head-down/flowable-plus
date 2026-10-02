package io.github.flowable.plus.extension.decision;

import java.nio.charset.StandardCharsets;

/**
 * 出站传输缝的内建 stub（<b>测试专用类型，非测试类</b>；{@code docs/impl/0042-verification-landings.md}
 * §3.1）。默认 Provider 的<b>可注入 Transport 缝</b>实现：固定 fixture（<b>Java 常量</b>）+ 请求留痕。
 *
 * <p><b>recorded 不进 v1</b>：fixture 是惰性 Java 常量、<b>不落资源文件</b> —— 本模块的测试树
 * <b>不建</b> {@code src/test/resources}，故 recorded fixture 无栖身处（「recorded 不进 v1」的结构保证）。
 * 录制需要真实 key，恰是本机制要避免的 CI 前提。</p>
 *
 * <p><b>无网</b>：本类不触碰任何网络栈，只把固定字节交回 —— 验收句「无外网、无真实凭据」由此成立。</p>
 */
final class StubDecisionTransport implements DecisionTransport {

    /** 固定 fixture：模型产出建议（平铺、可解析；字段名与证据 VO 同源） */
    static final String PRODUCED_FIXTURE =
            "{\"suggestedAction\":\"AGREE\","
                    + "\"actionSummary\":\"stub 建议同意\","
                    + "\"rationaleFacts\":[{\"key\":\"SCORE\",\"value\":\"0.91\"}],"
                    + "\"rationaleNarrative\":\"stub 依据：分值高于阈值\","
                    + "\"modelId\":\"" + DecisionFixtures.MODEL_ID + "\","
                    + "\"chainStage\":\"PRIMARY\"}";

    /** 固定 fixture：模型主动不产出（显式位，非空字段反推） */
    static final String DECLINED_FIXTURE =
            "{\"declined\":true,"
                    + "\"modelId\":\"" + DecisionFixtures.MODEL_ID + "\","
                    + "\"chainStage\":\"PRIMARY\"}";

    /** 默认 HTTP 状态（成功） */
    private static final int STATUS_OK = 200;

    /** 一次响应的固定形态（状态 + 响应体原文） */
    private final int status;

    /** 响应体原文（固定 fixture） */
    private final String body;

    /** 最近一次收到的请求（无网回放的留痕面） */
    private DecisionTransportRequest lastRequest;

    /** 收到的请求次数（重试复用同键的断言面） */
    private int callCount;

    /**
     * 构造一个成功响应的 stub。
     *
     * @param body 固定响应体原文
     */
    StubDecisionTransport(final String body) {
        this(STATUS_OK, body);
    }

    /**
     * 构造一个固定响应的 stub。
     *
     * @param status 固定 HTTP 状态
     * @param body   固定响应体原文
     */
    StubDecisionTransport(final int status, final String body) {
        this.status = status;
        this.body = body;
    }

    @Override
    public DecisionTransportResponse send(final DecisionTransportRequest request) {
        this.lastRequest = request;
        this.callCount++;
        return new DecisionTransportResponse(status, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 最近一次收到的请求。
     *
     * @return 请求；未收到时为 null
     */
    DecisionTransportRequest getLastRequest() {
        return lastRequest;
    }

    /**
     * 最近一次收到的请求体文本（UTF-8）。
     *
     * @return 请求体文本；未收到请求时为 null
     */
    String lastRequestBodyText() {
        return lastRequest == null ? null : new String(lastRequest.getBody(), StandardCharsets.UTF_8);
    }

    /**
     * 收到的请求次数。
     *
     * @return 次数
     */
    int getCallCount() {
        return callCount;
    }
}
