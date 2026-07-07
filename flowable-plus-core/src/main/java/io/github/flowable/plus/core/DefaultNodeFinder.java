package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.common.engine.impl.el.ExpressionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

    private final HistoryService historyService;
    private final BpmnModelCache bpmnModelCache;
    private final ExpressionManager expressionManager;

    public DefaultNodeFinder(BpmnModelCache bpmnModelCache, HistoryService historyService,
                             ExpressionManager expressionManager) {
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
        traceBackward(bpmnModel, currentElement, processInstanceId, visited, result);

        if (result.isEmpty()) {
            throw new NoPreviousNodeException("节点 " + currentActivityId + " 无上一审批节点");
        }
        return result;
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
     * 从指定元素开始向后追踪，收集所有上一 UserTask。
     */
    private void traceBackward(BpmnModel bpmnModel, FlowElement element,
                                String processInstanceId, Set<String> visited, List<String> result) {
        if (!(element instanceof FlowNode)) {
            return;
        }

        if (!visited.add(element.getId())) {
            return; // 防止死循环
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
            } else if (source instanceof ExclusiveGateway) {
                traceExclusiveGatewayBackward(bpmnModel, (ExclusiveGateway) source, processInstanceId, visited, result);
            } else if (source instanceof ParallelGateway) {
                traceBackward(bpmnModel, source, processInstanceId, visited, result);
            } else if (source instanceof StartEvent) {
                // 到达 StartEvent，无上一审批节点
            }
        }
    }

    /**
     * 穿越排他网关向后追踪，通过历史数据判定实际执行路径。
     */
    private void traceExclusiveGatewayBackward(BpmnModel bpmnModel, ExclusiveGateway gateway,
                                                String processInstanceId, Set<String> visited, List<String> result) {
        if (!visited.add(gateway.getId())) {
            return;
        }

        List<SequenceFlow> resolvedFlows = resolveExclusiveGateway(processInstanceId, gateway.getIncomingFlows());
        for (SequenceFlow resolvedFlow : resolvedFlows) {
            FlowElement source = bpmnModel.getFlowElement(resolvedFlow.getSourceRef());
            if (source == null) {
                continue;
            }

            if (source instanceof UserTask) {
                result.add(source.getId());
            } else {
                traceBackward(bpmnModel, source, processInstanceId, visited, result);
            }
        }
    }

    /**
     * 解析排他网关的实际执行分支。
     */
    private List<SequenceFlow> resolveExclusiveGateway(String processInstanceId, List<SequenceFlow> incomingFlows) {
        if (incomingFlows == null || incomingFlows.isEmpty()) {
            return Collections.emptyList();
        }

        if (processInstanceId == null) {
            return incomingFlows;
        }

        List<HistoricActivityInstance> historicInstances = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .orderByHistoricActivityInstanceEndTime().desc()
                .list();

        for (SequenceFlow flow : incomingFlows) {
            for (HistoricActivityInstance instance : historicInstances) {
                if (flow.getSourceRef().equals(instance.getActivityId())) {
                    return Collections.singletonList(flow);
                }
            }
        }

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
        traceForwardAll(bpmnModel, startEvent, variables, visited, result);
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
     * 支持通过 variables 评估网关条件进行分支选择。
     */
    private void traceForwardAll(BpmnModel bpmnModel, FlowElement element,
                                 Map<String, Object> variables, Set<String> visited, List<String> result) {
        if (!visited.add(element.getId())) {
            return; // 防止循环
        }

        // 遇到 UserTask，收集并继续遍历
        if (element instanceof UserTask) {
            result.add(element.getId());
            // 注意：UserTask 可能后接网关，继续遍历 outgoing
        }

        if (element instanceof FlowNode) {
            FlowNode flowNode = (FlowNode) element;
            List<SequenceFlow> outgoingFlows = flowNode.getOutgoingFlows();
            if (outgoingFlows == null || outgoingFlows.isEmpty()) {
                return;
            }

            for (SequenceFlow flow : outgoingFlows) {
                // 评估网关条件
                if (variables != null && flow.getConditionExpression() != null
                        && !flow.getConditionExpression().isEmpty()) {
                    if (!evaluateCondition(flow.getConditionExpression(), variables)) {
                        continue;
                    }
                }

                FlowElement target = bpmnModel.getFlowElement(flow.getTargetRef());
                if (target != null) {
                    traceForwardAll(bpmnModel, target, variables, visited, result);
                }
            }
        }
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
            // 条件评估失败时不过滤分支，保持最大兼容性
            return true;
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
