package io.github.flowable.plus.core;

import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.support.ProcessEndDetector;
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
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
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
import static org.mockito.Mockito.times;
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
    private ProcessEndDetector mockProcessEndDetector;
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

        mockProcessEndDetector = mock(ProcessEndDetector.class);

        counterSignWorkflow = new CounterSignWorkflow(userContext, mockTaskService,
                mockHistoryService, mockRuntimeService, mockMultiInstanceDetector, mockNodeFinder,
                Collections.singletonList(trackingCallback), null, mockProcessEndDetector);
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
        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.COUNTER_SIGN_AGREE.name(), "同意");
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

        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.COUNTER_SIGN_REJECT.name(), "不同意");
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

        // isMultiInstanceFinished 中 finishedCount 查询（无人已完成 → 未完成）
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(0L);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqFinished);

        // Q1: validateTaskExists, Q2: resolveCurrentAssignees, Q3: isMultiInstanceFinished
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
        // Q3: isMultiInstanceFinished — activeCount=1 + finishedCount=0 → 未完成
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        verify(mockRuntimeService).addMultiInstanceExecution("csTask", "pi-001",
                new HashMap<String, Object>() {{ put("assignee", "newUser"); }});
        verify(mockTaskService).addComment(anyString(), eq("pi-001"), eq(CommentType.ADD_SIGN.name()), anyString());
        assertThat(onStartCount.get()).isEqualTo(1);
    }

    @Test
    void testAddCounterSignerMultipleNewUsers() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // isMultiInstanceFinished 中 finishedCount 查询（无人已完成）
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(0L);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqFinished);

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
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3);

        counterSignWorkflow.addCounterSigner("task-001", Arrays.asList("newUser1", "newUser2"));

        HashMap<String, Object> expectedVars1 = new HashMap<>();
        expectedVars1.put("assignee", "newUser1");
        HashMap<String, Object> expectedVars2 = new HashMap<>();
        expectedVars2.put("assignee", "newUser2");
        verify(mockRuntimeService).addMultiInstanceExecution("csTask", "pi-001", expectedVars1);
        verify(mockRuntimeService).addMultiInstanceExecution("csTask", "pi-001", expectedVars2);
    }

    /**
     * 伪单例场景（nrOfInstances=1，只有 1 人）：加签时应识别为未完成，
     * 不进入新轮次分支，但仍应设置 csRoundIndex=0（与原始审批人同轮次），
     * 评论不包含"第 2 轮"。
     */
    @Test
    void testAddCounterSignerPseudoSingletonNotFinished() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // isMultiInstanceFinished + isPseudoSingleton 中 finishedCount 查询：0 人已完成 → 伪单例
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(0L);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqFinished);

        // 当前只有一个活跃任务（伪单例）
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
        // Q3: isPseudoSingleton — activeCount=1（trySetCounterSignInitiator 内部）
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);

        // Q4: isMultiInstanceFinished — activeCount=1
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.count()).thenReturn(1L);

        // Q5: determineCurrentRoundIndex 活跃任务查询
        TaskQuery q5 = mock(TaskQuery.class);
        when(q5.processInstanceId(anyString())).thenReturn(q5);
        when(q5.taskDefinitionKey(anyString())).thenReturn(q5);
        when(q5.active()).thenReturn(q5);
        when(q5.list()).thenReturn(Collections.singletonList(assignee));

        // Q6: setVariableLocal 活跃任务查询（返回加签后的 B、C、D）
        Task taskB = createMockTask("sub-B", definitionId, "csTask", "pi-001", "B");
        Task taskC = createMockTask("sub-C", definitionId, "csTask", "pi-001", "C");
        Task taskD = createMockTask("sub-D", definitionId, "csTask", "pi-001", "D");
        TaskQuery q6 = mock(TaskQuery.class);
        when(q6.processInstanceId(anyString())).thenReturn(q6);
        when(q6.taskDefinitionKey(anyString())).thenReturn(q6);
        when(q6.active()).thenReturn(q6);
        when(q6.list()).thenReturn(Arrays.asList(assignee, taskB, taskC, taskD));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q3, q4, q5, q6);

        // determineCurrentRoundIndex：活跃任务有 csRoundIndex=0（原始审批人隐式轮次）
        when(mockTaskService.getVariableLocal(eq("sub-1"), eq("csRoundIndex"))).thenReturn(0);

        counterSignWorkflow.addCounterSigner("task-001", Arrays.asList("B", "C", "D"));

        // 验证：加签执行正常
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "B".equals(map.get("assignee"))));
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "C".equals(map.get("assignee"))));
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "D".equals(map.get("assignee"))));

        // 验证：不应包含"第 2 轮" — 这是第一轮本身
        verify(mockTaskService).addComment(eq(task.getId()), eq("pi-001"),
                eq(CommentType.ADD_SIGN.name()),
                Mockito.argThat(msg -> msg.contains("加签审批人") && !msg.contains("第 2 轮")));

        // 验证：始终设置 csRoundIndex=0（原始审批人隐式轮次 0）
        verify(mockTaskService, times(3)).setVariableLocal(anyString(), eq("csRoundIndex"), eq(0));
    }

    @Test
    void testAddCounterSignerSkipsDuplicate() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

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
     * removeCounterSigner 调用顺序（当前会签审批人权限模型）：
     * 1. validateTaskExists → taskService.createTaskQuery() [Q1]
     * 2. validateCounterSignPermission → resolveCurrentAssignees [Q2: list 含 USER_ID]
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

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // HQ2: hasVoted(task, "user2") → 0
        HistoricTaskInstanceQuery hq2 = mock(HistoricTaskInstanceQuery.class);
        when(hq2.processInstanceId(anyString())).thenReturn(hq2);
        when(hq2.taskDefinitionKey(anyString())).thenReturn(hq2);
        when(hq2.taskAssignee(anyString())).thenReturn(hq2);
        when(hq2.finished()).thenReturn(hq2);
        when(hq2.count()).thenReturn(0L);

        // Q2/Q3: 权限校验 + 主逻辑的 resolveCurrentAssignees → [USER_ID, user2, user3]
        Task assignee1 = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        Task assignee2 = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        Task assignee3 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user3");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(assignee1, assignee2, assignee3));

        // HQ3..N: hasVoted for USER_ID, user2, user3 → 0

        // Q4: findActiveTask → targetTask
        Task targetTask = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.taskAssignee(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.singleResult()).thenReturn(targetTask);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q3);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hq2);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution("exec-sub-1", false);
        verify(mockTaskService).addComment(anyString(), eq("pi-001"), eq(CommentType.DELETE_SIGN.name()), anyString());
    }

    @Test
    void testRemoveCounterSignerRejectsAlreadyVoted() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: validateCounterSignPermission → resolveCurrentAssignees（不含 USER_ID）
        // 权限通过 countersignInitiator 变量校验（initiator=USER_ID）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn(USER_ID);

        Task otherAssignee = createMockTask("sub-999", definitionId, "csTask", "pi-001", "other");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(otherAssignee));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // HQ: hasVoted(task, "user2") → 1 (已投票)
        HistoricTaskInstanceQuery hq2 = mock(HistoricTaskInstanceQuery.class);
        when(hq2.processInstanceId(anyString())).thenReturn(hq2);
        when(hq2.taskDefinitionKey(anyString())).thenReturn(hq2);
        when(hq2.taskAssignee(anyString())).thenReturn(hq2);
        when(hq2.finished()).thenReturn(hq2);
        when(hq2.count()).thenReturn(1L);

        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hq2);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已投票");
    }

    @Test
    void testRemoveCounterSignerRejectsInsufficientUnvoted() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2/Q3: 权限校验 + 主逻辑 resolveCurrentAssignees → [user2]（不含 USER_ID）
        // 权限通过 countersignInitiator 变量校验（initiator=USER_ID）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn(USER_ID);

        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(assignee));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2);

        // HQ2: hasVoted → 0 for all calls
        HistoricTaskInstanceQuery hq2 = mock(HistoricTaskInstanceQuery.class);
        when(hq2.processInstanceId(anyString())).thenReturn(hq2);
        when(hq2.taskDefinitionKey(anyString())).thenReturn(hq2);
        when(hq2.taskAssignee(anyString())).thenReturn(hq2);
        when(hq2.finished()).thenReturn(hq2);
        when(hq2.count()).thenReturn(0L);

        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hq2);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("剩余未投票审批人不足");
    }

    // ======================== 加签/减签权限 ========================

    @Test
    void testAddCounterSignerRejectsUnauthorized() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: validateCounterSignPermission → resolveCurrentAssignees（不含 USER_ID）
        Task otherAssignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", "otherUser");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(otherAssignee));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // countersignInitiator 变量未设置（mock 默认 null）→ 回退到活跃审批人检查
        // USER_ID 不在活跃审批人列表中 → 拒绝

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList("u")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无权");
    }

    // ======================== 新权限模型：伪单例模式（模式A） ========================

    /**
     * 伪单例状态（activeCount=1, finishedCount=0），当前用户是活跃审批人且首次加签：
     * 加签成功，且写入 countersignInitiator 变量。
     */
    @Test
    void testAddCounterSignerInPseudoSingletonSetsInitiator() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);

        // Q1: validateTaskExists
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: validateCounterSignPermission + resolveCurrentAssignees → [USER_ID]
        Task assignee = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(assignee));

        // Q3: isPseudoSingleton — activeCount=1
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);

        // isPseudoSingleton + isMultiInstanceFinished: finishedCount=0
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(0L);

        // Q4: determineCurrentRoundIndex 活跃任务查询
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.list()).thenReturn(Collections.singletonList(assignee));

        // Q5: setVariableLocal 活跃任务查询（加签后）
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q5 = mock(TaskQuery.class);
        when(q5.processInstanceId(anyString())).thenReturn(q5);
        when(q5.taskDefinitionKey(anyString())).thenReturn(q5);
        when(q5.active()).thenReturn(q5);
        when(q5.list()).thenReturn(Arrays.asList(assignee, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q3, q4, q5);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqFinished, hqFinished);

        when(mockTaskService.getVariableLocal(eq("sub-1"), eq("csRoundIndex"))).thenReturn(0);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 验证加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));

        // 验证写入 countersignInitiator
        verify(mockRuntimeService).setVariable(eq("pi-001"),
                eq("countersignInitiator_csTask"), eq(USER_ID));
    }

    /**
     * 伪单例模式下，会签发起人可减签。
     */
    @Test
    void testRemoveCounterSignerInPseudoSingletonWithInitiator() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator_csTask = USER_ID
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn(USER_ID);

        // hasVoted(task, "user2") → 0
        HistoricTaskInstanceQuery hqVoted = mock(HistoricTaskInstanceQuery.class);
        when(hqVoted.processInstanceId(anyString())).thenReturn(hqVoted);
        when(hqVoted.taskDefinitionKey(anyString())).thenReturn(hqVoted);
        when(hqVoted.taskAssignee(anyString())).thenReturn(hqVoted);
        when(hqVoted.finished()).thenReturn(hqVoted);
        when(hqVoted.count()).thenReturn(0L);

        // Q2/Q3: resolveCurrentAssignees + findActiveTask → [USER_ID, user2, user3]
        Task self = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        Task other1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        Task other2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user3");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(self, other1, other2));

        // Q3: findActiveTask → targetTask（权限通过 initiator 变量后，resolveCurrentAssignees 仅在主逻辑调用一次）
        Task targetTask = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.taskAssignee("user2")).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.singleResult()).thenReturn(targetTask);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqVoted);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution("exec-sub-1", false);
        verify(mockTaskService).addComment(anyString(), eq("pi-001"),
                eq(CommentType.DELETE_SIGN.name()), anyString());
    }

    /**
     * 伪单例模式下，非发起人（被加签者）加签被拒绝。
     */
    @Test
    void testAddCounterSignerRejectsNonInitiatorInPseudoSingleton() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator_csTask = "owner"（不是 USER_ID）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn("owner");

        // Q2: resolveCurrentAssignees（仅在权限校验时调用一次）
        Task active = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(active));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList("newUser")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("会签发起人");
    }

    /**
     * 伪单例模式下，非发起人（被加签者）减签被拒绝。
     */
    @Test
    void testRemoveCounterSignerRejectsNonInitiatorInPseudoSingleton() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator_csTask = "owner"（不是 USER_ID）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn("owner");

        Task active = createMockTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(active));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("会签发起人");
    }

    /**
     * 伪单例模式下，流程发起人（非会签发起人）加签被拒绝。
     * 直接验证旧 startUserId 旁路已被移除。
     */
    @Test
    void testAddCounterSignerRejectsProcessInitiatorInPseudoSingleton() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator_csTask = "owner"（不是 USER_ID）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn("owner");

        Task active = createMockTask("sub-1", definitionId, "csTask", "pi-001", "owner");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(active));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // USER_ID 不是会签发起人 → 即使曾是流程发起人也应拒绝
        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList("newUser")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("会签发起人");
    }

    // ======================== 新权限模型：固定会签模式（模式B） ========================

    /**
     * 固定会签模式下，活跃审批人加签成功，且不写入 countersignInitiator。
     */
    @Test
    void testAddCounterSignerInFixedModeActiveApprover() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);

        // Q1: validateTaskExists
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: validateCounterSignPermission + resolveCurrentAssignees → [USER_ID, user2]
        Task self = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        Task other = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(self, other));

        // Q3: isPseudoSingleton — activeCount=2（不是伪单例，不写 initiator）
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(2L);

        // isMultiInstanceFinished: finishedCount=0
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(0L);

        // Q4: determineCurrentRoundIndex
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.list()).thenReturn(Arrays.asList(self, other));

        // Q5: setVariableLocal
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q5 = mock(TaskQuery.class);
        when(q5.processInstanceId(anyString())).thenReturn(q5);
        when(q5.taskDefinitionKey(anyString())).thenReturn(q5);
        when(q5.active()).thenReturn(q5);
        when(q5.list()).thenReturn(Arrays.asList(self, other, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q3, q4, q5);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqFinished, hqFinished);

        when(mockTaskService.getVariableLocal(eq("sub-0"), eq("csRoundIndex"))).thenReturn(0);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));

        // 固定会签模式下不写入 countersignInitiator
        verify(mockRuntimeService, never()).setVariable(anyString(),
                eq("countersignInitiator_csTask"), anyString());
    }

    /**
     * 固定会签模式下，活跃审批人减签成功。
     */
    @Test
    void testRemoveCounterSignerInFixedModeActiveApprover() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: no countersignInitiator → 活跃审批人模式
        // Q2/Q3: resolveCurrentAssignees → [USER_ID, user2, user3]
        Task self = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        Task other1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        Task other2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user3");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(self, other1, other2));

        // hasVoted: all → 0
        HistoricTaskInstanceQuery hqVoted = mock(HistoricTaskInstanceQuery.class);
        when(hqVoted.processInstanceId(anyString())).thenReturn(hqVoted);
        when(hqVoted.taskDefinitionKey(anyString())).thenReturn(hqVoted);
        when(hqVoted.taskAssignee(anyString())).thenReturn(hqVoted);
        when(hqVoted.finished()).thenReturn(hqVoted);
        when(hqVoted.count()).thenReturn(0L);

        // Q4: findActiveTask
        Task targetTask = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.taskAssignee("user2")).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.singleResult()).thenReturn(targetTask);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q3);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqVoted);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution("exec-sub-1", false);
    }

    /**
     * 固定会签模式下，非活跃审批人加签被拒绝。
     */
    @Test
    void testAddCounterSignerInFixedModeRejectsNonApprover() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: no countersignInitiator, USER_ID 不在活跃审批人中
        Task other = createMockTask("sub-999", definitionId, "csTask", "pi-001", "otherUser");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(other));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList("u")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("活跃审批人");
    }

    /**
     * 固定会签模式下，非活跃审批人减签被拒绝。
     */
    @Test
    void testRemoveCounterSignerInFixedModeRejectsNonApprover() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: no countersignInitiator, USER_ID 不在活跃审批人中
        Task other = createMockTask("sub-999", definitionId, "csTask", "pi-001", "otherUser");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(other));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("活跃审批人");
    }

    // ======================== 新权限模型：跨轮次 ========================

    /**
     * 伪单例模式下，多轮会签的后续轮次中 initiator 仍保有加签控制权。
     */
    @Test
    void testAddCounterSignerMultiRoundInitiatorKeepsPower() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator_csTask = USER_ID（跨轮次仍生效）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn(USER_ID);

        // Q2: resolveCurrentAssignees
        Task self = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));

        // isMultiInstanceFinished: activeCount=1, finishedCount=1（不是伪单例，但已全部完成）
        // isPseudoSingleton: activeCount=1 → false（finishedCount != 0, 跳过）
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        // Q3: isPseudoSingleton - count=1, Q4: isMultiInstanceFinished - count=1
        when(q3.count()).thenReturn(1L).thenReturn(1L, 1L);

        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        // finishedCount for isPseudoSingleton: 1 (not pseudo), then isMultiInstanceFinished: 
        when(hqFinished.count()).thenReturn(1L);

        // isMultiInstanceFinished → activeCount=1, finishedCount=1 → sole check
        TaskQuery qSole = mock(TaskQuery.class);
        when(qSole.processInstanceId(anyString())).thenReturn(qSole);
        when(qSole.taskDefinitionKey(anyString())).thenReturn(qSole);
        when(qSole.active()).thenReturn(qSole);
        when(qSole.singleResult()).thenReturn(self);

        // nextRound 历史任务查询
        HistoricTaskInstanceQuery hqHistory = mock(HistoricTaskInstanceQuery.class);
        when(hqHistory.processInstanceId("pi-001")).thenReturn(hqHistory);
        when(hqHistory.taskDefinitionKey("csTask")).thenReturn(hqHistory);
        when(hqHistory.list()).thenReturn(Collections.emptyList());

        // Q5: setVariableLocal
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q5 = mock(TaskQuery.class);
        when(q5.processInstanceId(anyString())).thenReturn(q5);
        when(q5.taskDefinitionKey(anyString())).thenReturn(q5);
        when(q5.active()).thenReturn(q5);
        when(q5.list()).thenReturn(Arrays.asList(self, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q3, q3, qSole, q5);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqFinished, hqFinished, hqFinished);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 验证加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));

        // 验证不重复写入 initiator（已存在）
        verify(mockRuntimeService, never()).setVariable(eq("pi-001"),
                eq("countersignInitiator_csTask"), anyString());
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
                Collections.singletonList(failingCb), null, mockProcessEndDetector);

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

    private HistoricProcessInstance mockHistoricProcessInstance(String id, String startUserId) {
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getId()).thenReturn(id);
        when(hpi.getStartUserId()).thenReturn(startUserId);
        return hpi;
    }

    private void stubProcessInitiator(String processInstanceId, String initiatorId) {
        HistoricProcessInstance hpi = mockHistoricProcessInstance(processInstanceId, initiatorId);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);
        when(hpiQuery.processInstanceId(processInstanceId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
    }

    // ======================== 事件发布 ========================

    private CounterSignWorkflow createWorkflowWithEventPublisher(EventPublisher ep) {
        ProcessEndDetector ped = new ProcessEndDetector(mockRuntimeService, mockHistoryService, ep);
        return new CounterSignWorkflow(userContext, mockTaskService,
                mockHistoryService, mockRuntimeService, mockMultiInstanceDetector, mockNodeFinder,
                Collections.emptyList(), ep, ped);
    }

    @Test
    void counterSignApprovedShouldPublishTaskCompletedEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        CounterSignWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockTaskObj = createMockTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        Task assignee = createMockTask("sub-1", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubCounterSignFull(task, mockTaskObj, Collections.singletonList(assignee), 1L, 0L);

        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId("pi-001")).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(mock(ProcessInstance.class));
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        wf.counterSign("task-001", true, null, "同意");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.TaskCompletedEvent.class));
    }

    @Test
    void counterSignRejectedShouldPublishTaskRejectedEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        CounterSignWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockTaskObj = createMockTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        Task assignee = createMockTask("sub-1", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubCounterSignFull(task, mockTaskObj, Collections.singletonList(assignee), 1L, 0L);

        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId("pi-001")).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(mock(ProcessInstance.class));
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        wf.counterSign("task-001", false, null, "不同意");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.TaskRejectedEvent.class));
    }

    @Test
    void delegateTaskShouldPublishTaskDelegatedEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        CounterSignWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        wf.delegateTask("task-001", "userB", "委派原因");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.TaskDelegatedEvent.class));
    }

    @Test
    void counterSignShouldPublishProcessEndedWhenProcessFinished() {
        EventPublisher mockEp = mock(EventPublisher.class);
        CounterSignWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        PlusTask task = createTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task mockTaskObj = createMockTask("task-001", "leave:1:abc", "csTask", "pi-001", USER_ID);
        Task assignee = createMockTask("sub-1", "leave:1:abc", "csTask", "pi-001", USER_ID);
        stubCounterSignFull(task, mockTaskObj, Collections.singletonList(assignee), 1L, 0L);

        // Mock ProcessInstance query to return null (流程已结束)
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId(anyString())).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(null);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        HistoricProcessInstance mockHpi = mock(HistoricProcessInstance.class);
        when(mockHpi.getProcessDefinitionKey()).thenReturn("leave");
        when(mockHpi.getBusinessKey()).thenReturn("biz-001");
        when(mockHpi.getEndTime()).thenReturn(new Date());
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId(anyString())).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(mockHpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        wf.counterSign("task-001", true, null, "同意");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.ProcessEndedEvent.class));
    }
}
