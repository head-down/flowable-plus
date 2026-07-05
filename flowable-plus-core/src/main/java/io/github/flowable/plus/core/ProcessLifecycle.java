package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;

/**
 * 流程生命周期接口，定义流程实例级别的启动、撤回和撤销操作。
 *
 * <p>覆盖 {@link FlowablePlus} 中的流程生命周期管理操作，
 * 调用方可通过注入此接口限制可用的操作范围。</p>
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface ProcessLifecycle {

    /**
     * 撤回已提交的任务。
     *
     * <p>上一节点审批人主动收回已提交的待办，阻止当前审批人继续处理。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @param reason 撤回原因，可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是上一节点审批人或尝试撤回自己的任务时抛出
     * @throws NoPreviousNodeException       无上一审批节点或处于并行网关汇合之后时抛出
     */
    void withdrawTask(String taskId, String reason);

    /**
     * 撤销整个流程实例。
     *
     * <p>流程发起人撤销运行中的流程实例，采用软删除策略——
     * 删除运行时实例但保留历史记录供审计。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @param reason            撤销原因，可为 null
     * @throws NotFoundException            流程实例不存在时抛出
     * @throws TaskAlreadyCompletedException 流程已结束或已推进后续节点时抛出
     * @throws PermissionDeniedException     调用者不是流程发起人时抛出
     */
    void revokeProcess(String processInstanceId, String reason);
}
