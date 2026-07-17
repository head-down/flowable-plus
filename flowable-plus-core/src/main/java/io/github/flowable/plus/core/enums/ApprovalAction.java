package io.github.flowable.plus.core.enums;

/**
 * 审批动作展示枚举，用于展示层表示审批记录的操作类型。
 *
 * <p>CommentType 偏向引擎内部持久化语义，ApprovalAction 偏向展示层语义。
 * 二者的差异映射由 {@link CommentTypeConverter} 负责。
 */
public enum ApprovalAction {

    /** 发起：流程启动 */
    START,

    /** 同意：审批人同意当前任务 */
    AGREE,

    /** 驳回：审批人不同意当前任务 */
    REJECT,

    /** 撤回：上一节点审批人撤回到自己的待办 */
    WITHDRAW,

    /** 撤销：流程发起人撤销整个流程实例 */
    REVOKE,

    /** 会签同意：会签参与者投赞成票 */
    COUNTER_SIGN_AGREE,

    /** 会签驳回：会签参与者投反对票 */
    COUNTER_SIGN_REJECT,

    /** 转办：审批人将任务所有权彻底转移给他人 */
    TRANSFER,

    /** 加签：向多实例节点中添加审批人 */
    ADD_SIGN,

    /** 减签：从多实例节点中移除审批人 */
    DELETE_SIGN,

    /** 终止：流程被强制终止 */
    TERMINATE

}
