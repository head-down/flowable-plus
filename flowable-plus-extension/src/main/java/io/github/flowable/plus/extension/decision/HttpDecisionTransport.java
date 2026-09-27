package io.github.flowable.plus.extension.decision;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

/**
 * 出站传输缝的默认实现（ADR-0042 第 7 节）：基于 Apache HttpClient 的纯 I/O 层。
 *
 * <p><b>职责边界</b>：只把请求送出去、把 HTTP 状态与响应字节原样交回 —— <b>不解释响应体、不做失败分类</b>
 * （分类归 Provider 缝）。</p>
 *
 * <p><b>超时</b>：每次尝试<b>一组</b>出站超时（连接 / 读取），构造期注入；本类不做「只许调低」的收口
 * —— 硬上限由装配面（starter）承载，本类只按注入值生效。</p>
 *
 * <p><b>未取得响应</b>：连接 / 读取超时与其它 I/O 故障一律归
 * {@link DecisionTransportResponse#STATUS_UNREACHABLE}，由 Provider 缝映射为
 * {@link io.github.flowable.plus.core.enums.DecisionFailureKind#OUTBOUND_TIMEOUT}（可重试、WARN）
 * —— 二者在 <b>SPI 的可见面上不可分</b>（响应只有状态与字节），如实登记为默认方言的既定口径。</p>
 *
 * <p><b>凭据附着</b>：凭据以不透明对象传入，由本类（与 {@link DecisionCredential} 同包的框架自有实现）
 * 完成实际的请求装饰 —— 见 {@link DecisionCredential} 的「包内转交点」一节。</p>
 *
 * <p><b>资源</b>：内部持有一个 {@code CloseableHttpClient}（连接池），需由装配面在销毁时关闭。</p>
 */
public final class HttpDecisionTransport implements DecisionTransport, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionMetrics.LOGGER_NAME);

    /** 请求体内容类型（默认方言固定为 JSON） */
    private static final String CONTENT_TYPE = "application/json";

    /** 凭据请求头名（默认方言的附着形态；替换 Transport 缝即可改换） */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** 凭据请求头前缀（Bearer 方案） */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 底层客户端（连接池由它管） */
    private final CloseableHttpClient httpClient;

    /** 每次尝试的连接超时（毫秒） */
    private final int connectTimeoutMs;

    /** 每次尝试的读取超时（毫秒） */
    private final int readTimeoutMs;

    /**
     * 构造默认传输实现。
     *
     * @param connectTimeoutMs 每次尝试的连接超时（毫秒），正数
     * @param readTimeoutMs    每次尝试的读取超时（毫秒），正数
     */
    public HttpDecisionTransport(final int connectTimeoutMs, final int readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("出站超时必须为正数（毫秒）：connect=" + connectTimeoutMs
                    + "，read=" + readTimeoutMs);
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.httpClient = HttpClients.custom().build();
    }

    @Override
    public DecisionTransportResponse send(final DecisionTransportRequest request) {
        final HttpPost httpPost = new HttpPost(request.getUrl());
        httpPost.setHeader("Content-Type", CONTENT_TYPE);
        httpPost.setConfig(RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .build());
        httpPost.setEntity(new ByteArrayEntity(request.getBody()));
        decorateWithCredential(httpPost, request.getCredential());
        try (CloseableHttpResponse httpResponse = httpClient.execute(httpPost)) {
            final int status = httpResponse.getStatusLine().getStatusCode();
            final byte[] body = httpResponse.getEntity() == null
                    ? new byte[0]
                    : EntityUtils.toByteArray(httpResponse.getEntity());
            return new DecisionTransportResponse(status, body);
        } catch (IOException unreachable) {
            // 有意的宽捕获（不是吞异常）：SPI 的可见面只有「状态 + 字节」，故 I/O 故障与超时在此
            // 收敛为同一约定值 STATUS_UNREACHABLE，由 Provider 缝映射为 OUTBOUND_TIMEOUT。
            // 异常 message 与堆栈不进观测面（禁载），故此处不记录异常内容，只记现场定位字段。
            final String requestedUrl = request.getUrl();
            LOG.warn("决策出站调用未取得响应（连接 / 读取超时或 I/O 故障）⇒ 归 OUTBOUND_TIMEOUT：url={}",
                    requestedUrl);
            return DecisionTransportResponse.unreachable();
        }
    }

    /**
     * 凭据附着（发生在装配与 clamp <b>之后</b>，故凭据从不是载荷的一部分）。
     *
     * @param httpPost   请求
     * @param credential 凭据，可为 null（无需认证材料）
     */
    private static void decorateWithCredential(final HttpPost httpPost, final DecisionCredential credential) {
        if (credential == null) {
            return;
        }
        httpPost.setHeader(AUTHORIZATION_HEADER, BEARER_PREFIX + credential.material());
    }

    /**
     * 关闭底层连接池。
     *
     * @throws IOException 关闭失败
     */
    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
