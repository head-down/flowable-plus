package io.github.flowable.plus.extension.decision;

import java.util.Locale;

/**
 * 决策可观测面的信号契约（ADR-0042 第 10 节「可观测面」）。
 *
 * <p><b>单一常量类，只出常量、不出类型</b> —— 机制侧任何信号名 / 维度键 / 闭集 tag value 一律
 * 引用本类成员，<b>禁裸字面量</b>。九信号与十维度键的清单、格式与闭集取值是契约，改动即契约变更。</p>
 *
 * <p><b>格式三则</b>：① 信号<b>全小写、点分隔</b>，统一带 {@code flowable.plus.decision.} 前缀；
 * ② 信号常量名取<b>全名 SCREAMING_SNAKE</b>，<b>不带</b> {@code METRIC} / {@code COUNTER} 一类后缀；
 * ③ 维度键的值取<b>冻结字段名的小驼峰原文</b>（与证据面对齐，端到端可核对）。</p>
 *
 * <p><b>维度受控</b>：<b>不</b>做 {@code instanceId} / {@code decisionId} 维度。故
 * {@code processInstanceId} <b>只在日志里可记、在指标里不可</b> —— 这条不对称是有意的。</p>
 *
 * <p><b>闭集 tag value</b>：{@code direction} 与 {@code cause} 取本类具名常量；
 * {@code reason} 与 {@code contextSource} 取<b>枚举常量名的小写蛇形</b>，由
 * {@link #tagValue(Enum)} 单点派生（同一换算不得在两处各写一遍）。</p>
 */
public final class DecisionMetrics {

    // ======================== 信号名（9） ========================

    /** 失败计数：只收「未产出 / 失败」，计错判据 = {@code failureKind != null} */
    public static final String FLOWABLE_PLUS_DECISION_FAILURE = "flowable.plus.decision.failure";

    /** 按政策未产出计数：<b>独立计数、绝不计入错误率</b> */
    public static final String FLOWABLE_PLUS_DECISION_NO_SUGGESTION_BY_POLICY =
            "flowable.plus.decision.no.suggestion.by.policy";

    /** 重放计数：<b>独立运维信号、不算错误</b>；由写入点判别命中时直发、不经观测构造点 */
    public static final String FLOWABLE_PLUS_DECISION_REPLAY = "flowable.plus.decision.replay";

    /** 装配器丢弃的数据源计数（与 clamp 丢弃分列，二者不得混成一条曲线） */
    public static final String FLOWABLE_PLUS_DECISION_CONTEXT_SOURCE_DROPPED =
            "flowable.plus.decision.context.source.dropped";

    /** 单次决策尝试的延迟 */
    public static final String FLOWABLE_PLUS_DECISION_LATENCY = "flowable.plus.decision.latency";

    /** token 用量计数：{@code input} / {@code output} <b>分记</b>（维度 {@code direction}） */
    public static final String FLOWABLE_PLUS_DECISION_TOKENS = "flowable.plus.decision.tokens";

    /** 用量缺失计数：<b>不设 {@code unknown} 标记值</b>，可见性出口 = 本独立计数 */
    public static final String FLOWABLE_PLUS_DECISION_TOKENS_USAGE_MISSING =
            "flowable.plus.decision.tokens.usage.missing";

    /** 写入期降级计数（锚点失效 / 实例已结束；不成行、只降级） */
    public static final String FLOWABLE_PLUS_DECISION_WRITE_DEGRADED = "flowable.plus.decision.write.degraded";

    /** 位点准入拒绝计数 */
    public static final String FLOWABLE_PLUS_DECISION_SUBMISSION_REJECTED =
            "flowable.plus.decision.submission.rejected";

    // ======================== 维度键（10） ========================

    /** 维度键：失败类别 */
    public static final String FAILURE_KIND = "failureKind";

    /** 维度键：严重度 */
    public static final String SEVERITY = "severity";

    /** 维度键：节点标识 */
    public static final String NODE_ID = "nodeId";

    /** 维度键：主体类型 */
    public static final String SUBJECT_TYPE = "subjectType";

    /** 维度键：政策原因 */
    public static final String POLICY_REASON = "policyReason";

    /** 维度键：token 方向（取值见 {@link #DIRECTION_INPUT} / {@link #DIRECTION_OUTPUT}） */
    public static final String DIRECTION = "direction";

    /** 维度键：模型标识 */
    public static final String MODEL_ID = "modelId";

    /** 维度键：写入降级原因（取值见 {@link #CAUSE_ANCHOR_LOST} / {@link #CAUSE_INSTANCE_ENDED}） */
    public static final String CAUSE = "cause";

    /** 维度键：准入失败原因（取 {@link SuggestionAdmissionReason} 常量名的小写蛇形） */
    public static final String REASON = "reason";

    /** 维度键：数据源声明（取 {@link io.github.flowable.plus.core.enums.DecisionContextSource} 常量名的小写蛇形） */
    public static final String CONTEXT_SOURCE = "contextSource";

    // ======================== 闭集 tag value ========================

    /** {@code direction} 取值：入站方向 */
    public static final String DIRECTION_INPUT = "input";

    /** {@code direction} 取值：出站方向 */
    public static final String DIRECTION_OUTPUT = "output";

    /** {@code cause} 取值：锚点失效 */
    public static final String CAUSE_ANCHOR_LOST = "anchor_lost";

    /** {@code cause} 取值：实例已结束 */
    public static final String CAUSE_INSTANCE_ENDED = "instance_ended";

    // ======================== logger ========================

    /** 观测面唯一 logger 名（单一来源，供下游按前缀配 appender / 级别） */
    public static final String LOGGER_NAME = "io.github.flowable.plus.extension.decision";

    private DecisionMetrics() {
    }

    /**
     * 枚举常量名到观测面 tag value 的<b>单点换算</b>：取<b>枚举常量名的小写蛇形</b>。
     *
     * <p>本机制语料内的枚举常量名一律为 {@code SCREAMING_SNAKE_CASE}，故小写化即小写蛇形。
     * 该取值风格与建模面 token（取枚举常量名<b>原文</b>）<b>有意不同族</b> —— 二者分属
     * 「建模面 token」与「观测面 tag value」两个不同的硬域与消费者。</p>
     *
     * @param value 枚举常量，不得为 null
     * @return 小写蛇形 tag value
     */
    public static String tagValue(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
