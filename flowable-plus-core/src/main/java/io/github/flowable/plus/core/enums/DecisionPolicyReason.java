package io.github.flowable.plus.core.enums;

/**
 * 「按政策未产出」（{@link DecisionOutcome#NO_SUGGESTION_BY_POLICY}）的原因枚举（ADR-0042 第 5 / 10 节）。
 *
 * <p>本枚举不进 {@link DecisionFailureKind}，也不计入错误指标 —— 「按政策未产出」是机制的结论，不是异常。</p>
 *
 * <p>「禁用」不在本枚举内：禁用 = 机制未激活，无记录可写。</p>
 */
public enum DecisionPolicyReason {

    /** 零 token（未声明任何数据源）⇒ 空装配 ⇒ 短路、不调 provider */
    NO_SOURCE_DECLARED,

    /** 出域策略按设计拦下（{@code permitted = false}）—— 合规拒绝，不计错误 */
    POLICY_REJECTED,

    /** 生产者主动不产出（发生过一次出站调用） */
    MODEL_DECLINED,

    /** 运行暂停：机制已激活、运行期被暂止拉取 */
    SUSPENDED,

    /** 过载：专属有界线程池池满（承载面 = 结构化日志 + 独立计数，不落证据行） */
    OVERLOADED
}
