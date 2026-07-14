package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.Task;

/**
 * 任务校验工具类，提供任务存在性和权限校验的共享方法。
 *
 * @author flowable-plus
 */
final class TaskValidation {

    private TaskValidation() {
    }

    /**
     * 校验任务存在性和完成状态（不做权限校验）。
     */
    static PlusTask validateTaskExists(TaskService taskService, HistoryService historyService,
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
    static void validateCurrentUserIsAssignee(PlusTask task, String currentUserId,
                                               String taskId, String operation) {
        if (task.getAssignee() == null || !task.getAssignee().equals(currentUserId)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是任务 " + taskId + " 的审批人，无权" + operation);
        }
    }

    /**
     * 断言任务为多实例子任务，适用于会签场景。
     *
     * @throws IllegalArgumentException 非多实例子任务时抛出
     */
    static void validateMultiInstance(MultiInstanceDetector multiInstanceDetector, PlusTask task,
                                       String taskId, String operation) {
        if (!multiInstanceDetector.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 不是多实例子任务，无法" + operation);
        }
    }

    /**
     * 断言任务为非多实例子任务，适用于常规审批/驳回/撤回场景。
     *
     * @throws IllegalArgumentException 多实例子任务时抛出
     */
    static void validateNotMultiInstance(MultiInstanceDetector multiInstanceDetector, PlusTask task, String taskId) {
        if (multiInstanceDetector.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 是多实例子任务，请使用会签操作(counterSign)");
        }
    }
}
