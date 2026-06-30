package io.github.flowable.plus.core.exception;

/**
 * 权限不足时抛出。
 */
public class PermissionDeniedException extends FlowablePlusException {

    public PermissionDeniedException(String message) {
        super(message);
    }

    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
