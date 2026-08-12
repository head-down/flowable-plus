package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.enums.CommentType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 常规审批操作多实例拦截改运行时判定（ADR-0034）集成测试。
 *
 * <p>使用 test-multi-instance.bpmn20.xml（start → draft → counterSign(会签) → confirmTask → end）：
 * <ul>
 *   <li>伪单例（counterSignUsers 只有 1 人）→ 放行 completeTask / rejectTask / withdrawTask /
 *       rejectTaskToInitiator / jumpToNode，且 reject/jump 后无孤立 miBody 执行残留、流程可继续</li>
 *   <li>真多实例（多人）→ 常规操作拦截，必须走 counterSign</li>
 *   <li>会签剩最后 1 人未投 → 保持拦截（历史任务数 &gt; 1 → 非伪单例）</li>
 * </ul></p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class RuntimeMultiInstanceIntegrationTest extends AbstractIntegrationTest {

    private static final String PROCESS_KEY = "testMultiInstance";
    private static final String INITIATOR = "initiator";
    private static final String USER_A = "user_a";
    private static final String USER_B = "user_b";

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

    // ======================== 伪单例放行 ========================

    @Test
    void testPseudoSingletonCompleteTaskAllowed() {
        ProcessInstance pi = startProcess("biz-ps-complete", USER_A);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        // 伪单例：completeTask 放行 → 推进到 confirmTask
        DynamicUserContext.set(USER_A);
        String csTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(csTaskId).isNotNull();
        taskExecutionWorkflow.completeTask(csTaskId, null, "同意");

        assertThat(findActiveTaskId(pi.getId(), "confirmTask")).isNotNull();
        assertThat(findActiveTaskId(pi.getId(), "counterSign")).isNull();
    }

    @Test
    void testPseudoSingletonRejectTaskAllowedAndNoStrayMiExecution() {
        ProcessInstance pi = startProcess("biz-ps-reject", USER_A);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(USER_A);
        String csTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(csTaskId).isNotNull();

        // 伪单例：rejectTask 放行 → 回到 draft
        taskExecutionWorkflow.rejectTask(csTaskId, "不同意");

        assertThat(findActiveTaskId(pi.getId(), "draft")).isNotNull();

        // 无孤立 miBody/子执行残留
        long miExecutions = runtimeService.createExecutionQuery()
                .processInstanceId(pi.getId())
                .activityId("counterSign")
                .count();
        assertThat(miExecutions).isZero();

        // 流程可继续：发起人重新提交 → 折返会签节点，任务干净重建（无残留）
        completeTask(pi.getId(), "draft", INITIATOR, "重新发起");
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId())
                .taskDefinitionKey("counterSign").active().count()).isEqualTo(1L);

        // 折返后全局历史任务数>1（含上一周期被跳离任务）→ 非伪单例，需走 counterSign
        DynamicUserContext.set(USER_A);
        String rebuiltCsTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(rebuiltCsTaskId).isNotNull();
        counterSignWorkflow.counterSign(rebuiltCsTaskId, true, null, "同意");
        assertThat(findActiveTaskId(pi.getId(), "confirmTask")).isNotNull();
    }

    @Test
    void testPseudoSingletonJumpToNodeAllowedAndNoStrayMiExecution() {
        ProcessInstance pi = startProcess("biz-ps-jump", USER_A);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(USER_A);
        String csTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(csTaskId).isNotNull();

        // 伪单例：jumpToNode 放行 → 跳回 draft
        taskExecutionWorkflow.jumpToNode(csTaskId, "draft", "退回修改", CommentType.RETURN);

        assertThat(findActiveTaskId(pi.getId(), "draft")).isNotNull();

        // 无孤立 miBody/子执行残留
        long miExecutions = runtimeService.createExecutionQuery()
                .processInstanceId(pi.getId())
                .activityId("counterSign")
                .count();
        assertThat(miExecutions).isZero();

        // 流程可继续：重新提交 → 折返会签节点 → 走 counterSign 投票完成
        completeTask(pi.getId(), "draft", INITIATOR, "重新发起");
        DynamicUserContext.set(USER_A);
        String rebuiltCsTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(rebuiltCsTaskId).isNotNull();
        counterSignWorkflow.counterSign(rebuiltCsTaskId, true, null, "同意");
        assertThat(findActiveTaskId(pi.getId(), "confirmTask")).isNotNull();
    }

    @Test
    void testPseudoSingletonWithdrawTaskAllowed() {
        ProcessInstance pi = startProcess("biz-ps-withdraw", USER_A);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        // 上一节点（draft）审批人=INITIATOR → 可撤回伪单例会签任务
        DynamicUserContext.set(INITIATOR);
        String csTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(csTaskId).isNotNull();
        taskExecutionWorkflow.withdrawTask(csTaskId, "发起人撤回");

        assertThat(findActiveTaskId(pi.getId(), "draft")).isNotNull();
    }

    @Test
    void testPseudoSingletonRejectTaskToInitiatorAllowed() {
        ProcessInstance pi = startProcess("biz-ps-reject-init", USER_A);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(USER_A);
        String csTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(csTaskId).isNotNull();

        // 伪单例：rejectTaskToInitiator 放行 → 回到发起人节点
        taskExecutionWorkflow.rejectTaskToInitiator(csTaskId, "驳回至发起人");

        assertThat(findActiveTaskId(pi.getId(), "draft")).isNotNull();
    }

    // ======================== 真多实例拦截 ========================

    @Test
    void testRealMultiInstanceCompleteTaskBlocked() {
        ProcessInstance pi = startProcess("biz-real-complete", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(USER_A);
        String csTaskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(csTaskId).isNotNull();

        assertThatThrownBy(() -> taskExecutionWorkflow.completeTask(csTaskId, null, "同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testRealMultiInstanceRejectTaskBlocked() {
        ProcessInstance pi = startProcess("biz-real-reject", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(USER_A);
        String csTaskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(csTaskId).isNotNull();

        assertThatThrownBy(() -> taskExecutionWorkflow.rejectTask(csTaskId, "不同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testRealMultiInstanceJumpToNodeBlocked() {
        ProcessInstance pi = startProcess("biz-real-jump", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(USER_A);
        String csTaskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(csTaskId).isNotNull();

        assertThatThrownBy(() -> taskExecutionWorkflow.jumpToNode(csTaskId, "draft", "退回", CommentType.RETURN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    // ======================== 会签剩最后 1 人未投拦截 ========================

    @Test
    void testLastUnvotedCompleteTaskBlocked() {
        ProcessInstance pi = startProcess("biz-last-unvoted", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        // USER_A 投票 → 只剩 USER_B 一人未投（历史任务数=2 → 非伪单例）
        counterSign(pi.getId(), USER_A, true, "同意");

        DynamicUserContext.set(USER_B);
        String lastTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(lastTaskId).isNotNull();

        // 最后 1 人未投仍属真多实例 → completeTask 拦截，必须走 counterSign
        assertThatThrownBy(() -> taskExecutionWorkflow.completeTask(lastTaskId, null, "同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");

        // counterSign 仍可正常投票完成
        counterSign(pi.getId(), USER_B, true, "同意");
        assertThat(findActiveTaskId(pi.getId(), "confirmTask")).isNotNull();
    }

    // ======================== Helpers ========================

    private ProcessInstance startProcess(String businessKey, String... signers) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("counterSignUsers", Arrays.asList(signers));

        identityService.setAuthenticatedUserId(INITIATOR);
        DynamicUserContext.set(INITIATOR);
        return runtimeService.startProcessInstanceByKey(PROCESS_KEY, businessKey, variables);
    }

    private void completeTask(String processInstanceId, String nodeId, String userId, String comment) {
        DynamicUserContext.set(userId);
        String taskId = findActiveTaskId(processInstanceId, nodeId);
        assertThat(taskId).withFailMessage("未找到节点 %s 的活跃任务", nodeId).isNotNull();
        taskExecutionWorkflow.completeTask(taskId, null, comment);
    }

    private void counterSign(String processInstanceId, String userId, boolean approved, String comment) {
        DynamicUserContext.set(userId);
        String taskId = findActiveCounterSignTaskId(processInstanceId, userId);
        assertThat(taskId).isNotNull();
        counterSignWorkflow.counterSign(taskId, approved, null, comment);
    }

    private String findActiveTaskId(String processInstanceId, String taskDefinitionKey) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .singleResult();
        return task != null ? task.getId() : null;
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
