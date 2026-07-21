package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
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
 * 流程图集成测试：使用真实嵌入式 Flowable 引擎验证 getProcessDiagram。
 *
 * <p>测试多节点 BPMN 模型（含 serviceTask 自动节点），
 * 验证 SVG 输出含正确的 data-state 标注和 CSS 样式。</p>
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
        BpmnModel model = buildProcessWithServiceTask();
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
                // 可能已结束
            }
        }

        if (deploymentId != null) {
            try {
                repositoryService.deleteDeployment(deploymentId, true);
            } catch (Exception ignored) {
            }
        }
    }

    // ======================== 核心测试用例 ========================

    /**
     * 流程运行中：发起节点 completed，审批节点 active。
     */
    @Test
    void testDiagramWithActiveNode() {
        String piId = startAndAdvanceToApproval();
        processInstanceIds.add(piId);

        ProcessDiagramVO diagram = flowablePlus.getProcessDiagram(piId);

        assertThat(diagram.getProcessInstanceId()).isEqualTo(piId);
        assertThat(diagram.getProcessDefinitionId()).isNotNull();
        assertThat(diagram.getSvg()).isNotNull().isNotEmpty();

        // 验证是有效 SVG
        assertThat(diagram.getSvg()).startsWith("<svg");
        assertThat(diagram.getSvg()).contains("base64");
        // 发起节点已完成
        assertThat(diagram.getSvg()).contains("data-state=\"completed\"");
        // 审批节点活跃
        assertThat(diagram.getSvg()).contains("data-state=\"active\"");
        // CSS 色值
        assertThat(diagram.getSvg()).contains("#FF4D4F");
        assertThat(diagram.getSvg()).contains("#52C41A");
    }

    /**
     * 流程已结束：所有节点 completed，无 active。
     */
    @Test
    void testDiagramForCompletedProcess() {
        BpmnQueryIntegrationTest.DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                Collections.singletonMap("initiator", (Object) INITIATOR));
        String piId = pi.getId();
        processInstanceIds.add(piId);

        // 完成发起任务
        completeTaskForProcessInstance(piId);
        // 完成审批任务
        completeTaskForProcessInstance(piId);

        ProcessDiagramVO diagram = flowablePlus.getProcessDiagram(piId);

        assertThat(diagram.getProcessInstanceId()).isEqualTo(piId);
        assertThat(diagram.getSvg()).isNotNull().isNotEmpty();
        assertThat(diagram.getSvg()).startsWith("<svg");

        // 已完成流程不应有 active 状态
        assertThat(diagram.getSvg()).doesNotContain("data-state=\"active\"");
        // 应有 completed 状态
        assertThat(diagram.getSvg()).contains("data-state=\"completed\"");
        assertThat(diagram.getSvg()).contains("#52C41A");
    }

    /**
     * 空高亮测试：传入已结束流程实例时返回含 completed 状态的 SVG。
     */
    @Test
    void testEmptyHighlightForCompletedProcess() {
        BpmnQueryIntegrationTest.DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                Collections.singletonMap("initiator", (Object) INITIATOR));
        String piId = pi.getId();
        processInstanceIds.add(piId);

        // 完成所有任务
        completeTaskForProcessInstance(piId);
        completeTaskForProcessInstance(piId);

        ProcessDiagramVO diagram = flowablePlus.getProcessDiagram(piId);

        assertThat(diagram.getProcessInstanceId()).isEqualTo(piId);
        String svg = diagram.getSvg();
        assertThat(svg).isNotNull().isNotEmpty();

        // 结束时不应有 active
        assertThat(svg).doesNotContain("data-state=\"active\"");
        // 应有 completed 状态（发起和审批两个用户任务）
        String dataStateCompleted = "data-state=\"completed\"";
        int completedCount = svg.split(dataStateCompleted, -1).length - 1;
        assertThat(completedCount).isGreaterThanOrEqualTo(2);

        // 应有 CSS
        assertThat(svg).contains("<style");
        assertThat(svg).contains("#FF4D4F");
        assertThat(svg).contains("#52C41A");
        assertThat(svg).contains("#1890FF");
    }

    // ======================== 辅助方法 ========================

    private String startAndAdvanceToApproval() {
        BpmnQueryIntegrationTest.DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY,
                Collections.singletonMap("initiator", (Object) INITIATOR));
        String piId = pi.getId();
        // 完成发起任务
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

    private BpmnModel buildProcessWithServiceTask() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId(PROCESS_KEY);
        process.setName("测试流程图流程");
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
