package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;

import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventsubscription.api.EventSubscription;
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

/**
 * ExecutionTreeHelper（并行网关分支剥离）集成测试。
 *
 * <p>使用 test-parallel-gateway.bpmn20.xml（start → draft → fork → [branch1, branch2]
 * → join → decision → finalTask/end），覆盖 {@link ExecutionTreeHelper#detachFromParallelGateway}
 * 的真实引擎行为（C11 候选：FlowableExecutionTreeHelper 此前零直接测试）：
 * <ul>
 *   <li>直接调用：并行双分支下剥离当前执行 → 级联删除并行 Scope（无幽灵分支残留）</li>
 *   <li>全链路：branch1 上 rejectTaskToInitiator → 回到 draft，且无幽灵分支、重新提交流程正常</li>
 * </ul></p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class FlowableExecutionTreeHelperIntegrationTest extends AbstractIntegrationTest {

    private static final String PARALLEL_KEY = "testParallelGateway";
    private static final String INITIATOR = "initiator";
    private static final String APPROVER_1 = "user_a";
    private static final String APPROVER_2 = "user_b";

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private TaskExecutionWorkflow taskExecutionWorkflow;

    @Autowired
    private ExecutionTreeHelper executionTreeHelper;

    private String deploymentId;
    private final List<String> processInstanceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource("bpmn/test-parallel-gateway.bpmn20.xml")
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

    // ======================== 场景 1：直接调 detachFromParallelGateway ========================

    /**
     * 直接调用：并行双分支下剥离 branch1 执行 → 并行 Scope 级联删除，
     * branch2 幽灵分支被清理，branch1 执行存活。
     */
    @Test
    void detachFromParallelGatewayShouldCleanGhostBranch() {
        ProcessInstance pi = startParallelProcess("biz-detach-direct");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        Task branch1 = findActiveTask(pi.getId(), "branch1");
        assertThat(branch1).isNotNull();
        assertThat(findActiveTask(pi.getId(), "branch2")).isNotNull();

        DynamicUserContext.set(APPROVER_1);
        executionTreeHelper.detachFromParallelGateway(branch1.getExecutionId(), "detach test");

        // 幽灵分支清理：branch2 无活跃任务、无活动执行
        assertThat(countActiveTasks(pi.getId(), "branch2")).isZero();
        assertThat(countActiveExecutions(pi.getId(), "branch2")).isZero();

        // 当前执行存活：branch1 任务仍存在
        assertThat(countActiveTasks(pi.getId(), "branch1")).isEqualTo(1L);

        // 历史保留（deleteHistory=false 契约）：branch2 的历史活动实例仍在
        assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(pi.getId()).activityId("branch2").count()).isEqualTo(1L);
    }

    // ======================== 场景 1b：串行任务 no-op ========================

    /**
     * 串行流程（无兄弟分支）：detach 静默返回，任务不受影响。
     */
    @Test
    void detachOnSerialTaskShouldBeNoOp() {
        ProcessInstance pi = startParallelProcess("biz-detach-serial");
        processInstanceIds.add(pi.getId());

        Task draft = findActiveTask(pi.getId(), "draft");
        assertThat(draft).isNotNull();

        DynamicUserContext.set(INITIATOR);
        executionTreeHelper.detachFromParallelGateway(draft.getExecutionId(), "serial no-op");

        // 串行流程：无兄弟分支，静默返回
        assertThat(countActiveTasks(pi.getId(), "draft")).isEqualTo(1L);
    }

    // ======================== 场景 1c：事件子流程不被误删 ========================

    /**
     * 串行主路径 + 活跃事件子流程（scope 结构性子执行）：detach 不应误删
     * 事件子流程等待执行，其消息订阅在 detach 后仍可正常触发。
     */
    @Test
    void detachShouldNotDeleteEventSubProcess() {
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource("bpmn/test-event-subprocess.bpmn20.xml")
                .deploy();
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("initiator", INITIATOR);
            variables.put("approver1", APPROVER_1);
            variables.put("approver2", APPROVER_2);
            DynamicUserContext.set(INITIATOR);
            ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                    "testEventSubProcess", "biz-detach-esc", variables);
            processInstanceIds.add(pi.getId());

            EventSubscription subscription = runtimeService.createEventSubscriptionQuery()
                    .eventType("message").processInstanceId(pi.getId()).singleResult();
            assertThat(subscription).as("事件子流程消息订阅应存在").isNotNull();

            completeTask(pi.getId(), "draft", INITIATOR, "发起申请");
            Task approve = findActiveTask(pi.getId(), "approve");
            assertThat(approve).isNotNull();

            DynamicUserContext.set(APPROVER_1);
            executionTreeHelper.detachFromParallelGateway(approve.getExecutionId(), "detach esc");

            // 事件子流程等待执行未被误删：订阅仍在，主路径任务不受影响
            assertThat(runtimeService.createEventSubscriptionQuery()
                    .eventType("message").processInstanceId(pi.getId()).count()).isEqualTo(1L);
            assertThat(countActiveTasks(pi.getId(), "approve")).isEqualTo(1L);

            // 订阅仍可正常触发：中断型消息启动事件子流程，主路径被中断
            runtimeService.messageEventReceived("alert", subscription.getExecutionId());
            assertThat(countActiveTasks(pi.getId(), "approve")).isZero();
            assertThat(findActiveTask(pi.getId(), "escTask")).isNotNull();
        } finally {
            try {
                repositoryService.deleteDeployment(deployment.getId(), true);
            } catch (Exception ignored) {
                // 忽略清理错误
            }
        }
    }

    // ======================== 场景 2：rejectTaskToInitiator 全链路 ========================

    /**
     * 全链路：并行分支上驳回至发起人 → 回到 draft，无幽灵分支残留，
     * 发起人重新提交后可正常完成流程。
     */
    @Test
    void rejectTaskToInitiatorFromParallelBranchLeavesNoGhostBranch() {
        ProcessInstance pi = startParallelProcess("biz-detach-reject");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(APPROVER_1);
        String branch1TaskId = findActiveTask(pi.getId(), "branch1").getId();
        taskExecutionWorkflow.rejectTaskToInitiator(branch1TaskId, "驳回至发起人");

        // 回到发起节点
        assertThat(findActiveTask(pi.getId(), "draft")).isNotNull();

        // 无幽灵分支：branch1 / branch2 均无活跃任务、无活动执行
        assertThat(countActiveTasks(pi.getId(), "branch1")).isZero();
        assertThat(countActiveTasks(pi.getId(), "branch2")).isZero();
        assertThat(countActiveExecutions(pi.getId(), "branch1")).isZero();
        assertThat(countActiveExecutions(pi.getId(), "branch2")).isZero();

        // 重新提交 → 可正常走完并行分支到终审
        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");
        completeTask(pi.getId(), "branch1", APPROVER_1, "部门同意");
        completeTask(pi.getId(), "branch2", APPROVER_2, "财务同意");
        completeTask(pi.getId(), "finalTask", "user_c", "终审通过");

        ProcessInstance ended = runtimeService.createProcessInstanceQuery()
                .processInstanceId(pi.getId()).singleResult();
        assertThat(ended).isNull();
    }

    // ======================== Helpers ========================

    private ProcessInstance startParallelProcess(String businessKey) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver1", APPROVER_1);
        variables.put("approver2", APPROVER_2);
        variables.put("approver3", "user_c");
        variables.put("skipFinal", false);
        DynamicUserContext.set(INITIATOR);
        return runtimeService.startProcessInstanceByKey(PARALLEL_KEY, businessKey, variables);
    }

    private void completeTask(String processInstanceId, String nodeId,
                              String userId, String comment) {
        DynamicUserContext.set(userId);
        String taskId = findActiveTask(processInstanceId, nodeId).getId();
        taskExecutionWorkflow.completeTask(taskId, null, comment);
    }

    private Task findActiveTask(String processInstanceId, String taskDefinitionKey) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .singleResult();
    }

    private long countActiveTasks(String processInstanceId, String taskDefinitionKey) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .count();
    }

    private long countActiveExecutions(String processInstanceId, String activityId) {
        return runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId)
                .activityId(activityId)
                .count();
    }
}
