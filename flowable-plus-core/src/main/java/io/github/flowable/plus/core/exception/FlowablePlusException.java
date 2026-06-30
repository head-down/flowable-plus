package io.github.flowable.plus.core.exception;

/**
 * Flowable-Plus 基础异常，所有自定义异常的基类。
 */
public class FlowablePlusException extends RuntimeException {

    public FlowablePlusException(String message) {
        super(message);
    }

    public FlowablePlusException(String message, Throwable cause) {
        super(message, cause);
    }
}
