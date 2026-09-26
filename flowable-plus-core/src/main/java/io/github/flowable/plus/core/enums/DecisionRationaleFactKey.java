package io.github.flowable.plus.core.enums;

/**
 * 决策依据事实的键集（ADR-0042 第 5 节）。
 *
 * <p>闭集枚举，<b>扩展走框架发版</b>，生产者不得自定义键。</p>
 *
 * <p>内容规则按产出路径各异：直提列须 <b>≥1 条 {@link #BASIS_CODE}</b>；
 * 按政策未产出列按 {@code policyReason} 分支 —— {@code NO_SOURCE_DECLARED} ⇒ ≥1 条 {@link #MISSING_INPUT}，
 * 其余四值 ⇒ ≥1 条 {@link #POLICY_RULE}。</p>
 */
public enum DecisionRationaleFactKey {

    /** 依据码 —— 直提列的必要键 */
    BASIS_CODE,

    /** 命中的政策规则 —— 按政策未产出列的必要键（除 NO_SOURCE_DECLARED 外） */
    POLICY_RULE,

    /** 缺失的输入 —— 空装配分支的必要键 */
    MISSING_INPUT,

    /** 分值 */
    SCORE,

    /** 出处引用 */
    SOURCE_REF
}
