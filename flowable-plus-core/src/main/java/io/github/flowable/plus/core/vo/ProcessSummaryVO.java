package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 流程实例运行时摘要信息，用于列表页批量展示流程状态。
 *
 * <p>不包含业务数据，仅含引擎可查询的元数据。调用方自行将 {@link #businessKey}
 * 映射到业务表获取业务字段。</p>
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSummaryVO {

    /** 流程实例 ID */
    private String instanceId;

    /** 业务主键 */
    private String businessKey;

    /** 流程定义 Key */
    private String processDefinitionKey;

    /** 流程定义名称 */
    private String processDefinitionName;

    /** 发起人 ID */
    private String startUserId;

    /** 流程发起时间 */
    private Date createTime;

    /** 流程结束时间（运行时为 null） */
    private Date endTime;

    /** 当前活跃任务 ID（多实例时取第一个子任务，无活跃任务时为 null） */
    private String currentTaskId;

    /** 当前节点名称 */
    private String currentTaskName;

    /** 当前节点 definitionKey */
    private String currentNodeId;

    /** 实例挂起状态：1=激活, 2=挂起（与 Flowable SuspensionState 一致） */
    private int suspendState;

    /** 流程是否已结束 */
    private Boolean isEnded;

    /** 结束原因（正常结束为 null，撤销/异常终止时有值，来源 HistoricProcessInstance.deleteReason） */
    private String endReason;

    /** 当前活跃审批人列表（普通节点为单元素，多实例为多元素，含 userId→taskId 映射） */
    private List<AssigneeInfo> activeAssignees;
}
