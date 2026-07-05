package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;

import java.util.List;

/**
 * BPMN 节点导航接口，定义流程定义中节点的前驱和发起人查找能力。
 *
 * <p>此接口将节点查找职责从 {@link FlowablePlus} 中分离，
 * 调用方可通过注入此接口获得编译期约束——仅能调用节点查找操作。</p>
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface NodeNavigation {

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
