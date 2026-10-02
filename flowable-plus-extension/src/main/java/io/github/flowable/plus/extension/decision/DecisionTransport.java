package io.github.flowable.plus.extension.decision;

/**
 * 出站传输缝（ADR-0042 第 7 节「Transport 缝」）：<b>公开 SPI</b>，<b>只报状态与字节</b> —— 纯网络 I/O 层。
 *
 * <p><b>两层缝的分工</b>：协议 / 方言格式化、凭据附着与响应解析归 {@link DecisionProvider}；
 * <b>HTTP 状态 → {@code failureKind} 的映射也在 Provider 缝内完成</b>。本层只管把请求送出去、把
 * HTTP 状态与响应字节原样交回。</p>
 *
 * <p><b>公开的用途之一是测试回放固定 fixture</b>：默认 Provider 暴露可注入的 Transport 缝
 * （{@link DefaultDecisionProvider} 的构造入参），无网测试以内建固定 fixture 替换本实现。</p>
 *
 * <p><b>「未能取得响应」的约定</b>：{@link DecisionTransportResponse#STATUS_UNREACHABLE}（{@code 0}）
 * 表示<b>没有取得任何 HTTP 响应</b>（连接 / 读取超时或 I/O 故障）。该取值由 Provider 缝映射为
 * {@link io.github.flowable.plus.core.enums.DecisionFailureKind#OUTBOUND_TIMEOUT}。</p>
 */
public interface DecisionTransport {

    /**
     * 发起一次出站调用。
     *
     * @param request 请求（地址 + 请求体字节 + 可选凭据），不得为 null
     * @return 响应（HTTP 状态 + 响应体字节）；未取得响应时返回
     *         {@link DecisionTransportResponse#unreachable()}
     */
    DecisionTransportResponse send(DecisionTransportRequest request);
}
