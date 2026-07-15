package io.github.flowable.plus.core.api;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;

import java.util.List;
import java.util.Map;

/**
 * 会签操作接口，定义多实例审批任务的投票与人员管理操作。
 *
 * <p>覆盖 {@link FlowablePlus} 中的会签投票、加签、减签操作，
 * 调用方可通过注入此接口限制可用的操作范围。</p>
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface CounterSignOperations {

    /**
     * 会签操作：完成当前用户的会签子任务。
     *
     * <p>自动认领任务后，根据 {@code approved} 参数写入 AGREE 或 COUNTER_SIGN_REJECT
     * 类型的审批意见，最后调用引擎 complete。</p>
     *
     * @param taskId    任务 ID，不可为 null
     * @param approved  true 表示同意，false 表示驳回
     * @param variables 流程变量，可为 null
     * @param comment   审批意见，可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws IllegalArgumentException     非多实例子任务时抛出（请使用审批操作）
     */
    void counterSign(String taskId, boolean approved, Map<String, Object> variables, String comment);

    /**
     * 加签：向当前会签节点动态追加审批人。
     *
     * <p>仅上一节点审批人可操作（无上一节点时回退到流程发起人）。
     * 已是当前节点审批人的会被静默跳过。</p>
     *
     * @param taskId    任务 ID，不可为 null
     * @param assignees 要追加的审批人 ID 列表，不可为 null 或空
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者无权操作时抛出
     * @throws NoPreviousNodeException       并行网关汇合场景无法确定操作权限时抛出
     * @throws IllegalArgumentException     非多实例子任务时抛出
     */
    void addCounterSigner(String taskId, List<String> assignees);

    /**
     * 减签：从当前会签节点移除指定审批人。
     *
     * <p>仅上一节点审批人可操作（无上一节点时回退到流程发起人）。
     * 已投票的审批人不可移除，减签后至少保留一个未投票审批人。</p>
     *
     * @param taskId   任务 ID，不可为 null
     * @param assignee 要移除的审批人 ID，不可为 null
     * @throws NotFoundException            任务或审批人不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者无权操作时抛出
     * @throws NoPreviousNodeException       并行网关汇合场景无法确定操作权限时抛出
     * @throws IllegalArgumentException     目标审批人已投票、减签后剩余人数不足或非多实例节点时抛出
     */
    void removeCounterSigner(String taskId, String assignee);

    // ======================== 委派 ========================

    /**
     * 委派：会签参与者将当前会签子任务临时委托他人处理。
     *
     * <p>仅当前 assignee 可操作。委派后原审批人成为 owner，被委派人成为 assignee。
     * 委派不可级联——不允许委派已被委派的任务。</p>
     *
     * @param taskId          任务 ID，不可为 null
     * @param delegateUserId  被委派人 ID，不可为 null
     * @param reason          委派原因，可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws IllegalArgumentException     非多实例子任务时抛出或委派目标为当前审批人时抛出
     */
    void delegateTask(String taskId, String delegateUserId, String reason);

    /**
     * 收回委派：委派人收回已委托出去的会签子任务。
     *
     * <p>仅任务 owner（原委派人）可操作，恢复为当前审批人。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是任务 owner 或任务未被委派时抛出
     */
    void resolveDelegate(String taskId);
}
