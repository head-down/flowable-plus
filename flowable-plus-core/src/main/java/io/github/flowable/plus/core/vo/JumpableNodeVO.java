package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 可跳转节点 VO，表示当前任务可跳转至的历史审批节点。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JumpableNodeVO {

    /** 节点 definitionKey（BPMN XML id） */
    private String nodeId;

    /** 节点名称（来自 BPMN 模型） */
    private String nodeName;

    /** 最近一次处理该节点的审批人 */
    private String assignee;

    /** 最近一次完成时间 */
    private Date completeTime;

}
