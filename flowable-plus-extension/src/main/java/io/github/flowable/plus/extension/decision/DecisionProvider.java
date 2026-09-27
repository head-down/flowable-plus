package io.github.flowable.plus.extension.decision;

/**
 * 出站提供方缝（ADR-0042 第 7 节「Provider 缝」，协议 / 方言层）：<b>单一扩展点 Bean，整体替换</b>
 * （{@code @ConditionalOnMissingBean}），<b>不引 key / registry / 多 Provider 链</b>。
 *
 * <p><b>职责四面</b>：格式化请求（载荷 → 方言请求体）+ 凭据附着 + 发起 Transport 调用 + 解析响应；
 * <b>HTTP 状态 → {@code failureKind} 的映射也在本层完成</b>（401 / 403 ⇒
 * {@code OUTBOUND_CREDENTIAL_INVALID}；未取得响应 ⇒ {@code OUTBOUND_TIMEOUT}；其它非 2xx ⇒
 * {@code OUTBOUND_HTTP_ERROR}；解析违规 ⇒ {@code RESPONSE_UNPARSEABLE}）。</p>
 *
 * <p><b>替换代价（写进契约）</b>：应用替换 Provider 缝后，框架只保留<b>超时 / 总预算 / 幂等键复用 /
 * 可观测</b>；HTTP 语义的 {@code failureKind} 随之失效（退化为应用自报或 {@code INTERNAL_ERROR}），
 * <b>同时失去框架侧凭据位点</b>。</p>
 *
 * <p><b>{@code modelId} 的写侧守卫判定点在本层</b>：语义义务 = 「非直提 ∧ 本层从响应解析到模型标识 ⇒
 * 必填」。该判据只有 Provider 缝可知（管线拿到 {@code null} 无法区分「缝没解析到」与「缝漏填」），故
 * 管线侧<b>不做反推</b>；应用自建 Provider 的同一义务走<b>接口 javadoc（文档纪律）</b>。</p>
 */
public interface DecisionProvider {

    /**
     * 发起一次出站调用并解析响应。
     *
     * @param request 出站请求（决策目标 + 已过策略与 clamp 的载荷），不得为 null
     * @return 响应；本次调用失败时返回带 {@code failureKind} 的失败响应（<b>不抛异常</b> ——
     *         失败分类是契约面的一部分）
     */
    DecisionProviderResponse send(DecisionProviderRequest request);
}
