package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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
 * 正常路径和所有异常路径。
 */
public class TaskWorkflowTest {

    private static final String USER_ID = "user1";

    private UserContext userContext;
    private TaskRepository mockTaskRepo;
    private HistoricRepository mockHistoricRepo;
    private RuntimeService mockRuntimeService;
    private IdentityService mockIdentityService;
    private NodeFinder mockNodeFinder;
    private BpmnModelCache mockBpmnModelCache;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private TaskWorkflow taskWorkflow;

    @BeforeEach
    void setUp() {
        userContext = () -> USER_ID;
        mockTaskRepo = mock(TaskRepository.class);
        mockHistoricRepo = mock(HistoricRepository.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockIdentityService = mock(IdentityService.class);
        mockNodeFinder = mock(NodeFinder.class);
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);

        taskWorkflow = new TaskWorkflow(userContext, mockTaskRepo, mockHistoricRepo,
                mockRuntimeService, mockIdentityService, mockNodeFinder, mockMultiInstanceDetector);
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
        // finally 块仍应清除认证
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

    // ======================== 同意 ========================

    @Test
    void testCompleteTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

        taskWorkflow.completeTask("task-001", null, null);

        verify(mockTaskRepo).claim("task-001", USER_ID);
        verify(mockTaskRepo).complete("task-001", null);
        verify(mockTaskRepo, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testCompleteTaskWithComment() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

        taskWorkflow.completeTask("task-001", null, "同意");

        verify(mockTaskRepo).addComment("task-001", null, null, "同意");
        verify(mockTaskRepo).complete("task-001", null);
    }

    @Test
    void testCompleteTaskWithVariables() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

        Map<String, Object> vars = new HashMap<>();
        vars.put("approved", true);
        taskWorkflow.completeTask("task-001", vars, null);

        verify(mockTaskRepo).complete("task-001", vars);
    }

    @Test
    void testCompleteTaskRejectsMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

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
        when(mockTaskRepo.findById("task-nope")).thenReturn(null);
        when(mockHistoricRepo.findTaskById("task-nope")).thenReturn(null);

        assertThatThrownBy(() -> taskWorkflow.completeTask("task-nope", null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    // ======================== 认领 ========================

    @Test
    void testClaimTask() {
        taskWorkflow.claimTask("task-001");
        verify(mockTaskRepo).claim("task-001", USER_ID);
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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        taskWorkflow.rejectTask("task-001", "不同意");

        verify(mockTaskRepo).addComment("task-001", "pi-001", "REJECT", "不同意");
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    @Test
    void testRejectTaskRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user2");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testRejectTaskRejectsParallelGateway() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);
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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        stubRollback();

        taskWorkflow.rejectTaskToInitiator("task-001", "退回发起人");

        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    @Test
    void testRejectTaskToInitiatorWhenAlreadyAtInitiator() {
        PlusTask task = createTask("task-001", "leave:1:abc", "startTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);
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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        PlusHistoricTask prevTask = new PlusHistoricTask(
                "ht-prev", "leave:1:abc", "task1", "pi-001",
                USER_ID, "上一节点", new Date(), new Date(), null);
        when(mockHistoricRepo.findLatestFinishedTask("pi-001", "task1"))
                .thenReturn(prevTask);

        stubRollback();

        taskWorkflow.withdrawTask("task-001", "撤回测试");

        verify(mockTaskRepo).addComment("task-001", "pi-001", "WITHDRAW", "撤回测试");
    }

    @Test
    void testWithdrawTaskRejectsOwnTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

        assertThatThrownBy(() -> taskWorkflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无法撤回自己当前处理的任务");
    }

    @Test
    void testWithdrawTaskRejectsNotPrevAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        PlusHistoricTask prevTask = new PlusHistoricTask(
                "ht-prev", "leave:1:abc", "task1", "pi-001",
                "otherUser", "上一节点", new Date(), new Date(), null);
        when(mockHistoricRepo.findLatestFinishedTask("pi-001", "task1"))
                .thenReturn(prevTask);

        assertThatThrownBy(() -> taskWorkflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是上一节点审批人");
    }

    // ======================== 撤销 ========================

    @Test
    void testRevokeProcess() {
        PlusHistoricProcessInstance hpi = new PlusHistoricProcessInstance(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批",
                USER_ID, new Date(), null, null);
        when(mockHistoricRepo.findProcessInstance("pi-001")).thenReturn(hpi);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(mockPi);

        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        PlusTask activeTask = createTask("task-001", "leave:1:abc", "startTask", "pi-001", "user2");
        when(mockTaskRepo.findActiveByProcessInstance("pi-001")).thenReturn(activeTask);

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
        when(mockHistoricRepo.findProcessInstance("pi-nope")).thenReturn(null);

        assertThatThrownBy(() -> taskWorkflow.revokeProcess("pi-nope", "撤销"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void testRevokeProcessRejectsNonInitiator() {
        PlusHistoricProcessInstance hpi = new PlusHistoricProcessInstance(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批",
                "anotherUser", new Date(), null, null);
        when(mockHistoricRepo.findProcessInstance("pi-001")).thenReturn(hpi);

        assertThatThrownBy(() -> taskWorkflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无权撤销");
    }

    @Test
    void testRevokeProcessRejectsAlreadyEnded() {
        PlusHistoricProcessInstance hpi = new PlusHistoricProcessInstance(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批",
                USER_ID, new Date(), null, null);
        when(mockHistoricRepo.findProcessInstance("pi-001")).thenReturn(hpi);

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
        PlusHistoricProcessInstance hpi = new PlusHistoricProcessInstance(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批",
                USER_ID, new Date(), null, null);
        when(mockHistoricRepo.findProcessInstance("pi-001")).thenReturn(hpi);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(mockPi);

        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        // 活跃任务在非发起人节点
        PlusTask activeTask = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user2");
        when(mockTaskRepo.findActiveByProcessInstance("pi-001")).thenReturn(activeTask);

        assertThatThrownBy(() -> taskWorkflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已推进后续节点");
    }

    // ======================== 驳回无 reason ========================

    @Test
    void testRejectTaskWithoutReason() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        taskWorkflow.rejectTask("task-001", null);

        // 没有 reason 时不应添加评论
        verify(mockTaskRepo, never()).addComment(anyString(), anyString(), anyString(), anyString());
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    // ======================== Test Helpers ========================

    private PlusTask createTask(String taskId, String definitionId, String taskDefKey,
            String instanceId, String assignee) {
        return new PlusTask(taskId, definitionId, taskDefKey, instanceId,
                assignee, "测试任务", "exec-" + taskId, new Date());
    }

    private void stubTaskExists(PlusTask task) {
        when(mockTaskRepo.findById(task.getId())).thenReturn(task);
    }

    private void stubTaskExistsWithAssignee(PlusTask task) {
        when(mockTaskRepo.findById(task.getId())).thenReturn(task);
    }

    private void stubCompletedTask(String taskId) {
        when(mockTaskRepo.findById(taskId)).thenReturn(null);
        when(mockHistoricRepo.findTaskById(taskId))
                .thenReturn(new PlusHistoricTask(taskId, "leave:1:abc", "task1", "pi-001",
                        USER_ID, null, new Date(), new Date(), null));
    }

    private void stubRollback() {
        ChangeActivityStateBuilder mockBuilder = mock(ChangeActivityStateBuilder.class);
        when(mockRuntimeService.createChangeActivityStateBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.processInstanceId(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.moveActivityIdTo(anyString(), anyString())).thenReturn(mockBuilder);
    }
}
