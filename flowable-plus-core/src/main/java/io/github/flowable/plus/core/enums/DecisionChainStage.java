package io.github.flowable.plus.core.enums;

/**
 * 决策链路的阶段枚举（ADR-0042 第 5 节）。
 *
 * <p>属出处组（与 {@code provider} / {@code degraded} 同组）：三者全为 {@code null} ⇔ 直提。</p>
 */
public enum DecisionChainStage {

    /** 主链路 */
    PRIMARY,

    /** 降级链路 */
    FALLBACK,

    /** 规则链路 */
    RULE
}
