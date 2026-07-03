package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
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
 * FlowablePlus 集成测试：验证构造注入、服务访问、业务委托和异常包装。
 */
public class FlowablePlusTest {

    private ProcessEngine mockEngine;
    private RepositoryService mockRepoService;
    private RuntimeService mockRuntimeService;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private IdentityService mockIdentityService;
    private FlowablePlus flowablePlus;

    private UserContext userContext;
    private NodeFinder mockNodeFinder;

    @BeforeEach
    public void setUp() {
        mockEngine = mock(ProcessEngine.class);
        mockRepoService = mock(RepositoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockIdentityService = mock(IdentityService.class);
        userContext = () -> "testUser";
        mockNodeFinder = mock(NodeFinder.class);

        when(mockEngine.getRepositoryService()).thenReturn(mockRepoService);
        when(mockEngine.getRuntimeService()).thenReturn(mockRuntimeService);
        when(mockEngine.getTaskService()).thenReturn(mockTaskService);
        when(mockEngine.getHistoryService()).thenReturn(mockHistoryService);
        when(mockEngine.getIdentityService()).thenReturn(mockIdentityService);

        flowablePlus = new FlowablePlus(mockEngine, userContext, new DefaultNodeFinder(mockRepoService, mockHistoryService));
    }

    // ======================== 构造注入 ========================

    @Test
    public void testConstructorInjectsProcessEngine() {
        assertThat(flowablePlus.getProcessEngine()).isSameAs(mockEngine);
    }

    @Test
    public void testConstructorRejectsNullProcessEngine() {
        assertThatThrownBy(() -> new FlowablePlus(null, userContext, mockNodeFinder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProcessEngine 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullUserContext() {
        assertThatThrownBy(() -> new FlowablePlus(mockEngine, null, mockNodeFinder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserContext 不可为 null");
    }

    // ======================== getter ========================

    @Test
    public void testGetUserContext() {
        assertThat(flowablePlus.getUserContext()).isSameAs(userContext);
    }

    // ======================== findPreviousNodes ========================

    /**
     * 正常委托：start → task1 → task2，从 task2 回溯应找到 [task1]
     */
    @Test
    public void testFindPreviousNodesDelegation() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        List<String> result = flowablePlus.findPreviousNodes("proc-1", "task2", null);

        assertThat(result).containsExactly("task1");
    }

    @Test
    public void testFindPreviousNodesModelNotFound() {
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(null);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "task1", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 proc-1 不存在");
    }

    @Test
    public void testFindPreviousNodesNodeNotFound() {
        TestModelBuilder builder = new TestModelBuilder();
        builder.addStartEvent("start");
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "nonexistent", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("节点 nonexistent 不存在");
    }

    @Test
    public void testFindPreviousNodesNoPreviousNode() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "task1", null))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("节点 task1 无上一审批节点");
    }

    @Test
    public void testFindPreviousNodesNullProcDefId() {
        assertThatThrownBy(() -> flowablePlus.findPreviousNodes(null, "task1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    @Test
    public void testFindPreviousNodesNullActivityId() {
        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentActivityId");
    }

    // ======================== findInitiatorNode ========================

    /**
     * 正常委托：start → task1，应返回 task1
     */
    @Test
    public void testFindInitiatorNodeDelegation() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        String result = flowablePlus.findInitiatorNode("proc-1");

        assertThat(result).isEqualTo("task1");
    }

    @Test
    public void testFindInitiatorNodeModelNotFound() {
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(null);

        assertThatThrownBy(() -> flowablePlus.findInitiatorNode("proc-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 proc-1 不存在");
    }

    @Test
    public void testFindInitiatorNodeNullProcDefId() {
        assertThatThrownBy(() -> flowablePlus.findInitiatorNode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    // ======================== startProcess ========================

    @Test
    public void testStartProcess() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("key", "value");
        ProcessInstance mockInstance = mock(ProcessInstance.class);
        when(mockRuntimeService.startProcessInstanceByKey("myProcess", "biz-001", variables))
                .thenReturn(mockInstance);

        ProcessInstance result = flowablePlus.startProcess("myProcess", "biz-001", variables);

        assertThat(result).isSameAs(mockInstance);
        // 验证身份服务设置
        verify(mockIdentityService).setAuthenticatedUserId("testUser");
        verify(mockIdentityService).setAuthenticatedUserId(null);
        // 验证启动流程
        verify(mockRuntimeService).startProcessInstanceByKey("myProcess", "biz-001", variables);
    }

    @Test
    public void testStartProcessWithNullBusinessKey() {
        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockInstance = mock(ProcessInstance.class);
        when(mockRuntimeService.startProcessInstanceByKey("myProcess", null, variables))
                .thenReturn(mockInstance);

        ProcessInstance result = flowablePlus.startProcess("myProcess", null, variables);

        assertThat(result).isSameAs(mockInstance);
        verify(mockIdentityService).setAuthenticatedUserId("testUser");
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    @Test
    public void testStartProcessNullKey() {
        assertThatThrownBy(() -> flowablePlus.startProcess(null, "biz", new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionKey");
    }

    // ======================== completeTask ========================

    @Test
    public void testCompleteTaskWithComment() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("approved", true);

        flowablePlus.completeTask("task-001", variables, "同意");

        // 验证操作顺序：先认领、再添加意见、最后完成
        verify(mockTaskService).claim("task-001", "testUser");
        verify(mockTaskService).addComment("task-001", null, "同意");
        verify(mockTaskService).complete("task-001", variables);
    }

    @Test
    public void testCompleteTaskWithoutComment() {
        flowablePlus.completeTask("task-002", null, null);

        verify(mockTaskService).claim("task-002", "testUser");
        verify(mockTaskService, never()).addComment(any(), any(), any());
        verify(mockTaskService).complete("task-002", null);
    }

    @Test
    public void testCompleteTaskWithEmptyComment() {
        flowablePlus.completeTask("task-003", null, "");

        verify(mockTaskService).claim("task-003", "testUser");
        verify(mockTaskService, never()).addComment(any(), any(), any());
        verify(mockTaskService).complete("task-003", null);
    }

    @Test
    public void testCompleteTaskNullId() {
        assertThatThrownBy(() -> flowablePlus.completeTask(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== claimTask ========================

    @Test
    public void testClaimTask() {
        flowablePlus.claimTask("task-001");

        verify(mockTaskService).claim("task-001", "testUser");
    }

    @Test
    public void testClaimTaskNullId() {
        assertThatThrownBy(() -> flowablePlus.claimTask(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== 三参构造器 (NodeFinder 注入) ========================

    @Test
    public void testConstructorRejectsNullNodeFinder() {
        assertThatThrownBy(() -> new FlowablePlus(mockEngine, userContext, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder");
    }

    @Test
    public void testCustomNodeFinderInjection() {
        NodeFinder mockNodeFinder = mock(NodeFinder.class);
        when(mockNodeFinder.findPreviousNodes("proc-1", "task1", null))
                .thenReturn(java.util.Collections.singletonList("task0"));
        when(mockNodeFinder.findInitiatorNode("proc-1"))
                .thenReturn("initiatorTask");

        FlowablePlus customFp = new FlowablePlus(mockEngine, userContext, mockNodeFinder);

        assertThat(customFp.findPreviousNodes("proc-1", "task1", null)).containsExactly("task0");
        assertThat(customFp.findInitiatorNode("proc-1")).isEqualTo("initiatorTask");

        verify(mockNodeFinder).findPreviousNodes("proc-1", "task1", null);
        verify(mockNodeFinder).findInitiatorNode("proc-1");
    }

    // ======================== rejectTask ========================

    /**
     * 正常驳回：task1 → task2，审批 task2 时驳回至 task1。
     */
    @Test
    public void testRejectTaskNormalFlow() {
        // 准备 BPMN 模型：start → task1 → task2
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 准备任务查询：当前任务为 task2
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "testUser");
        stubTaskQuery(mockTask);

        // 准备 ChangeActivityStateBuilder
        ChangeActivityStateBuilder mockBuilder = stubChangeActivityStateBuilder();

        flowablePlus.rejectTask("task-001", "审批不通过");

        // 验证驳回意见写入
        verify(mockTaskService).addComment("task-001", "pi-001", "REJECT", "审批不通过");
        // 验证节点跳转
        verify(mockBuilder).processInstanceId("pi-001");
        verify(mockBuilder).moveActivityIdTo("task2", "task1");
        verify(mockBuilder).changeState();
    }

    /**
     * 并行网关汇合后驳回，应抛出 NoPreviousNodeException。
     */
    @Test
    public void testRejectTaskParallelGatewayMerge() {
        // BPMN 模型：start → task1 → pgwSplit → task2 / task3 → pgwMerge → task4
        // 从 task4 回溯会找到 [task2, task3]，触发并行网关拒绝
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f0", start, task1);
        ParallelGateway pgwSplit = builder.addParallelGateway("pgwSplit");
        builder.addSequenceFlow("f1", task1, pgwSplit);
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f2", pgwSplit, task2);
        builder.addSequenceFlow("f3", pgwSplit, task3);
        ParallelGateway pgwMerge = builder.addParallelGateway("pgwMerge");
        builder.addSequenceFlow("f4", task2, pgwMerge);
        builder.addSequenceFlow("f5", task3, pgwMerge);
        UserTask task4 = builder.addUserTask("task4");
        builder.addSequenceFlow("f6", pgwMerge, task4);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 当前任务为 task4
        Task mockTask = createMockTask("task-004", "pi-001", "proc-1", "task4", "testUser");
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.rejectTask("task-004", "驳回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行网关汇合");
    }

    /**
     * 任务不存在，应抛出 NotFoundException。
     */
    @Test
    public void testRejectTaskNotFound() {
        // 任务运行时查不到
        stubTaskQuery(null);
        // 历史也查不到
        stubHistoricTaskQuery(null);

        assertThatThrownBy(() -> flowablePlus.rejectTask("task-nonexistent", "驳回"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("任务 task-nonexistent 不存在");
    }

    /**
     * 任务已完成，应抛出 TaskAlreadyCompletedException。
     */
    @Test
    public void testRejectTaskAlreadyCompleted() {
        // 任务运行时查不到
        stubTaskQuery(null);
        // 历史中查到已完成
        HistoricTaskInstance mockHistoric = mock(HistoricTaskInstance.class);
        stubHistoricTaskQuery(mockHistoric);

        assertThatThrownBy(() -> flowablePlus.rejectTask("task-completed", "驳回"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("任务 task-completed 已完成");
    }

    /**
     * 非审批人调用驳回，应抛出 PermissionDeniedException。
     */
    @Test
    public void testRejectTaskPermissionDenied() {
        // 当前用户为 testUser，但任务审批人为 otherUser
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "otherUser");
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.rejectTask("task-001", "驳回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是任务 task-001 的审批人");
    }

    /**
     * 任务未认领（assignee 为 null），应抛出 PermissionDeniedException。
     */
    @Test
    public void testRejectTaskUnassigned() {
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", null);
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.rejectTask("task-001", "驳回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无权驳回");
    }

    /**
     * 无上一审批节点，应抛出 NoPreviousNodeException。
     */
    @Test
    public void testRejectTaskNoPreviousNode() {
        // BPMN 模型：start → task1（只有发起人节点）
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 当前任务为 task1，无上一节点
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task1", "testUser");
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.rejectTask("task-001", "驳回"))
                .isInstanceOf(NoPreviousNodeException.class);
    }

    /**
     * null taskId 校验。
     */
    @Test
    public void testRejectTaskNullId() {
        assertThatThrownBy(() -> flowablePlus.rejectTask(null, "驳回"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    /**
     * 驳回原因为空字符串时不写入意见。
     */
    @Test
    public void testRejectTaskWithEmptyReason() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "testUser");
        stubTaskQuery(mockTask);
        stubChangeActivityStateBuilder();

        flowablePlus.rejectTask("task-001", "");

        // 不写入空意见
        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString());
    }

    /**
     * 驳回原因 null 时跳过意见写入。
     */
    @Test
    public void testRejectTaskWithNullReason() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "testUser");
        stubTaskQuery(mockTask);
        stubChangeActivityStateBuilder();

        flowablePlus.rejectTask("task-001", null);

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString());
    }

    // ======================== rejectTaskToInitiator ========================

    /**
     * 正常驳回至发起人节点。
     */
    @Test
    public void testRejectTaskToInitiatorNormalFlow() {
        // BPMN 模型：start → task1 → task2
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 当前任务为 task2
        Task mockTask = createMockTask("task-002", "pi-002", "proc-1", "task2", "testUser");
        stubTaskQuery(mockTask);

        ChangeActivityStateBuilder mockBuilder = stubChangeActivityStateBuilder();

        flowablePlus.rejectTaskToInitiator("task-002", "退回发起人");

        // 验证驳回意见
        verify(mockTaskService).addComment("task-002", "pi-002", "REJECT", "退回发起人");
        // 验证跳转到发起人节点 task1
        verify(mockBuilder).processInstanceId("pi-002");
        verify(mockBuilder).moveActivityIdTo("task2", "task1");
        verify(mockBuilder).changeState();
    }

    /**
     * 当前节点已是发起人节点，应抛出 NoPreviousNodeException。
     */
    @Test
    public void testRejectTaskToInitiatorAlreadyAtInitiator() {
        // BPMN 模型：start → task1
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 当前任务就是发起人节点 task1
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task1", "testUser");
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.rejectTaskToInitiator("task-001", "驳回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("当前已是发起人节点");
    }

    /**
     * 任务不存在（运行时和历史均查不到）。
     */
    @Test
    public void testRejectTaskToInitiatorNotFound() {
        stubTaskQuery(null);
        stubHistoricTaskQuery(null);

        assertThatThrownBy(() -> flowablePlus.rejectTaskToInitiator("task-nonexistent", "驳回"))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * 任务已完成。
     */
    @Test
    public void testRejectTaskToInitiatorAlreadyCompleted() {
        stubTaskQuery(null);
        HistoricTaskInstance mockHistoric = mock(HistoricTaskInstance.class);
        stubHistoricTaskQuery(mockHistoric);

        assertThatThrownBy(() -> flowablePlus.rejectTaskToInitiator("task-completed", "驳回"))
                .isInstanceOf(TaskAlreadyCompletedException.class);
    }

    /**
     * 非审批人无权驳回至发起人。
     */
    @Test
    public void testRejectTaskToInitiatorPermissionDenied() {
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "otherUser");
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.rejectTaskToInitiator("task-001", "驳回"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * null taskId 校验。
     */
    @Test
    public void testRejectTaskToInitiatorNullId() {
        assertThatThrownBy(() -> flowablePlus.rejectTaskToInitiator(null, "驳回"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== withdrawTask ========================

    /**
     * 正常撤回：task1 → task2，task1 审批人撤回已提交的 task2。
     */
    @Test
    public void testWithdrawTaskNormalFlow() {
        // BPMN 模型：start → task1 → task2
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 当前任务为 task2，审批人为 otherUser
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "otherUser");
        stubTaskQuery(mockTask);

        // 历史任务：task1 审批人为当前用户
        HistoricTaskInstance prevHistoric = mock(HistoricTaskInstance.class);
        when(prevHistoric.getAssignee()).thenReturn("testUser");
        stubHistoricTaskQueryByDefKey("pi-001", "task1", prevHistoric);

        ChangeActivityStateBuilder mockBuilder = stubChangeActivityStateBuilder();

        flowablePlus.withdrawTask("task-001", "需要修改内容");

        // 验证撤回意见写入
        verify(mockTaskService).addComment("task-001", "pi-001", "WITHDRAW", "需要修改内容");
        // 验证节点跳转：从 task2 回到 task1
        verify(mockBuilder).processInstanceId("pi-001");
        verify(mockBuilder).moveActivityIdTo("task2", "task1");
        verify(mockBuilder).changeState();
    }

    /**
     * 撤回自己的任务，应抛出 PermissionDeniedException。
     */
    @Test
    public void testWithdrawTaskSelfWithdraw() {
        // 当前任务审批人就是当前用户
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "testUser");
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无法撤回自己当前处理的任务");
    }

    /**
     * 非上一节点审批人撤回，应抛出 PermissionDeniedException。
     */
    @Test
    public void testWithdrawTaskNotPreviousAssignee() {
        // BPMN 模型：start → task1 → task2
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 当前任务审批人为 otherUser
        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "otherUser");
        stubTaskQuery(mockTask);

        // 历史任务 task1 审批人为 anotherUser（不是当前用户）
        HistoricTaskInstance prevHistoric = mock(HistoricTaskInstance.class);
        when(prevHistoric.getAssignee()).thenReturn("anotherUser");
        stubHistoricTaskQueryByDefKey("pi-001", "task1", prevHistoric);

        assertThatThrownBy(() -> flowablePlus.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是上一节点审批人");
    }

    /**
     * 上一节点无历史任务记录，应抛出 PermissionDeniedException。
     */
    @Test
    public void testWithdrawTaskNoPreviousHistoricTask() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "otherUser");
        stubTaskQuery(mockTask);

        // 历史查询返回空列表
        stubHistoricTaskQueryByDefKey("pi-001", "task1", null);

        assertThatThrownBy(() -> flowablePlus.withdrawTask("task-001", "撤回"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * 并行网关汇合后撤回，应抛出 NoPreviousNodeException。
     */
    @Test
    public void testWithdrawTaskParallelGatewayMerge() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f0", start, task1);
        ParallelGateway pgwSplit = builder.addParallelGateway("pgwSplit");
        builder.addSequenceFlow("f1", task1, pgwSplit);
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f2", pgwSplit, task2);
        builder.addSequenceFlow("f3", pgwSplit, task3);
        ParallelGateway pgwMerge = builder.addParallelGateway("pgwMerge");
        builder.addSequenceFlow("f4", task2, pgwMerge);
        builder.addSequenceFlow("f5", task3, pgwMerge);
        UserTask task4 = builder.addUserTask("task4");
        builder.addSequenceFlow("f6", pgwMerge, task4);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        Task mockTask = createMockTask("task-004", "pi-001", "proc-1", "task4", "otherUser");
        stubTaskQuery(mockTask);

        assertThatThrownBy(() -> flowablePlus.withdrawTask("task-004", "撤回"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("并行网关汇合");
    }

    /**
     * 任务不存在，应抛出 NotFoundException。
     */
    @Test
    public void testWithdrawTaskNotFound() {
        stubTaskQuery(null);
        stubHistoricTaskQuery(null);

        assertThatThrownBy(() -> flowablePlus.withdrawTask("task-nonexistent", "撤回"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("任务 task-nonexistent 不存在");
    }

    /**
     * 任务已完成，应抛出 TaskAlreadyCompletedException。
     */
    @Test
    public void testWithdrawTaskAlreadyCompleted() {
        stubTaskQuery(null);
        HistoricTaskInstance mockHistoric = mock(HistoricTaskInstance.class);
        stubHistoricTaskQuery(mockHistoric);

        assertThatThrownBy(() -> flowablePlus.withdrawTask("task-completed", "撤回"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("任务 task-completed 已完成");
    }

    /**
     * null taskId 校验。
     */
    @Test
    public void testWithdrawTaskNullId() {
        assertThatThrownBy(() -> flowablePlus.withdrawTask(null, "撤回"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    /**
     * 撤回原因为空字符串时不写入意见。
     */
    @Test
    public void testWithdrawTaskEmptyReason() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "otherUser");
        stubTaskQuery(mockTask);

        HistoricTaskInstance prevHistoric = mock(HistoricTaskInstance.class);
        when(prevHistoric.getAssignee()).thenReturn("testUser");
        stubHistoricTaskQueryByDefKey("pi-001", "task1", prevHistoric);

        stubChangeActivityStateBuilder();

        flowablePlus.withdrawTask("task-001", "");

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 撤回原因 null 时跳过意见写入。
     */
    @Test
    public void testWithdrawTaskNullReason() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        Task mockTask = createMockTask("task-001", "pi-001", "proc-1", "task2", "otherUser");
        stubTaskQuery(mockTask);

        HistoricTaskInstance prevHistoric = mock(HistoricTaskInstance.class);
        when(prevHistoric.getAssignee()).thenReturn("testUser");
        stubHistoricTaskQueryByDefKey("pi-001", "task1", prevHistoric);

        stubChangeActivityStateBuilder();

        flowablePlus.withdrawTask("task-001", null);

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    // ======================== revokeProcess ========================

    /**
     * 正常撤销：发起人节点上的流程实例被撤销。
     */
    @Test
    public void testRevokeProcessNormalFlow() {
        // BPMN 模型：start → task1
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        // 历史流程实例，发起人为 testUser
        HistoricProcessInstance historicPi = mock(HistoricProcessInstance.class);
        when(historicPi.getStartUserId()).thenReturn("testUser");
        when(historicPi.getProcessDefinitionId()).thenReturn("proc-1");
        stubHistoricProcessInstanceQuery("pi-001", historicPi);

        // 运行时流程实例存在
        ProcessInstance runtimePi = mock(ProcessInstance.class);
        stubRuntimeProcessInstanceQuery("pi-001", runtimePi);

        // 当前活跃任务在发起人节点 task1
        Task activeTask = createMockTask("task-001", "pi-001", "proc-1", "task1", "testUser");
        stubActiveTaskQuery("pi-001", activeTask);

        flowablePlus.revokeProcess("pi-001", "发起人撤销流程");

        // 验证软删除
        verify(mockRuntimeService).deleteProcessInstance("pi-001", "发起人撤销流程");
    }

    /**
     * 非发起人撤销，应抛出 PermissionDeniedException。
     */
    @Test
    public void testRevokeProcessNotInitiator() {
        HistoricProcessInstance historicPi = mock(HistoricProcessInstance.class);
        when(historicPi.getStartUserId()).thenReturn("otherUser");
        stubHistoricProcessInstanceQuery("pi-001", historicPi);

        assertThatThrownBy(() -> flowablePlus.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是流程实例 pi-001 的发起人");
    }

    /**
     * 流程已推进后续节点（活跃任务不在发起人节点），应抛出 TaskAlreadyCompletedException。
     */
    @Test
    public void testRevokeProcessPastInitiatorNode() {
        // BPMN 模型：start → task1 → task2
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        HistoricProcessInstance historicPi = mock(HistoricProcessInstance.class);
        when(historicPi.getStartUserId()).thenReturn("testUser");
        when(historicPi.getProcessDefinitionId()).thenReturn("proc-1");
        stubHistoricProcessInstanceQuery("pi-001", historicPi);

        ProcessInstance runtimePi = mock(ProcessInstance.class);
        stubRuntimeProcessInstanceQuery("pi-001", runtimePi);

        // 活跃任务在 task2（已过发起人节点）
        Task activeTask = createMockTask("task-002", "pi-001", "proc-1", "task2", "otherUser");
        stubActiveTaskQuery("pi-001", activeTask);

        assertThatThrownBy(() -> flowablePlus.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已推进后续节点");
    }

    /**
     * 流程实例不存在（历史查不到），应抛出 NotFoundException。
     */
    @Test
    public void testRevokeProcessNotFound() {
        stubHistoricProcessInstanceQuery("pi-001", null);

        assertThatThrownBy(() -> flowablePlus.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程实例 pi-001 不存在");
    }

    /**
     * 流程已结束（历史有但运行时无），应抛出 TaskAlreadyCompletedException。
     */
    @Test
    public void testRevokeProcessAlreadyCompleted() {
        HistoricProcessInstance historicPi = mock(HistoricProcessInstance.class);
        when(historicPi.getStartUserId()).thenReturn("testUser");
        stubHistoricProcessInstanceQuery("pi-001", historicPi);

        // 运行时查不到
        stubRuntimeProcessInstanceQuery("pi-001", null);

        assertThatThrownBy(() -> flowablePlus.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("流程实例 pi-001 已结束");
    }

    /**
     * null processInstanceId 校验。
     */
    @Test
    public void testRevokeProcessNullId() {
        assertThatThrownBy(() -> flowablePlus.revokeProcess(null, "撤销"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    /**
     * 撤销原因 null 也可正常执行。
     */
    @Test
    public void testRevokeProcessNullReason() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        HistoricProcessInstance historicPi = mock(HistoricProcessInstance.class);
        when(historicPi.getStartUserId()).thenReturn("testUser");
        when(historicPi.getProcessDefinitionId()).thenReturn("proc-1");
        stubHistoricProcessInstanceQuery("pi-001", historicPi);

        ProcessInstance runtimePi = mock(ProcessInstance.class);
        stubRuntimeProcessInstanceQuery("pi-001", runtimePi);

        Task activeTask = createMockTask("task-001", "pi-001", "proc-1", "task1", "testUser");
        stubActiveTaskQuery("pi-001", activeTask);

        flowablePlus.revokeProcess("pi-001", null);

        verify(mockRuntimeService).deleteProcessInstance("pi-001", null);
    }

    // ======================== Test Helpers ========================

    private Task createMockTask(String taskId, String processInstanceId,
            String processDefinitionId, String activityId, String assignee) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(task.getProcessDefinitionId()).thenReturn(processDefinitionId);
        when(task.getTaskDefinitionKey()).thenReturn(activityId);
        when(task.getAssignee()).thenReturn(assignee);
        return task;
    }

    private void stubTaskQuery(Task result) {
        TaskQuery mockTaskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.taskId(anyString())).thenReturn(mockTaskQuery);
        when(mockTaskQuery.singleResult()).thenReturn(result);
    }

    private void stubHistoricTaskQuery(HistoricTaskInstance result) {
        HistoricTaskInstanceQuery mockHistoricQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.taskId(anyString())).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.singleResult()).thenReturn(result);
    }

    private HistoricTaskInstanceQuery stubHistoricTaskQueryByDefKey(
            String processInstanceId, String taskDefinitionKey, HistoricTaskInstance result) {
        HistoricTaskInstanceQuery mockQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(mockQuery);
        when(mockQuery.processInstanceId(processInstanceId)).thenReturn(mockQuery);
        when(mockQuery.taskDefinitionKey(taskDefinitionKey)).thenReturn(mockQuery);
        when(mockQuery.finished()).thenReturn(mockQuery);
        when(mockQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockQuery);
        when(mockQuery.desc()).thenReturn(mockQuery);
        List<HistoricTaskInstance> list = result != null
                ? java.util.Collections.singletonList(result) : java.util.Collections.emptyList();
        when(mockQuery.listPage(0, 1)).thenReturn(list);
        return mockQuery;
    }

    private void stubHistoricProcessInstanceQuery(String processInstanceId, HistoricProcessInstance result) {
        HistoricProcessInstanceQuery mockQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(mockQuery);
        when(mockQuery.processInstanceId(processInstanceId)).thenReturn(mockQuery);
        when(mockQuery.singleResult()).thenReturn(result);
    }

    private void stubRuntimeProcessInstanceQuery(String processInstanceId, ProcessInstance result) {
        ProcessInstanceQuery mockQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockQuery);
        when(mockQuery.processInstanceId(processInstanceId)).thenReturn(mockQuery);
        when(mockQuery.singleResult()).thenReturn(result);
    }

    private void stubActiveTaskQuery(String processInstanceId, Task result) {
        TaskQuery mockQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(mockQuery);
        when(mockQuery.processInstanceId(processInstanceId)).thenReturn(mockQuery);
        when(mockQuery.active()).thenReturn(mockQuery);
        when(mockQuery.singleResult()).thenReturn(result);
    }

    private ChangeActivityStateBuilder stubChangeActivityStateBuilder() {
        ChangeActivityStateBuilder mockBuilder = mock(ChangeActivityStateBuilder.class);
        when(mockBuilder.processInstanceId(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.moveActivityIdTo(anyString(), anyString())).thenReturn(mockBuilder);
        when(mockRuntimeService.createChangeActivityStateBuilder()).thenReturn(mockBuilder);
        return mockBuilder;
    }
}
