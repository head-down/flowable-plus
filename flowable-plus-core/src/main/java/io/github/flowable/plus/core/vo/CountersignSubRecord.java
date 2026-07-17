package io.github.flowable.plus.core.vo;

import io.github.flowable.plus.core.enums.ApprovalAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会签子记录 VO，用于表示会签节点中单个参与者的投票记录。
 *
 * <p>该 VO 不可嵌套 -- ApprovalRecordVO 中持有 List&lt;CountersignSubRecord&gt;，
 * 但 CountersignSubRecord 自身不再包含子记录列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountersignSubRecord {

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
}
