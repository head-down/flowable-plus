package io.github.flowable.plus.core.internal;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
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

    BpmnModel build() {
        return bpmnModel;
    }
}
