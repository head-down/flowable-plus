package io.github.flowable.plus.core.enums;

/**
 * 决策失败的类别枚举（ADR-0042 第 5 / 10 节）。
 *
 * <p><b>例外值登记</b>：{@link #INBOUND_PROCESSING_FAILED} 是本枚举七个取值中
 * <b>唯一</b>可在产出态（{@link DecisionOutcome#SUGGESTION_PRODUCED}）出现者 —— 它承载
 * 「入站加工失败的子失败标记 + 最小化占位」这一破例，取破例取值、不另设字段。双向守卫：</p>
 *
 * <ul>
 *   <li>{@code SUGGESTION_PRODUCED} ⇒ {@code failureKind == null ∨ failureKind == INBOUND_PROCESSING_FAILED}</li>
 *   <li>{@code failureKind == INBOUND_PROCESSING_FAILED} ⇒ {@code outcome == SUGGESTION_PRODUCED}</li>
 *   <li>{@code NO_SUGGESTION_BY_POLICY} ⇒ {@code failureKind == null}</li>
 * </ul>
 *
 * <p>计错判据 = {@code failureKind != null}（不挂在 {@code outcome} 上）。</p>
 */
public enum DecisionFailureKind {

    /** 出站调用超时（可重试） */
    OUTBOUND_TIMEOUT,

    /** 出站调用返回非 2xx（可重试） */
    OUTBOUND_HTTP_ERROR,

    /** 出站调用凭据失效 —— 只认 Transport 层返回的 401/403（不可重试） */
    OUTBOUND_CREDENTIAL_INVALID,

    /** 响应不可解析（可重试） */
    RESPONSE_UNPARSEABLE,

    /** 入站加工失败 —— 本枚举唯一的产出态例外值（不可重试） */
    INBOUND_PROCESSING_FAILED,

    /** 位点准入拒绝（不可重试） */
    SITE_ADMISSION_REJECTED,

    /** 内部错误：最后防御阻断式不出域，或写入侧超限拒绝写入 */
    INTERNAL_ERROR
}
