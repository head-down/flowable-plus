package io.github.flowable.plus.core.exception;

/**
 * 无上一审批节点时抛出（如流程起始节点即当前节点）。
 */
public class NoPreviousNodeException extends FlowablePlusException {

    public NoPreviousNodeException(String message) {
        super(message);
    }

    public NoPreviousNodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
