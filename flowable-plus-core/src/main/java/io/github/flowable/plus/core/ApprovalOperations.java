package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.vo.JumpableNodeVO;

import java.util.List;
import java.util.Map;

/**
 * 审批操作接口，统一定义流程发起、同意、驳回、撤回和撤销操作。
 *
 * <p>合并了原 TaskOperations、RejectionOperations 和 ProcessLifecycle
 * 三个接口，对调用方提供一个统一的审批生命周期入口。</p>
 *
 * @author flowable-plus
 * @see TaskWorkflow
 */
public interface ApprovalOperations {

    // ======================== 发起与同意 ========================

    /**
     * 启动流程实例。
     *
     * @param processDefinitionKey 流程定义 KEY，不可为 null
     * @param businessKey          业务主键，可为 null
     * @param variables            流程变量，可为 null
     * @return 流程实例领域对象
     * @throws NotFoundException 流程定义不存在时抛出
     */
    PlusProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables);

    /**
     * 完成任务审批。
     *
     * <p>自动认领任务、添加审批意见后完成。</p>
     *
     * @param taskId    任务 ID，不可为 null
     * @param variables 流程变量，可为 null
     * @param comment   审批意见，可为 null
     * @throws NotFoundException 任务不存在时抛出
     * @throws IllegalArgumentException 任务为多实例子任务时抛出（请使用会签操作）
     */
    void completeTask(String taskId, Map<String, Object> variables, String comment);

    /**
     * 认领任务（低级 API）。
     *
     * <p>通常无需手动调用，{@link #completeTask} 已自动认领。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @throws NotFoundException 任务不存在时抛出
     */
    void claimTask(String taskId);

    // ======================== 驳回 ========================

    /**
     * 驳回至上一审批节点。
     *
     * <p>审批人不同意当前任务，退回至上一审批节点。仅支持串行流程中的驳回，
     * 并行网关汇合后的节点无法驳回至单一上级节点。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @param reason 驳回原因，可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws NoPreviousNodeException       无上一审批节点或处于并行网关汇合之后时抛出
     */
    void rejectTask(String taskId, String reason);

    /**
     * 驳回至流程发起人节点。
     *
     * <p>审批人不同意当前任务，退回至流程的第一个审批节点（发起人所在节点）。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @param reason 驳回原因，可为 null
     * @throws NotFoundException            任务或流程定义不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws NoPreviousNodeException       当前已是发起人节点时抛出
     */
    void rejectTaskToInitiator(String taskId, String reason);

    // ======================== 撤回与撤销 ========================

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

    // ======================== 任意跳转 ========================

    /**
     * 将当前任务跳转至任意历史审批节点。
     *
     * <p>审批人将当前任务驳回或退回至流程中任意历史审批节点，不再局限于上一节点或发起人。
     * 驳回(REJECT)和退回(RETURN)行为完全一致，仅历史记录的 commentType 不同。</p>
     *
     * <p><b>并行网关风险</b>：允许从并行网关汇合节点执行跳转。跳转到并行分支中的某个节点后，
     * 其他分支的历史状态可能破坏 join 网关的同步机制。调用方需确认目标节点位于单一执行路径上。</p>
     *
     * @param taskId       任务 ID，不可为 null
     * @param targetNodeId 目标节点 definitionKey，不可为 null
     * @param reason       跳转原因，可为 null
     * @param commentType  操作类型（REJECT 或 RETURN），不可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws InvalidTargetNodeException    目标节点不合法时抛出（不存在/非 UserTask/历史无记录）
     * @throws IllegalArgumentException     任务为多实例子任务时抛出（请使用会签操作）
     */
    void jumpToNode(String taskId, String targetNodeId, String reason, CommentType commentType);

    /**
     * 查询当前任务可跳转的历史节点列表。
     *
     * <p>从当前节点出发 BPMN 回溯收集所有已完成的上游 UserTask，
     * 按完成时间正序排列。同一节点多次完成只返回最后一次执行的记录。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @return 可跳转节点列表，按完成时间正序排列；无历史节点时返回空列表
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     */
    List<JumpableNodeVO> getJumpableNodes(String taskId);
}
