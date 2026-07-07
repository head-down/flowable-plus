package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 已办任务 VO。
 *
 * <p>不含 comment 字段——审批意见归属审批轨迹详情。</p>
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoneTaskVO {

    /** 历史任务 ID（HistoricTaskInstance.id） */
    private String taskId;

    /** 节点名称（HistoricTaskInstance.name） */
    private String taskName;

    /** 流程实例 ID（HistoricTaskInstance.processInstanceId） */
    private String processInstanceId;

    /** 流程定义 Key（ProcessDefinition.key） */
    private String processDefinitionKey;

    /** 流程定义名称（ProcessDefinition.name） */
    private String processDefinitionName;

    /** 业务主键（HistoricTaskInstance.businessKey） */
    private String businessKey;

    /** 发起人 ID（ProcessInstance.startUserId） */
    private String startUserId;

    /** 任务创建时间（HistoricTaskInstance.createTime） */
    private Date createTime;

    /** 任务结束时间（HistoricTaskInstance.endTime） */
    private Date endTime;

    /** 审批人（HistoricTaskInstance.assignee） */
    private String assignee;

    /** 删除原因（HistoricTaskInstance.deleteReason） */
    private String deleteReason;
}
