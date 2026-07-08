package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审批人明细 VO，用于 S5/S6 下一节点审批人预览。
 *
 * <p>与 {@link AssigneeInfo} 的区别：ApproverInfoVO 用于"将来谁会审批"（含分组信息），
 * AssigneeInfo 用于"当前谁在审批"（含 taskId 映射）。</p>
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApproverInfoVO {

    /** 审批人 ID（用户名） */
    private String id;

    /** 审批人显示名称 */
    private String name;

    /** 来源类型：assignee（指定人）、candidateUser（候选用户）、candidateGroup（候选组内成员） */
    private String type;

    /** 节点 definitionKey */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 候选组 ID（type=candidateGroup 时有值） */
    private String groupId;

    /** 候选组名称（type=candidateGroup 时有值） */
    private String groupName;
}
