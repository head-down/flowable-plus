package io.github.flowable.plus.extension.decision;

/**
 * 「写入期降级」的原因枚举（ADR-0042 第 9 节第 10 条：两个写入失败槽位）。
 *
 * <p>取值域是<b>闭集两值</b>，承载「提交已发生、写入期落不下」这一类结构性失败：</p>
 *
 * <ul>
 *   <li>{@link #ANCHOR_LOST} —— 写入决策证据时，锚点所指的当前任务已不存在；</li>
 *   <li>{@link #INSTANCE_ENDED} —— 写入时流程实例已终结。</li>
 * </ul>
 *
 * <p><b>两者都不是结局</b> —— 证据无处可挂，故不成行、不进任何结局分支、不产生
 * {@code SUGGESTION_FAILED}，只降级为结构化日志 + 独立计数。它与「准入失败」（提交没发生）、
 * 「按政策未产出 / 失败」（拉取机制已给出结论）是<b>三处不得混</b>的不同事实，故各自独立承载位、
 * 不合并、不造上位词。</p>
 *
 * <p>观测面的 tag value 取<b>枚举常量名的小写蛇形</b>（见 {@link DecisionMetrics#tagValue}），
 * 与建模面 token 取常量名原文<b>有意不同族</b>。</p>
 */
public enum WriteDegradedCause {

    /** 锚点失效：写入时锚点所指任务已不存在 */
    ANCHOR_LOST,

    /** 实例已结束：写入时流程实例已终结 */
    INSTANCE_ENDED
}
