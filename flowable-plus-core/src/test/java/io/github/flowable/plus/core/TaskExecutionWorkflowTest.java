package io.github.flowable.plus.core;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.event.EventBus;
import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.strategy.CountersignRollbackStrategies;
import io.github.flowable.plus.core.strategy.CountersignRollbackStrategy;
import io.github.flowable.plus.core.strategy.PreviousNodeResolvers;
import io.github.flowable.plus.core.support.ProcessEndDetector;
import io.github.flowable.plus.core.vo.JumpableNodeVO;
import io.github.flowable.plus.core.vo.RollbackResult;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * TaskExecutionWorkflow 单元测试：覆盖同意、驳回、撤回、跳转、转办、认领的
 * 正常路径和所有异常路径。
 */
public class TaskExecutionWorkflowTest {

    private static final String USER_ID = "user1";

    private UserContext userContext;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private RuntimeService mockRuntimeService;
    private NodeFinder mockNodeFinder;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private ExecutionTreeHelper mockExecutionTreeHelper;
    private ProcessEndDetector mockProcessEndDetector;
    private TaskExecutionWorkflow workflow;
    private ChangeActivityStateBuilder mockChangeStateBuilder;
    private CountersignRollbackStrategy mockCountersignRollbackStrategy;

    @BeforeEach
    void setUp() {
        userContext = () -> USER_ID;
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockNodeFinder = mock(NodeFinder.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        mockExecutionTreeHelper = mock(ExecutionTreeHelper.class);
        mockProcessEndDetector = mock(ProcessEndDetector.class);

        mockCountersignRollbackStrategy = CountersignRollbackStrategies.strict(mockMultiInstanceDetector);

        // 默认 stub：createExecutionQuery 返回空执行对象（非并行分支场景）
        stubNoParallelBranch();

        workflow = new TaskExecutionWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockNodeFinder, mockMultiInstanceDetector,
                mockExecutionTreeHelper, new EventBus(null), mockProcessEndDetector,
                mockCountersignRollbackStrategy);
    }

    // ======================== 同意 ========================

    @Test
    void testCompleteTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        workflow.completeTask("task-001", null, null);

