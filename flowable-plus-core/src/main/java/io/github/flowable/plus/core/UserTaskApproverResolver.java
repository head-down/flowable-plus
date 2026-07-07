package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import org.flowable.bpmn.model.UserTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ApproverResolver} 的默认实现，从 BPMN UserTask 中提取 assignee、
 * candidateUsers 和 candidateGroups 信息。
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

    @Override
    public List<ApproverInfoVO> resolveApprovers(UserTask userTask) {
        List<ApproverInfoVO> approvers = new ArrayList<>();

        // assignee
        if (userTask.getAssignee() != null && !userTask.getAssignee().isEmpty()) {
            approvers.add(ApproverInfoVO.builder()
                    .id(userTask.getAssignee())
                    .type("assignee")
                    .build());
        }

        // candidateUsers
        if (userTask.getCandidateUsers() != null) {
            for (String candidateUser : userTask.getCandidateUsers()) {
                approvers.add(ApproverInfoVO.builder()
                        .id(candidateUser)
                        .type("candidateUser")
                        .build());
            }
        }

        // candidateGroups
        if (userTask.getCandidateGroups() != null && groupResolver != null) {
            for (String groupId : userTask.getCandidateGroups()) {
                List<String> members = groupResolver.getGroupMembers(groupId);
                for (String memberId : members) {
                    approvers.add(ApproverInfoVO.builder()
                            .id(memberId)
                            .type("candidateGroup")
                            .groupId(groupId)
                            .build());
                }
            }
        }

        return approvers;
    }
}
