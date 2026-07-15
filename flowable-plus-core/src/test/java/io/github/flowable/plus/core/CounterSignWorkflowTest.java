package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.workflow.CounterSignWorkflow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.Mockito;

/**
 * CounterSignWorkflow 单元测试：覆盖会签投票、加签、减签的
 * 正常路径和所有异常路径。
 */
public class CounterSignWorkflowTest {

    private static final String USER_ID = "user1";

    private UserContext userContext;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private RuntimeService mockRuntimeService;
    private BpmnModelCache mockBpmnModelCache;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private NodeFinder mockNodeFinder;

    private AtomicInteger onStartCount;
    private AtomicInteger onVoteCount;
    private AtomicInteger onFinishCount;
    private CounterSignWorkflow counterSignWorkflow;

    @BeforeEach
    void setUp() {
        userContext = () -> USER_ID;
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        mockNodeFinder = mock(NodeFinder.class);

        onStartCount = new AtomicInteger(0);
        onVoteCount = new AtomicInteger(0);
        onFinishCount = new AtomicInteger(0);

        CounterSignCallback trackingCallback = new CounterSignCallback() {
            @Override
            public void onStart(String pid, String tid, List<String> assignees) {
                onStartCount.incrementAndGet();
            }
            @Override
            public void onVote(String pid, String tid, String assignee, boolean approved, String comment) {
                onVoteCount.incrementAndGet();
            }
            @Override
            public void onFinish(String pid, String tid, String result) {
                onFinishCount.incrementAndGet();
            }
        };

        counterSignWorkflow = new CounterSignWorkflow(userContext, mockTaskService,
                mockHistoryService, mockRuntimeService, mockMultiInstanceDetector, mockNodeFinder,
                Collections.singletonList(trackingCallback));
    }

    // ======================== 会签：首次投票 ========================

    @Test
    void testCounterSignFirstVote() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // 未投票过，活跃人数 2 人
        Task mockTaskObj = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user2");
        stubCounterSignFull(task, mockTaskObj, Arrays.asList(assignee1, assignee2), 1L, 0L);

        counterSignWorkflow.counterSign("task-001", true, null, "同意");

