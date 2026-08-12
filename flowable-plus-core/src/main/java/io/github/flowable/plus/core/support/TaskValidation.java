package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.Task;

import java.util.List;

/**
 * 任务校验工具类，提供任务存在性和权限校验的共享方法。
 *
 * @author flowable-plus
 */
public final class TaskValidation {

    private TaskValidation() {
    }

    /**
     * 校验任务存在性和完成状态（不做权限校验）。
     */
    public static PlusTask validateTaskExists(TaskService taskService, HistoryService historyService,
                                    String taskId, String operation) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        Task taskObj = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (taskObj == null) {
            HistoricTaskInstance hti = historyService.createHistoricTaskInstanceQuery()
                    .taskId(taskId).singleResult();
            if (hti != null) {
                throw new TaskAlreadyCompletedException("任务 " + taskId + " 已完成，无法" + operation);
            }
            throw new NotFoundException("任务 " + taskId + " 不存在");
        }

        return PlusTask.from(taskObj);
    }

    /**
     * 校验当前用户是否为任务审批人。
     *
     * <p>调用方需先通过 {@link #validateTaskExists} 获取 task 对象。</p>
     */
    public static void validateCurrentUserIsAssignee(PlusTask task, String currentUserId,
                                               String taskId, String operation) {
        if (task.getAssignee() == null || !task.getAssignee().equals(currentUserId)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是任务 " + taskId + " 的审批人，无权" + operation);
        }
    }

    /**
     * 校验当前用户是否为上一节点审批人（撤回专用权限）。
     *
     * <p>查询上一节点最后一次完成的历史任务并比对审批人；
     * 无历史任务或审批人不匹配时抛 {@link PermissionDeniedException}。</p>
     *
     * <p>调用方需先通过 {@link #validateTaskExists} 获取 task 对象，
     * 并已解析出唯一的上一节点 ID。</p>
     *
     * @param historyService    历史服务
     * @param processInstanceId 流程实例 ID
     * @param prevNodeId        上一节点定义 key
     * @param currentUserId     当前用户 ID
     * @param taskId            任务 ID（用于错误消息）
     * @param operation         操作名（用于错误消息）
     */
    public static void validatePreviousNodeAssignee(HistoryService historyService,
                                                    String processInstanceId, String prevNodeId,
                                                    String currentUserId, String taskId, String operation) {
        List<HistoricTaskInstance> prevTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(prevNodeId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1);
        if (prevTasks.isEmpty() || !currentUserId.equals(prevTasks.get(0).getAssignee())) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是任务 " + taskId + " 的上一节点审批人，无权" + operation);
        }
    }

    /**
     * 断言任务为多实例子任务，适用于会签场景。
     *
     * @throws IllegalArgumentException 非多实例子任务时抛出
     */
    public static void validateMultiInstance(MultiInstanceDetector multiInstanceDetector, PlusTask task,
                                       String taskId, String operation) {
        if (!multiInstanceDetector.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 不是多实例子任务，无法" + operation);
        }
    }

    /**
     * 断言任务为<b>运行时</b>非多实例子任务，适用于常规审批/驳回/撤回/跳转场景。
     *
     * <p>采用运行时判定（ADR-0034）：伪单例（模型为会签但运行时仅 1 人）放行，
     * 真多实例（含"会签剩最后 1 人未投"）拦截。</p>
     *
     * @throws IllegalArgumentException 运行时多实例子任务时抛出
     */
    public static void validateNotMultiInstance(MultiInstanceDetector multiInstanceDetector, PlusTask task, String taskId) {
        if (multiInstanceDetector.isRuntimeMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 是多实例子任务，请使用会签操作(counterSign)");
        }
    }
}
