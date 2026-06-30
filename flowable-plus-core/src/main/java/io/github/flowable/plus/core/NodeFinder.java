package io.github.flowable.plus.core;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricActivityInstance;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BPMN 模型 + 历史数据混合查找引擎，用于在流程定义中查找节点的前驱和后继。
 *
 * <p>本类通过遍历 BPMN 模型并结合历史活动实例数据，实现以下查找能力：</p>
 * <ul>
 *   <li>向后查找——从当前节点反向追踪上一审批节点，处理排他网关和并行网关</li>
 *   <li>向前查找——从 StartEvent 正向追踪第一个 UserTask 作为发起人节点</li>
 * </ul>
 *
 * <p>包级私有类，仅由 {@link FlowablePlus} 内部使用。</p>
 */
@RequiredArgsConstructor
class NodeFinder {

    private final RepositoryService repositoryService;
    private final HistoryService historyService;

    /**
     * 向后查找上一审批节点。
     *
     * @param processDefinitionId 流程定义 ID
     * @param currentActivityId   当前节点 ID
     * @param processInstanceId   流程实例 ID（用于查询历史数据判定网关分支，可为 null）
     * @return 上一审批节点 ID 列表，无上一节点时返回空列表
     */
    List<String> findPreviousNodes(String processDefinitionId, String currentActivityId, String processInstanceId) {
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            return Collections.emptyList();
        }

        FlowElement currentElement = bpmnModel.getFlowElement(currentActivityId);
        if (currentElement == null) {
            return Collections.emptyList();
        }

        Set<String> visited = new HashSet<>();
        List<String> result = new ArrayList<>();
        traceBackward(bpmnModel, currentElement, processInstanceId, visited, result);
        return result;
    }

    /**
     * 向前查找流程发起人节点（第一个 UserTask）。
     *
     * @param processDefinitionId 流程定义 ID
     * @return 第一个 UserTask 的 ID，未找到时返回 null
     */
    String findInitiatorNode(String processDefinitionId) {
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            return null;
        }

        if (bpmnModel.getProcesses() == null || bpmnModel.getProcesses().isEmpty()) {
            return null;
        }

        // 从 StartEvent 正向追踪第一个 UserTask
        for (FlowElement element : bpmnModel.getProcesses().get(0).getFlowElements()) {
            if (element instanceof StartEvent) {
                Set<String> visited = new HashSet<>();
                String userTaskId = traceForward(bpmnModel, element, visited);
                if (userTaskId != null) {
                    return userTaskId;
                }
            }
        }
        return null;
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
                // 直接上游是 UserTask
                result.add(source.getId());
            } else if (source instanceof ExclusiveGateway) {
                // 排他网关：通过历史数据判定实际执行的分支
                traceExclusiveGatewayBackward(bpmnModel, (ExclusiveGateway) source, processInstanceId, visited, result);
            } else if (source instanceof ParallelGateway) {
                // 并行网关：所有分支都经过此处，递归收集所有上游
                traceBackward(bpmnModel, source, processInstanceId, visited, result);
            } else if (source instanceof StartEvent) {
                // 到达 StartEvent，无上一审批节点
            }
            // 其他节点类型（如 ServiceTask、SubProcess 等）暂不处理
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
                // 解析到上一审批节点，直接添加
                result.add(source.getId());
            } else {
                // 继续向后追踪（如穿越多个网关的场景）
                traceBackward(bpmnModel, source, processInstanceId, visited, result);
            }
        }
    }

    /**
     * 解析排他网关的实际执行分支。
     *
     * <p>策略：查询该网关在流程实例中的历史执行记录，找到网关之后执行的第一个活动，
     * 反推出实际经过的 SequenceFlow。</p>
     */
    private List<SequenceFlow> resolveExclusiveGateway(String processInstanceId, List<SequenceFlow> incomingFlows) {
        if (incomingFlows == null || incomingFlows.isEmpty()) {
            return Collections.emptyList();
        }

        if (processInstanceId == null) {
            // 无历史数据时返回所有入边（回退策略）
            return incomingFlows;
        }

        // 查询该流程实例中最后完成的几个活动
        List<HistoricActivityInstance> historicInstances = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .orderByHistoricActivityInstanceEndTime().desc()
                .list();

        // 对于排他网关的每条入边，检查其源节点是否在历史记录中出现
        for (SequenceFlow flow : incomingFlows) {
            for (HistoricActivityInstance instance : historicInstances) {
                if (flow.getSourceRef().equals(instance.getActivityId())) {
                    // 该分支实际被执行过
                    return Collections.singletonList(flow);
                }
            }
        }

        // 未匹配到，返回所有入边（回退策略）
        return incomingFlows;
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
