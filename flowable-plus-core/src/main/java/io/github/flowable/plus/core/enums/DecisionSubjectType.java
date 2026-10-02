package io.github.flowable.plus.core.enums;

/**
 * 决策主体类型枚举（ADR-0042 第 3 节）。
 *
 * <p>闭集三值，<b>扩展必须走框架发版</b>。三值是<b>语义上位</b>而非实现枚举：
 * 模型类 = {@link #AI}；离线批算 / 外部规则服务 = {@link #SYSTEM}；人 = {@link #USER}。</p>
 *
 * <p>判别式是 {@code subjectType}，故 {@code subjectId} / {@code subjectName} 为可空的描述性字段。</p>
 */
public enum DecisionSubjectType {

    /** 模型类决策源 */
    AI,

    /** 离线批算 / 外部规则服务 */
    SYSTEM,

    /** 人 */
    USER
}
