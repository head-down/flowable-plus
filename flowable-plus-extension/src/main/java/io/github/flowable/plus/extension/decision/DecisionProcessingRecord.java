package io.github.flowable.plus.extension.decision;

import lombok.Getter;

/**
 * 出域 / 入站加工的<b>加工事实</b>记录（ADR-0042 第 6 节）—— 只承「发生了什么加工」，<b>不承「说不」</b>。
 *
 * <p><b>与结果位的边界是结构保证</b>：策略的「允许 / 拒绝」住 {@link DecisionOutboundResult#isPermitted()} /
 * {@link DecisionInboundResult#isPersistable()}，本类型字段集<b>恰二</b>（{@link #redacted} /
 * {@link #truncated}），<b>无</b>判定位 —— 避免让一个字面恒真的字段兼职（撞第 5 节「{@code NO_PAYLOAD}
 * 不被布尔兼职」的同一把尺子）。</p>
 *
 * <p>标志位语义分列：{@code redacted} 含<b>脱敏与摘要</b>、<b>不含截断</b>；截断由 {@code truncated}
 * <b>单列</b>。框架从「加工记录 + 自身 clamp」推导出域 / 入站两套标志，<b>策略不自填标志</b>。</p>
 *
 * <p><b>实现细节，不入机制术语表</b>（命名宪章 §4.5）。</p>
 */
@Getter
public final class DecisionProcessingRecord {

    /** 是否发生脱敏 / 摘要（不含截断） */
    private final boolean redacted;

    /** 是否发生截断 */
    private final boolean truncated;

    /**
     * 构造一段加工记录。
     *
     * @param redacted  是否发生脱敏 / 摘要
     * @param truncated 是否发生截断
     */
    public DecisionProcessingRecord(final boolean redacted, final boolean truncated) {
        this.redacted = redacted;
        this.truncated = truncated;
    }

    /**
     * 无任何加工的加工记录（两布尔皆为 {@code false}）。
     *
     * @return 「无加工」记录
     */
    public static DecisionProcessingRecord none() {
        return new DecisionProcessingRecord(false, false);
    }
}
