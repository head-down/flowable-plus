package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;

import java.util.List;

/**
 * BPMN 节点遍历策略接口，定义流程定义中节点的前驱和后继查找能力。
 *
 * <p>实现方负责加载 BPMN 模型并结合历史数据执行遍历。当流程定义或节点不存在、
 * 或当前节点无上一审批节点时，实现方抛出的异常由 {@link FlowablePlus}
 * 统一透传给调用方。</p>
 *
 * @author flowable-plus
 */
public interface NodeFinder {

    /**
     * 向后查找上一审批节点。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @param currentActivityId   当前节点 ID，不可为 null
     * @param processInstanceId   流程实例 ID（用于查询历史数据判定网关分支），可为 null
     * @return 上一审批节点 ID 列表，至少包含一个元素
     * @throws NotFoundException      流程定义或节点不存在时抛出
     * @throws NoPreviousNodeException 当前节点无上一审批节点时抛出
     */
    List<String> findPreviousNodes(String processDefinitionId, String currentActivityId, String processInstanceId);

    /**
     * 向前查找流程发起人节点（第一个 UserTask）。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @return 第一个 UserTask 的 ID
     * @throws NotFoundException 流程定义不存在或未找到发起人节点时抛出
     */
    String findInitiatorNode(String processDefinitionId);
}
