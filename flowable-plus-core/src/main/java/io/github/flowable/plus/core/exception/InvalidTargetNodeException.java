package io.github.flowable.plus.core.exception;

import lombok.experimental.StandardException;

/**
 * 跳转目标节点不合法时抛出（如目标节点不存在、非 UserTask 或历史无记录）。
 */
@StandardException
public class InvalidTargetNodeException extends FlowablePlusException {
}
