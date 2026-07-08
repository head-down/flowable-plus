package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 审批轨迹节点 VO，表示流程实例中单个审批节点的历史记录。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTraceVO {

    /** 任务 ID */
    private String taskId;

    /** 节点名称 */
    private String taskName;

    /** 节点 definitionKey */
    private String nodeId;

    /** 审批人 */
    private String assignee;

    /** 任务开始时间 */
    private Date startTime;

    /** 任务结束时间（当前节点为 null） */
    private Date endTime;

    /** 耗时（毫秒） */
    private Long durationMillis;

    /** 审批意见（来自 Comment 表） */
    private String comment;

    /** 是否同意（由 deleteReason 推断） */
    private Boolean approved;

    /** 是否驳回（由 deleteReason 推断） */
    private Boolean isRejected;

    /** 会签子详情（会签节点非 null，普通节点为 null） */
    private List<ApprovalTraceVO> countersignDetails;
}
