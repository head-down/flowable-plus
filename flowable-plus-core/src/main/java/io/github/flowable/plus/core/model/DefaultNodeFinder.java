package io.github.flowable.plus.core.model;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.spi.UserTaskTraversalFilter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.common.engine.impl.el.ExpressionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link NodeFinder} 的默认实现：BPMN 模型 + 历史数据混合查找引擎。
 *
 * <p>通过遍历 BPMN 模型并结合历史活动实例数据，实现以下查找能力：</p>
 * <ul>
 *   <li>向后查找——从当前节点反向追踪上一审批节点，处理排他网关和并行网关</li>
 *   <li>向前查找——从 StartEvent 正向追踪第一个 UserTask 作为发起人节点</li>
 * </ul>
 *
 * <p>本类内聚了 BPMN 模型加载和节点存在性校验，调用方通过接口无需预加载模型。</p>
 */
public class DefaultNodeFinder implements NodeFinder {

    private final BpmnModelCache bpmnModelCache;
    private final HistoryService historyService;
    private final ExpressionManager expressionManager;
    private final List<UserTaskTraversalFilter> traversalFilters;

    /**
     * 回溯遍历策略，控制 UserTask 发现后的行为和网关分支选择方式。
     */
    private enum BackwardTraversalStrategy {
        /**
         * 发现第一个 UserTask 即停止回溯。排他网关通过历史数据解析实际执行分支。
         * 用于 {@link #findPreviousNodes}。
         */
        STOP_AT_FIRST_USER_TASK,

        /**
         * 收集所有上游 UserTask，穿越 UserTask 继续回溯。排他网关遍历所有入边（不做历史过滤）。
         * 用于 {@link #findCompletedUserTasks}。
         */
        COLLECT_ALL_UPSTREAM
    }

    public DefaultNodeFinder(BpmnModelCache bpmnModelCache, HistoryService historyService,
                             ExpressionManager expressionManager,
                             List<UserTaskTraversalFilter> traversalFilters) {
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (expressionManager == null) {
            throw new IllegalArgumentException("ExpressionManager 不可为 null");
        }
        this.bpmnModelCache = bpmnModelCache;
        this.historyService = historyService;
        this.expressionManager = expressionManager;
        this.traversalFilters = traversalFilters != null ? traversalFilters : Collections.emptyList();
    }

    @Override
    public List<String> findPreviousNodes(String processDefinitionId, String currentActivityId, String processInstanceId) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

        FlowElement currentElement = bpmnModel.getFlowElement(currentActivityId);
        if (currentElement == null) {
            throw new NotFoundException("节点 " + currentActivityId + " 不存在");
        }

        Set<String> visited = new HashSet<>();
        List<String> result = new ArrayList<>();
        traceBackward(bpmnModel, currentElement, processInstanceId, visited, result,
                BackwardTraversalStrategy.STOP_AT_FIRST_USER_TASK);

        if (result.isEmpty()) {
            throw new NoPreviousNodeException("节点 " + currentActivityId + " 无上一审批节点");
        }

