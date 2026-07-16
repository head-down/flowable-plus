package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.AutoApprovalRule;
import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.JumpableNodeVO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.workflow.TaskWorkflow;
import io.github.flowable.plus.core.domain.PlusProcessInstance;
import io.github.flowable.plus.core.enums.CommentType;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskWorkflow 单元测试：覆盖发起、同意、驳回、撤回、撤销的
 * 正常路径��所有异常路径。
 */
public class TaskWorkflowTest {

    private static final String USER_ID = "user1";

    private UserContext userContext;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private RuntimeService mockRuntimeService;
    private IdentityService mockIdentityService;
    private NodeFinder mockNodeFinder;
    private BpmnModelCache mockBpmnModelCache;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private ExecutionTreeHelper mockExecutionTreeHelper;
    private TaskWorkflow taskWorkflow;

    @BeforeEach
    void setUp() {
        userContext = () -> USER_ID;
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockIdentityService = mock(IdentityService.class);
        mockNodeFinder = mock(NodeFinder.class);
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        mockExecutionTreeHelper = mock(ExecutionTreeHelper.class);

        // 默认 stub：createExecutionQuery 返回空执行对象（非并行分支场景）
        stubNoParallelBranch();

        taskWorkflow = new TaskWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockIdentityService, mockNodeFinder, mockMultiInstanceDetector, null,
                mockExecutionTreeHelper);
    }

    // ======================== 发起 ========================

    @Test
    void testStartProcess() {
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", null))
                .thenReturn(mockPi);

        PlusProcessInstance result = taskWorkflow.startProcess("leave", "biz-001", null);

        assertThat(result.getProcessInstanceId()).isEqualTo("pi-001");
        assertThat(result.getBusinessKey()).isEqualTo("biz-001");
        assertThat(result.getProcessDefinitionId()).isEqualTo("leave:1:abc");
        verify(mockIdentityService).setAuthenticatedUserId(USER_ID);
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    @Test
    void testStartProcessRejectsNullKey() {
        assertThatThrownBy(() -> taskWorkflow.startProcess(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionKey");
    }

    @Test
    void testStartProcessClearsAuthOnException() {
        when(mockRuntimeService.startProcessInstanceByKey("leave", null, null))
                .thenThrow(new RuntimeException("引擎异常"));

        assertThatThrownBy(() -> taskWorkflow.startProcess("leave", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("引擎异常");
        verify(mockIdentityService).setAuthenticatedUserId(USER_ID);
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    @Test
    void testStartProcessWithVariables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 5000);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-002");
        when(mockPi.getBusinessKey()).thenReturn("biz-002");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-002", variables))
                .thenReturn(mockPi);

        PlusProcessInstance result = taskWorkflow.startProcess("leave", "biz-002", variables);

        assertThat(result.getProcessInstanceId()).isEqualTo("pi-002");
    }

    @Test
    void testStartProcessPropagatesAutoCompleteException() {
        // 注册一个会抛异常的 AutoApprovalRule
        AutoApprovalRule failingRule = (task, vars) -> {
            throw new RuntimeException("自动提交规则异常");
        };

        // stub 自动提交内部的 historyService 查询
        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        // stub 自动提交内部的 taskService 查询
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mock(Task.class)));

        taskWorkflow = new TaskWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockIdentityService, mockNodeFinder, mockMultiInstanceDetector,
                Collections.singletonList(failingRule), mockExecutionTreeHelper);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-003");
        when(mockPi.getBusinessKey()).thenReturn("biz-003");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");

        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 1000);
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-003", variables))
                .thenReturn(mockPi);

        // 快速失败：异常应向上传播，不被吞没
        assertThatThrownBy(() -> taskWorkflow.startProcess("leave", "biz-003", variables))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("自动提交规则异常");
        // 身份清理仍应在 finally 中执行
        verify(mockIdentityService).setAuthenticatedUserId(USER_ID);
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    // ======================== 同意 ========================

    @Test
    void testCompleteTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        taskWorkflow.completeTask("task-001", null, null);

        verify(mockTaskService).claim("task-001", USER_ID);
        verify(mockTaskService).complete("task-001", null);
        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testCompleteTaskWithComment() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        taskWorkflow.completeTask("task-001", null, "同意");

        verify(mockTaskService).addComment("task-001", null, null, "同意");
        verify(mockTaskService).complete("task-001", null);
    }

    @Test
    void testCompleteTaskWithVariables() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        Map<String, Object> vars = new HashMap<>();
        vars.put("approved", true);
        taskWorkflow.completeTask("task-001", vars, null);

        verify(mockTaskService).complete("task-001", vars);
    }

    @Test
    void testCompleteTaskRejectsMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        assertThatThrownBy(() -> taskWorkflow.completeTask("task-001", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testCompleteTaskRejectsCompletedTask() {
        stubCompletedTask("task-001");

        assertThatThrownBy(() -> taskWorkflow.completeTask("task-001", null, null))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已完成");
    }

    @Test
    void testCompleteTaskRejectsNonexistentTask() {
        stubNonexistentTask("task-nope");

        assertThatThrownBy(() -> taskWorkflow.completeTask("task-nope", null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    // ======================== 认领 ========================

    @Test
    void testClaimTask() {
        taskWorkflow.claimTask("task-001");
        verify(mockTaskService).claim("task-001", USER_ID);
    }

    @Test
    void testClaimTaskRejectsNullTaskId() {
        assertThatThrownBy(() -> taskWorkflow.claimTask(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== 驳回 ========================

    @Test
    void testRejectTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        taskWorkflow.rejectTask("task-001", "不同意");

        verify(mockTaskService).addComment("task-001", "pi-001", "REJECT", "不同意");
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    @Test
    void testRejectTaskRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user2");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testRejectTaskRejectsParallelGateway() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Arrays.asList("task1a", "task1b"));

        assertThatThrownBy(() -> taskWorkflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行网关");
    }

    // ======================== 驳回至发起人 ========================

    @Test
    void testRejectTaskToInitiator() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        stubRollback();

        taskWorkflow.rejectTaskToInitiator("task-001", "退回发起人");

        // cleanup 已委托给 ExecutionTreeHelper，回退逻辑独立执行
        verify(mockExecutionTreeHelper).detachFromParallelGateway(eq("exec-task-001"), anyString());
    }

    @Test
    void testRejectTaskToInitiatorWhenAlreadyAtInitiator() {
        PlusTask task = createTask("task-001", "leave:1:abc", "startTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        assertThatThrownBy(() -> taskWorkflow.rejectTaskToInitiator("task-001", "退回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("已是发起人节点");
    }

    // ======================== 撤回 ========================

    @Test
    void testWithdrawTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", "leave:1:abc", "task1", "pi-001", USER_ID, "上一节点",
                new Date(), new Date(), null);
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId("pi-001")).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey("task1")).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        stubRollback();

        taskWorkflow.withdrawTask("task-001", "撤回测试");

        verify(mockTaskService).addComment("task-001", "pi-001", "WITHDRAW", "撤回测试");
    }

    @Test
    void testWithdrawTaskRejectsOwnTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无法撤回自己当前处理的任务");
    }

    @Test
    void testWithdrawTaskRejectsNotPrevAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", "leave:1:abc", "task1", "pi-001", "otherUser", "上一节点",
                new Date(), new Date(), null);
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId("pi-001")).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey("task1")).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        assertThatThrownBy(() -> taskWorkflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是上一节点审批人");
    }

    // ======================== 撤销 ========================

    @Test
    void testRevokeProcess() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", USER_ID, new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(mockPi);

        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        Task activeTask = createMockTask("task-001", "leave:1:abc", "startTask", "pi-001", "user2");
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId("pi-001")).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(activeTask);

        taskWorkflow.revokeProcess("pi-001", "发起人撤销");

        verify(mockRuntimeService).deleteProcessInstance("pi-001", "发起人撤销");
    }

    @Test
    void testRevokeProcessRejectsNullProcessInstanceId() {
        assertThatThrownBy(() -> taskWorkflow.revokeProcess(null, "撤销"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    @Test
    void testRevokeProcessRejectsNonExistent() {
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-nope")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> taskWorkflow.revokeProcess("pi-nope", "撤销"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void testRevokeProcessRejectsNonInitiator() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", "anotherUser", new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        assertThatThrownBy(() -> taskWorkflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无权撤销");
    }

    @Test
    void testRevokeProcessRejectsAlreadyEnded() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", USER_ID, new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> taskWorkflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    void testRevokeProcessRejectsAdvancedBeyondInitiator() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", USER_ID, new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(mockPi);

        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        Task activeTask = createMockTask("task-001", "leave:1:abc", "task2", "pi-001", "user2");
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId("pi-001")).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(activeTask);

        assertThatThrownBy(() -> taskWorkflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已推进后续节点");
    }

    // ======================== 驳回无 reason ========================

    @Test
    void testRejectTaskWithoutReason() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        taskWorkflow.rejectTask("task-001", null);

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    // ======================== 任意跳转 — jumpToNode ========================

    @Test
    void testJumpToNodeReject() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("task1", "task2"));

        stubRollback();

        taskWorkflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT);

        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.REJECT.name(), "不同意");
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    @Test
    void testJumpToNodeReturn() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("task1", "task2"));

        stubRollback();

        taskWorkflow.jumpToNode("task-001", "task2", "退回重审", CommentType.RETURN);

        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.RETURN.name(), "退回重审");
    }

    @Test
    void testJumpToNodeWithoutReason() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        taskWorkflow.jumpToNode("task-001", "task1", null, CommentType.REJECT);

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testJumpToNodeRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", "user2");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testJumpToNodeRejectsInvalidTarget() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("task1", "task2"));

        assertThatThrownBy(() -> taskWorkflow.jumpToNode("task-001", "task5", "不同意", CommentType.REJECT))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("不在可跳转的历史节点列表中");
    }

    @Test
    void testJumpToNodeRejectsSelfJump() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.jumpToNode("task-001", "task3", "不同意", CommentType.REJECT))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("自跳转");
    }

    @Test
    void testJumpToNodeRejectsNullTargetNodeId() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.jumpToNode("task-001", null, "不同意", CommentType.REJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetNodeId");
    }

    @Test
    void testJumpToNodeRejectsNullCommentType() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.jumpToNode("task-001", "task1", "不同意", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commentType");
    }

    @Test
    void testJumpToNodeRejectsMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        assertThatThrownBy(() -> taskWorkflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例");
    }

    // ======================== 任意跳转 — getJumpableNodes ========================

    @Test
    void testGetJumpableNodes() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("task2", "task1"));
        when(mockNodeFinder.getNodeName("leave:1:abc", "task1")).thenReturn("发起人节点");
        when(mockNodeFinder.getNodeName("leave:1:abc", "task2")).thenReturn("部门经理审批");

        Date earlierTime = new Date(1000);
        Date laterTime = new Date(2000);

        HistoricTaskInstance historicTask1 = createMockHistoricTask(
                "ht-1", "leave:1:abc", "task1", "pi-001", "user1", "发起人节点", new Date(), earlierTime, null);
        HistoricTaskInstance historicTask2 = createMockHistoricTask(
                "ht-2", "leave:1:abc", "task2", "pi-001", "user2", "部门经理审批", new Date(), laterTime, null);

        HistoricTaskInstanceQuery q1 = mock(HistoricTaskInstanceQuery.class);
        when(q1.processInstanceId("pi-001")).thenReturn(q1);
        when(q1.taskDefinitionKey("task1")).thenReturn(q1);
        when(q1.finished()).thenReturn(q1);
        when(q1.orderByHistoricTaskInstanceEndTime()).thenReturn(q1);
        when(q1.desc()).thenReturn(q1);
        when(q1.listPage(0, 1)).thenReturn(Collections.singletonList(historicTask1));

        HistoricTaskInstanceQuery q2 = mock(HistoricTaskInstanceQuery.class);
        when(q2.processInstanceId("pi-001")).thenReturn(q2);
        when(q2.taskDefinitionKey("task2")).thenReturn(q2);
        when(q2.finished()).thenReturn(q2);
        when(q2.orderByHistoricTaskInstanceEndTime()).thenReturn(q2);
        when(q2.desc()).thenReturn(q2);
        when(q2.listPage(0, 1)).thenReturn(Collections.singletonList(historicTask2));

        // findCompletedUserTasks returns ["task2", "task1"], so first query is for task2, then task1
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(q2).thenReturn(q1);

        List<JumpableNodeVO> result = taskWorkflow.getJumpableNodes("task-001");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getNodeId()).isEqualTo("task1");
        assertThat(result.get(0).getNodeName()).isEqualTo("发起人节点");
        assertThat(result.get(0).getAssignee()).isEqualTo("user1");
        assertThat(result.get(1).getNodeId()).isEqualTo("task2");
        assertThat(result.get(1).getNodeName()).isEqualTo("部门经理审批");
        assertThat(result.get(1).getAssignee()).isEqualTo("user2");
    }

    @Test
    void testGetJumpableNodesEmpty() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task1", "pi-001"))
                .thenReturn(Collections.emptyList());

        List<JumpableNodeVO> result = taskWorkflow.getJumpableNodes("task-001");

        assertThat(result).isEmpty();
    }

    @Test
    void testGetJumpableNodesFiltersHistoryWithoutRecord() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Collections.singletonList("task2"));
        when(mockNodeFinder.getNodeName("leave:1:abc", "task2")).thenReturn("部门经理审批");

        stubHistoricTaskLookupEmpty("pi-001", "task2");

        List<JumpableNodeVO> result = taskWorkflow.getJumpableNodes("task-001");

        assertThat(result).isEmpty();
    }

    @Test
    void testGetJumpableNodesRequiresCurrentAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", "user2");
        stubTaskExists(task);

        assertThatThrownBy(() -> taskWorkflow.getJumpableNodes("task-001"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    // ======================== 转办 ========================

    @Test
    void testTransferTaskNormal() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        taskWorkflow.transferTask("task-001", "transferUser", "工作交接");

        verify(mockTaskService).setAssignee("task-001", "transferUser");
        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq(CommentType.TRANSFER.name()), eq("转办给 transferUser（工作交接）"));
    }

    @Test
    void testTransferTaskWithoutReason() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        taskWorkflow.transferTask("task-001", "transferUser", null);

        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq(CommentType.TRANSFER.name()), eq("转办给 transferUser"));
    }

    @Test
    void testTransferTaskRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", "user2");
        stubTaskExists(task);

        assertThatThrownBy(() -> taskWorkflow.transferTask("task-001", "transferUser", null))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testTransferTaskRejectsTransferToSelf() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        assertThatThrownBy(() -> taskWorkflow.transferTask("task-001", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("转办目标不可为当前审批人");
    }

    @Test
    void testTransferTaskRejectsNullTaskId() {
        assertThatThrownBy(() -> taskWorkflow.transferTask(null, "transferUser", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void testTransferTaskRejectsBlankTransferUserId() {
        assertThatThrownBy(() -> taskWorkflow.transferTask("task-001", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transferUserId");
    }

    // ======================== 并行分支防护 ========================

    @Test
    void testRejectTaskOnForkBranchBlocked() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2a", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubForkBranchExecution("exec-task-001", "scope-exec", 2L);

        assertThatThrownBy(() -> taskWorkflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行分支");
    }

    @Test
    void testWithdrawTaskOnForkBranchBlocked() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2a", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubForkBranchExecution("exec-task-001", "scope-exec", 2L);

        assertThatThrownBy(() -> taskWorkflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行分支");
    }

    @Test
    void testJumpToNodeOnForkBranchBlocked() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubForkBranchExecution("exec-task-001", "scope-exec", 2L);

        assertThatThrownBy(() -> taskWorkflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行分支");
    }

    @Test
    void testRejectTaskToInitiatorForkBranchCleanup() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2a", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        stubRollback();

        taskWorkflow.rejectTaskToInitiator("task-001", "退回发起人");

        // cleanup 委托给 ExecutionTreeHelper，回退逻辑独立执行
        verify(mockExecutionTreeHelper).detachFromParallelGateway(eq("exec-task-001"), anyString());
    }

    // ======================== Test Helpers ========================

    /**
     * 默认 stub：模拟非并行分支场景，查询结果为空执行对象。
     * 这样 checkActiveParallelBranch 会直接 return，不抛异常。
     */
    private void stubNoParallelBranch() {
        ExecutionQuery execQuery = mock(ExecutionQuery.class);
        when(execQuery.executionId(anyString())).thenReturn(execQuery);
        when(execQuery.singleResult()).thenReturn(null);
        when(mockRuntimeService.createExecutionQuery()).thenReturn(execQuery);
    }

    /**
     * Stub RuntimeService 使其模拟一个处于并行分支上的执行对象。
     * 当前执行的 parentId 不为 null，且同级活跃执行数为 siblingCount。
     */
    private void stubForkBranchExecution(String executionId, String parentExecutionId, long siblingCount) {
        Execution mockExec = mock(Execution.class);
        when(mockExec.getId()).thenReturn(executionId);
        when(mockExec.getParentId()).thenReturn(parentExecutionId);

        ExecutionQuery execQueryById = mock(ExecutionQuery.class);
        when(execQueryById.executionId(executionId)).thenReturn(execQueryById);
        when(execQueryById.singleResult()).thenReturn(mockExec);

        ExecutionQuery execQueryByParent = mock(ExecutionQuery.class);
        when(execQueryByParent.parentId(parentExecutionId)).thenReturn(execQueryByParent);
        when(execQueryByParent.count()).thenReturn(siblingCount);

        when(mockRuntimeService.createExecutionQuery())
                .thenReturn(execQueryById)
                .thenReturn(execQueryByParent);
    }

    private PlusTask createTask(String taskId, String definitionId, String taskDefKey,
            String instanceId, String assignee) {
        return new PlusTask(taskId, definitionId, taskDefKey, instanceId,
                assignee, null, "测试任务", "exec-" + taskId, new Date());
    }

    private Task createMockTask(String id, String definitionId, String taskDefKey,
            String instanceId, String assignee) {
        Task mockTask = mock(Task.class);
        when(mockTask.getId()).thenReturn(id);
        when(mockTask.getProcessDefinitionId()).thenReturn(definitionId);
        when(mockTask.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(mockTask.getProcessInstanceId()).thenReturn(instanceId);
        when(mockTask.getAssignee()).thenReturn(assignee);
        when(mockTask.getName()).thenReturn("测试任务");
        when(mockTask.getExecutionId()).thenReturn("exec-" + id);
        when(mockTask.getCreateTime()).thenReturn(new Date());
        return mockTask;
    }

    private HistoricTaskInstance createMockHistoricTask(String id, String definitionId, String taskDefKey,
            String instanceId, String assignee, String name, Date createTime, Date endTime, String deleteReason) {
        HistoricTaskInstance mockTask = mock(HistoricTaskInstance.class);
        when(mockTask.getId()).thenReturn(id);
        when(mockTask.getProcessDefinitionId()).thenReturn(definitionId);
        when(mockTask.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(mockTask.getProcessInstanceId()).thenReturn(instanceId);
        when(mockTask.getAssignee()).thenReturn(assignee);
        when(mockTask.getName()).thenReturn(name);
        when(mockTask.getCreateTime()).thenReturn(createTime);
        when(mockTask.getEndTime()).thenReturn(endTime);
        when(mockTask.getDeleteReason()).thenReturn(deleteReason);
        return mockTask;
    }

    private HistoricProcessInstance createMockHistoricPi(String id, String businessKey, String definitionId,
            String definitionKey, String definitionName, String startUserId,
            Date startTime, Date endTime, String deleteReason) {
        HistoricProcessInstance mockHpi = mock(HistoricProcessInstance.class);
        when(mockHpi.getId()).thenReturn(id);
        when(mockHpi.getBusinessKey()).thenReturn(businessKey);
        when(mockHpi.getProcessDefinitionId()).thenReturn(definitionId);
        when(mockHpi.getProcessDefinitionKey()).thenReturn(definitionKey);
        when(mockHpi.getProcessDefinitionName()).thenReturn(definitionName);
        when(mockHpi.getStartUserId()).thenReturn(startUserId);
        when(mockHpi.getStartTime()).thenReturn(startTime);
        when(mockHpi.getEndTime()).thenReturn(endTime);
        when(mockHpi.getDeleteReason()).thenReturn(deleteReason);
        return mockHpi;
    }

    private void stubTaskExists(PlusTask task) {
        Task mockTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(task.getId())).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(mockTask);
    }

    private void stubTaskExistsWithAssignee(PlusTask task) {
        stubTaskExists(task);
    }

    private void stubCompletedTask(String taskId) {
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(taskId)).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(null);

        HistoricTaskInstance mockHTI = createMockHistoricTask(taskId, "leave:1:abc", "task1", "pi-001",
                USER_ID, null, new Date(), new Date(), null);
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.taskId(taskId)).thenReturn(histTaskQuery);
        when(histTaskQuery.singleResult()).thenReturn(mockHTI);
    }

    private void stubNonexistentTask(String taskId) {
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(taskId)).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(null);

        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.taskId(taskId)).thenReturn(histTaskQuery);
        when(histTaskQuery.singleResult()).thenReturn(null);
    }

    private void stubRollback() {
        ChangeActivityStateBuilder mockBuilder = mock(ChangeActivityStateBuilder.class);
        when(mockRuntimeService.createChangeActivityStateBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.processInstanceId(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.moveActivityIdTo(anyString(), anyString())).thenReturn(mockBuilder);
    }

    private void stubHistoricTaskLookup(String processInstanceId, String taskDefKey,
                                         HistoricTaskInstance task) {
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId(processInstanceId)).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey(taskDefKey)).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.singletonList(task));
    }

    private void stubHistoricTaskLookupEmpty(String processInstanceId, String taskDefKey) {
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId(processInstanceId)).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey(taskDefKey)).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.emptyList());
    }
}
