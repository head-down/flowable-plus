package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.workflow.CounterSignWorkflow;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;

import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.Execution;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.flowable.plus.starter.BpmnQueryIntegrationTest.DynamicUserContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 折返后发起人决策任务放行常规操作（ADR-0035）集成测试。
 *
 * <p>使用 test-multi-instance.bpmn20.xml（start → draft → counterSign(会签) → confirmTask → end）：
 * <ul>
 *   <li>模式A折返：伪单例发起 → 发起人加签 → 全部投票 → confirmTask → 折返回 counterSign
 *       （counterSignUsers=[发起人] 重建 1 人 MI）→ 生成"折返后发起人决策任务"</li>
 *   <li>该任务运行时特征：活跃任务数==1、全局历史任务数&gt;1、assignee==countersignInitiator_&lt;key&gt;
 *       → 放行 rejectTask / rejectTaskToInitiator / jumpToNode / withdrawTask，且无孤立 miBody 残留</li>
 *   <li>completeTask 保持拦截；"会签剩最后 1 人未投"与无变量场景保持拦截</li>
 * </ul></p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class FoldbackInitiatorDecisionTaskIntegrationTest extends AbstractIntegrationTest {

    private static final String PROCESS_KEY = "testMultiInstance";
    private static final String INITIATOR = "initiator";
    private static final String USER_A = "user_a";
    private static final String USER_B = "user_b";

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

    // ======================== 折返发起人决策任务：4 个常规操作放行 ========================

    /**
     * 验收标准 1：折返后发起人决策任务上 rejectTask → 流程回到上一节点（draft），
     * 目标节点出现新待办，无 miBody 残留。
     */
    @Test
    void testFoldbackRejectTaskAllowedAndNoStrayMiExecution() {
        Task decisionTask = foldbackToInitiatorDecisionTask("biz-foldback-reject", USER_A);
        String piId = decisionTask.getProcessInstanceId();

        DynamicUserContext.set(INITIATOR);
        taskExecutionWorkflow.rejectTask(decisionTask.getId(), "不同意退回修改");

        assertThat(findActiveTaskId(piId, "draft")).isNotNull();
        assertThat(findActiveTaskId(piId, "counterSign")).isNull();

        // 无孤立 miBody/子执行残留
        assertThat(countMiExecutions(piId)).isZero();
    }

    /**
     * 验收标准 2：折返后发起人决策任务上 rejectTaskToInitiator → 回到发起人节点（draft）。
     */
    @Test
    void testFoldbackRejectTaskToInitiatorAllowed() {
        Task decisionTask = foldbackToInitiatorDecisionTask("biz-foldback-reject-init", USER_A);
        String piId = decisionTask.getProcessInstanceId();

        DynamicUserContext.set(INITIATOR);
        taskExecutionWorkflow.rejectTaskToInitiator(decisionTask.getId(), "驳回至发起人");

        assertThat(findActiveTaskId(piId, "draft")).isNotNull();
        assertThat(findActiveTaskId(piId, "counterSign")).isNull();
        assertThat(countMiExecutions(piId)).isZero();
    }

    /**
     * 验收标准 3：折返后发起人决策任务上 jumpToNode → 跳回指定历史节点（draft）。
     */
    @Test
    void testFoldbackJumpToNodeAllowed() {
        Task decisionTask = foldbackToInitiatorDecisionTask("biz-foldback-jump", USER_A);
        String piId = decisionTask.getProcessInstanceId();

        DynamicUserContext.set(INITIATOR);
        taskExecutionWorkflow.jumpToNode(decisionTask.getId(), "draft", "退回修改", CommentType.RETURN);

        assertThat(findActiveTaskId(piId, "draft")).isNotNull();
        assertThat(findActiveTaskId(piId, "counterSign")).isNull();
        assertThat(countMiExecutions(piId)).isZero();
    }

    /**
     * 验收标准 4：折返后发起人决策任务上 withdrawTask → 撤回（上一节点 draft 审批人=INITIATOR 校验通过）。
     */
    @Test
    void testFoldbackWithdrawTaskAllowed() {
        Task decisionTask = foldbackToInitiatorDecisionTask("biz-foldback-withdraw", USER_A);
        String piId = decisionTask.getProcessInstanceId();

        DynamicUserContext.set(INITIATOR);
        taskExecutionWorkflow.withdrawTask(decisionTask.getId(), "发起人撤回");

        assertThat(findActiveTaskId(piId, "draft")).isNotNull();
        assertThat(findActiveTaskId(piId, "counterSign")).isNull();
        assertThat(countMiExecutions(piId)).isZero();
    }

    // ======================== 保持拦截的场景 ========================

    /**
     * 验收标准 5：会签剩最后 1 人未投（投票人持任务）→ 仍拦截，counterSign 正常完成。
     */
    @Test
    void testLastUnvotedVoterStillBlocked() {
        ProcessInstance pi = startProcess("biz-last-unvoted", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);
        counterSign(pi.getId(), USER_A, true, "同意");

        // 只剩 USER_B 未投：assignee 是投票人而非发起人（且无 countersignInitiator 变量）→ 不识别 → 拦截
        DynamicUserContext.set(USER_B);
        String lastTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(lastTaskId).isNotNull();

        assertThatThrownBy(() -> taskExecutionWorkflow.rejectTask(lastTaskId, "不同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");

        // counterSign 仍可正常投票完成
        counterSign(pi.getId(), USER_B, true, "同意");
        assertThat(findActiveTaskId(pi.getId(), "confirmTask")).isNotNull();
    }

    /**
     * 验收标准 6：折返后发起人决策任务上 completeTask → 仍拦截（同意路径由上游
     * completePseudoSingletonByEngine 独立处理）。
     */
    @Test
    void testFoldbackCompleteTaskStillBlocked() {
        Task decisionTask = foldbackToInitiatorDecisionTask("biz-foldback-complete", USER_A);
        String piId = decisionTask.getProcessInstanceId();

        DynamicUserContext.set(INITIATOR);
        assertThatThrownBy(() -> taskExecutionWorkflow.completeTask(decisionTask.getId(), null, "同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    /**
     * 验收标准 7：发起人重新提交 → 重新进入会签节点任务干净重建，可正常 counterSign 完成。
     */
    @Test
    void testFoldbackRejectThenResubmitCounterSignCompletes() {
        Task decisionTask = foldbackToInitiatorDecisionTask("biz-foldback-resubmit", USER_A);
        String piId = decisionTask.getProcessInstanceId();

        DynamicUserContext.set(INITIATOR);
        taskExecutionWorkflow.rejectTask(decisionTask.getId(), "退回修改");
        assertThat(findActiveTaskId(piId, "draft")).isNotNull();

        // 发起人重新提交 → 重新进入会签节点，任务干净重建（仅 1 个活跃任务/1 个活跃执行，无残留多分支）
        completeTask(piId, "draft", INITIATOR);
        assertThat(taskService.createTaskQuery().processInstanceId(piId)
                .taskDefinitionKey("counterSign").active().count()).isEqualTo(1L);
        assertThat(countMiExecutions(piId)).isEqualTo(1L);

        // 重建后的会签任务可正常 counterSign 完成
        DynamicUserContext.set(INITIATOR);
        String rebuiltCsTaskId = findActiveTaskId(piId, "counterSign");
        assertThat(rebuiltCsTaskId).isNotNull();
        counterSignWorkflow.counterSign(rebuiltCsTaskId, true, null, "同意");
        assertThat(findActiveTaskId(piId, "confirmTask")).isNotNull();
    }

    /**
     * 验收标准 8：无 countersignInitiator_&lt;key&gt; 变量的场景（模式B折返）→ 不识别，保持拦截。
     */
    @Test
    void testFoldbackWithoutInitiatorVariableStillBlocked() {
        ProcessInstance pi = startProcess("biz-foldback-no-var", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);
        counterSign(pi.getId(), USER_A, true, "同意");
        counterSign(pi.getId(), USER_B, true, "同意");
        assertThat(findActiveTaskId(pi.getId(), "confirmTask")).isNotNull();

        // 模式B从未加签 → 无 countersignInitiator 变量
        assertThat(runtimeService.getVariable(pi.getId(), "countersignInitiator_counterSign")).isNull();

        // 折返：counterSignUsers=[USER_A] 重建 1 人 MI → 不识别 → 拦截
        runtimeService.setVariable(pi.getId(), "counterSignUsers", Collections.singletonList(USER_A));
        DynamicUserContext.set(INITIATOR);
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(pi.getId())
                .moveActivityIdTo("confirmTask", "counterSign")
                .changeState();

        Task decisionTask = findActiveTask(pi.getId(), "counterSign");
        assertThat(decisionTask).isNotNull();
        assertThat(decisionTask.getAssignee()).isEqualTo(USER_A);

        DynamicUserContext.set(USER_A);
        assertThatThrownBy(() -> taskExecutionWorkflow.rejectTask(decisionTask.getId(), "不同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    /**
     * 验收标准 9：checkActiveParallelBranch 在折返决策任务上不误报。
     */
    @Test
    void testFoldbackCheckActiveParallelBranchNoFalsePositive() {
        Task decisionTask = foldbackToInitiatorDecisionTask("biz-foldback-parallel", USER_A);
        String piId = decisionTask.getProcessInstanceId();

        // 折返任务在重建 MI body 内：父执行（miBody）的子执行数应为 1，不触发并行分支误报
        Execution execution = runtimeService.createExecutionQuery()
                .executionId(decisionTask.getExecutionId()).singleResult();
        assertThat(execution).isNotNull();
        assertThat(execution.getParentId()).isNotNull();
        long siblings = runtimeService.createExecutionQuery()
                .parentId(execution.getParentId()).count();
        assertThat(siblings).isEqualTo(1L);

        // rejectTask 内部调用 checkActiveParallelBranch → 不抛"并行分支"即不误报
        DynamicUserContext.set(INITIATOR);
        taskExecutionWorkflow.rejectTask(decisionTask.getId(), "退回修改");
        assertThat(findActiveTaskId(piId, "draft")).isNotNull();
    }

    // ======================== Helpers ========================

    /**
     * 构造"折返后发起人决策任务"：
     * <ol>
     *   <li>模式A伪单例发起（counterSignUsers=[INITIATOR]）→ 完成 draft</li>
     *   <li>发起人加签 addedSigners → 写入 countersignInitiator_counterSign=INITIATOR</li>
     *   <li>发起人与全部加签人投票 → counterSign 完成 → confirmTask</li>
     *   <li>moveActivityIdTo(confirmTask → counterSign) 折返，counterSignUsers 仍为 [INITIATOR]
     *       → 重建 1 人 MI，生成发起人决策任务</li>
     * </ol>
     *
     * @return 折返后 counterSign 节点的活跃任务（发起人决策任务）
     */
    private Task foldbackToInitiatorDecisionTask(String businessKey, String... addedSigners) {
        ProcessInstance pi = startProcess(businessKey, INITIATOR);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        // 发起人加签 → 写 countersignInitiator_counterSign
        DynamicUserContext.set(INITIATOR);
        String ownerTaskId = findActiveTaskId(pi.getId(), "counterSign");
        assertThat(ownerTaskId).withFailMessage("未找到发起人会签任务").isNotNull();
        counterSignWorkflow.addCounterSigner(ownerTaskId, Arrays.asList(addedSigners));

        // 全部投票完成 → confirmTask
        counterSign(pi.getId(), INITIATOR, true, "同意");
        for (String signer : addedSigners) {
            counterSign(pi.getId(), signer, true, "同意");
        }
        assertThat(findActiveTaskId(pi.getId(), "confirmTask")).isNotNull();

        // 折返：confirmTask 跳回 counterSign → 引擎重建新执行周期（counterSignUsers=[INITIATOR]）
        DynamicUserContext.set(INITIATOR);
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(pi.getId())
                .moveActivityIdTo("confirmTask", "counterSign")
                .changeState();

        Task decisionTask = findActiveTask(pi.getId(), "counterSign");
        assertThat(decisionTask).withFailMessage("折返后未生成发起人决策任务").isNotNull();
        // 识别口径一致：assignee == countersignInitiator_<key> 变量
        assertThat(decisionTask.getAssignee()).isEqualTo(INITIATOR);
        assertThat(runtimeService.getVariable(pi.getId(), "countersignInitiator_counterSign"))
                .isEqualTo(INITIATOR);
        return decisionTask;
    }

    private ProcessInstance startProcess(String businessKey, String... signers) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("counterSignUsers", Arrays.asList(signers));

        identityService.setAuthenticatedUserId(INITIATOR);
        DynamicUserContext.set(INITIATOR);
        return runtimeService.startProcessInstanceByKey(PROCESS_KEY, businessKey, variables);
    }

    private void completeTask(String processInstanceId, String nodeId, String userId) {
        DynamicUserContext.set(userId);
        String taskId = findActiveTaskId(processInstanceId, nodeId);
        assertThat(taskId).withFailMessage("未找到节点 %s 的活跃任务", nodeId).isNotNull();
        taskExecutionWorkflow.completeTask(taskId, null, "发起");
    }

    private void counterSign(String processInstanceId, String userId, boolean approved, String comment) {
        DynamicUserContext.set(userId);
        String taskId = findActiveCounterSignTaskId(processInstanceId, userId);
        assertThat(taskId).withFailMessage("未找到用户 %s 的会签任务", userId).isNotNull();
        counterSignWorkflow.counterSign(taskId, approved, null, comment);
    }

    private Task findActiveTask(String processInstanceId, String taskDefinitionKey) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .singleResult();
    }

    private String findActiveTaskId(String processInstanceId, String taskDefinitionKey) {
        Task task = findActiveTask(processInstanceId, taskDefinitionKey);
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

    /** 统计 counterSign 节点残留的活跃执行数（含 miBody 与子执行），用于"无孤立残留"断言 */
    private long countMiExecutions(String processInstanceId) {
        return runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId)
                .activityId("counterSign")
                .count();
    }
}
