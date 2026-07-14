package io.github.flowable.plus.core;

/**
 * 审批操作类型枚举，用于区分驳回、退回、委派、收回委派、转办等操作类型。
 */
public enum CommentType {

    /** 驳回：审批人不同意当前任务 */
    REJECT,

    /** 退回：审批人同意当前任务，但将流程返回历史节点重新处理 */
    RETURN,

    /** 委派：会签参与者将投票权临时委托他人处理 */
    DELEGATE,

    /** 收回委派：委派人收回已委托出去的任务 */
    RESOLVE_DELEGATE,

    /** 转办：审批人将任务所有权彻底转移给他人 */
    TRANSFER

}
