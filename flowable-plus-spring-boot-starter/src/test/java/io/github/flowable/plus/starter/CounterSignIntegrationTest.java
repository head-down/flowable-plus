package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import io.github.flowable.plus.core.workflow.CounterSignWorkflow;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;

import org.flowable.engine.IdentityService;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.flowable.plus.starter.BpmnQueryIntegrationTest.DynamicUserContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 2 集成测试：会签操作。
 *
 * <p>使用 test-multi-instance.bpmn20.xml 测试 counterSign / addCounterSigner /
 * removeCounterSigner / delegateTask / resolveDelegate。
 * 并行多实例会签，全部同意后流转到发起人确认节点。</p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class CounterSignIntegrationTest {

    private static final String PROCESS_KEY = "testMultiInstance";
    private static final String INITIATOR = "initiator";
    private static final String USER_A = "user_a";
    private static final String USER_B = "user_b";
    private static final String USER_C = "user_c";
    private static final String EXTRA_USER = "extra_user";
    private static final String DELEGATE_TARGET = "delegate_target";

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private TaskExecutionWorkflow taskExecutionWorkflow;

    @Autowired
    private CounterSignWorkflow counterSignWorkflow;

    @Autowired
    private FlowablePlus flowablePlus;

    private String deploymentId;
    private final List<String> processInstanceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource("bpmn/test-multi-instance.bpmn20.xml")
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

    // ======================== 会签投票 ========================

    @Test
    void testCounterSignAllApprove() {
        ProcessInstance pi = startProcessWithSigners("biz-cs-001", USER_A, USER_B, USER_C);
        processInstanceIds.add(pi.getId());

        // 完成发起
        completeTask(pi.getId(), "draft", INITIATOR);

        // 所有会签人投票同意
        counterSign(pi.getId(), USER_A, true, "同意");
        counterSign(pi.getId(), USER_B, true, "通过");
        counterSign(pi.getId(), USER_C, true, "OK");

        // 验证流程推进到 confirmTask
        Task confirmTask = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("confirmTask")
                .active()
                .singleResult();
        assertThat(confirmTask).isNotNull();
    }

    @Test
    void testCounterSignPartialComplete() {
        ProcessInstance pi = startProcessWithSigners("biz-cs-002", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        // 完成发起
        completeTask(pi.getId(), "draft", INITIATOR);

        // 只有 USER_A 投票 → 流程还在会签节点
        counterSign(pi.getId(), USER_A, true, "同意");

        // 验证会签节点还有活跃任务
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("counterSign")
                .active()
                .list();
        assertThat(activeTasks).hasSize(1); // USER_B 的待签
    }

    // ======================== 加签 ========================

    @Test
    void testAddCounterSigner() {
        ProcessInstance pi = startProcessWithSigners("biz-addsign-001", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        // 完成发起 → 会签节点（USER_A, USER_B 各有一个任务）
        completeTask(pi.getId(), "draft", INITIATOR);

        // 获取 USER_A 的会签任务
        String taskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(taskId).isNotNull();

        // 加签 EXTRA_USER（仅上一节点审批人 INITIATOR 可操作）
        DynamicUserContext.set(INITIATOR);
        counterSignWorkflow.addCounterSigner(taskId, Arrays.asList(EXTRA_USER));

        // 验证 EXTRA_USER 也有待签任务
        List<Task> allTasks = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("counterSign")
                .active()
                .list();
        assertThat(allTasks).hasSize(3);

        // EXTRA_USER 可以完成投票
        counterSign(pi.getId(), EXTRA_USER, true, "加签同意");
    }

    // ======================== 减签 ========================

    @Test
    void testRemoveCounterSigner() {
        ProcessInstance pi = startProcessWithSigners("biz-removesign-001", USER_A, USER_B, USER_C);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        // 获取 USER_A 的会签任务
        String taskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(taskId).isNotNull();

        // 减签 USER_C（未投票、剩余 >= 2）
        DynamicUserContext.set(INITIATOR);
        counterSignWorkflow.removeCounterSigner(taskId, USER_C);

        // 验证 USER_C 的任务已移除
        List<Task> allTasks = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("counterSign")
                .active()
                .list();
        assertThat(allTasks).hasSize(2);

        List<String> assignees = new ArrayList<>();
        for (Task t : allTasks) {
            assignees.add(t.getAssignee());
        }
        assertThat(assignees).contains(USER_A, USER_B);
        assertThat(assignees).doesNotContain(USER_C);
    }

    // ======================== 委派与收回 ========================

    @Test
    void testDelegateAndResolve() {
        ProcessInstance pi = startProcessWithSigners("biz-delegate-001", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        // USER_A 委派给 DELEGATE_TARGET
        DynamicUserContext.set(USER_A);
        String taskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(taskId).isNotNull();

        counterSignWorkflow.delegateTask(taskId, DELEGATE_TARGET, "请帮忙审批");

        // DELEGATE_TARGET 现在拥有任务
        String delegatedTaskId = findActiveCounterSignTaskId(pi.getId(), DELEGATE_TARGET);
        assertThat(delegatedTaskId).isNotNull();

        // USER_A 收回委派
        counterSignWorkflow.resolveDelegate(delegatedTaskId);

        // 验证任务回到 USER_A
        String resolvedTaskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(resolvedTaskId).isNotNull();
    }

    // ======================== 查询验证 ========================

    @Test
    void testTodoQueryIncludesCounterSignTasks() {
        ProcessInstance pi = startProcessWithSigners("biz-query-cs-001", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        // USER_A 应看到会签待办
        PageResult<?> result = flowablePlus.queryTodoTasks(USER_A, query);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
    }

    // ======================== Helpers ========================

    private ProcessInstance startProcessWithSigners(String businessKey, String... signers) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("counterSignUsers", Arrays.asList(signers));

        identityService.setAuthenticatedUserId(INITIATOR);
        DynamicUserContext.set(INITIATOR);
        return runtimeService.startProcessInstanceByKey(PROCESS_KEY, businessKey, variables);
    }

    private void completeTask(String processInstanceId, String nodeId, String userId) {
        DynamicUserContext.set(userId);
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(nodeId)
                .active()
                .singleResult();
        if (task != null) {
            taskExecutionWorkflow.completeTask(task.getId(), null, "test");
        }
    }

    private void counterSign(String processInstanceId, String userId,
                              boolean approved, String comment) {
        DynamicUserContext.set(userId);
        String taskId = findActiveCounterSignTaskId(processInstanceId, userId);
        if (taskId != null) {
            counterSignWorkflow.counterSign(taskId, approved, null, comment);
        }
    }

    private String findActiveCounterSignTaskId(String processInstanceId, String userId) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey("counterSign")
                .taskAssignee(userId)
                .active()
                .singleResult();
        return task != null ? task.getId() : null;
    }
}
