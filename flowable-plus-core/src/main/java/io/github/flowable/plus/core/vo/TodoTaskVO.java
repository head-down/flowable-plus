package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 待办任务 VO。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoTaskVO {

    /** 任务 ID（Task.id） */
    private String taskId;

    /** 节点名称（Task.name） */
    private String taskName;

    /** 流程实例 ID（Task.processInstanceId） */
    private String processInstanceId;

    /** 流程定义 Key（ProcessDefinition.key） */
    private String processDefinitionKey;

    /** 流程定义名称（ProcessDefinition.name） */
    private String processDefinitionName;

    /** 业务主键（Task.businessKey） */
    private String businessKey;

    /** 发起人 ID（ProcessInstance.startUserId） */
    private String startUserId;

    /** 任务创建时间（Task.createTime） */
    private Date createTime;

    /** 当前审批人（Task.assignee，会签时有值，组任务时 null） */
    private String assignee;
}
