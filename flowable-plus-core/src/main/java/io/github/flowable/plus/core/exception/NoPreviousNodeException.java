package io.github.flowable.plus.core.exception;

import lombok.experimental.StandardException;

/**
 * 无上一审批节点时抛出（如流程起始节点即当前节点）。
 */
@StandardException
public class NoPreviousNodeException extends FlowablePlusException {
}
