package io.github.flowable.plus.core.spi;

import io.github.flowable.plus.core.vo.ApproverInfoVO;
import org.flowable.bpmn.model.UserTask;

import java.util.List;

/**
 * 审批人解析策略接口，从 BPMN UserTask 中提取审批人信息。
 *
 * <p>支持 assignee、candidateUsers、candidateGroups 三种审批人来源。
 * 当无 {@link GroupResolver} 实现时，candidateGroups 被静默跳过。</p>
 *
 * <p>自 1.0.0 起支持运行上下文感知：实现方应实现
 * {@link #resolveApprovers(UserTask, ApproverContext)}（本接口唯一抽象方法），
 * 感知流程变量、当前操作用户与任务锚点，完成表达式求值（如 {@code ${applyUserId}}）、
 * 动态审批人计算或结合当前用户过滤等能力。单参方法为默认实现，转发到
 * {@link ApproverContext#EMPTY}——不感知上下文的调用路径行为保持兼容。</p>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface ApproverResolver {

    /**
     * 从 UserTask 元素中解析所有审批人（无运行上下文）。
     *
     * @param userTask BPMN UserTask 元素，不可为 null
     * @return 审批人信息列表，无审批人时返回空列表
     */
    default List<ApproverInfoVO> resolveApprovers(UserTask userTask) {
        return resolveApprovers(userTask, ApproverContext.EMPTY);
    }

    /**
     * 从 UserTask 元素中解析所有审批人，并感知运行上下文。
     *
     * <p>context 全字段可空：定义锚点（发起前预览）无 processInstanceId / taskId，
     * variables 来自调用方；任务锚点（审批中）三者均有值。</p>
     *
     * @param userTask BPMN UserTask 元素，不可为 null
     * @param context  审批人解析上下文，不可为 null（无上下文时使用 {@link ApproverContext#EMPTY}）
     * @return 审批人信息列表，无审批人时返回空列表
     */
    List<ApproverInfoVO> resolveApprovers(UserTask userTask, ApproverContext context);
}
