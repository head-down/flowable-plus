package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 审批人员信息 VO，包含用户字段 + 任务字段。
 *
 * <p>已审批人员包含 approvalTime 但 taskId 通常为空（任务已结束），
 * 未审批人员包含 taskId 但 approvalTime 为空。</p>
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonnelInfo {

    /** 用户 ID */
    private String userId;

    /** 用户昵称（由 UserInfoResolver 补全，未注入时为 null） */
    private String nickName;

    /** 部门 ID（由 UserInfoResolver 补全，未注入时为 null） */
    private String deptId;

    /** 部门名称（由 UserInfoResolver 补全，未注入时为 null） */
    private String deptName;

    /** 对应任务 ID（未审批任务时有值） */
    private String taskId;

    /** 审批时间（已审批任务时有值） */
    private Date approvalTime;
}
