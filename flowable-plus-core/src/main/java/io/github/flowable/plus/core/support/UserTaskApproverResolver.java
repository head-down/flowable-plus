package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.spi.ApproverContext;
import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import org.flowable.bpmn.model.UserTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ApproverResolver} 的默认实现，从 BPMN UserTask 中提取 assignee、
 * candidateUsers 和 candidateGroups 信息。
 *
 * <p>同一节点内按优先级去重（assignee &gt; candidateUser &gt; candidateGroup），
 * 同一用户不会因为多种分配方式而在结果列表中出现多次。</p>
 *
 * @author flowable-plus
 */
public class UserTaskApproverResolver implements ApproverResolver {

    private final GroupResolver groupResolver;

    /**
     * @param groupResolver 候选组解析器，可为 null（跳过 candidateGroups）
     */
    public UserTaskApproverResolver(GroupResolver groupResolver) {
        this.groupResolver = groupResolver;
    }

    /**
     * 两参方法委托单参方法：默认实现不消费运行上下文（ADR-0033 决策 3），
     * 输出严格保持 1.0.0。注意委托方向为两参 → 单参，与接口 default（单参 → 两参）相反，
     * 确保无循环调用。
     */
    @Override
    public List<ApproverInfoVO> resolveApprovers(UserTask userTask, ApproverContext context) {
        return resolveApprovers(userTask);
    }

    @Override
    public List<ApproverInfoVO> resolveApprovers(UserTask userTask) {
        List<ApproverInfoVO> approvers = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // assignee（最高优先级）
        if (userTask.getAssignee() != null && !userTask.getAssignee().isEmpty()) {
            seen.add(userTask.getAssignee());
            approvers.add(ApproverInfoVO.builder()
                    .id(userTask.getAssignee())
                    .type("assignee")
                    .build());
        }

        // candidateUsers（跳过已被 assignee 包含的用户）
        if (userTask.getCandidateUsers() != null) {
            for (String candidateUser : userTask.getCandidateUsers()) {
                if (seen.add(candidateUser)) {
                    approvers.add(ApproverInfoVO.builder()
                            .id(candidateUser)
                            .type("candidateUser")
                            .build());
                }
            }
        }

        // candidateGroups（跳过已被 assignee 或 candidateUsers 包含的用户）
        if (userTask.getCandidateGroups() != null && groupResolver != null) {
            for (String groupId : userTask.getCandidateGroups()) {
                List<String> members = groupResolver.getGroupMembers(groupId);
                for (String memberId : members) {
                    if (seen.add(memberId)) {
                        approvers.add(ApproverInfoVO.builder()
                                .id(memberId)
                                .type("candidateGroup")
                                .groupId(groupId)
                                .build());
                    }
                }
            }
        }

        return approvers;
    }
}
