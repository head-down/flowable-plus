package io.github.flowable.plus.core.exception;

/**
 * 任务或流程不存在时抛出。
 */
public class NotFoundException extends FlowablePlusException {

    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
