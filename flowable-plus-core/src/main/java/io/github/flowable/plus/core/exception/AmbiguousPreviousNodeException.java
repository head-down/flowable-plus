package io.github.flowable.plus.core.exception;

import lombok.experimental.StandardException;

/**
 * 前置节点歧义异常：当前节点的上游存在多个已完成的审批节点，无法确定唯一"上一节点"。
 *
 * <p>当 {@code findPreviousNodes} 返回多个候选节点时（如并行网关汇合），
 * 调用方应使用 {@code PreviousNodeResolutionStrategy} 提供节点选择策略，
 * 通过 {@code isAuthorized(String, String, PreviousNodeResolutionStrategy)} 重载明确选择行为。</p>
 *
 * @author flowable-plus
 */
@StandardException
public class AmbiguousPreviousNodeException extends FlowablePlusException {
}
