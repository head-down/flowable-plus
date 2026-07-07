package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;

import java.util.List;

/**
 * 下一节点/审批人预览操作接口。
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface NodePreviewOperations {

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人。
     * 用于发起流程前展示审批链路，支持多节点。不评估网关条件表达式。
     *
     * @param processKey 流程定义 Key
     * @return 初始审批节点列表，每个节点包含审批人列表
     */
    List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey);

    /**
     * 获取当前任务所有下一节点的审批人（扁平列表，不分组）。
     * 基于运行时上下文评估网关条件表达式。
     *
     * @param taskId 当前任务 ID
     * @return 所有下一节点的审批人列表
     */
    List<ApproverInfoVO> getNextTaskApprovers(String taskId);

    /**
     * 获取当前任务指定目标节点的审批人。
     *
     * @param taskId       当前任务 ID
     * @param targetNodeId 目标节点 definitionKey
     * @return 指定目标节点的审批人列表
     */
    List<ApproverInfoVO> getNextTaskApprovers(String taskId, String targetNodeId);

    /**
     * 获取当前任务可流转至的下游节点列表。
     * 用于审批页面展示分支选项（如多分支网关后的不同节点）。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskId            当前任务 ID
     * @return 下一节点列表，含节点信息和表单配置
     */
    List<NextTaskNodeVO> getNextTaskNodes(String processInstanceId, String taskId);
}
