package io.github.flowable.plus.extension.decision;

import java.util.Objects;

/**
 * Provider 缝的请求（ADR-0042 第 7 节）：<b>决策目标 + 待格式化的决策载荷</b>。
 *
 * <p><b>请求体 = 载荷四段无信封</b>（默认方言）：顶层即 {@code processVariables} / {@code taskVariables} /
 * {@code taskMetadata} / {@code processInstanceMetadata}，<b>不携带</b>任务身份（{@code taskId} /
 * {@code nodeId}）、<b>不携带</b>幂等键、<b>不携带</b> prompt / 模板。</p>
 *
 * <p><b>幂等键不上线的理由</b>：拉面键 = 一元锚点 {@code taskId}（框架自造、不透明串），放进请求体等于把
 * <b>未在有效数据源声明内</b>的数据送出去 ⇒ 撞「无未留痕出域」。它只用于框架本地（同一次执行内推导一次、
 * 重试复用）。</p>
 *
 * <p><b>prompt 归方言层</b>：需要 prompt 装配的决策目标由应用<b>替换 Provider 缝</b>表达
 * —— 这正是「换厂商 SDK = 换这一层」的定位。</p>
 */
public final class DecisionProviderRequest {

    /** 本次出站调用的决策目标 */
    private final DecisionTarget target;

    /** 待格式化的决策载荷（已过出域策略与框架 clamp） */
    private final DecisionPayload payload;

    /**
     * 构造一次出站请求。
     *
     * @param target  决策目标，不得为 null
     * @param payload 已过策略与 clamp 的决策载荷，不得为 null
     */
    public DecisionProviderRequest(final DecisionTarget target, final DecisionPayload payload) {
        this.target = Objects.requireNonNull(target, "决策目标不得为 null");
        this.payload = Objects.requireNonNull(payload, "决策载荷不得为 null");
    }

    /**
     * 决策目标。
     *
     * @return 目标（其 {@code key()} 即本次出站调用的 {@code provider} 标识来源）
     */
    public DecisionTarget getTarget() {
        return target;
    }

    /**
     * 决策载荷。
     *
     * @return 载荷（四段定型外壳）
     */
    public DecisionPayload getPayload() {
        return payload;
    }
}
