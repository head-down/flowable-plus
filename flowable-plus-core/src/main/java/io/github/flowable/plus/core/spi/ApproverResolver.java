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
 * @author flowable-plus
 */
@FunctionalInterface
public interface ApproverResolver {

    /**
     * 从 UserTask 元素中解析所有审批人。
     *
     * @param userTask BPMN UserTask 元素，不可为 null
     * @return 审批人信息列表，无审批人时返回空列表
     */
    List<ApproverInfoVO> resolveApprovers(UserTask userTask);
}
