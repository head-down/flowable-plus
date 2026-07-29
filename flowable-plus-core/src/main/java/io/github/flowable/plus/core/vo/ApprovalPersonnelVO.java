package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 审批人员分组 VO，按已审批/未审批分组返回流程实例的参与人员。
 *
 * <p>典型展示场景：审批详情页顶部"已审批：张三、李四 | 未审批：王五"。</p>
 *
 * <p>各列表中的人员信息已通过 {@link io.github.flowable.plus.core.spi.UserInfoResolver}
 * 补全 nickName/deptId/deptName，未注入该 SPI 时相应字段为 null。</p>
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalPersonnelVO {

    /** 已审批人员列表（按审批时间升序，同 userId 去重） */
    private List<PersonnelInfo> approved;

    /** 未审批人员列表（按任务创建时间升序，同 userId 去重） */
    private List<PersonnelInfo> pending;
}
