package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.vo.DiagramStatesVO;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流程图集成测试。
 *
 * @author flowable-plus
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
class DiagramIntegrationTest extends AbstractIntegrationTest {

    private static final String PROCESS_KEY = "testDiagramProcess";
    private static final String INITIATOR = "initiator";
    private static final String APPROVER = "approver1";

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FlowablePlus flowablePlus;

    private String deploymentId;
    private final List<String> processInstanceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BpmnModel model = buildProcess();
        Deployment deployment = repositoryService.createDeployment()
                .addBpmnModel(PROCESS_KEY + ".bpmn20.xml", model)
                .key(PROCESS_KEY)
                .deploy();
        deploymentId = deployment.getId();
        processInstanceIds.clear();
    }

    @AfterEach
    void tearDown() {
        BpmnQueryIntegrationTest.DynamicUserContext.CURRENT_USER.remove();
        for (String piId : processInstanceIds) {
            try {
                runtimeService.deleteProcessInstance(piId, "test cleanup");
            } catch (Exception ignored) {
            }
        }
        if (deploymentId != null) {
            try {
                repositoryService.deleteDeployment(deploymentId, true);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void testGetXml() {
        String piId = startAndAdvanceToApproval();
        processInstanceIds.add(piId);

        String pdId = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).latestVersion().singleResult().getId();

        ProcessDiagramVO result = flowablePlus.getProcessDiagramXml(pdId);
        assertThat(result.getProcessDefinitionId()).isEqualTo(pdId);
        assertThat(result.getXml()).contains(PROCESS_KEY);
    }

    @Test
    void testStatesWithActiveNode() {
        String piId = startAndAdvanceToApproval();
        processInstanceIds.add(piId);

        DiagramStatesVO states = flowablePlus.getProcessDiagramStates(piId);

        assertThat(states.getStates().getCompleted()).contains("initiateTask");
        assertThat(states.getStates().getActive()).contains("approvalTask");
    }

    @Test
    void testStatesForCompletedProcess() {
        BpmnQueryIntegrationTest.DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                Collections.singletonMap("initiator", (Object) INITIATOR));
        String piId = pi.getId();
        processInstanceIds.add(piId);
        completeTaskForProcessInstance(piId);
        completeTaskForProcessInstance(piId);

        DiagramStatesVO states = flowablePlus.getProcessDiagramStates(piId);
        assertThat(states.getStates().getActive()).isEmpty();
        assertThat(states.getStates().getCompleted()).contains("initiateTask", "approvalTask");
    }

    @Test
    void testCompletedFlows() {
        BpmnQueryIntegrationTest.DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                Collections.singletonMap("initiator", (Object) INITIATOR));
        String piId = pi.getId();
        processInstanceIds.add(piId);
        completeTaskForProcessInstance(piId);
        completeTaskForProcessInstance(piId);

        DiagramStatesVO states = flowablePlus.getProcessDiagramStates(piId);
        assertThat(states.getCompletedFlows()).isNotEmpty();
    }

    @Test
    void testActiveTasks() {
        String piId = startAndAdvanceToApproval();
        processInstanceIds.add(piId);

        DiagramStatesVO states = flowablePlus.getProcessDiagramStates(piId);

        assertThat(states.getActiveTasks()).hasSize(1);
        DiagramStatesVO.TaskBriefVO task = states.getActiveTasks().get(0);
        assertThat(task.getTaskId()).isNotNull();
        assertThat(task.getActivityId()).isEqualTo("approvalTask");
        assertThat(task.getTaskName()).isEqualTo("审批");
        assertThat(task.getAssignee()).isEqualTo(APPROVER);
    }

    @Test
    void testActiveTasksEmptyForCompletedProcess() {
        BpmnQueryIntegrationTest.DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                Collections.singletonMap("initiator", (Object) INITIATOR));
        String piId = pi.getId();
        processInstanceIds.add(piId);
        completeTaskForProcessInstance(piId);
        completeTaskForProcessInstance(piId);

        DiagramStatesVO states = flowablePlus.getProcessDiagramStates(piId);
        assertThat(states.getActiveTasks()).isEmpty();
    }

    // ======================== helpers ========================

    private String startAndAdvanceToApproval() {
        BpmnQueryIntegrationTest.DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                Collections.singletonMap("initiator", (Object) INITIATOR));
        String piId = pi.getId();
        completeTaskForProcessInstance(piId);
        return piId;
    }

    private void completeTaskForProcessInstance(String processInstanceId) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult();
        if (task != null) {
            BpmnQueryIntegrationTest.DynamicUserContext.set(task.getAssignee());
            taskService.complete(task.getId());
        }
    }

    private BpmnModel buildProcess() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId(PROCESS_KEY);
        process.setName("测试流程");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask initTask = new UserTask();
        initTask.setId("initiateTask");
        initTask.setName("发起");
        initTask.setAssignee("${initiator}");
        process.addFlowElement(initTask);

        UserTask approvalTask = new UserTask();
        approvalTask.setId("approvalTask");
        approvalTask.setName("审批");
        approvalTask.setAssignee(APPROVER);
        process.addFlowElement(approvalTask);

        addFlow(process, "f_start_init", start, initTask);
        addFlow(process, "f_init_approval", initTask, approvalTask);

        return model;
    }

    private static void addFlow(Process process, String id, FlowElement sourceEl, FlowElement targetEl) {
        SequenceFlow flow = new SequenceFlow();
        flow.setId(id);
        flow.setSourceRef(sourceEl.getId());
        flow.setTargetRef(targetEl.getId());
        process.addFlowElement(flow);

        if (sourceEl instanceof FlowNode) {
            FlowNode sourceNode = (FlowNode) sourceEl;
            if (sourceNode.getOutgoingFlows() == null) {
                sourceNode.setOutgoingFlows(new ArrayList<>());
            }
            sourceNode.getOutgoingFlows().add(flow);
        }
        if (targetEl instanceof FlowNode) {
            FlowNode targetNode = (FlowNode) targetEl;
            if (targetNode.getIncomingFlows() == null) {
                targetNode.setIncomingFlows(new ArrayList<>());
            }
            targetNode.getIncomingFlows().add(flow);
        }
    }
}
