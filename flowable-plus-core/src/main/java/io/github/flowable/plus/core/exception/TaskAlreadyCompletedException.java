package io.github.flowable.plus.core.exception;

/**
 * 任务已完成时抛出。
 */
public class TaskAlreadyCompletedException extends FlowablePlusException {

    public TaskAlreadyCompletedException(String message) {
        super(message);
    }

    public TaskAlreadyCompletedException(String message, Throwable cause) {
        super(message, cause);
    }
}
