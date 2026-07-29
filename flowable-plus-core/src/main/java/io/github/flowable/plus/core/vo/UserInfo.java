package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息 VO，用于审批人员信息补全。
 *
 * <p>由 {@link io.github.flowable.plus.core.spi.UserInfoResolver} 批量查询返回，
 * PersonnelWorkflow 消费后填充至 {@link PersonnelInfo} 中。</p>
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {

    /** 用户昵称 */
    private String nickName;

    /** 部门 ID */
    private String deptId;

    /** 部门名称 */
    private String deptName;
}
