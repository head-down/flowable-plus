package io.github.flowable.plus.core.enums;

/**
 * 审批操作类型枚举，覆盖同意、驳回、退回、撤回、撤销、会签、加签、减签、委派、收回委派、转办、自动提交等全部操作类型。
 */
public enum CommentType {

    /** 同意：审批人同意当前任务 */
    AGREE,

    /** 驳回：审批人不同意当前任务 */
    REJECT,

    /** 退回：审批人同意当前任务，但将流程返回历史节点重新处理 */
    RETURN,

    /** 撤回：上一节点审批人撤回到自己的待办 */
    WITHDRAW,

    /** 作废：流程发起人作废整个流程实例 */
    INVALID,

    /** 会签同意：会签参与者投赞成票 */
    COUNTER_SIGN_AGREE,

    /** 会签驳回：会签参与者投反对票 */
    COUNTER_SIGN_REJECT,

    /** 加签：向多实例节点中添加审批人 */
    ADD_SIGN,

    /** 减签：从多实例节点中移除审批人 */
    DELETE_SIGN,

    /** 委派：会签参与者将投票权临时委托他人处理 */
    DELEGATE,

    /** 收回委派：委派人收回已委托出去的任务 */
    RESOLVE_DELEGATE,

    /** 转办：审批人将任务所有权彻底转移给他人 */
    TRANSFER,

    /** 自动提交：发起人自动提交首审批任务 */
    AUTO_COMPLETE,

    /** 发起会签：伪单例审批人发起会签，自动完成自身子任务 */
    INITIATE_COUNTERSIGN,

    /**
     * 决策证据：框架自产的只读证据行（ADR-0042 第 5 节）。
     *
     * <p><b>决策源中立</b>（不绑 AI，改名是破坏性变更）。它构成<b>第三个分组</b>「证据组」——
     * 与业务意见组、操作注释组并列；证据组常量见 {@link DecisionEvidenceComment#EVIDENCE_COMMENT_TYPES}。</p>
     *
     * <p>读写两处不得遗漏：写入侧标记由 {@code DecisionEvidenceComment} 从本取值派生；
     * 读侧 {@link CommentTypeConverter} 必须为本取值配<b>显式 case</b> —— 该 {@code switch} 带
     * {@code default}，不加则<b>编译通过、运行时抛异常</b>。</p>
     */
    DECISION_EVIDENCE

}
