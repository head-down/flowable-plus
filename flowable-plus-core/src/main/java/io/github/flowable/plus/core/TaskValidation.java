package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
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
    static Task validateTaskExists(TaskRepository taskRepo, HistoricRepository historicRepo,
                                    String taskId, String operation) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        Task task = taskRepo.findById(taskId);
        if (task == null) {
            if (historicRepo.findTaskById(taskId) != null) {
                throw new TaskAlreadyCompletedException("任务 " + taskId + " 已完成，无法" + operation);
            }
            throw new NotFoundException("任务 " + taskId + " 不存在");
        }

        return task;
    }

    /**
     * 校验当前用户是否为任务审批人。
     *
     * <p>调用方需先通过 {@link #validateTaskExists} 获取 task 对象。</p>
     */
    static void validateCurrentUserIsAssignee(Task task, String currentUserId,
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
    static void validateMultiInstance(BpmnModelCache bpmnModelCache, Task task, String taskId, String operation) {
        if (!bpmnModelCache.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 不是多实例子任务，无法" + operation);
        }
    }

    /**
     * 断言任务为非多实例子任务，适用于常规审批/驳回/撤回场景。
     *
     * @throws IllegalArgumentException 多实例子任务时抛出
     */
    static void validateNotMultiInstance(BpmnModelCache bpmnModelCache, Task task, String taskId) {
        if (bpmnModelCache.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 是多实例子任务，请使用会签操作(counterSign)");
        }
    }
}
