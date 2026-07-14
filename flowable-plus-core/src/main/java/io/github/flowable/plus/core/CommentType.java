package io.github.flowable.plus.core;

/**
 * 审批操作类型枚举，用于区分驳回(REJECT)和退回(RETURN)两种语义变体。
 */
public enum CommentType {

    /** 驳回：审批人不同意当前任务 */
    REJECT,

    /** 退回：审批人同意当前任务，但将流程返回历史节点重新处理 */
    RETURN

}
