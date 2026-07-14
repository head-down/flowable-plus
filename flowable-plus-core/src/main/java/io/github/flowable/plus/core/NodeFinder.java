package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;

import java.util.List;
import java.util.Map;

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

    /**
     * 从 StartEvent 出发正向查找所有可达的 UserTask 节点。
     * 支持通过可选变量上下文评估网关条件表达式进行分支选择。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @param variables 变量上下文（用于评估网关条件），为 null 时不评估条件，全部展开
     * @return 按遍历顺序排列的 UserTask 节点 ID 列表
     * @throws NotFoundException 流程定义不存在或未找到 StartEvent 时抛出
     */
    List<String> findAllReachableUserTasks(String processDefinitionId, Map<String, Object> variables);

    /**
     * 从指定节点出发正向查找所有可达的下游 UserTask 节点。
     * 支持通过运行时变量评估网关条件表达式，递归进入子流程和 CallActivity。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @param currentActivityId   当前节点 ID，不可为 null
     * @param processInstanceId   流程实例 ID，不可为 null
     * @param variables           运行时变量上下文，用于评估网关条件（不可为 null）
     * @return 按遍历顺序排列的可达 UserTask 节点 ID 列表，无下游节点时返回空列表
     * @throws NotFoundException 流程定义或节点不存在时抛出
     */
    List<String> findNextUserTasks(String processDefinitionId, String currentActivityId,
                                   String processInstanceId, Map<String, Object> variables);

    /**
     * 从当前节点向后回溯，收集所有已完成的上游 UserTask 节点 ID。
     * 与 {@link #findPreviousNodes} 不同：遇到 UserTask 时收集并继续回溯（不停在最近一个），
     * 最终经历史数据确认节点确实执行过，无历史记录的 nodeId 静默丢弃。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @param currentActivityId   当前节点 ID，不可为 null
     * @param processInstanceId   流程实例 ID（用于查询历史数据确认节点执行过），不可为 null
     * @return 已完成的上游 UserTask 节点 ID 列表（无序），无上游节点时返回空列表
     * @throws NotFoundException 流程定义或节点不存在时抛出
     */
    List<String> findCompletedUserTasks(String processDefinitionId, String currentActivityId,
                                        String processInstanceId);

    /**
     * 根据节点 ID 获取 BPMN 模型中的节点名称。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @param nodeId              节点 definitionKey，不可为 null
     * @return 节点名称，节点不存在时返回 null
     */
    String getNodeName(String processDefinitionId, String nodeId);
}