        verify(mockTaskService).claim("task-001", USER_ID);
        verify(mockTaskService).complete("task-001", null);
        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testCompleteTaskWithComment() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        workflow.completeTask("task-001", null, "同意");

        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.AGREE.name(), "同意");
        verify(mockTaskService).complete("task-001", null);
    }

    @Test
    void testCompleteTaskWithVariables() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        Map<String, Object> vars = new HashMap<>();
        vars.put("approved", true);
        workflow.completeTask("task-001", vars, null);

        verify(mockTaskService).complete("task-001", vars);
    }

    @Test
    void testCompleteTaskRejectsMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);

        assertThatThrownBy(() -> workflow.completeTask("task-001", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    // ======================== 折返发起人决策任务豁免（ADR-0035） ========================

    @Test
    void testCompleteTaskBlocksInitiatorDecisionTask() {
        // completeTask 不放行折返发起人决策任务（同意路径由上游 completePseudoSingletonByEngine 独立处理）
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(any(PlusTask.class))).thenReturn(true);

        assertThatThrownBy(() -> workflow.completeTask("task-001", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testRejectTaskAllowsInitiatorDecisionTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(any(PlusTask.class))).thenReturn(true);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        workflow.rejectTask("task-001", "不同意");

        verify(mockChangeStateBuilder).moveActivityIdTo("task2", "task1");
    }

    @Test
    void testRejectTaskStillBlocksMultiInstanceWhenNotInitiatorDecisionTask() {
        // 会签剩最后 1 人未投（投票人持任务）：isInitiatorDecisionTask=false → 仍拦截
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> workflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testRejectTaskToInitiatorAllowsInitiatorDecisionTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(any(PlusTask.class))).thenReturn(true);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        stubRollback();

        workflow.rejectTaskToInitiator("task-001", "退回发起人");

        verify(mockExecutionTreeHelper).detachFromParallelGateway(eq("exec-task-001"), anyString());
    }

    @Test
    void testWithdrawTaskAllowsInitiatorDecisionTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(any(PlusTask.class))).thenReturn(true);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubPreviousNodeHistory("task1", USER_ID);
        stubRollback();

        workflow.withdrawTask("task-001", "撤回");

        verify(mockChangeStateBuilder).moveActivityIdTo("task2", "task1");
    }

    @Test
    void testJumpToNodeAllowsInitiatorDecisionTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(any(PlusTask.class))).thenReturn(true);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        workflow.jumpToNode("task-001", "task1", "退回重审", CommentType.RETURN);

        verify(mockChangeStateBuilder).moveActivityIdTo("task3", "task1");
    }

    @Test
    void testCompleteTaskRejectsCompletedTask() {
        stubCompletedTask("task-001");

        assertThatThrownBy(() -> workflow.completeTask("task-001", null, null))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已完成");
    }

    @Test
    void testCompleteTaskRejectsNonexistentTask() {
        stubNonexistentTask("task-nope");

        assertThatThrownBy(() -> workflow.completeTask("task-nope", null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    // ======================== 认领 ========================

    @Test
    void testClaimTask() {
        workflow.claimTask("task-001");
        verify(mockTaskService).claim("task-001", USER_ID);
    }

    @Test
    void testClaimTaskRejectsNullTaskId() {
        assertThatThrownBy(() -> workflow.claimTask(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== 驳回 ========================

    @Test
    void testRejectTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        workflow.rejectTask("task-001", "不同意");

        verify(mockTaskService).addComment("task-001", "pi-001", "REJECT", "不同意");
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    @Test
    void testRejectTaskRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user2");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> workflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testRejectTaskRejectsParallelGateway() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Arrays.asList("task1a", "task1b"));

        assertThatThrownBy(() -> workflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("无法确定唯一上一审批节点");
    }

    @Test
    void testRejectTaskWithFirstCandidateStrategy() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Arrays.asList("task1a", "task1b"));

        stubRollback();

        workflow.rejectTask("task-001", "不同意",
                PreviousNodeResolvers.firstCandidate());

        verify(mockChangeStateBuilder).moveActivityIdTo("task2", "task1a");
    }

    @Test
    void testRejectTaskWithEarliestStartedStrategy() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Arrays.asList("task1a", "task1b"));

        // task1b started earlier (500000 < 1000000), strategy should select task1b
        HistoricActivityInstance activityA = mock(HistoricActivityInstance.class);
        when(activityA.getStartTime()).thenReturn(new Date(1000000L));
        HistoricActivityInstance activityB = mock(HistoricActivityInstance.class);
        when(activityB.getStartTime()).thenReturn(new Date(500000L));

        HistoricActivityInstanceQuery queryA = mock(HistoricActivityInstanceQuery.class);
        HistoricActivityInstanceQuery queryB = mock(HistoricActivityInstanceQuery.class);
        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(queryA).thenReturn(queryB);

        when(queryA.processInstanceId("pi-001")).thenReturn(queryA);
        when(queryA.activityId("task1a")).thenReturn(queryA);
        when(queryA.finished()).thenReturn(queryA);
        when(queryA.orderByHistoricActivityInstanceStartTime()).thenReturn(queryA);
        when(queryA.asc()).thenReturn(queryA);
        when(queryA.listPage(0, 1)).thenReturn(Collections.singletonList(activityA));

        when(queryB.processInstanceId("pi-001")).thenReturn(queryB);
        when(queryB.activityId("task1b")).thenReturn(queryB);
        when(queryB.finished()).thenReturn(queryB);
        when(queryB.orderByHistoricActivityInstanceStartTime()).thenReturn(queryB);
        when(queryB.asc()).thenReturn(queryB);
        when(queryB.listPage(0, 1)).thenReturn(Collections.singletonList(activityB));

        stubRollback();

        workflow.rejectTask("task-001", "不同意",
                PreviousNodeResolvers.earliestStarted());

        verify(mockChangeStateBuilder).moveActivityIdTo("task2", "task1b");
    }

    @Test
    void testWithdrawTaskRejectsMultiNodeWithoutStrategy() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Arrays.asList("task1a", "task1b"));

        assertThatThrownBy(() -> workflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("无法确定唯一上一审批节点");
    }

    // ======================== 驳回至发起人 ========================

    @Test
    void testRejectTaskToInitiator() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        stubRollback();

        workflow.rejectTaskToInitiator("task-001", "退回发起人");

        // cleanup 已委托给 ExecutionTreeHelper，回退逻辑独立执行
        verify(mockExecutionTreeHelper).detachFromParallelGateway(eq("exec-task-001"), anyString());
    }

    @Test
    void testRejectTaskToInitiatorWhenAlreadyAtInitiator() {
        PlusTask task = createTask("task-001", "leave:1:abc", "startTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        assertThatThrownBy(() -> workflow.rejectTaskToInitiator("task-001", "退回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("已是发起人节点");
    }

    // ======================== 撤回 ========================

    @Test
    void testWithdrawTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
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

        workflow.withdrawTask("task-001", "撤回测试");

        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.WITHDRAW.name(), "撤回测试");
    }

    @Test
    void testWithdrawTaskWithFirstCandidateStrategy() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Arrays.asList("task1a", "task1b"));

        stubPreviousNodeHistory("task1a", USER_ID);
        stubRollback();

        workflow.withdrawTask("task-001", "撤回测试",
                PreviousNodeResolvers.firstCandidate());

        verify(mockChangeStateBuilder).moveActivityIdTo("task2", "task1a");
    }

    @Test
    void testWithdrawTaskWithLatestEndedStrategy() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Arrays.asList("task1a", "task1b"));

        // task1a ended later (3000000 > 2000000), strategy should select task1a
        HistoricActivityInstance activityA = mock(HistoricActivityInstance.class);
        when(activityA.getEndTime()).thenReturn(new Date(3000000L));
        HistoricActivityInstance activityB = mock(HistoricActivityInstance.class);
        when(activityB.getEndTime()).thenReturn(new Date(2000000L));

        HistoricActivityInstanceQuery queryA = mock(HistoricActivityInstanceQuery.class);
        HistoricActivityInstanceQuery queryB = mock(HistoricActivityInstanceQuery.class);
        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(queryA).thenReturn(queryB);

        when(queryA.processInstanceId("pi-001")).thenReturn(queryA);
        when(queryA.activityId("task1a")).thenReturn(queryA);
        when(queryA.finished()).thenReturn(queryA);
        when(queryA.orderByHistoricActivityInstanceEndTime()).thenReturn(queryA);
        when(queryA.desc()).thenReturn(queryA);
        when(queryA.listPage(0, 1)).thenReturn(Collections.singletonList(activityA));

        when(queryB.processInstanceId("pi-001")).thenReturn(queryB);
        when(queryB.activityId("task1b")).thenReturn(queryB);
        when(queryB.finished()).thenReturn(queryB);
        when(queryB.orderByHistoricActivityInstanceEndTime()).thenReturn(queryB);
        when(queryB.desc()).thenReturn(queryB);
        when(queryB.listPage(0, 1)).thenReturn(Collections.singletonList(activityB));

        stubPreviousNodeHistory("task1a", USER_ID);
        stubRollback();

        workflow.withdrawTask("task-001", "撤回测试",
                PreviousNodeResolvers.latestEnded());

        verify(mockChangeStateBuilder).moveActivityIdTo("task2", "task1a");
    }

    @Test
    void testWithdrawTaskRejectsNotPrevAssigneeWhenAlsoTaskAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubPreviousNodeHistory("task1", "otherUser");

        assertThatThrownBy(() -> workflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("上一节点审批人");
    }

    @Test
    void testWithdrawTaskRejectsNotPrevAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubPreviousNodeHistory("task1", "otherUser");

        assertThatThrownBy(() -> workflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("上一节点审批人");
    }

    @Test
    void testWithdrawTaskRejectsWhenNoPrevHistory() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        // 上一节点无历史任务 → 无审批人可比对 → 拒绝
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId("pi-001")).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey("task1")).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> workflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("上一节点审批人");
    }

    // ======================== 驳回无 reason ========================

    @Test
    void testRejectTaskWithoutReason() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        workflow.rejectTask("task-001", null);

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    // ======================== 任意跳转 - jumpToNode ========================

    @Test
    void testJumpToNodeReject() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("task1", "task2"));

        stubRollback();

        workflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT);

        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.REJECT.name(), "不同意");
        verify(mockRuntimeService).createChangeActivityStateBuilder();
    }

    @Test
    void testJumpToNodeReturn() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("task1", "task2"));

        stubRollback();

        workflow.jumpToNode("task-001", "task2", "退回重审", CommentType.RETURN);

        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.RETURN.name(), "退回重审");
    }

    @Test
    void testJumpToNodeWithoutReason() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));

        stubRollback();

        workflow.jumpToNode("task-001", "task1", null, CommentType.REJECT);

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testJumpToNodeRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", "user2");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> workflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testJumpToNodeRejectsInvalidTarget() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("task1", "task2"));

        assertThatThrownBy(() -> workflow.jumpToNode("task-001", "task5", "不同意", CommentType.REJECT))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("不在可跳转的历史节点列表中");
    }

    @Test
    void testJumpToNodeRejectsSelfJump() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> workflow.jumpToNode("task-001", "task3", "不同意", CommentType.REJECT))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("自跳转");
    }

    @Test
    void testJumpToNodeRejectsNullTargetNodeId() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> workflow.jumpToNode("task-001", null, "不同意", CommentType.REJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetNodeId");
    }

    @Test
    void testJumpToNodeRejectsNullCommentType() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> workflow.jumpToNode("task-001", "task1", "不同意", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commentType");
    }

    @Test
    void testJumpToNodeRejectsMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(true);

        assertThatThrownBy(() -> workflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例");
    }

    // ======================== 任意跳转 - getJumpableNodes ========================

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

        List<JumpableNodeVO> result = workflow.getJumpableNodes("task-001");

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

        List<JumpableNodeVO> result = workflow.getJumpableNodes("task-001");

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

        List<JumpableNodeVO> result = workflow.getJumpableNodes("task-001");

        assertThat(result).isEmpty();
    }

    @Test
    void testGetJumpableNodesRequiresCurrentAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", "user2");
        stubTaskExists(task);

        assertThatThrownBy(() -> workflow.getJumpableNodes("task-001"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    // ======================== getJumpableNodes - MI 运行时判断 ========================

    @Test
    void testGetJumpableNodesIncludesRuntimeSingleMI() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        // MI 节点在 BPMN 模型中，但运行时 count <= 1
        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Collections.singletonList("miNode"));
        when(mockNodeFinder.getNodeName("leave:1:abc", "miNode")).thenReturn("会签节点");
        when(mockMultiInstanceDetector.isMultiInstanceNode("leave:1:abc", "miNode")).thenReturn(true);

        // count = 1（运行时单例）
        HistoricTaskInstanceQuery countQuery = mock(HistoricTaskInstanceQuery.class);
        when(countQuery.processInstanceId("pi-001")).thenReturn(countQuery);
        when(countQuery.taskDefinitionKey("miNode")).thenReturn(countQuery);
        when(countQuery.count()).thenReturn(1L);

        // 历史任务查询
        HistoricTaskInstance historicTask = createMockHistoricTask(
                "ht-mi", "leave:1:abc", "miNode", "pi-001", "user1", "会签节点",
                new Date(1000), new Date(2000), null);
        HistoricTaskInstanceQuery histQuery = mock(HistoricTaskInstanceQuery.class);
        when(histQuery.processInstanceId("pi-001")).thenReturn(histQuery);
        when(histQuery.taskDefinitionKey("miNode")).thenReturn(histQuery);
        when(histQuery.finished()).thenReturn(histQuery);
        when(histQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histQuery);
        when(histQuery.desc()).thenReturn(histQuery);
        when(histQuery.listPage(0, 1)).thenReturn(Collections.singletonList(historicTask));

        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(countQuery).thenReturn(histQuery);

        List<JumpableNodeVO> result = workflow.getJumpableNodes("task-001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("miNode");
        assertThat(result.get(0).getNodeName()).isEqualTo("会签节点");
        // 运行时单例，displayName 应为 null
        assertThat(result.get(0).getDisplayName()).isNull();
    }

    @Test
    void testGetJumpableNodesIncludesMultiMIWithPredecessor() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Collections.singletonList("miNode"));
        when(mockNodeFinder.getNodeName("leave:1:abc", "miNode")).thenReturn("会签节点");
        when(mockMultiInstanceDetector.isMultiInstanceNode("leave:1:abc", "miNode")).thenReturn(true);

        // count = 3（运行时真正多实例）
        HistoricTaskInstanceQuery countQuery = mock(HistoricTaskInstanceQuery.class);
        when(countQuery.processInstanceId("pi-001")).thenReturn(countQuery);
        when(countQuery.taskDefinitionKey("miNode")).thenReturn(countQuery);
        when(countQuery.count()).thenReturn(3L);

        // 前置节点查找
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "miNode", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));
        when(mockMultiInstanceDetector.isMultiInstanceNode("leave:1:abc", "task1")).thenReturn(false);
        when(mockNodeFinder.getNodeName("leave:1:abc", "task1")).thenReturn("提前请假");

        // 历史任务查询
        HistoricTaskInstance historicTask = createMockHistoricTask(
                "ht-mi", "leave:1:abc", "miNode", "pi-001", "user1", "会签节点",
                new Date(1000), new Date(2000), null);
        HistoricTaskInstanceQuery histQuery = mock(HistoricTaskInstanceQuery.class);
        when(histQuery.processInstanceId("pi-001")).thenReturn(histQuery);
        when(histQuery.taskDefinitionKey("miNode")).thenReturn(histQuery);
        when(histQuery.finished()).thenReturn(histQuery);
        when(histQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histQuery);
        when(histQuery.desc()).thenReturn(histQuery);
        when(histQuery.listPage(0, 1)).thenReturn(Collections.singletonList(historicTask));

        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(countQuery).thenReturn(histQuery);

        List<JumpableNodeVO> result = workflow.getJumpableNodes("task-001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("miNode");
        assertThat(result.get(0).getNodeName()).isEqualTo("会签节点");
        assertThat(result.get(0).getDisplayName())
                .isNotNull()
                .contains("会签节点")
                .contains("系统将重定向至")
                .contains("提前请假");
    }

    @Test
    void testGetJumpableNodesExcludesMultiMIWithoutPredecessor() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        when(mockNodeFinder.findCompletedUserTasks("leave:1:abc", "task3", "pi-001"))
                .thenReturn(Arrays.asList("miNode", "task2"));
        when(mockNodeFinder.getNodeName("leave:1:abc", "miNode")).thenReturn("会签节点");
        when(mockNodeFinder.getNodeName("leave:1:abc", "task2")).thenReturn("普通节点");
        when(mockMultiInstanceDetector.isMultiInstanceNode("leave:1:abc", "miNode")).thenReturn(true);
        when(mockMultiInstanceDetector.isMultiInstanceNode("leave:1:abc", "task2")).thenReturn(false);

        // miNode count = 3，但无前置 → 应被过滤
        HistoricTaskInstanceQuery countQuery = mock(HistoricTaskInstanceQuery.class);
        when(countQuery.processInstanceId("pi-001")).thenReturn(countQuery);
        when(countQuery.taskDefinitionKey("miNode")).thenReturn(countQuery);
        when(countQuery.count()).thenReturn(3L);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "miNode", "pi-001"))
                .thenReturn(Collections.emptyList());

        // task2 普通节点
        HistoricTaskInstance historicTask2 = createMockHistoricTask(
                "ht-2", "leave:1:abc", "task2", "pi-001", "user2", "普通节点",
                new Date(1000), new Date(2000), null);
        HistoricTaskInstanceQuery histQuery2 = mock(HistoricTaskInstanceQuery.class);
        when(histQuery2.processInstanceId("pi-001")).thenReturn(histQuery2);
        when(histQuery2.taskDefinitionKey("task2")).thenReturn(histQuery2);
        when(histQuery2.finished()).thenReturn(histQuery2);
        when(histQuery2.orderByHistoricTaskInstanceEndTime()).thenReturn(histQuery2);
        when(histQuery2.desc()).thenReturn(histQuery2);
        when(histQuery2.listPage(0, 1)).thenReturn(Collections.singletonList(historicTask2));

        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(countQuery).thenReturn(histQuery2);

        List<JumpableNodeVO> result = workflow.getJumpableNodes("task-001");

        // miNode 被过滤（count > 1 + 无前置），只保留 task2
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("task2");
    }

    // ======================== executeRollback - RONYBZYSJFU Comment ========================

    @Test
    void testExecuteRollbackWritesRedirectComment() {
        // 使用可返回 redirect 结果的策略
        CountersignRollbackStrategy redirectStrategy = (task, targetActivityId) ->
                RollbackResult.redirect("predecessorNode",
                        "选择的节点 [目标节点] 在本次流程中为多人会签，已自动重定向至: 前置节点");

        TaskExecutionWorkflow wfWithRedirect = new TaskExecutionWorkflow(
                userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockNodeFinder, mockMultiInstanceDetector,
                mockExecutionTreeHelper, new EventBus(null), mockProcessEndDetector,
                redirectStrategy);

        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));
        stubRollback();

        wfWithRedirect.rejectTask("task-001", "驳回测试");

        // 验证 RONYBZYSJFU comment 被写入
        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq("RONYBZYSJFU"), org.mockito.ArgumentMatchers.contains("多人会签"));
        // 验证原始驳回原因 comment 仍被写入
        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq("REJECT"), eq("驳回测试"));
        // 验证回退目标是重定向后的节点
        verify(mockChangeStateBuilder).moveActivityIdTo("task2", "predecessorNode");
    }

    @Test
    void testExecuteRollbackNoRedirectCommentWhenDirect() {
        // 确认直接回退时不写入 RONYBZYSJFU comment
        PlusTask task = createTask("task-001", "leave:1:abc", "task2", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findPreviousNodes("leave:1:abc", "task2", "pi-001"))
                .thenReturn(Collections.singletonList("task1"));
        stubRollback();

        workflow.rejectTask("task-001", "不同意");

        // StrictCountersignRollbackStrategy 返回 direct，不应写入 RONYBZYSJFU
        verify(mockTaskService, never()).addComment(eq("task-001"), eq("pi-001"),
                eq("RONYBZYSJFU"), anyString());
    }

    @Test
    void testTransferTaskWithoutReason() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        workflow.transferTask("task-001", "transferUser", null);

        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq(CommentType.TRANSFER.name()), eq("转办给 transferUser"));
    }

    @Test
    void testTransferTaskRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", "user2");
        stubTaskExists(task);

        assertThatThrownBy(() -> workflow.transferTask("task-001", "transferUser", null))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testTransferTaskRejectsTransferToSelf() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        assertThatThrownBy(() -> workflow.transferTask("task-001", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("转办目标不可为当前审批人");
    }

    @Test
    void testTransferTaskRejectsNullTaskId() {
        assertThatThrownBy(() -> workflow.transferTask(null, "transferUser", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void testTransferTaskRejectsBlankTransferUserId() {
        assertThatThrownBy(() -> workflow.transferTask("task-001", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transferUserId");
    }

    // ======================== 并行分支防护 ========================

    @Test
    void testRejectTaskOnForkBranchBlocked() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2a", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubForkBranchExecution("exec-task-001", "scope-exec", 2L);

        assertThatThrownBy(() -> workflow.rejectTask("task-001", "不同意"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行分支");
    }

    @Test
    void testWithdrawTaskOnForkBranchBlocked() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2a", "pi-001", "user3");
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubForkBranchExecution("exec-task-001", "scope-exec", 2L);

        assertThatThrownBy(() -> workflow.withdrawTask("task-001", "撤回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行分支");
    }

    @Test
    void testJumpToNodeOnForkBranchBlocked() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task3", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubForkBranchExecution("exec-task-001", "scope-exec", 2L);

        assertThatThrownBy(() -> workflow.jumpToNode("task-001", "task1", "不同意", CommentType.REJECT))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行分支");
    }

    @Test
    void testRejectTaskToInitiatorForkBranchCleanup() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task2a", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        stubRollback();

        workflow.rejectTaskToInitiator("task-001", "退回发起人");

        // cleanup 委托给 ExecutionTreeHelper，回退逻辑独立执行
        verify(mockExecutionTreeHelper).detachFromParallelGateway(eq("exec-task-001"), anyString());
    }

    // ======================== 事件发布 ========================

    private TaskExecutionWorkflow createWorkflowWithEventPublisher(EventPublisher ep) {
        EventBus eventBus = new EventBus(ep);
        ProcessEndDetector ped = new ProcessEndDetector(mockRuntimeService, mockHistoryService, eventBus);
        return new TaskExecutionWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockNodeFinder, mockMultiInstanceDetector,
                mockExecutionTreeHelper, eventBus, ped,
                mockCountersignRollbackStrategy);
    }

    @Test
    void completeTaskShouldPublishTaskCompletedEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        TaskExecutionWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        // 让 tryPublishProcessEnded 认为流程还在运行，不进入历史查询
        ProcessInstance pi = mock(ProcessInstance.class);
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId("pi-001")).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(pi);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        wf.completeTask("task-001", null, "同意");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.TaskCompletedEvent.class));
    }

    @Test
    void rejectTaskShouldPublishTaskRejectedEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        TaskExecutionWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubNoParallelBranch();
        when(mockNodeFinder.findPreviousNodes(anyString(), anyString(), anyString()))
                .thenReturn(Collections.singletonList("node-prev"));
        stubRollback();

        wf.rejectTask("task-001", "不同意");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.TaskRejectedEvent.class));
    }

    @Test
    void withdrawTaskShouldPublishTaskWithdrawnEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        TaskExecutionWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", "user2");
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(any(PlusTask.class))).thenReturn(false);
        stubNoParallelBranch();
        when(mockNodeFinder.findPreviousNodes(anyString(), anyString(), anyString()))
                .thenReturn(Collections.singletonList("node-prev"));
        HistoricTaskInstance prevTask = createMockHistoricTask("ht-prev", "leave:1:abc", "node-prev",
                "pi-001", USER_ID, "审批", new Date(), new Date(), null);
        stubHistoricTaskLookup("pi-001", "node-prev", prevTask);
        stubRollback();

        wf.withdrawTask("task-001", "撤回原因");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.TaskWithdrawnEvent.class));
    }

    @Test
    void transferTaskShouldPublishTaskTransferredEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        TaskExecutionWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);

        wf.transferTask("task-001", "userB", "转办原因");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.TaskTransferredEvent.class));
    }

    // ======================== Test Helpers ========================

    private void stubNoParallelBranch() {
        ExecutionQuery execQuery = mock(ExecutionQuery.class);
        when(execQuery.executionId(anyString())).thenReturn(execQuery);
        when(execQuery.singleResult()).thenReturn(null);
        when(mockRuntimeService.createExecutionQuery()).thenReturn(execQuery);
    }

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
        mockChangeStateBuilder = mock(ChangeActivityStateBuilder.class);
        when(mockRuntimeService.createChangeActivityStateBuilder()).thenReturn(mockChangeStateBuilder);
        when(mockChangeStateBuilder.processInstanceId(anyString())).thenReturn(mockChangeStateBuilder);
        when(mockChangeStateBuilder.moveActivityIdTo(anyString(), anyString())).thenReturn(mockChangeStateBuilder);
    }

    /**
     * stub 上一节点最后一次完成的历史任务（撤回授权校验用）。
     */
    private void stubPreviousNodeHistory(String nodeId, String assignee) {
        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", "leave:1:abc", nodeId, "pi-001", assignee, "上一节点",
                new Date(), new Date(), null);
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId("pi-001")).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey(nodeId)).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));
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
