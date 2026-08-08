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
import org.flowable.engine.task.Comment;
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

/**
 * Layer 2 集成测试：会签操作。
 *
 * <p>使用 test-multi-instance.bpmn20.xml 测试 counterSign / addCounterSigner /
 * removeCounterSigner / delegateTask / resolveDelegate。
 * 并行多实例会签，全部同意后流转到发起人确认节点。</p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class CounterSignIntegrationTest extends AbstractIntegrationTest {

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

        // 加签 EXTRA_USER（活跃审批人 USER_A 可操作）
        DynamicUserContext.set(USER_A);
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

    /**
     * 模式 A（伪单例）：当前轮次只剩操作者一人未投时加签，
     * 新加签人应并入当前轮（csRoundIndex=0），不开启新一轮（评论不含"第 N 轮"）。
     * 回归：bug 报告"当前轮次只剩操作者一人未投时加签，新加签人被错误归入新一轮"。
     */
    @Test
    void testAddCounterSignerModeALastUnvotedMergesIntoCurrentRound() {
        // 模式 A：伪单例发起（assigneeList 只放发起人）
        ProcessInstance pi = startProcessWithSigners("biz-cs-a-001", INITIATOR);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        // 发起人（伪单例）加签 USER_A → 并入第 1 轮（csRoundIndex=0）
        String ownerTaskId = findActiveCounterSignTaskId(pi.getId(), INITIATOR);
        assertThat(ownerTaskId).isNotNull();
        DynamicUserContext.set(INITIATOR);
        counterSignWorkflow.addCounterSigner(ownerTaskId, Collections.singletonList(USER_A));
        // 按 ADR-0019 时序：调用方将发起任务与子任务归到同一轮次
        taskService.setVariableLocal(ownerTaskId, "csRoundIndex", 0);

        // USER_A 投票 → 当前轮次只剩发起人一人未投
        counterSign(pi.getId(), USER_A, true, "同意");

        // 发起人（唯一未投者）再加签 USER_B → 应并入当前轮
        DynamicUserContext.set(INITIATOR);
        counterSignWorkflow.addCounterSigner(ownerTaskId, Collections.singletonList(USER_B));

        // USER_B 归入当前轮 csRoundIndex=0（而非新一轮 1）
        String userBTaskId = findActiveCounterSignTaskId(pi.getId(), USER_B);
        assertThat(userBTaskId).isNotNull();
        assertThat(taskService.getVariableLocal(userBTaskId, "csRoundIndex")).isEqualTo(0);

        // 评论不含"开启第 2 轮"
        List<Comment> comments = taskService.getProcessInstanceComments(pi.getId());
        assertThat(comments).extracting(Comment::getFullMessage)
                .anyMatch(m -> m.contains("加签审批人: " + USER_B) && !m.contains("第 2 轮"));
    }

    /**
     * 模式 B（固定会签）：当前轮次只剩操作者一人未投时加签，
     * 模式 B 无轮次概念，单执行周期内加签永远并入当前轮（csRoundIndex=0）。
     */
    @Test
    void testAddCounterSignerModeBLastUnvotedMergesIntoCurrentRound() {
        // 模式 B：固定会签 [USER_A, USER_B]
        ProcessInstance pi = startProcessWithSigners("biz-cs-b-001", USER_A, USER_B);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        // USER_A 投票 → 只剩 USER_B 一人未投
        counterSign(pi.getId(), USER_A, true, "同意");

        // USER_B（唯一未投者）加签 USER_C → 应并入当前轮
        String userBTaskId = findActiveCounterSignTaskId(pi.getId(), USER_B);
        assertThat(userBTaskId).isNotNull();
        DynamicUserContext.set(USER_B);
        counterSignWorkflow.addCounterSigner(userBTaskId, Collections.singletonList(USER_C));

        // USER_C 归入当前轮 csRoundIndex=0（而非新一轮 1）
        String userCTaskId = findActiveCounterSignTaskId(pi.getId(), USER_C);
        assertThat(userCTaskId).isNotNull();
        assertThat(taskService.getVariableLocal(userCTaskId, "csRoundIndex")).isEqualTo(0);

        // 评论不含"开启第 2 轮"
        List<Comment> comments = taskService.getProcessInstanceComments(pi.getId());
        assertThat(comments).extracting(Comment::getFullMessage)
                .anyMatch(m -> m.contains("加签审批人: " + USER_C) && !m.contains("第 2 轮"));
    }

    /**
     * 模式 B（固定会签）：经 removeCounterSigner 减签至只剩 1 人未投、且无人投过票时加签。
     * 回归 review 隐患 A：isPseudoSingleton 若用全局 finished 判据，减签后可能误判为伪单例
     * 而写入 countersignInitiator，把模式 B 永久翻转为模式 A。修复后按「全局历史任务数==1」
     * 判据，减签场景历史任务数 > 1 → 非伪单例 → 不写 initiator → 加签永远并入当前轮。
     */
    @Test
    void testAddCounterSignerModeBAfterRemoveSignerDownToOneDoesNotFlipToModeA() {
        // 模式 B：固定会签 [USER_A, USER_B, USER_C]，全部未投票
        ProcessInstance pi = startProcessWithSigners("biz-cs-b-rm-001", USER_A, USER_B, USER_C);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        // USER_A（活跃审批人）减签 USER_B、USER_C → 只剩 USER_A 一人未投，无人投过票
        String userATaskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(userATaskId).isNotNull();
        DynamicUserContext.set(USER_A);
        counterSignWorkflow.removeCounterSigner(userATaskId, USER_B);
        counterSignWorkflow.removeCounterSigner(userATaskId, USER_C);

        // 只剩 1 人未投
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("counterSign")
                .active()
                .list();
        assertThat(activeTasks).hasSize(1);

        // USER_A 加签 USER_C → 模式 B 应保持：不写 countersignInitiator，并入当前轮（csRoundIndex=0）
        DynamicUserContext.set(USER_A);
        counterSignWorkflow.addCounterSigner(userATaskId, Collections.singletonList(USER_C));

        // 不写 initiator（未翻转为模式 A）
        assertThat(runtimeService.getVariable(pi.getId(), "countersignInitiator_counterSign"))
                .isNull();

        // USER_C 归入当前轮 csRoundIndex=0（而非新一轮 1）
        String userCTaskId = findActiveCounterSignTaskId(pi.getId(), USER_C);
        assertThat(userCTaskId).isNotNull();
        assertThat(taskService.getVariableLocal(userCTaskId, "csRoundIndex")).isEqualTo(0);

        // 评论不含"开启第 N 轮"
        List<Comment> comments = taskService.getProcessInstanceComments(pi.getId());
        assertThat(comments).extracting(Comment::getFullMessage)
                .anyMatch(m -> m.contains("加签审批人: " + USER_C) && !m.contains("第"));
    }

    /**
     * 折返后轮次编号重置（隐患 C 修复后的语义）：
     * <ol>
     *   <li>周期 1 内发起人多次加签<b>并入当前轮</b>（内化打标后 owner 首次加签即获显式 csRoundIndex=0，
     *       后续加签走 roundVar 分支并入当前轮，不再因缺显式轮次而误开新一轮——隐患 C 回归验证）</li>
     *   <li>手工注入 csRoundIndex=2 历史变量模拟上一周期存在多轮</li>
     *   <li>折返重新进入会签节点后（新执行周期），加签应并入周期 2 的当前轮（csRoundIndex=0），
     *       不沿用上一周期的全局 max+1</li>
     * </ol>
     */
    @Test
    void testAddCounterSignerRoundResetsAfterRebuild() {
        // 模式 A：伪单例发起
        ProcessInstance pi = startProcessWithSigners("biz-cs-rebuild-001", INITIATOR);
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR);

        // 周期 1：第 1 轮加签 USER_A（csRoundIndex=0），且发起人任务被内化打标（不再依赖调用方时序）
        String ownerTaskId = findActiveCounterSignTaskId(pi.getId(), INITIATOR);
        assertThat(ownerTaskId).isNotNull();
        DynamicUserContext.set(INITIATOR);
        counterSignWorkflow.addCounterSigner(ownerTaskId, Collections.singletonList(USER_A));
        String userATaskId = findActiveCounterSignTaskId(pi.getId(), USER_A);
        assertThat(userATaskId).isNotNull();
        assertThat(taskService.getVariableLocal(userATaskId, "csRoundIndex")).isEqualTo(0);
        assertThat(taskService.getVariableLocal(ownerTaskId, "csRoundIndex")).isEqualTo(0);

        // USER_A 投票 → 发起人再加签 USER_B → 本轮未结束，并入当前轮（csRoundIndex=0，不开启新一轮）
        counterSign(pi.getId(), USER_A, true, "同意");
        DynamicUserContext.set(INITIATOR);
        counterSignWorkflow.addCounterSigner(ownerTaskId, Collections.singletonList(USER_B));
        String userBTaskId = findActiveCounterSignTaskId(pi.getId(), USER_B);
        assertThat(userBTaskId).isNotNull();
        assertThat(taskService.getVariableLocal(userBTaskId, "csRoundIndex")).isEqualTo(0);

        // 手工将发起人任务轮次标记为 2，构造"上一周期存在 csRoundIndex=2"的历史（模拟多轮会签旧数据），
        // 用于验证折返后 determineNextRoundIndex 不沿用全局 max
        taskService.setVariableLocal(ownerTaskId, "csRoundIndex", 2);

        // 周期 1 完成 → confirmTask
        counterSign(pi.getId(), INITIATOR, true, "同意");
        counterSign(pi.getId(), USER_B, true, "同意");

        // 折返：confirmTask 跳回 counterSign → 引擎重建新执行周期（重新解析 counterSignUsers=[INITIATOR]）
        DynamicUserContext.set(INITIATOR);
        Task confirmTask = taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey("confirmTask")
                .active()
                .singleResult();
        assertThat(confirmTask).isNotNull();
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(pi.getId())
                .moveActivityIdTo("confirmTask", "counterSign")
                .changeState();

        // 周期 2：发起人加签 USER_C → 应并入周期 2 当前轮（csRoundIndex=0，不沿用周期 1 的 max=2）
        String owner2TaskId = findActiveCounterSignTaskId(pi.getId(), INITIATOR);
        assertThat(owner2TaskId).isNotNull();
        DynamicUserContext.set(INITIATOR);
        counterSignWorkflow.addCounterSigner(owner2TaskId, Collections.singletonList(USER_C));

        String userCTaskId = findActiveCounterSignTaskId(pi.getId(), USER_C);
        assertThat(userCTaskId).isNotNull();
        assertThat(taskService.getVariableLocal(userCTaskId, "csRoundIndex")).isEqualTo(0);
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

        // 减签 USER_C（未投票、剩余 >= 2，活跃审批人 USER_A 可操作）
        DynamicUserContext.set(USER_A);
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
