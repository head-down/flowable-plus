package io.github.flowable.plus.core.enums;

/**
 * 决策载荷的完整度枚举（ADR-0042 第 5 / 6 节）。
 *
 * <p>四值。取值域<b>按方向不同</b>：{@code outboundCompleteness} 只取 {@link #FULL} / {@link #PARTIAL} /
 * {@link #NO_PAYLOAD}（<b>出域方向不取 {@link #RESTRICTED}</b> —— {@code inputSnapshot} 是「离开本域的到底是什么」
 * 的唯一证据，允许策略屏蔽它等于给 I2「无未留痕出域」开后门）；{@code inboundCompleteness} 四值全可达。</p>
 *
 * <p>互锁（写入侧契约）：每方向 {@code PARTIAL ⇔ (redacted ∨ truncated)}；
 * {@code (FULL ∨ NO_PAYLOAD) ⇒ (¬redacted ∧ ¬truncated)}；
 * {@code NO_PAYLOAD ⇔ 该方向载荷字段为空 ∧ ¬redacted ∧ ¬truncated}；{@code FULL / PARTIAL ⇒ 该方向载荷字段非空}。</p>
 */
public enum DecisionCompleteness {

    /** 无任何加工 */
    FULL,

    /** 发生任一加工（脱敏 / 摘要 / 截断） */
    PARTIAL,

    /** 该方向本无载荷 */
    NO_PAYLOAD,

    /** 有载荷但按政策不可落盘（仅入站方向可达；落原因、不计错误指标） */
    RESTRICTED
}
