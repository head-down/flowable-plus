package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.JumpableNodeVO;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;

import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
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
 * Layer 1 集成测试：任务执行操作。
 *
 * <p>测试 completeTask / rejectTask / withdrawTask / transferTask /
 * jumpToNode / getJumpableNodes。
 * 驳回/撤回使用线性流程避免并行网关阻塞，
 * 完成/跳转/转办使用并行网关流程。</p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class TaskExecutionIntegrationTest extends AbstractIntegrationTest {

    private static final String LINEAR_KEY = "testSimpleLinear";
    private static final String PARALLEL_KEY = "testParallelGateway";
    private static final String INITIATOR = "initiator";
    private static final String APPROVER = "approver";
    private static final String APPROVER_1 = "user_a";
    private static final String APPROVER_2 = "user_b";
    private static final String APPROVER_3 = "user_c";
    private static final String TRANSFER_TARGET = "user_x";

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
    private FlowablePlus flowablePlus;

    private String deploymentId;
    private final List<String> processInstanceIds = new ArrayList<>();

    /** 部署两个 BPMN：线性流程用于驳回/撤回，并行网关用于完成/跳转/转办 */
    @BeforeEach
    void setUp() {
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource("bpmn/test-simple-linear.bpmn20.xml")
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
            } catch (Exception ignored) {}
        }
        if (deploymentId != null) {
            try {
                repositoryService.deleteDeployment(deploymentId, true);
            } catch (Exception ignored) {}
        }
    }

    // ======================== 流程完成（并行网关） ========================

    @Test
    void testCompleteTask() {
        ProcessInstance pi = startParallelProcess("biz-complete");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");
        completeTask(pi.getId(), "branch1", APPROVER_1, "部门同意");
        completeTask(pi.getId(), "branch2", APPROVER_2, "财务同意");
        completeTask(pi.getId(), "finalTask", APPROVER_3, "终审通过");

        ProcessInstance ended = runtimeService.createProcessInstanceQuery()
                .processInstanceId(pi.getId()).singleResult();
        assertThat(ended).isNull();
    }

    // ======================== 驳回（线性流程） ========================

    @Test
    void testRejectTask() {
        ProcessInstance pi = startLinearProcess("biz-reject");
        processInstanceIds.add(pi.getId());

        // 完成发起 → 到达 deptApprove
        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        // 驳回
        DynamicUserContext.set(APPROVER);
        String approveTaskId = findActiveTaskId(pi.getId(), "deptApprove");
        assertThat(approveTaskId).isNotNull();
        taskExecutionWorkflow.rejectTask(approveTaskId, "不同意");

        // 验证回到发起节点
        String draftTaskId = findActiveTaskId(pi.getId(), "draft");
        assertThat(draftTaskId).isNotNull();
    }

    // ======================== 驳回至发起人（并行网关） ========================

    @Test
    void testRejectTaskToInitiator() {
        ProcessInstance pi = startParallelProcess("biz-reject-init");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        // 从并行分支驳回至发起人
        DynamicUserContext.set(APPROVER_1);
        String branch1TaskId = findActiveTaskId(pi.getId(), "branch1");
        assertThat(branch1TaskId).isNotNull();
        taskExecutionWorkflow.rejectTaskToInitiator(branch1TaskId, "驳回至发起人");

        // 验证回到发起节点
        String draftTaskId = findActiveTaskId(pi.getId(), "draft");
        assertThat(draftTaskId).isNotNull();
    }

    // ======================== 撤回（线性流程） ========================

    @Test
    void testWithdrawTask() {
        ProcessInstance pi = startLinearProcess("biz-withdraw");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        // 发起人撤回审批任务
        DynamicUserContext.set(INITIATOR);
        String approveTaskId = findActiveTaskId(pi.getId(), "deptApprove");
        assertThat(approveTaskId).isNotNull();
        taskExecutionWorkflow.withdrawTask(approveTaskId, "发起人撤回");

        // 验证回到发起节点
        String draftTaskId = findActiveTaskId(pi.getId(), "draft");
        assertThat(draftTaskId).isNotNull();
    }

    @Test
    void testWithdrawTaskByCurrentAssigneeShouldFail() {
        ProcessInstance pi = startLinearProcess("biz-withdraw-self");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        // 当前审批人尝试撤回自己 → 应失败
        DynamicUserContext.set(APPROVER);
        String approveTaskId = findActiveTaskId(pi.getId(), "deptApprove");
        assertThat(approveTaskId).isNotNull();

        assertThatThrownBy(() -> taskExecutionWorkflow.withdrawTask(approveTaskId, "撤回"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    // ======================== 转办 ========================

    @Test
    void testTransferTask() {
        ProcessInstance pi = startParallelProcess("biz-transfer");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(APPROVER_1);
        String branch1TaskId = findActiveTaskId(pi.getId(), "branch1");
        assertThat(branch1TaskId).isNotNull();
        taskExecutionWorkflow.transferTask(branch1TaskId, TRANSFER_TARGET, "请代为审批");

        // 原审批人无该待办
        DynamicUserContext.set(TRANSFER_TARGET);
        String transferredTaskId = findActiveTaskId(pi.getId(), "branch1");
        assertThat(transferredTaskId).isNotNull();

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);
        assertThat(flowablePlus.queryTodoTasks(APPROVER_1, query).getTotal()).isZero();
        assertThat(flowablePlus.queryTodoTasks(TRANSFER_TARGET, query).getTotal()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void testTransferTaskToSelfShouldFail() {
        ProcessInstance pi = startParallelProcess("biz-transfer-self");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");

        DynamicUserContext.set(APPROVER_1);
        String branch1TaskId = findActiveTaskId(pi.getId(), "branch1");
        assertThat(branch1TaskId).isNotNull();

        assertThatThrownBy(() -> taskExecutionWorkflow.transferTask(branch1TaskId, APPROVER_1, "转给自己"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("转办目标不可为当前审批人");
    }

    // ======================== 任意跳转 ========================

    @Test
    void testJumpToNode() {
        ProcessInstance pi = startParallelProcess("biz-jump");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");
        completeTask(pi.getId(), "branch1", APPROVER_1, "部门同意");
        completeTask(pi.getId(), "branch2", APPROVER_2, "财务同意");

        // 从终审跳回发起节点
        DynamicUserContext.set(APPROVER_3);
        String finalTaskId = findActiveTaskId(pi.getId(), "finalTask");
        assertThat(finalTaskId).isNotNull();
        taskExecutionWorkflow.jumpToNode(finalTaskId, "draft", "跳回修改", CommentType.RETURN);

        String draftTaskId = findActiveTaskId(pi.getId(), "draft");
        assertThat(draftTaskId).isNotNull();
    }

    @Test
    void testGetJumpableNodes() {
        ProcessInstance pi = startParallelProcess("biz-jumpable");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");
        completeTask(pi.getId(), "branch1", APPROVER_1, "部门同意");
        completeTask(pi.getId(), "branch2", APPROVER_2, "财务同意");

        DynamicUserContext.set(APPROVER_3);
        String finalTaskId = findActiveTaskId(pi.getId(), "finalTask");
        assertThat(finalTaskId).isNotNull();

        List<JumpableNodeVO> jumpableNodes = taskExecutionWorkflow.getJumpableNodes(finalTaskId);
        assertThat(jumpableNodes).isNotEmpty();

        List<String> nodeIds = new ArrayList<>();
        for (JumpableNodeVO node : jumpableNodes) {
            nodeIds.add(node.getNodeId());
        }
        assertThat(nodeIds).contains("draft", "branch1", "branch2");
        assertThat(nodeIds).doesNotContain("finalTask");
    }

    // ======================== 查询已办 ========================

    @Test
    void testQueryDoneTasksAfterCompletion() {
        identityService.setAuthenticatedUserId(INITIATOR);
        ProcessInstance pi = startParallelProcess("biz-done");
        processInstanceIds.add(pi.getId());

        completeTask(pi.getId(), "draft", INITIATOR, "发起申请");
        completeTask(pi.getId(), "branch1", APPROVER_1, "部门同意");
        completeTask(pi.getId(), "branch2", APPROVER_2, "财务同意");
        completeTask(pi.getId(), "finalTask", APPROVER_3, "终审通过");

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks(INITIATOR, query);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
    }

    // ======================== Helpers ========================

    private ProcessInstance startParallelProcess(String businessKey) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver1", APPROVER_1);
        variables.put("approver2", APPROVER_2);
        variables.put("approver3", APPROVER_3);
        variables.put("skipFinal", false);
        DynamicUserContext.set(INITIATOR);
        return runtimeService.startProcessInstanceByKey(PARALLEL_KEY, businessKey, variables);
    }

    private ProcessInstance startLinearProcess(String businessKey) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiator", INITIATOR);
        variables.put("approver", APPROVER);
        DynamicUserContext.set(INITIATOR);
        return runtimeService.startProcessInstanceByKey(LINEAR_KEY, businessKey, variables);
    }

    private void completeTask(String processInstanceId, String nodeId,
                               String userId, String comment) {
        DynamicUserContext.set(userId);
        String taskId = findActiveTaskId(processInstanceId, nodeId);
        assertThat(taskId).withFailMessage("未找到节点 %s 的活跃任务", nodeId).isNotNull();
        taskExecutionWorkflow.completeTask(taskId, null, comment);
    }

    private String findActiveTaskId(String processInstanceId, String taskDefinitionKey) {
        org.flowable.task.api.Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .singleResult();
        return task != null ? task.getId() : null;
    }
}