        // 多候选节点时，通过历史数据过滤出实际执行的路径。
        // 非受控汇合：多个 model 候选 → 1 个实际执行 → 返回 1 个
        // 并行网关汇合：多个 model 候选 → 多个都执行 → 返回多个 → rejectTask size>1 拦截
        // 排他网关历史缺失：resolveExclusiveGateway 返回全量入边 → 多个候选 → filterByHistory 裁决
        if (result.size() > 1 && processInstanceId != null) {
            result = filterByHistory(result, processInstanceId);
            if (result.isEmpty()) {
                throw new NoPreviousNodeException("节点 " + currentActivityId + " 无上一审批节点");
            }
        }
        return result;
    }

    /**
     * 通过历史数据过滤候选节点，保留实际执行过的节点。
     * 使用 activityId + count() 逐候选查询，避免 .list() 全量加载性能问题。
     */
    private List<String> filterByHistory(List<String> candidateNodeIds, String processInstanceId) {
        List<String> executedNodes = new ArrayList<>();
        for (String nodeId : candidateNodeIds) {
            long count = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .activityId(nodeId)
                    .finished()
                    .count();
            if (count > 0) {
                executedNodes.add(nodeId);
            }
        }
        return executedNodes;
    }

    @Override
    public String findInitiatorNode(String processDefinitionId) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

        if (bpmnModel.getProcesses() == null || bpmnModel.getProcesses().isEmpty()) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 中未找到发起人节点");
        }

        for (FlowElement element : bpmnModel.getProcesses().get(0).getFlowElements()) {
            if (element instanceof StartEvent) {
                Set<String> visited = new HashSet<>();
                String userTaskId = traceForward(bpmnModel, element, visited);
                if (userTaskId != null) {
                    return userTaskId;
                }
            }
        }

        throw new NotFoundException("流程定义 " + processDefinitionId + " 中未找到发起人节点");
    }

    /**
     * 从指定元素开始向后追踪，根据策略收集上一 UserTask。
     */
    private void traceBackward(BpmnModel bpmnModel, FlowElement element,
                                String processInstanceId, Set<String> visited,
                                java.util.Collection<String> result,
                                BackwardTraversalStrategy strategy) {
        if (!(element instanceof FlowNode)) {
            return;
        }

        if (!visited.add(element.getId())) {
            return;
        }

        FlowNode flowNode = (FlowNode) element;
        List<SequenceFlow> incomingFlows = flowNode.getIncomingFlows();
        if (incomingFlows == null || incomingFlows.isEmpty()) {
            return;
        }

        for (SequenceFlow incoming : incomingFlows) {
            FlowElement source = bpmnModel.getFlowElement(incoming.getSourceRef());
            if (source == null) {
                continue;
            }

            if (source instanceof UserTask) {
                result.add(source.getId());
                if (strategy == BackwardTraversalStrategy.COLLECT_ALL_UPSTREAM) {
                    // 穿越 UserTask 继续回溯上游节点
                    traceBackward(bpmnModel, source, processInstanceId, visited, result, strategy);
                }
            } else if (source instanceof ExclusiveGateway) {
                traceExclusiveGatewayBackward(bpmnModel, (ExclusiveGateway) source,
                        processInstanceId, visited, result, strategy);
            } else if (source instanceof ParallelGateway) {
                traceBackward(bpmnModel, source, processInstanceId, visited, result, strategy);
            } else if (source instanceof StartEvent) {
                // 到达 StartEvent，停止
            }
        }
    }

    /**
     * 穿越排他网关向后追踪，根据策略选择分支路径。
     */
    private void traceExclusiveGatewayBackward(BpmnModel bpmnModel, ExclusiveGateway gateway,
                                                String processInstanceId, Set<String> visited,
                                                java.util.Collection<String> result,
                                                BackwardTraversalStrategy strategy) {
        if (!visited.add(gateway.getId())) {
            return;
        }

        List<SequenceFlow> resolvedFlows;
        if (strategy == BackwardTraversalStrategy.STOP_AT_FIRST_USER_TASK) {
            resolvedFlows = resolveExclusiveGateway(processInstanceId, gateway.getIncomingFlows());
        } else {
            List<SequenceFlow> incomingFlows = gateway.getIncomingFlows();
            resolvedFlows = incomingFlows != null ? incomingFlows : Collections.<SequenceFlow>emptyList();
        }

        for (SequenceFlow resolvedFlow : resolvedFlows) {
            FlowElement source = bpmnModel.getFlowElement(resolvedFlow.getSourceRef());
            if (source == null) {
                continue;
            }

            if (source instanceof UserTask) {
                result.add(source.getId());
                if (strategy == BackwardTraversalStrategy.COLLECT_ALL_UPSTREAM) {
                    traceBackward(bpmnModel, source, processInstanceId, visited, result, strategy);
                }
            } else {
                traceBackward(bpmnModel, source, processInstanceId, visited, result, strategy);
            }
        }
    }

    /**
     * 解析排他网关的实际执行分支。
     *
     * <p>使用 activityId + count() 逐候选查询，避免 .list() 全量加载性能问题。
     * 历史匹配失败时返回全部入边（而非盲猜首条），
     * 让外层 filterByHistory 做最终裁决，防止"安全网穿透"导致静默数据污染。</p>
     */
    private List<SequenceFlow> resolveExclusiveGateway(String processInstanceId, List<SequenceFlow> incomingFlows) {
        if (incomingFlows == null || incomingFlows.isEmpty()) {
            return Collections.emptyList();
        }

        if (processInstanceId == null) {
            return incomingFlows;
        }

        for (SequenceFlow flow : incomingFlows) {
            long count = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .activityId(flow.getSourceRef())
                    .finished()
                    .count();
            if (count > 0) {
                return Collections.singletonList(flow);
            }
        }

        // 历史匹配失败：返回全部入边，故意触发外层 result.size() > 1 → filterByHistory 裁决。
        // 不返回 singletonList(firstFlow)：会造成安全网穿透，静默选择错误节点。
        return incomingFlows;
    }

    @Override
    public List<String> findAllReachableUserTasks(String processDefinitionId, Map<String, Object> variables) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

        if (bpmnModel.getProcesses() == null || bpmnModel.getProcesses().isEmpty()) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 中未找到任何流程");
        }

        StartEvent startEvent = findStartEvent(bpmnModel);
        if (startEvent == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 中未找到 StartEvent");
        }

        Set<String> visited = new HashSet<>();
        List<String> result = new ArrayList<>();
        traceForwardAll(bpmnModel, startEvent, variables, visited, result, false);
        return result;
    }

    @Override
    public List<String> findNextUserTasks(String processDefinitionId, String currentActivityId,
                                           String processInstanceId, Map<String, Object> variables) {
        return traceForwardFromOutgoing(processDefinitionId, currentActivityId, variables, false);
    }

    @Override
    public List<String> findAdjacentUserTasks(String processDefinitionId, String startNodeId,
                                               Map<String, Object> variables) {
        return traceForwardFromOutgoing(processDefinitionId, startNodeId, variables, true);
    }

    /**
     * 从指定节点的 outgoing flows 出发正向遍历，收集可达的 UserTask 节点。
     * <p>
     * 条件兜底：当所有带条件的 outgoing flow 均被排除（结果为空）时，
     * 回退到不评估条件的方式重新遍历。这处理了审批阶段预览时路由变量（如
     * nextNodeCodeTmp）尚未写入的场景，避免因变量缺失隐藏用户可能选择的下一节点。
     *
     * @param processDefinitionId 流程定义 ID
     * @param nodeId 起始节点 ID
     * @param variables 变量上下文，为 null 时不评估网关条件，全部展开
     * @param stopAtUserTask 遇到 UserTask 时是否停止深入（不穿越其 outgoing）
     * @return 按遍历顺序排列的 UserTask 节点 ID 列表
     * @throws NotFoundException 流程定义或节点不存在时抛出
     */
    private List<String> traceForwardFromOutgoing(String processDefinitionId, String nodeId,
                                                   Map<String, Object> variables, boolean stopAtUserTask) {
        if (processDefinitionId == null || processDefinitionId.isEmpty()) {
            throw new IllegalArgumentException("processDefinitionId 不可为 null 或空");
        }
        if (nodeId == null || nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId 不可为 null 或空");
        }

        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

        FlowElement startElement = bpmnModel.getFlowElement(nodeId);
        if (startElement == null) {
            throw new NotFoundException("节点 " + nodeId + " 不存在");
        }

        Set<String> visited = new HashSet<>();
        List<String> result = new ArrayList<>();

        if (startElement instanceof FlowNode) {
            FlowNode startFlowNode = (FlowNode) startElement;
            List<SequenceFlow> outgoingFlows = startFlowNode.getOutgoingFlows();
            if (outgoingFlows != null) {
                int conditionalCount = 0;
                List<SequenceFlow> excludedFlows = new ArrayList<>();

                for (SequenceFlow flow : outgoingFlows) {
                    if (variables != null && flow.getConditionExpression() != null
                            && !flow.getConditionExpression().isEmpty()) {
                        conditionalCount++;
                        if (!evaluateCondition(flow.getConditionExpression(), variables)) {
                            excludedFlows.add(flow);
                            continue;
                        }
                    }
                    FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                    if (target != null) {
                        traceForwardAll(bpmnModel, target, variables, visited, result, stopAtUserTask);
                    }
                }

                // 兜底：所有条件分支均被排除 → 不评估条件重新遍历
                // 审批阶段预览时，路由变量（如 nextNodeCodeTmp）尚未写入，
                // 不应因此隐藏用户可能选择的下一审批节点
                if (conditionalCount > 0 && excludedFlows.size() == conditionalCount) {
                    for (SequenceFlow flow : excludedFlows) {
                        FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                        if (target != null) {
                            traceForwardAll(bpmnModel, target, null, visited, result, stopAtUserTask);
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * 在 BPMN 模型中查找 StartEvent。
     */
    private StartEvent findStartEvent(BpmnModel bpmnModel) {
        for (FlowElement element : bpmnModel.getProcesses().get(0).getFlowElements()) {
            if (element instanceof StartEvent) {
                return (StartEvent) element;
            }
        }
        return null;
    }

    /**
     * 从指定元素开始正向遍历，收集所有可达的 UserTask 节点。
     * 支持通过 variables 评估网关条件进行分支选择，
     * 递归进入 SubProcess 和 CallActivity 引用的流程定义。
     */
    private void traceForwardAll(BpmnModel bpmnModel, FlowElement element,
                                 Map<String, Object> variables, Set<String> visited, List<String> result,
                                 boolean stopAtUserTask) {
        if (!visited.add(element.getId())) {
            return; // 防止循环
        }

        // 遇到 UserTask，通过 Filter 决定是否收集，继续遍历后续节点
        if (element instanceof UserTask) {
            UserTask userTask = (UserTask) element;
            if (shouldIncludeUserTask(userTask, variables)) {
                result.add(userTask.getId());
                if (stopAtUserTask) {
                    return; // 紧邻遍历：仅当节点被收集时才停止深入
                }
            }
            // 注意：无论是否收集，UserTask 可能后接网关，继续遍历 outgoing
        }

        // 递归进入 SubProcess 内部
        if (element instanceof SubProcess) {
            SubProcess subProcess = (SubProcess) element;
            if (subProcess.getFlowElements() != null) {
                int beforeCount = result.size();
                for (FlowElement subElement : subProcess.getFlowElements()) {
                    if (subElement instanceof StartEvent) {
                        traceForwardAll(bpmnModel, subElement, variables, visited, result, stopAtUserTask);
                    }
                }
                // 紧邻遍历时，若在子流程内已找到紧邻节点，不再穿透出边界
                if (stopAtUserTask && result.size() > beforeCount) {
                    return;
                }
            }
        }

        // 递归进入 CallActivity 引用的流程定义
        if (element instanceof CallActivity) {
            CallActivity callActivity = (CallActivity) element;
            String calledElement = callActivity.getCalledElement();
            if (calledElement != null && !calledElement.isEmpty()) {
                BpmnModel calledModel = bpmnModelCache.getBpmnModelByProcessKey(calledElement);
                if (calledModel != null) {
                    StartEvent calledStartEvent = findStartEvent(calledModel);
                    if (calledStartEvent != null) {
                        traceForwardAll(calledModel, calledStartEvent, variables, visited, result, stopAtUserTask);
                    }
                }
            }
        }

        if (element instanceof FlowNode) {
            FlowNode flowNode = (FlowNode) element;
            List<SequenceFlow> outgoingFlows = flowNode.getOutgoingFlows();
            if (outgoingFlows == null || outgoingFlows.isEmpty()) {
                return;
            }

            // stopAtUserTask 模式：网关若存在直达 UserTask 的出边，仅跟随 UserTask 和
            // SubProcess/CallActivity 路径，阻止 ServiceTask/ScriptTask 等中间节点
            // 穿透到下游 UserTask（如会签入口网关不要跟随 SKIP→路由→下游审批人）
            if (stopAtUserTask && (element instanceof ExclusiveGateway || element instanceof ParallelGateway)) {
                boolean hasDirectUserTaskPath = false;
                for (SequenceFlow flow : outgoingFlows) {
                    FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                    if (target instanceof UserTask) {
                        hasDirectUserTaskPath = true;
                        break;
                    }
                }
                if (hasDirectUserTaskPath) {
                    gatedTraverse(outgoingFlows, bpmnModel, variables, visited, result, stopAtUserTask);
                    return;
                }
            }

            int conditionalCount = 0;
            List<SequenceFlow> excludedFlows = new ArrayList<>();

            for (SequenceFlow flow : outgoingFlows) {
                // 评估网关条件
                if (variables != null && flow.getConditionExpression() != null
                        && !flow.getConditionExpression().isEmpty()) {
                    conditionalCount++;
                    if (!evaluateCondition(flow.getConditionExpression(), variables)) {
                        excludedFlows.add(flow);
                        continue;
                    }
                }

                FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                if (target != null) {
                    traceForwardAll(bpmnModel, target, variables, visited, result, stopAtUserTask);
                }
            }

            // 兜底：所有条件分支均被排除 → 不评估条件重新遍历
            // 审批阶段预览时，路由变量（如 nextNodeCodeTmp）尚未写入，
            // 不应因此隐藏用户可能选择的下一审批节点
            if (conditionalCount > 0 && excludedFlows.size() == conditionalCount) {
                for (SequenceFlow flow : excludedFlows) {
                    FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                    if (target != null) {
                        traceForwardAll(bpmnModel, target, null, visited, result, stopAtUserTask);
                    }
                }
            }
        }
    }

    /**
     * stopAtUserTask 模式下网关的出边遍历：仅跟随直达 UserTask 和 SubProcess/CallActivity，
     * 阻止 ServiceTask/ScriptTask 等中间节点穿透到下游 UserTask。
     *
     * <p>与下方主循环的关键差异：排除"间接路径"（中间节点 + 下游 UserTask），
     * 不拦截同样承载子 UserTask 的 SubProcess/CallActivity。</p>
     */
    private void gatedTraverse(List<SequenceFlow> outgoingFlows, BpmnModel bpmnModel,
                               Map<String, Object> variables, Set<String> visited,
                               List<String> result, boolean stopAtUserTask) {
        int conditionalCount = 0;
        int excludedCount = 0;
        List<SequenceFlow> excludedFlows = new ArrayList<>();

        for (SequenceFlow flow : outgoingFlows) {
            FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());

            if (target instanceof UserTask) {
                if (variables != null && flow.getConditionExpression() != null
                        && !flow.getConditionExpression().isEmpty()) {
                    conditionalCount++;
                    if (!evaluateCondition(flow.getConditionExpression(), variables)) {
                        excludedCount++;
                        excludedFlows.add(flow);
                        continue;
                    }
                }
                traceForwardAll(bpmnModel, target, variables, visited, result, stopAtUserTask);
            } else if (target instanceof SubProcess || target instanceof CallActivity) {
                traceForwardAll(bpmnModel, target, variables, visited, result, stopAtUserTask);
            }
        }

        if (conditionalCount > 0 && excludedCount == conditionalCount) {
            for (SequenceFlow flow : excludedFlows) {
                FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                if (target instanceof UserTask) {
                    traceForwardAll(bpmnModel, target, null, visited, result, stopAtUserTask);
                }
            }
        }
    }

    /**
     * 通过已注册的 {@link UserTaskTraversalFilter} 列表决定是否收集当前 UserTask。
     * 多个 Filter 以 AND 逻辑合并，无 Filter 注册时默认收集。
     */
    private boolean shouldIncludeUserTask(UserTask userTask, Map<String, Object> variables) {
        if (traversalFilters.isEmpty()) {
            return true;
        }
        for (UserTaskTraversalFilter filter : traversalFilters) {
            if (!filter.shouldInclude(userTask, variables)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> findCompletedUserTasks(String processDefinitionId, String currentActivityId,
                                                String processInstanceId) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

        FlowElement currentElement = bpmnModel.getFlowElement(currentActivityId);
        if (currentElement == null) {
            throw new NotFoundException("节点 " + currentActivityId + " 不存在");
        }

        // 1. BPMN 回溯收集所有上游 UserTask
        Set<String> visited = new HashSet<>();
        Set<String> allUpstreamUserTasks = new LinkedHashSet<>();
        traceBackward(bpmnModel, currentElement, processInstanceId, visited, allUpstreamUserTasks,
                BackwardTraversalStrategy.COLLECT_ALL_UPSTREAM);

        if (allUpstreamUserTasks.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查询历史数据确认节点确实执行过
        List<HistoricActivityInstance> historicInstances = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .orderByHistoricActivityInstanceEndTime().desc()
                .list();
        Set<String> executedNodeIds = new HashSet<>();
        for (HistoricActivityInstance instance : historicInstances) {
            if (instance.getActivityId() != null) {
                executedNodeIds.add(instance.getActivityId());
            }
        }

        // 3. 保留有历史记录的 nodeId
        List<String> result = new ArrayList<>();
        for (String nodeId : allUpstreamUserTasks) {
            if (executedNodeIds.contains(nodeId)) {
                result.add(nodeId);
            }
        }

        return result;
    }

    @Override
    public String getNodeName(String processDefinitionId, String nodeId) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            return null;
        }
        FlowElement element = bpmnModel.getFlowElement(nodeId);
        if (element == null) {
            return null;
        }
        return element.getName();
    }

    // ======================== findReachableEndEvents ========================

    @Override
    public List<String> findReachableEndEvents(String processDefinitionId, String nodeId,
                                                Map<String, Object> variables) {
        if (processDefinitionId == null || processDefinitionId.isEmpty()) {
            throw new IllegalArgumentException("processDefinitionId 不可为 null 或空");
        }
        if (nodeId == null || nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId 不可为 null 或空");
        }

        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

        FlowElement startElement = bpmnModel.getFlowElement(nodeId);
        if (startElement == null) {
            throw new NotFoundException("节点 " + nodeId + " 不存在");
        }

        if (!(startElement instanceof FlowNode)) {
            return Collections.emptyList();
        }

        FlowNode startFlowNode = (FlowNode) startElement;
        List<SequenceFlow> outgoingFlows = startFlowNode.getOutgoingFlows();
        if (outgoingFlows == null || outgoingFlows.isEmpty()) {
            return Collections.emptyList();
        }

        List<FlowElement> targets = resolveOutgoingTargets(bpmnModel, outgoingFlows, variables);
        if (targets.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> visited = new HashSet<>();
        List<String> endEventIds = new ArrayList<>();

        for (FlowElement target : targets) {
            collectEndEvents(bpmnModel, target, variables, visited, endEventIds, false);
        }

        return endEventIds;
    }

    /**
     * 沿 BPMN 图正向深度遍历，收集所有可达的流程级 EndEvent。
     *
     * <p>遇到 UserTask 时仅停止当前分支，不阻断其他分支的 EndEvent 收集。
     * SubProcess 和 CallActivity 内部的 EndEvent 不视为流程级终止，不收集。</p>
     *
     * @param bpmnModel    当前 BPMN 模型
     * @param element      当前遍历到的元素
     * @param variables    变量上下文，用于评估网关条件
     * @param visited      已访问节点 ID 集合（防无限循环）
     * @param endEventIds  已收集的流程级 EndEvent ID
     * @param inSubProcess 是否在子流程内部遍历
     */
    private void collectEndEvents(BpmnModel bpmnModel, FlowElement element,
                                   Map<String, Object> variables,
                                   Set<String> visited, List<String> endEventIds,
                                   boolean inSubProcess) {
        // UserTask: 停止当前分支但不影响其他分支
        if (element instanceof UserTask) {
            return;
        }

        // EndEvent: 流程级收集；子流程内部不收集
        if (element instanceof EndEvent) {
            if (!inSubProcess && !endEventIds.contains(element.getId())) {
                endEventIds.add(element.getId());
            }
            return;
        }

        // 防无限循环
        if (!visited.add(element.getId())) {
            return;
        }

        // SubProcess: 进入内部遍历，内部 EndEvent 不视为流程级终止
        if (element instanceof SubProcess) {
            SubProcess subProcess = (SubProcess) element;
            collectSubProcessEndEvents(bpmnModel, subProcess, variables, visited, endEventIds);
            // 继续走 SubProcess 的 outgoing（由后续 FlowNode 块处理）
        }

        // CallActivity: 加载被调用流程定义并进入遍历
        if (element instanceof CallActivity) {
            CallActivity callActivity = (CallActivity) element;
            String calledElement = callActivity.getCalledElement();
            if (calledElement != null && !calledElement.isEmpty()) {
                BpmnModel calledModel = bpmnModelCache.getBpmnModelByProcessKey(calledElement);
                if (calledModel != null) {
                    StartEvent calledStartEvent = findStartEvent(calledModel);
                    if (calledStartEvent != null) {
                        collectCallActivityEndEvents(calledModel, calledStartEvent,
                                variables, visited, endEventIds);
                    }
                }
            }
        }

        // FlowNode: 遍历 outgoing flows
        if (element instanceof FlowNode) {
            FlowNode flowNode = (FlowNode) element;
            List<SequenceFlow> outgoingFlows = flowNode.getOutgoingFlows();
            if (outgoingFlows == null || outgoingFlows.isEmpty()) {
                return;
            }

            List<FlowElement> targets = resolveOutgoingTargets(bpmnModel, outgoingFlows, variables);
            for (FlowElement target : targets) {
                collectEndEvents(bpmnModel, target, variables, visited, endEventIds, inSubProcess);
            }
        }
    }

    /**
     * 遍历 SubProcess 内部节点收集 EndEvent（内部 EndEvent 不视为流程级终止）。
     */
    private void collectSubProcessEndEvents(BpmnModel bpmnModel, SubProcess subProcess,
                                              Map<String, Object> variables,
                                              Set<String> visited, List<String> endEventIds) {
        if (subProcess.getFlowElements() == null) {
            return;
        }
        for (FlowElement subElement : subProcess.getFlowElements()) {
            if (subElement instanceof StartEvent && subElement instanceof FlowNode) {
                FlowNode subStart = (FlowNode) subElement;
                List<SequenceFlow> subOutgoing = subStart.getOutgoingFlows();
                if (subOutgoing != null) {
                    for (SequenceFlow flow : subOutgoing) {
                        FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                        if (target != null) {
                            collectEndEvents(bpmnModel, target, variables, visited, endEventIds, true);
                        }
                    }
                }
            }
        }
    }

    /**
     * 遍历 CallActivity 引用的流程定义内部节点收集 EndEvent（内部 EndEvent 不视为流程级终止）。
     */
    private void collectCallActivityEndEvents(BpmnModel calledModel, StartEvent startEvent,
                                                Map<String, Object> variables,
                                                Set<String> visited, List<String> endEventIds) {
        if (startEvent instanceof FlowNode) {
            FlowNode startFlowNode = (FlowNode) startEvent;
            List<SequenceFlow> outgoing = startFlowNode.getOutgoingFlows();
            if (outgoing != null) {
                for (SequenceFlow flow : outgoing) {
                    FlowElement target = calledModel.getFlowElement(flow.getTargetRef());
                    if (target != null) {
                        collectEndEvents(calledModel, target, variables, visited, endEventIds, true);
                    }
                }
            }
        }
    }

    /**
     * 从 outgoing flows 解析目标元素，支持条件表达式过滤。
     * 当 variables 为非 null 时评估条件；为 null 时不评估，全部返回。
     *
     * @return 过滤后的目标元素列表；全部被过滤掉时返回空列表
     */
    private List<FlowElement> resolveOutgoingTargets(BpmnModel model,
                                                       List<SequenceFlow> outgoingFlows,
                                                       Map<String, Object> variables) {
        List<FlowElement> result = new ArrayList<>();
        for (SequenceFlow flow : outgoingFlows) {
            if (variables != null && flow.getConditionExpression() != null
                    && !flow.getConditionExpression().isEmpty()) {
                if (!evaluateCondition(flow.getConditionExpression(), variables)) {
                    continue;
                }
            }
            FlowElement target = model.getFlowElement(flow.getTargetRef());
            if (target != null) {
                result.add(target);
            }
        }
        return result;
    }

    /**
     * 评估 BPMN 条件表达式。
     */
    private boolean evaluateCondition(String conditionExpression, Map<String, Object> variables) {
        try {
            String expressionText = conditionExpression;
            if (expressionText.startsWith("${") && expressionText.endsWith("}")) {
                expressionText = expressionText.substring(2, expressionText.length() - 1);
            }
            Expression expression = expressionManager.createExpression(expressionText);
            MapVariableContainer container = new MapVariableContainer(variables);
            Object value = expression.getValue(container);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception e) {
            // 条件评估失败时跳过该分支，不展示不确定的审批人
            return false;
        }
    }

    /**
     * 简单的 VariableContainer 适配器，将 Map 包装为 Flowable 的 VariableContainer 接口。
     */
    private static class MapVariableContainer implements org.flowable.common.engine.api.variable.VariableContainer {
        private final Map<String, Object> variables;

        MapVariableContainer(Map<String, Object> variables) {
            this.variables = variables;
        }

        @Override
        public boolean hasVariable(String variableName) {
            return variables != null && variables.containsKey(variableName);
        }

        @Override
        public Object getVariable(String variableName) {
            return variables != null ? variables.get(variableName) : null;
        }

        @Override
        public void setVariable(String name, Object value) {
            // 条件评估场景下只读，不修改变量
        }

        @Override
        public void setTransientVariable(String name, Object value) {
            // 条件评估场景下只读，不修改变量
        }

        @Override
        public String getTenantId() {
            return null;
        }
    }

    /**
     * 从指定元素开始向前追踪，找到第一个 UserTask。
     */
    private String traceForward(BpmnModel bpmnModel, FlowElement element, Set<String> visited) {
        if (!visited.add(element.getId())) {
            return null;
        }

        if (element instanceof UserTask) {
            return element.getId();
        }

        if (element instanceof FlowNode) {
            FlowNode flowNode = (FlowNode) element;
            List<SequenceFlow> outgoingFlows = flowNode.getOutgoingFlows();
            if (outgoingFlows != null) {
                for (SequenceFlow flow : outgoingFlows) {
                    FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                    if (target != null) {
                        String result = traceForward(bpmnModel, target, visited);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            }
        }

        return null;
    }
}
