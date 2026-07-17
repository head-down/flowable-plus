package io.github.flowable.plus.core.vo;

import io.github.flowable.plus.core.enums.ApprovalAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 审批记录 VO，表示审批历史中的单条记录（发起、同意、驳回、撤回、撤销、转办、加签、减签、终止等）。
 *
 * <p>会签节点通过 {@code countersignRecords} 字段携带每位参与者的投票子记录。
 * 普通节点该字段为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRecordVO {

    /** 任务 ID */
    private String taskId;

    /** 节点 definitionKey */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 操作类型 */
    private ApprovalAction action;

    /** 操作人 ID */
    private String actorId;

    /** 操作人名称 */
    private String actorName;

    /** 审批意见 */
    private String comment;

    /** 任务开始时间 */
    private Date startTime;

    /** 任务结束时间（当前节点为 null） */
    private Date endTime;

    /** 耗时（毫秒） */
    private Long duration;

    /** 会签子记录（会签节点非 null，普通节点为 null） */
    private List<CountersignSubRecord> countersignRecords;
}
