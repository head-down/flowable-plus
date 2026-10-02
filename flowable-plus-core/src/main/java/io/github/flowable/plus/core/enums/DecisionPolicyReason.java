package io.github.flowable.plus.core.enums;

/**
 * 「按政策未产出」（{@link DecisionOutcome#NO_SUGGESTION_BY_POLICY}）的原因枚举（ADR-0042 第 5 / 10 节）。
 *
 * <p>本枚举不进 {@link DecisionFailureKind}，也不计入错误指标 —— 「按政策未产出」是机制的结论，不是异常。</p>
 *
 * <p><b>出站二分是证据矩阵的判据</b>（ADR-0042 第 5 节）：仅 {@link #MODEL_DECLINED} 结构上走过一次出站调用
 * （其行出处组与 {@code modelId} 必填）；<b>其余各值均未发生出站调用</b> —— 无论原因由框架侧算出
 * （声明面 / 内容面 / 运行态），还是由 {@link #CONTEXT_UNAVAILABLE} / {@link #CREDENTIAL_UNAVAILABLE}
 * 这类 <b>Provider 缝本地短路</b>给出（其行出处组 / {@code modelId} / token 结构上必须为 null）。</p>
 *
 * <p>每个取值携带一句<b>可自诊的中文描述</b>（{@link #getDescription()}），作「按政策未产出」证据行的
 * 文本兜底依据<b>与类型化事实值</b>，使审计面无需回查枚举名即可读懂。</p>
 *
 * <p>「禁用」不在本枚举内：禁用 = 机制未激活，无记录可写。</p>
 */
public enum DecisionPolicyReason {

    /** 零 token（未声明任何数据源）⇒ 空装配 ⇒ 短路、不调 provider */
    NO_SOURCE_DECLARED("本环节未声明任何可用数据源，未调用模型"),

    /** 出域策略按设计拦下（{@code permitted = false}）—— 合规拒绝，不计错误 */
    POLICY_REJECTED("出域策略按设计拦下，未调用模型"),

    /** 生产者主动不产出（发生过一次出站调用）—— <b>本枚举唯一走过出站调用者</b> */
    MODEL_DECLINED("模型主动未给出建议"),

    /**
     * Provider 缝<b>本地短路</b>（未发起出站调用）：载荷不具备可用上下文 —— 节点确实申报了数据源，
     * 只是运行期载荷没带那个键（存量流程实例 / 未升级的流程）。语义最贴「缺输入」，
     * 故其类型化依据取 {@link DecisionRationaleFactKey#MISSING_INPUT}。
     */
    CONTEXT_UNAVAILABLE("本环节未取到可用审批上下文，未调用模型"),

    /**
     * Provider 缝<b>本地短路</b>（未发起出站调用）：凭据不可用 —— AI 服务未配置或凭据无效。
     * 与<b>走过出站调用</b>、由远端 401 / 403 产生的
     * {@link DecisionFailureKind#OUTBOUND_CREDENTIAL_INVALID}（失败列、计错误）不同：本值表示调用根本未发起。
     */
    CREDENTIAL_UNAVAILABLE("AI 服务未配置或凭据无效，未调用模型"),

    /** 运行暂停：机制已激活、运行期被暂止拉取 */
    SUSPENDED("本环节运行期被暂止，未调用模型"),

    /** 过载：专属有界线程池池满（承载面 = 结构化日志 + 独立计数，不落证据行） */
    OVERLOADED("承载线程池已满，未触发拉取"),
    ;

    /** 可自诊的中文描述（作证据行的文本兜底依据） */
    private final String description;

    DecisionPolicyReason(final String description) {
        this.description = description;
    }

    /**
     * 可自诊的中文描述。
     *
     * @return 中文描述，恒非空白
     */
    public String getDescription() {
        return description;
    }
}
