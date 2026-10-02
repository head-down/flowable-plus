package io.github.flowable.plus.extension.decision;

import java.util.Objects;

/**
 * Transport 缝的请求（ADR-0042 第 7 节）：<b>地址 + 请求体字节 + 可选凭据</b>。
 *
 * <p>请求体由 Provider 缝格式化（默认方言 = 决策载荷四段、<b>无外层信封</b>）；<b>凭据以不透明对象原样转交</b>
 * —— 本层不解释它、不读它的内容，只把它作为请求装饰载体继续往下带。</p>
 */
public final class DecisionTransportRequest {

    /** 出站调用地址 */
    private final String url;

    /** 请求体字节（Provider 缝格式化后的形态） */
    private final byte[] body;

    /** 凭据（可空 = 本次调用不需认证材料） */
    private final DecisionCredential credential;

    /**
     * 构造一次出站请求。
     *
     * @param url        出站调用地址，不得为 null
     * @param body       请求体字节，不得为 null
     * @param credential 凭据，可为 null（无需认证材料）
     */
    public DecisionTransportRequest(final String url, final byte[] body, final DecisionCredential credential) {
        this.url = Objects.requireNonNull(url, "出站调用地址不得为 null");
        this.body = Objects.requireNonNull(body, "请求体字节不得为 null").clone();
        this.credential = credential;
    }

    /**
     * 出站调用地址。
     *
     * @return 地址
     */
    public String getUrl() {
        return url;
    }

    /**
     * 请求体字节（防御性副本）。
     *
     * @return 请求体字节副本
     */
    public byte[] getBody() {
        return body.clone();
    }

    /**
     * 凭据（不透明）。
     *
     * @return 凭据；无需认证材料时为 null
     */
    public DecisionCredential getCredential() {
        return credential;
    }
}
