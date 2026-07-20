package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.domain.PlusProcessInstance;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow;

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
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.flowable.plus.starter.BpmnQueryIntegrationTest.DynamicUserContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layer 4 集成测试：流程生命周期操作。
 *
 * <p>使用 test-simple-linear.bpmn20.xml 测试 startProcess / revokeProcess。
 * 验证发起流程、撤销流程（权限校验和状态校验）。</p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class ProcessLifecycleIntegrationTest {

    private static final String PROCESS_KEY = "testSimpleLinear";
    private static final String INITIATOR = "initiator";
    private static final String APPROVER = "approver";
    private static final String OTHER_USER = "other";

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessLifecycleWorkflow processLifecycleWorkflow;

    @Autowired
    private FlowablePlus flowablePlus;

    private String deploymentId;
    private final List<String> processInstanceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource("bpmn/test-simple-linear.bpmn20.xml")
                .deploy();
        deploymentId = deployment.getId();
        processInstanceIds.clear();
    }

    @AfterEach
    void tearDown() {
        DynamicUserContext.CURRENT_USER.remove();
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
                // 忽略清理错误
            }
        }
    }

    // ======================== 发起流程 ========================

    @Test
    void testStartProcess() {
        DynamicUserContext.set(INITIATOR);

        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver", APPROVER);

        PlusProcessInstance result = processLifecycleWorkflow.startProcess(
                PROCESS_KEY, "biz-lifecycle-001", variables);

        processInstanceIds.add(result.getProcessInstanceId());

        assertThat(result.getProcessInstanceId()).isNotNull();
        assertThat(result.getBusinessKey()).isEqualTo("biz-lifecycle-001");
        assertThat(result.getProcessDefinitionId()).isNotNull();

        // 验证流程实例存在且任务在 draft 节点
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(result.getProcessInstanceId()).singleResult();
        assertThat(pi).isNotNull();

        Task task = taskService.createTaskQuery()
                .processInstanceId(result.getProcessInstanceId()).active().singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("draft");
    }

    @Test
    void testStartProcessWithProcessDefinitionKey() {
        DynamicUserContext.set(INITIATOR);

        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver", APPROVER);

        PlusProcessInstance result = processLifecycleWorkflow.startProcess(
                PROCESS_KEY, null, variables);

        processInstanceIds.add(result.getProcessInstanceId());

        assertThat(result.getProcessInstanceId()).isNotNull();
        assertThat(result.getBusinessKey()).isNull();
    }

    // ======================== 撤销流程 ========================

    @Test
    void testRevokeProcessAtFirstNode() {
        DynamicUserContext.set(INITIATOR);

        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver", APPROVER);

        PlusProcessInstance result = processLifecycleWorkflow.startProcess(
                PROCESS_KEY, "biz-revoke-001", variables);

        processInstanceIds.add(result.getProcessInstanceId());

        // 撤销流程（仍在首个节点）
        processLifecycleWorkflow.revokeProcess(result.getProcessInstanceId(), "发起人撤销");

        // 验证流程已结束
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(result.getProcessInstanceId()).singleResult();
        assertThat(pi).isNull();
    }

    @Test
    void testRevokeProcessAfterAdvanceShouldFail() {
        DynamicUserContext.set(INITIATOR);

        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver", APPROVER);

        PlusProcessInstance result = processLifecycleWorkflow.startProcess(
                PROCESS_KEY, "biz-revoke-002", variables);

        processInstanceIds.add(result.getProcessInstanceId());

        // 完成 draft → 流转到 deptApprove
        Task draftTask = taskService.createTaskQuery()
                .processInstanceId(result.getProcessInstanceId())
                .taskDefinitionKey("draft").active().singleResult();
        assertThat(draftTask).isNotNull();
        taskService.claim(draftTask.getId(), INITIATOR);
        taskService.complete(draftTask.getId());

        // 此时流程已推进到 deptApprove → 撤销应失败
        assertThatThrownBy(() -> processLifecycleWorkflow.revokeProcess(
                result.getProcessInstanceId(), "试图撤销已推进的流程"))
                .isInstanceOf(TaskAlreadyCompletedException.class);
    }

    @Test
    void testRevokeProcessByWrongUserShouldFail() {
        DynamicUserContext.set(INITIATOR);

        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver", APPROVER);

        PlusProcessInstance result = processLifecycleWorkflow.startProcess(
                PROCESS_KEY, "biz-revoke-003", variables);

        processInstanceIds.add(result.getProcessInstanceId());

        // 其他用户尝试撤销 → 应失败
        DynamicUserContext.set(OTHER_USER);
        assertThatThrownBy(() -> processLifecycleWorkflow.revokeProcess(
                result.getProcessInstanceId(), "非发起人撤销"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    // ======================== 待办查询（发起后） ========================

    @Test
    void testTodoQueryAfterStart() {
        DynamicUserContext.set(INITIATOR);

        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver", APPROVER);

        PlusProcessInstance result = processLifecycleWorkflow.startProcess(
                PROCESS_KEY, "biz-todo-001", variables);

        processInstanceIds.add(result.getProcessInstanceId());

        // 发起人应看到 draft 待办
        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        PageResult<?> todo = flowablePlus.queryTodoTasks(INITIATOR, query);
        assertThat(todo.getTotal()).isGreaterThanOrEqualTo(1);
    }
}
