package io.github.flowable.plus.core.vo;

import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;

/**
 * 单条决策依据事实（ADR-0042 第 5 节）：{@code key} + {@code value}。
 *
 * <p>{@code key} 取闭集枚举 {@link DecisionRationaleFactKey}，生产者不得自定义键；扩展走框架发版。</p>
 */
public class DecisionRationaleFact {

    /** 依据事实的键（闭集枚举） */
    private DecisionRationaleFactKey key;

    /** 依据事实的值 */
    private String value;

    public DecisionRationaleFact() {
    }

    public DecisionRationaleFact(final DecisionRationaleFactKey key, final String value) {
        this.key = key;
        this.value = value;
    }

    public DecisionRationaleFactKey getKey() {
        return key;
    }

    public void setKey(final DecisionRationaleFactKey key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(final String value) {
        this.value = value;
    }
}