        verify(mockTaskService).claim("task-001", USER_ID);
        verify(mockTaskService).addComment("task-001", null, "AGREE", "同意");
        verify(mockTaskService).complete("task-001", null);
        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(0);
    }

    // ======================== 会签：最后一票触发 onFinish ========================

    @Test
    void testCounterSignLastVoteTriggersOnFinish() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // 之前已投过票（hasVoted == true），不触发 onStart
        Task mockTaskObj = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        stubCounterSignFull(task, mockTaskObj, Collections.singletonList(assignee), 0L, 1L);

        counterSignWorkflow.counterSign("task-001", true, null, "同意");

        assertThat(onStartCount.get()).isEqualTo(0);
        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(1);
    }

    // ======================== 会签：驳回投票 ========================

    @Test
    void testCounterSignRejection() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockTaskObj = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user2");
        stubCounterSignFull(task, mockTaskObj, Arrays.asList(assignee1, assignee2), 2L, 0L);

        counterSignWorkflow.counterSign("task-001", false, null, "不同意");

        verify(mockTaskService).addComment("task-001", null, "COUNTER_SIGN_REJECT", "不同意");
        verify(mockTaskService).complete("task-001", null);
    }

    // ======================== 会签：无评论 ========================

    @Test
    void testCounterSignWithoutComment() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockTaskObj = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        stubCounterSignFull(task, mockTaskObj, Collections.singletonList(assignee), 1L, 0L);

        counterSignWorkflow.counterSign("task-001", true, null, null);

        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    // ======================== 会签：带变量 ========================

    @Test
    void testCounterSignWithVariables() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockTaskObj = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        stubCounterSignFull(task, mockTaskObj, Collections.singletonList(assignee), 1L, 0L);

        HashMap<String, Object> vars = new HashMap<>();
        vars.put("amount", 5000);

        counterSignWorkflow.counterSign("task-001", true, vars, null);

        verify(mockTaskService).complete("task-001", vars);
    }

    // ======================== 会签：错误路径 ========================

    @Test
    void testCounterSignRejectsNonMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
    }

    @Test
    void testCounterSignRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", "user2");
        stubTaskExists(task);

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, null))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testCounterSignRejectsCompletedTask() {
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId("task-001")).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(null);

        HistoricTaskInstance mockHTI = createMockHistoricTask("task-001", "leave:1:abc", "csTask", "pi-001",
                USER_ID, null, new Date(), new Date(), null);
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.taskId("task-001")).thenReturn(histTaskQuery);
        when(histTaskQuery.singleResult()).thenReturn(mockHTI);

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, null))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已完成");
    }

    // ======================== 加签 ========================

    @Test
    void testAddCounterSigner() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);
        stubCounterSignPermission(task);

        // Q1: validateTaskExists + Q2: resolveCurrentAssignees
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(assignee));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        verify(mockRuntimeService).addMultiInstanceExecution("csTask", "pi-001",
                new HashMap<String, Object>() {{ put("assignee", "newUser"); }});
        verify(mockTaskService).addComment(anyString(), eq("pi-001"), eq("ADD_SIGN"), anyString());
        assertThat(onStartCount.get()).isEqualTo(1);
    }

    @Test
    void testAddCounterSignerMultipleNewUsers() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);
        stubCounterSignPermission(task);

        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);

        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());

        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(assignee));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        counterSignWorkflow.addCounterSigner("task-001", Arrays.asList("newUser1", "newUser2"));

        HashMap<String, Object> expectedVars1 = new HashMap<>();
        expectedVars1.put("assignee", "newUser1");
        HashMap<String, Object> expectedVars2 = new HashMap<>();
        expectedVars2.put("assignee", "newUser2");
        verify(mockRuntimeService).addMultiInstanceExecution("csTask", "pi-001", expectedVars1);
        verify(mockRuntimeService).addMultiInstanceExecution("csTask", "pi-001", expectedVars2);
    }

    @Test
    void testAddCounterSignerSkipsDuplicate() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);
        stubCounterSignPermission(task);

        // 当前审批人包含 USER_ID
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);

        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());

        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(assignee));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList(USER_ID));

        verify(mockRuntimeService, never()).addMultiInstanceExecution(anyString(), anyString(), any());
    }

    @Test
    void testAddCounterSignerRejectsNullArgs() {
        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner(null, Collections.singletonList("u")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("t", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignees");
        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("t", Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignees");
    }

    // ======================== 减签 ========================

    /**
     * removeCounterSigner 调用顺序：
     * 1. validateTaskExists → taskService.createTaskQuery() [Q1]
     * 2. validateCounterSignPermission → historyService.createHistoricTaskInstanceQuery() [HQ1: listPage→prevTask]
     * 3. hasVoted(task, assignee) → historyService.createHistoricTaskInstanceQuery() [HQ2: count]
     * 4. resolveCurrentAssignees → taskService.createTaskQuery() [Q2: list]
     * 5. hasVoted(task, a) for each → historyService.createHistoricTaskInstanceQuery() [HQ3..N: count]
     * 6. findActiveTask → taskService.createTaskQuery() [Q3: singleResult]
     */
    @Test
    void testRemoveCounterSigner() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // nodeFinder for validateCounterSignPermission
        when(mockNodeFinder.findPreviousNodes(definitionId, "csTask", "pi-001"))
                .thenReturn(Collections.singletonList("prevTask"));

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // HQ1: validateCounterSignPermission — findLatestFinishedTask for prevTask
        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", definitionId, "prevTask", "pi-001",
                USER_ID, null, new Date(), new Date(), null);
        HistoricTaskInstanceQuery hq1 = mock(HistoricTaskInstanceQuery.class);
        when(hq1.processInstanceId("pi-001")).thenReturn(hq1);
        when(hq1.taskDefinitionKey("prevTask")).thenReturn(hq1);
        when(hq1.finished()).thenReturn(hq1);
        when(hq1.orderByHistoricTaskInstanceEndTime()).thenReturn(hq1);
        when(hq1.desc()).thenReturn(hq1);
        when(hq1.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        // HQ2: hasVoted(task, "user2") → 0
        HistoricTaskInstanceQuery hq2 = mock(HistoricTaskInstanceQuery.class);
        when(hq2.processInstanceId(anyString())).thenReturn(hq2);
        when(hq2.taskDefinitionKey(anyString())).thenReturn(hq2);
        when(hq2.taskAssignee(anyString())).thenReturn(hq2);
        when(hq2.finished()).thenReturn(hq2);
        when(hq2.count()).thenReturn(0L);

        // Q2: resolveCurrentAssignees → [user2, user3]
        Task assignee1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        Task assignee2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user3");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(assignee1, assignee2));

        // HQ3, HQ4: hasVoted for user2 and user3 → 0
        // (reuse hq2 pattern — Mockito returns same mock for consecutive calls)

        // Q3: findActiveTask → targetTask
        Task targetTask = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.taskAssignee(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.singleResult()).thenReturn(targetTask);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3);
        // HQ1 for permission, then HQ2 for all hasVoted calls (count=0)
        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(hq1)
                .thenReturn(hq2);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution("exec-sub-1", false);
        verify(mockTaskService).addComment(anyString(), eq("pi-001"), eq("DELETE_SIGN"), anyString());
    }

    @Test
    void testRemoveCounterSignerRejectsAlreadyVoted() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        when(mockNodeFinder.findPreviousNodes(definitionId, "csTask", "pi-001"))
                .thenReturn(Collections.singletonList("prevTask"));

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        when(mockTaskService.createTaskQuery()).thenReturn(q1);

        // HQ1: validateCounterSignPermission
        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", definitionId, "prevTask", "pi-001",
                USER_ID, null, new Date(), new Date(), null);
        HistoricTaskInstanceQuery hq1 = mock(HistoricTaskInstanceQuery.class);
        when(hq1.processInstanceId("pi-001")).thenReturn(hq1);
        when(hq1.taskDefinitionKey("prevTask")).thenReturn(hq1);
        when(hq1.finished()).thenReturn(hq1);
        when(hq1.orderByHistoricTaskInstanceEndTime()).thenReturn(hq1);
        when(hq1.desc()).thenReturn(hq1);
        when(hq1.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        // HQ2: hasVoted(task, "user2") → 1 (已投票)
        HistoricTaskInstanceQuery hq2 = mock(HistoricTaskInstanceQuery.class);
        when(hq2.processInstanceId(anyString())).thenReturn(hq2);
        when(hq2.taskDefinitionKey(anyString())).thenReturn(hq2);
        when(hq2.taskAssignee(anyString())).thenReturn(hq2);
        when(hq2.finished()).thenReturn(hq2);
        when(hq2.count()).thenReturn(1L);

        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(hq1)
                .thenReturn(hq2);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已投票");
    }

    @Test
    void testRemoveCounterSignerRejectsInsufficientUnvoted() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        when(mockNodeFinder.findPreviousNodes(definitionId, "csTask", "pi-001"))
                .thenReturn(Collections.singletonList("prevTask"));

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: resolveCurrentAssignees → [user2] (only 1 person)
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(assignee));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // HQ1: validateCounterSignPermission
        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", definitionId, "prevTask", "pi-001",
                USER_ID, null, new Date(), new Date(), null);
        HistoricTaskInstanceQuery hq1 = mock(HistoricTaskInstanceQuery.class);
        when(hq1.processInstanceId("pi-001")).thenReturn(hq1);
        when(hq1.taskDefinitionKey("prevTask")).thenReturn(hq1);
        when(hq1.finished()).thenReturn(hq1);
        when(hq1.orderByHistoricTaskInstanceEndTime()).thenReturn(hq1);
        when(hq1.desc()).thenReturn(hq1);
        when(hq1.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        // HQ2: hasVoted → 0 for all calls
        HistoricTaskInstanceQuery hq2 = mock(HistoricTaskInstanceQuery.class);
        when(hq2.processInstanceId(anyString())).thenReturn(hq2);
        when(hq2.taskDefinitionKey(anyString())).thenReturn(hq2);
        when(hq2.taskAssignee(anyString())).thenReturn(hq2);
        when(hq2.finished()).thenReturn(hq2);
        when(hq2.count()).thenReturn(0L);

        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(hq1)
                .thenReturn(hq2);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("剩余未投票审批人不足");
    }

    // ======================== 加签/减签权限 ========================

    @Test
    void testAddCounterSignerRejectsUnauthorized() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // 上一节点审批人是 otherUser，不是当前用户
        when(mockNodeFinder.findPreviousNodes(definitionId, "csTask", "pi-001"))
                .thenReturn(Collections.singletonList("prevTask"));
        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", definitionId, "prevTask", "pi-001",
                "otherUser", null, new Date(), new Date(), null);
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId("pi-001")).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey("prevTask")).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("u")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无权加签");
    }

    // ======================== 回调异常隔离 ========================

    @Test
    void testCallbackExceptionIsolated() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockTaskObj = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        stubCounterSignFull(task, mockTaskObj, Collections.singletonList(assignee), 1L, 0L);

        // 使用会抛出异常的回调
        CounterSignCallback failingCb = new CounterSignCallback() {
            @Override
            public void onStart(String pid, String tid, List<String> assignees) {
                throw new RuntimeException("模拟异常");
            }
        };
        CounterSignWorkflow fp = new CounterSignWorkflow(userContext, mockTaskService,
                mockHistoryService, mockRuntimeService, mockMultiInstanceDetector, mockNodeFinder,
                Collections.singletonList(failingCb));

        // 不应抛异常，应继续完成
        fp.counterSign("task-001", true, null, "同意");

        verify(mockTaskService).complete("task-001", null);
    }

    // ======================== 委派与收回委派 ========================

    @Test
    void testDelegateTaskNormal() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithOwner(task, null);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        counterSignWorkflow.delegateTask("task-001", "delegateUser", "出差无法审批");

        verify(mockTaskService).delegateTask("task-001", "delegateUser");
        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq(CommentType.DELEGATE.name()), eq("委派给 delegateUser（出差无法审批）"));
    }

    @Test
    void testDelegateTaskWithoutReason() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithOwner(task, null);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        counterSignWorkflow.delegateTask("task-001", "delegateUser", null);

        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq(CommentType.DELEGATE.name()), eq("委派给 delegateUser"));
    }

    @Test
    void testDelegateTaskRejectsNonMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubTaskExistsWithOwner(task, null);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> counterSignWorkflow.delegateTask("task-001", "delegateUser", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
    }

    @Test
    void testDelegateTaskRejectsWrongAssignee() {
        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", "user2");
        stubTaskExistsWithOwner(task, null);

        assertThatThrownBy(() -> counterSignWorkflow.delegateTask("task-001", "delegateUser", null))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("审批人");
    }

    @Test
    void testDelegateTaskRejectsDelegateToSelf() {
        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubTaskExistsWithOwner(task, null);

        assertThatThrownBy(() -> counterSignWorkflow.delegateTask("task-001", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("委派目标不可为当前审批人");
    }

    @Test
    void testDelegateTaskRejectsNullTaskId() {
        assertThatThrownBy(() -> counterSignWorkflow.delegateTask(null, "delegateUser", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void testDelegateTaskRejectsBlankDelegateUserId() {
        assertThatThrownBy(() -> counterSignWorkflow.delegateTask("task-001", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegateUserId");
    }

    @Test
    void testResolveDelegateNormal() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", "delegateUser");
        stubTaskExistsWithOwner(task, USER_ID);

        counterSignWorkflow.resolveDelegate("task-001");

        verify(mockTaskService).resolveTask("task-001");
        verify(mockTaskService).addComment(eq("task-001"), eq("pi-001"),
                eq(CommentType.RESOLVE_DELEGATE.name()), eq("从 delegateUser 收回委派"));
    }

    @Test
    void testResolveDelegateRejectsNonOwner() {
        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", "assignee");
        stubTaskExistsWithOwner(task, "otherUser");

        assertThatThrownBy(() -> counterSignWorkflow.resolveDelegate("task-001"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是任务 task-001 的委派人");
    }

    @Test
    void testResolveDelegateRejectsWhenOwnerIsNull() {
        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", "assignee");
        stubTaskExistsWithOwner(task, null);

        assertThatThrownBy(() -> counterSignWorkflow.resolveDelegate("task-001"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是任务 task-001 的委派人");
    }

    @Test
    void testResolveDelegateRejectsNullTaskId() {
        assertThatThrownBy(() -> counterSignWorkflow.resolveDelegate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void testCounterSignRejectsSingleInstanceTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(false);

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
    }

    // ======================== Test Helpers ========================

    private PlusTask createTask(String taskId, String definitionId, String taskDefKey,
            String instanceId, String assignee) {
        return new PlusTask(taskId, definitionId, taskDefKey, instanceId,
                assignee, null, "测试任务", "exec-" + taskId, new Date());
    }

    private Task createMockTask(String id, String definitionId, String taskDefKey,
            String instanceId, String assignee) {
        return createMockTaskWithOwner(id, definitionId, taskDefKey, instanceId, assignee, null);
    }

    private Task createMockTaskWithOwner(String id, String definitionId, String taskDefKey,
            String instanceId, String assignee, String owner) {
        Task mockTask = mock(Task.class);
        when(mockTask.getId()).thenReturn(id);
        when(mockTask.getProcessDefinitionId()).thenReturn(definitionId);
        when(mockTask.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(mockTask.getProcessInstanceId()).thenReturn(instanceId);
        when(mockTask.getAssignee()).thenReturn(assignee);
        when(mockTask.getOwner()).thenReturn(owner);
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

    private void stubTaskExistsWithOwner(PlusTask task, String owner) {
        Task mockTask = createMockTaskWithOwner(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee(), owner);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(task.getId())).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(mockTask);
    }

    private void stubTaskExistsWithAssignee(PlusTask task) {
        stubTaskExists(task);
    }

    /**
     * stubCounterSignFull: 为 counterSign() 调用路径设置所有 mock。counterSign() 内部依次调用：
     * <ol>
     *   <li>validateTaskExists → createTaskQuery().taskId().singleResult()</li>
     *   <li>resolveCurrentAssignees → createTaskQuery().processInstanceId().taskDefinitionKey().active().list()</li>
     *   <li>isMultiInstanceFinished → createTaskQuery().processInstanceId().taskDefinitionKey().active().count()</li>
     *   <li>hasVoted → createHistoricTaskInstanceQuery().processInstanceId().taskDefinitionKey().taskAssignee().finished().count()</li>
     * </ol>
     * 通过 thenReturn 链按顺序返回不同的 query mock。
     */
    @SuppressWarnings("unchecked")
    private void stubCounterSignFull(PlusTask task, Task mockExistTask,
                                      List<Task> activeTaskList, long activeCount, long finishedCount) {
        // Q1 — validateTaskExists
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2 — resolveCurrentAssignees
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(activeTaskList);

        // Q3 — isMultiInstanceFinished
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(activeCount);

        // 按顺序返回：第一次调用返回 q1，第二��返回 q2，第三次返回 q3
        when(mockTaskService.createTaskQuery()).thenReturn(q1).thenReturn(q2).thenReturn(q3);

        // HistQ — hasVoted
        HistoricTaskInstanceQuery hq = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hq);
        when(hq.processInstanceId(anyString())).thenReturn(hq);
        when(hq.taskDefinitionKey(anyString())).thenReturn(hq);
        when(hq.taskAssignee(anyString())).thenReturn(hq);
        when(hq.finished()).thenReturn(hq);
        when(hq.count()).thenReturn(finishedCount);
    }

    private void stubHistoryCountFinishedForUser(String userId, long count) {
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId(anyString())).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey(anyString())).thenReturn(histTaskQuery);
        when(histTaskQuery.taskAssignee(userId)).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.count()).thenReturn(count);
    }

    private void stubCounterSignPermission(PlusTask task) {
        when(mockNodeFinder.findPreviousNodes(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(),
                task.getProcessInstanceId()))
                .thenReturn(Collections.singletonList("prevTask"));

        HistoricTaskInstance prevTask = createMockHistoricTask(
                "ht-prev", task.getProcessDefinitionId(), "prevTask",
                task.getProcessInstanceId(), USER_ID, null, new Date(), new Date(), null);
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(histTaskQuery.processInstanceId(task.getProcessInstanceId())).thenReturn(histTaskQuery);
        when(histTaskQuery.taskDefinitionKey("prevTask")).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.desc()).thenReturn(histTaskQuery);
        when(histTaskQuery.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));
    }
}
