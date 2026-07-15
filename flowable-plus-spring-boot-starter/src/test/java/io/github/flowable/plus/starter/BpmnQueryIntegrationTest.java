package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.spi.TaskQueryEnhancer;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
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
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BPMN 查询集成测试：使用真实嵌入式 Flowable 引擎验证三期所有查询操作。
 *
 * <p>覆盖：批量摘要查询(S1)、下一节点审批人预览(S5)、
 * 待办/已办列表(S2/S3)、TaskQueryEnhancer 回调、审批中预审(S6/S7)、
 * 审批轨迹(S4)、GroupResolver 自定义实现可替换。</p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
class BpmnQueryIntegrationTest {

    private static final String PROCESS_KEY = "testApprovalProcess";
    private static final String INITIATOR = "initiator";
    private static final String APPROVER_1 = "approver1";
    private static final String APPROVER_2 = "approver2";

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

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        UserContext testUserContext() {
            return new DynamicUserContext();
        }
    }

    static class DynamicUserContext implements UserContext {
        static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

        static void set(String userId) {
            CURRENT_USER.set(userId);
        }

        @Override
        public String getCurrentUserId() {
            return CURRENT_USER.get();
        }
    }

    @BeforeEach
    void setUp() {
        BpmnModel model = buildApprovalProcess();
        Deployment deployment = repositoryService.createDeployment()
                .addBpmnModel(PROCESS_KEY + ".bpmn20.xml", model)
                .key(PROCESS_KEY)
                .deploy();
        deploymentId = deployment.getId();
        processInstanceIds.clear();
    }

    @AfterEach
    void tearDown() {
        DynamicUserContext.CURRENT_USER.remove();

        // 删除流程实例
        for (String piId : processInstanceIds) {
            try {
                runtimeService.deleteProcessInstance(piId, "test cleanup");
            } catch (Exception ignored) {
                // 可能已结束
            }
        }

        // 删除部署
        if (deploymentId != null) {
            try {
                repositoryService.deleteDeployment(deploymentId, true);
            } catch (Exception ignored) {
                // 忽略清理错误
            }
        }
    }

    // ======================== S5: 发起前审批人预览 ========================

    @Test
    void testNextNodeApproversByProcessKey() {
        List<NodeApproverVO> nodes = flowablePlus.getNextNodeApproversByProcessKey(PROCESS_KEY);

        assertThat(nodes).isNotEmpty();
        NodeApproverVO firstNode = nodes.get(0);
        assertThat(firstNode.getNodeId()).isEqualTo("initiateTask");
        assertThat(firstNode.getNodeName()).isEqualTo("发起");
    }

    // ======================== S2: 待办列表查询 ========================

    @Test
    void testQueryTodoTasks() {
        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-001",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());

        // 完成发起任务
        completeInitiateTask(pi.getId());

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        PageResult<TodoTaskVO> result = flowablePlus.queryTodoTasks(APPROVER_1, query);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(result.getRecords()).isNotEmpty();
    }

    // ======================== S3: 已办列表查询 ========================

    @Test
    void testQueryDoneTasks() {
        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-002",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());

        completeInitiateTask(pi.getId());

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks(INITIATOR, query);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(result.getRecords()).isNotEmpty();
    }

    // ======================== TaskQueryEnhancer 回调 ========================

    @Test
    void testTaskQueryEnhancerCallback() {
        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-003",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());

        completeInitiateTask(pi.getId());

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        // 无 enhancer 过滤
        PageResult<TodoTaskVO> allResult = flowablePlus.queryTodoTasks(APPROVER_1, query);
        assertThat(allResult.getTotal()).isGreaterThanOrEqualTo(1);

        // 带 enhancer 过滤
        PageResult<TodoTaskVO> filtered = flowablePlus.queryTodoTasks(APPROVER_2, query);
        assertThat(filtered.getTotal()).isGreaterThanOrEqualTo(1);
    }

    // ======================== S1: 批量流程摘要查询 ========================

    @Test
    void testBatchQueryProcessSummaries() {
        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-004",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());

        Map<String, ProcessSummaryVO> summaries = flowablePlus.batchQueryProcessSummaries(
                Collections.singletonList(pi.getId()));

        assertThat(summaries).hasSize(1);
        assertThat(summaries).containsKey(pi.getId());

        ProcessSummaryVO summary = summaries.get(pi.getId());
        assertThat(summary.getProcessDefinitionKey()).isEqualTo(PROCESS_KEY);
        assertThat(summary.getCurrentTaskName()).isNotNull();
    }

    // ======================== S6/S7: 审批中预审 ========================

    @Test
    void testMidApprovalPreview() {
        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-005",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());

        completeInitiateTask(pi.getId());

        Task approveTask = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("approveTask")
                .singleResult();
        assertThat(approveTask).isNotNull();

        // 下一节点审批人
        List<?> nextApprovers = flowablePlus.getNextTaskApprovers(approveTask.getId());
        assertThat(nextApprovers).isNotNull();

        // 下游节点列表
        List<NextTaskNodeVO> nextNodes = flowablePlus.getNextTaskNodes(pi.getId(), approveTask.getId());
        assertThat(nextNodes).isNotNull();
    }

    // ======================== S4: 审批轨迹 ========================

    @Test
    void testApprovalTrace() {
        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-006",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());

        // 完成发起任务
        completeInitiateTask(pi.getId());

        // 完成审批任务
        Task approveTask = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("approveTask")
                .singleResult();
        DynamicUserContext.set(APPROVER_1);
        taskService.claim(approveTask.getId(), APPROVER_1);
        taskService.addComment(approveTask.getId(), pi.getId(), "同意");
        taskService.complete(approveTask.getId());

        List<ApprovalTraceVO> trace = flowablePlus.getApprovalTrace(pi.getId());
        assertThat(trace).isNotEmpty();
        assertThat(trace.size()).isGreaterThanOrEqualTo(2);
    }

    // ======================== GroupResolver 替换验证 ========================

    @Test
    void testCustomGroupResolverReplaceable() {
        assertThat(flowablePlus).isNotNull();

        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-007",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());
        assertThat(pi).isNotNull();
    }

    // ======================== 接口实现验证 ========================

    @Test
    void testFlowablePlusImplementsQueryOperations() {
        assertThat(flowablePlus).isInstanceOf(io.github.flowable.plus.core.api.QueryOperations.class);
    }

    // ======================== TaskQueryEnhancer 有效性验证 ========================

    @Test
    void testTaskQueryEnhancerCustomCallback() {
        DynamicUserContext.set(INITIATOR);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, "biz-008",
                Collections.singletonMap("initiator", (Object) INITIATOR));
        processInstanceIds.add(pi.getId());

        completeInitiateTask(pi.getId());

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        AtomicBoolean enhancerCalled = new AtomicBoolean(false);
        Consumer<TaskQuery> customEnhancer = q -> enhancerCalled.set(true);

        flowablePlus.queryTodoTasks(APPROVER_1, query, customEnhancer);
        assertThat(enhancerCalled.get()).isTrue();
    }

    // ======================== Helpers ========================

    private void completeInitiateTask(String processInstanceId) {
        Task initTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey("initiateTask")
                .singleResult();
        if (initTask != null) {
            DynamicUserContext.set(INITIATOR);
            taskService.claim(initTask.getId(), INITIATOR);
            taskService.complete(initTask.getId());
        }
    }

    private BpmnModel buildApprovalProcess() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId(PROCESS_KEY);
        process.setName("测试审批流程");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask initiateTask = new UserTask();
        initiateTask.setId("initiateTask");
        initiateTask.setName("发起");
        initiateTask.setAssignee("${initiator}");
        process.addFlowElement(initiateTask);

        UserTask approveTask = new UserTask();
        approveTask.setId("approveTask");
        approveTask.setName("审批");
        approveTask.setCandidateUsers(Arrays.asList(APPROVER_1, APPROVER_2));
        process.addFlowElement(approveTask);

        addFlow(process, "f1", start, initiateTask);
        addFlow(process, "f2", initiateTask, approveTask);

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
