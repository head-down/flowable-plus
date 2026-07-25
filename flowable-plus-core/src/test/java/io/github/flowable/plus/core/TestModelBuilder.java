package io.github.flowable.plus.core;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 BPMN 模型构建工具。
 */
class TestModelBuilder {

    private final BpmnModel bpmnModel = new BpmnModel();
    private final Process process = new Process();

    TestModelBuilder() {
        process.setId("testProcess");
        bpmnModel.addProcess(process);
    }

    StartEvent addStartEvent(String id) {
        StartEvent event = new StartEvent();
        event.setId(id);
        process.addFlowElement(event);
        return event;
    }

    UserTask addUserTask(String id) {
        UserTask task = new UserTask();
        task.setId(id);
        process.addFlowElement(task);
        return task;
    }

    ServiceTask addServiceTask(String id) {
        ServiceTask serviceTask = new ServiceTask();
        serviceTask.setId(id);
        process.addFlowElement(serviceTask);
        return serviceTask;
    }

    EndEvent addEndEvent(String id) {
        EndEvent event = new EndEvent();
        event.setId(id);
        process.addFlowElement(event);
        return event;
    }

    UserTask addMultiInstanceUserTask(String id, boolean sequential, String completionCondition) {
        UserTask task = addUserTask(id);
        MultiInstanceLoopCharacteristics mic = new MultiInstanceLoopCharacteristics();
        mic.setSequential(sequential);
        if (completionCondition != null) {
            mic.setCompletionCondition(completionCondition);
        }
        task.setLoopCharacteristics(mic);
        return task;
    }

    ExclusiveGateway addExclusiveGateway(String id) {
        ExclusiveGateway gateway = new ExclusiveGateway();
        gateway.setId(id);
        process.addFlowElement(gateway);
        return gateway;
    }

    ParallelGateway addParallelGateway(String id) {
        ParallelGateway gateway = new ParallelGateway();
        gateway.setId(id);
        process.addFlowElement(gateway);
        return gateway;
    }

    SequenceFlow addSequenceFlow(String id, FlowElement source, FlowElement target) {
        SequenceFlow flow = new SequenceFlow();
        flow.setId(id);
        flow.setSourceRef(source.getId());
        flow.setTargetRef(target.getId());

        if (source instanceof org.flowable.bpmn.model.FlowNode) {
            org.flowable.bpmn.model.FlowNode sourceNode = (org.flowable.bpmn.model.FlowNode) source;
            List<SequenceFlow> outgoing = sourceNode.getOutgoingFlows();
            if (outgoing == null) {
                outgoing = new ArrayList<>();
                sourceNode.setOutgoingFlows(outgoing);
            }
            outgoing.add(flow);
        }
        if (target instanceof org.flowable.bpmn.model.FlowNode) {
            org.flowable.bpmn.model.FlowNode targetNode = (org.flowable.bpmn.model.FlowNode) target;
            List<SequenceFlow> incoming = targetNode.getIncomingFlows();
            if (incoming == null) {
                incoming = new ArrayList<>();
                targetNode.setIncomingFlows(incoming);
            }
            incoming.add(flow);
        }

        process.addFlowElement(flow);
        return flow;
    }

    SequenceFlow addSequenceFlowWithCondition(String id, FlowElement source, FlowElement target, String condition) {
        SequenceFlow flow = addSequenceFlow(id, source, target);
        flow.setConditionExpression(condition);
        return flow;
    }

    SubProcess addSubProcess(String id) {
        SubProcess subProcess = new SubProcess();
        subProcess.setId(id);
        process.addFlowElement(subProcess);
        return subProcess;
    }

    /**
     * 在子流程内构建线性节点链，从 StartEvent 开始连接 UserTask 节点。
     * 调用方应先通过 {@link #addSubProcess} 创建 SubProcess 实例。
     *
     * @param subProcess 子流程实例
     * @param chain 节点 ID 列表，如 "taskA", "taskB"
     */
    void buildSubProcessWithChain(SubProcess subProcess, String... chain) {
        if (chain == null || chain.length == 0) {
            return;
        }

        // 创建内部 StartEvent
        StartEvent subStart = new StartEvent();
        subStart.setId(subProcess.getId() + "_start");
        subProcess.addFlowElement(subStart);

        // 链的第一个节点
        FlowNode prevNode = subStart;
        for (int i = 0; i < chain.length; i++) {
            UserTask task = new UserTask();
            task.setId(chain[i]);
            subProcess.addFlowElement(task);

            SequenceFlow flow = new SequenceFlow();
            flow.setId(subProcess.getId() + "_f" + i);
            flow.setSourceRef(prevNode.getId());
            flow.setTargetRef(task.getId());

            List<SequenceFlow> outgoing = prevNode.getOutgoingFlows();
            if (outgoing == null) {
                outgoing = new ArrayList<>();
                prevNode.setOutgoingFlows(outgoing);
            }
            outgoing.add(flow);

            List<SequenceFlow> incoming = task.getIncomingFlows();
            if (incoming == null) {
                incoming = new ArrayList<>();
                task.setIncomingFlows(incoming);
            }
            incoming.add(flow);

            prevNode = task;
        }
    }

    BpmnModel build() {
        return bpmnModel;
    }
}
