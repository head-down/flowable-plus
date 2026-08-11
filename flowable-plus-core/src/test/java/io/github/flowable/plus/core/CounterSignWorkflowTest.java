package io.github.flowable.plus.core;

import io.github.flowable.plus.core.event.EventBus;
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
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
                Collections.singletonList(trackingCallback), new EventBus(null), mockProcessEndDetector);
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
        // isPseudoSingleton 历史任务数查询：仅当前活跃任务 → 1（伪单例）
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(1L);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqFinished);

        // determineNextRoundIndex 兜底路径：周期边界查询 + 本节点 key 过滤查询（均空 → 不过滤）
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Collections.emptyList());
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Collections.emptyList());
        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        // 查询顺序：determineNextRoundIndex(边界,key) → resolveVotedAssigneesInRound(边界,已投票列表) → isPseudoSingleton(count)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyQ, boundaryQ, votedQ, hqFinished);
        // isPseudoSingleton：历史任务数查询（全局历史 = 当前唯一活跃任务 → 伪单例）

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

        // determineNextRoundIndex 兜底路径：周期边界查询 + 本节点 key 过滤查询（均空 → 不过滤）
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Collections.emptyList());
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Collections.emptyList());
        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        // 查询顺序：determineNextRoundIndex(边界,key) → resolveVotedAssigneesInRound(边界,已投票列表) → isPseudoSingleton(count)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyQ, boundaryQ, votedQ, hqFinished);

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

        // isMultiInstanceFinished + isPseudoSingleton 中历史任务数查询：仅当前活跃任务 → 伪单例
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(1L);
        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        // 查询顺序：resolveVotedAssigneesInRound(边界,已投票列表) → isPseudoSingleton(count)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ, hqFinished);

        // 状态感知的 initiator 变量：trySetCounterSignInitiator setVariable 后，
        // addCounterSigner 内模式 A 分派检查的 getVariable 应读到 USER_ID
        // （真实引擎中 setVariable 后 getVariable 立即可见；Mockito 默认不联动）。
        AtomicReference<Object> initiatorVar = new AtomicReference<>(null);
        when(mockRuntimeService.getVariable(eq("pi-001"), eq("countersignInitiator_csTask")))
                .thenAnswer(inv -> initiatorVar.get());
        Mockito.doAnswer(inv -> {
            initiatorVar.set(inv.getArgument(2));
            return null;
        }).when(mockRuntimeService).setVariable(eq("pi-001"), eq("countersignInitiator_csTask"), any());

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

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q5, q3, q6);

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

    /**
     * 全部重复（名单均为当前活跃会签人）→ 抛 IllegalArgumentException（ADR-0024），
     * 不创建任务、不写 comment、不打标、不写 initiator（零副作用）。
     */
    @Test
    void testAddCounterSignerRejectsDuplicate() {
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

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList(USER_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已在本轮会签中");

        // 零副作用：不建任务、不写 comment、不打标、不写 initiator
        verify(mockRuntimeService, never()).addMultiInstanceExecution(anyString(), anyString(), any());
        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
        verify(mockTaskService, never()).setVariableLocal(anyString(), anyString(), any());
        verify(mockRuntimeService, never()).setVariable(anyString(), anyString(), any());
    }

    /**
     * 部分重复（名单含当前活跃会签人）→ 整体失败抛 IllegalArgumentException（ADR-0024 决策 2a），
     * 不创建任何任务（原子性）。
     */
    @Test
    void testAddCounterSignerPartialDuplicateRejectsAll() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task self = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        Task other = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());

        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(self, other));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Arrays.asList("newUser", "user2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已在本轮会签中");

        // 原子性：整体失败，不创建任何任务、不写 comment
        verify(mockRuntimeService, never()).addMultiInstanceExecution(anyString(), anyString(), any());
        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 名单内自重复 [A, A]（隐藏 bug）→ 整体失败抛 IllegalArgumentException（ADR-0024 决策 2b），
     * 避免创建两个同 assignee 的重复任务。
     */
    @Test
    void testAddCounterSignerSelfDuplicateInListRejects() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

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

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Arrays.asList("userA", "userA")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("存在重复审批人");

        verify(mockRuntimeService, never()).addMultiInstanceExecution(anyString(), anyString(), any());
        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 本轮已投过票的人被重复加签 → 抛 IllegalArgumentException（维度二，csRoundIndex 匹配）。
     */
    @Test
    void testAddCounterSignerRejectsVotedInCurrentRound() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task self = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());

        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // 周期边界：无历史周期分隔 → 空列表 → 不过滤
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        // 本节点 key 过滤：无历史任务 → 空 → determineNextRoundIndex=1 → 当前轮次 0
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Collections.emptyList());
        // 本轮已投票：voterA 已完成且 csRoundIndex=0（与本轮 roundIndex=0 匹配）→ 拦截
        HistoricTaskInstance votedTask = createMockHistoricTaskWithRoundVar(
                "voted-a", definitionId, "csTask", "pi-001", "voterA",
                new Date(), new Date(), 0);
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.singletonList(votedTask));
        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(boundaryQ, keyQ, boundaryQ, votedQ);

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList("voterA")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已在本轮投过票");

        verify(mockRuntimeService, never()).addMultiInstanceExecution(anyString(), anyString(), any());
    }

    /**
     * 跨轮次复用通过：上一轮（csRoundIndex=0）已投票的人在本轮（roundIndex=1）加签应放行，
     * 与发起会签查重口径一致。
     */
    @Test
    void testAddCounterSignerAllowsReuseAcrossRounds() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task self = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());

        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // 当前活跃任务带 csRoundIndex=1 → 当前轮次 1（上一轮已投票不影响本轮）
        when(mockTaskService.getVariableLocal(eq(task.getId()), eq("csRoundIndex"))).thenReturn(1);

        // 已投票 voterA 属于上一轮（csRoundIndex=0）→ 本轮复用应放行
        HistoricTaskInstance votedTask = createMockHistoricTaskWithRoundVar(
                "voted-a", definitionId, "csTask", "pi-001", "voterA",
                new Date(), new Date(), 0);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.singletonList(votedTask));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("voterA"));

        // 跨轮次复用成功：正常创建任务
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "voterA".equals(map.get("assignee"))));
    }

    /**
     * 漏洞 A 回归（ADR-0024）：模式 B 固定会签历史任务从未打 csRoundIndex，
     * roundIndex==0 时"无标视为命中"应拦截已投票重复加签。
     */
    @Test
    void testAddCounterSignerRejectsVotedInModeBNoTag() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task self = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());

        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // 模式 B 已投票历史任务：无 csRoundIndex 局部变量（roundIndex==0 时视为隐式轮次 0）→ 拦截
        HistoricTaskInstance votedTask = createMockHistoricTaskWithRoundVar(
                "voted-a", definitionId, "csTask", "pi-001", "voterA",
                new Date(), new Date(), null);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Collections.emptyList());
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.singletonList(votedTask));
        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(boundaryQ, keyQ, boundaryQ, votedQ);

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList("voterA")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已在本轮投过票");

        verify(mockRuntimeService, never()).addMultiInstanceExecution(anyString(), anyString(), any());
    }

    /**
     * 漏洞 B 回归（ADR-0024）：折返后新执行周期内，上一周期已投票人（csRoundIndex 可能与本周期撞号）
     * 被周期边界（startTime）过滤 → 跨周期复用放行，不误拦。
     */
    @Test
    void testAddCounterSignerAllowsReuseAcrossCycle() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        Task self = createMockTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        Task mockExistTask = createMockTask(task.getId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), task.getAssignee());

        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));
        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // 历史时间线：上一周期 csTask(已投, csRoundIndex=0) → confirmTask → 本周期 csTask(活跃)
        Date t1 = new Date(1000);
        Date t2 = new Date(2000);
        Date t3 = new Date(3000);
        HistoricTaskInstance oldCS = createMockHistoricTaskWithRoundVar(
                "old-cs", definitionId, "csTask", "pi-001", "voterA", t1, t1, 0);
        HistoricTaskInstance confirm = createMockHistoricTask(
                "confirm", definitionId, "confirmTask", "pi-001", "initiator", null, t2, t2, null);
        when(confirm.getStartTime()).thenReturn(t2);
        HistoricTaskInstance newCS = createMockHistoricTask(
                "new-cs", definitionId, "csTask", "pi-001", "voterA", null, t3, null, null);
        when(newCS.getStartTime()).thenReturn(t3);

        // 周期边界：边界回退至本周期最早 csTask（t3）
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Arrays.asList(oldCS, confirm, newCS));
        // 本节点 key 过滤：仅本周期 new-cs 计入 → determineNextRoundIndex=1 → 当前轮次 0
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Arrays.asList(oldCS, newCS));
        // csRoundIndex 变量查询：无 → maxRound=0
        HistoricVariableInstanceQuery varQ = mock(HistoricVariableInstanceQuery.class);
        when(varQ.processInstanceId(anyString())).thenReturn(varQ);
        when(varQ.variableName("csRoundIndex")).thenReturn(varQ);
        when(varQ.list()).thenReturn(Collections.emptyList());
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);
        // 本轮已投票查重：已投票任务 oldCS(startTime=t1 < 边界 t3) 被周期过滤 → 复用放行
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.singletonList(oldCS));
        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(boundaryQ, keyQ, boundaryQ, votedQ);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("voterA"));

        // 跨周期复用成功：正常创建任务
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "voterA".equals(map.get("assignee"))));
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

        // isPseudoSingleton 历史任务数查询：仅当前活跃任务（historyTaskCount=1 → 伪单例）
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(1L);

        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());

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

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q4, q3, q5);
        // 查询顺序：resolveVotedAssigneesInRound(边界,已投票列表) → isPseudoSingleton(count)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ, hqFinished);

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
     * 模式A（伪单例）下，被加签的活跃审批人（非会签发起人）加签通过。
     * 权限放宽（2026-08-08）：模式A从"仅会签发起人"放宽为"会签发起人 OR 当前节点活跃审批人"，
     * 与模式B及钉钉/飞书"当前审批人可加签"保持一致。
     */
    @Test
    void testAddCounterSignerAsActiveApproverInPseudoSingleton() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // countersignInitiator = "owner"（非 USER_ID）→ 模式A成立，USER_ID 不是发起人
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn("owner");

        // Qperm: 权限校验 resolveCurrentAssignees → [USER_ID, user2]（USER_ID 在列 → 放行）
        Task self = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        Task other = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery qPerm = mock(TaskQuery.class);
        when(qPerm.processInstanceId(anyString())).thenReturn(qPerm);
        when(qPerm.taskDefinitionKey(anyString())).thenReturn(qPerm);
        when(qPerm.active()).thenReturn(qPerm);
        when(qPerm.list()).thenReturn(Arrays.asList(self, other));

        // Qmain: 主逻辑 resolveCurrentAssignees → 同样 [self, other]
        TaskQuery qMain = mock(TaskQuery.class);
        when(qMain.processInstanceId(anyString())).thenReturn(qMain);
        when(qMain.taskDefinitionKey(anyString())).thenReturn(qMain);
        when(qMain.active()).thenReturn(qMain);
        when(qMain.list()).thenReturn(Arrays.asList(self, other));

        // Qmif: isMultiInstanceFinished — activeCount=2 → false（本轮未结束，不加新轮）
        TaskQuery qMif = mock(TaskQuery.class);
        when(qMif.processInstanceId(anyString())).thenReturn(qMif);
        when(qMif.taskDefinitionKey(anyString())).thenReturn(qMif);
        when(qMif.active()).thenReturn(qMif);
        when(qMif.count()).thenReturn(2L);

        // Qround: determineCurrentRoundIndex 活跃任务查询（getVariableLocal 默认 null → 降级）
        TaskQuery qRound = mock(TaskQuery.class);
        when(qRound.processInstanceId(anyString())).thenReturn(qRound);
        when(qRound.taskDefinitionKey(anyString())).thenReturn(qRound);
        when(qRound.active()).thenReturn(qRound);
        when(qRound.list()).thenReturn(Arrays.asList(self, other));

        // determineNextRoundIndex（降级）：周期边界 + 本节点 key 过滤（均空 → 下一轮=1）
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Collections.emptyList());
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Collections.emptyList());
        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());

        // Qsvl: setVariableLocal 活跃任务列表
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery qSvl = mock(TaskQuery.class);
        when(qSvl.processInstanceId(anyString())).thenReturn(qSvl);
        when(qSvl.taskDefinitionKey(anyString())).thenReturn(qSvl);
        when(qSvl.active()).thenReturn(qSvl);
        when(qSvl.list()).thenReturn(Arrays.asList(self, other, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, qPerm, qMain, qMif, qRound, qSvl);
        // 查询顺序：determineNextRoundIndex(边界,key) → resolveVotedAssigneesInRound(边界,已投票列表)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyQ, boundaryQ, votedQ);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 验证加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));

        // 验证不重复写入 countersignInitiator（已存在）
        verify(mockRuntimeService, never()).setVariable(eq("pi-001"),
                eq("countersignInitiator_csTask"), anyString());
    }

    /**
     * 模式A（伪单例）下，非活跃审批人（既非发起人也非当前节点活跃审批人）加签被拒绝。
     */
    @Test
    void testAddCounterSignerRejectsInactiveApproverInPseudoSingleton() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // countersignInitiator = "owner"（非 USER_ID）→ 模式A成立
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn("owner");

        // Q2: 权限校验 resolveCurrentAssignees → [owner, user2]（不含 USER_ID）
        Task active1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", "owner");
        Task active2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(active1, active2));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        assertThatThrownBy(() -> counterSignWorkflow.addCounterSigner("task-001",
                Collections.singletonList("newUser")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("会签发起人")
                .hasMessageContaining("活跃审批人");
    }

    /**
     * 模式A（伪单例）下，活跃审批人（非会签发起人）减签通过。
     * validateCounterSignPermission 为加签/减签共享方法，权限同步放宽。
     */
    @Test
    void testRemoveCounterSignerAsActiveApproverInPseudoSingleton() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // countersignInitiator = "owner"（非 USER_ID）→ 模式A成立
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn("owner");

        // Qperm: 权限校验 resolveCurrentAssignees → [USER_ID, user2, user3]（USER_ID 在列 → 放行）
        Task self = createMockTask("sub-0", definitionId, "csTask", "pi-001", USER_ID);
        Task other1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        Task other2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user3");
        TaskQuery qPerm = mock(TaskQuery.class);
        when(qPerm.processInstanceId(anyString())).thenReturn(qPerm);
        when(qPerm.taskDefinitionKey(anyString())).thenReturn(qPerm);
        when(qPerm.active()).thenReturn(qPerm);
        when(qPerm.list()).thenReturn(Arrays.asList(self, other1, other2));

        // hasVoted(task, "user2") → 0
        HistoricTaskInstanceQuery hqVoted = mock(HistoricTaskInstanceQuery.class);
        when(hqVoted.processInstanceId(anyString())).thenReturn(hqVoted);
        when(hqVoted.taskDefinitionKey(anyString())).thenReturn(hqVoted);
        when(hqVoted.taskAssignee(anyString())).thenReturn(hqVoted);
        when(hqVoted.finished()).thenReturn(hqVoted);
        when(hqVoted.count()).thenReturn(0L);

        // Qmain: 主逻辑 resolveCurrentAssignees → [USER_ID, user2, user3]
        TaskQuery qMain = mock(TaskQuery.class);
        when(qMain.processInstanceId(anyString())).thenReturn(qMain);
        when(qMain.taskDefinitionKey(anyString())).thenReturn(qMain);
        when(qMain.active()).thenReturn(qMain);
        when(qMain.list()).thenReturn(Arrays.asList(self, other1, other2));

        // Qfind: findActiveTask → targetTask
        Task targetTask = createMockTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        TaskQuery qFind = mock(TaskQuery.class);
        when(qFind.processInstanceId(anyString())).thenReturn(qFind);
        when(qFind.taskDefinitionKey(anyString())).thenReturn(qFind);
        when(qFind.taskAssignee("user2")).thenReturn(qFind);
        when(qFind.active()).thenReturn(qFind);
        when(qFind.singleResult()).thenReturn(targetTask);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, qPerm, qMain, qFind);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hqVoted);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution("exec-sub-1", false);
    }

    /**
     * 模式A（伪单例）下，非活跃审批人（既非发起人也非当前节点活跃审批人）减签被拒绝。
     */
    @Test
    void testRemoveCounterSignerRejectsInactiveApproverInPseudoSingleton() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // countersignInitiator = "owner"（非 USER_ID）→ 模式A成立
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn("owner");

        // Q2: 权限校验 resolveCurrentAssignees → [owner, user2]（不含 USER_ID）
        Task active1 = createMockTask("sub-1", definitionId, "csTask", "pi-001", "owner");
        Task active2 = createMockTask("sub-2", definitionId, "csTask", "pi-001", "user2");
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Arrays.asList(active1, active2));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("会签发起人")
                .hasMessageContaining("活跃审批人");
    }

    /**
     * 模式A（伪单例）下，流程发起人（非会签发起人，也不在当前节点活跃审批人中）加签被拒绝。
     * 直接验证旧 startUserId 旁路已被移除，且流程发起人不会因"曾是流程发起人"获得加签权限。
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

        // Q2: 权限校验 resolveCurrentAssignees → ["owner"]（不含 USER_ID）
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
                .hasMessageContaining("会签发起人")
                .hasMessageContaining("活跃审批人");
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

        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());

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

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q4, q3, q5);
        // 查询顺序：resolveVotedAssigneesInRound(边界,已投票列表)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ);

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

        // 权限: countersignInitiator_csTask = USER_ID（跨轮次仍生效）→ initiator 分支直接放行
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn(USER_ID);

        // Q2: 主逻辑 resolveCurrentAssignees → [self]
        Task self = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));

        // Q3: isMultiInstanceFinished — activeCount=1
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);

        // 当前周期 finishedCount=1（他人已投，本轮将尽）
        HistoricTaskInstanceQuery finishedQ = stubFinishedListQuery(1);

        // QSole: isMultiInstanceFinished — sole==task（残留路径修复后恒返回 false → 并入当前轮，
        // 轮次经 determineCurrentRoundIndex 降级计算）
        TaskQuery qSole = mock(TaskQuery.class);
        when(qSole.processInstanceId(anyString())).thenReturn(qSole);
        when(qSole.taskDefinitionKey(anyString())).thenReturn(qSole);
        when(qSole.active()).thenReturn(qSole);
        when(qSole.singleResult()).thenReturn(self);

        // determineNextRoundIndex：周期边界查询 + 本节点 key 过滤查询（均空 → 下一轮=1）
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Collections.emptyList());
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Collections.emptyList());

        // Q4: setVariableLocal 活跃列表
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.list()).thenReturn(Arrays.asList(self, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3, qSole, q4);
        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        // 查询顺序：isMIF(边界,finished) → determineNextRoundIndex(边界,key) → resolveVotedAssigneesInRound(边界,已投票列表)
        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(boundaryQ, finishedQ, boundaryQ, keyQ, boundaryQ, votedQ);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 验证加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));

        // 验证不重复写入 initiator（已存在）
        verify(mockRuntimeService, never()).setVariable(eq("pi-001"),
                eq("countersignInitiator_csTask"), anyString());
    }

    /**
     * 模式 A（伪单例）：当前轮次只剩操作者一人未投（activeCount=1, finishedCount>0, sole==task），
     * 且操作者任务已带 csRoundIndex → 本轮尚未结束，加签应并入当前轮，
     * 新加签人 csRoundIndex 沿用当前轮（0），评论不含"第 N 轮"。
     * 回归场景：bug 报告"当前轮次只剩操作者一人未投时加签，新加签人被错误归入新一轮"。
     */
    @Test
    void testAddCounterSignerSameRoundWhenLastUnvotedWithRoundIndex() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists（taskId 与活跃任务一致，触发 sole==task 分支）
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator_csTask = USER_ID（模式 A，initiator 分支直接放行）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn(USER_ID);

        // Q2: 主逻辑 resolveCurrentAssignees → [self]
        Task self = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));

        // Q3: isMultiInstanceFinished — activeCount=1
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);

        // QSole: isMultiInstanceFinished — sole==task
        TaskQuery qSole = mock(TaskQuery.class);
        when(qSole.processInstanceId(anyString())).thenReturn(qSole);
        when(qSole.taskDefinitionKey(anyString())).thenReturn(qSole);
        when(qSole.active()).thenReturn(qSole);
        when(qSole.singleResult()).thenReturn(self);

        // finishedCount=1（他人已完成投票，当前周期内）
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        HistoricTaskInstanceQuery finishedQ = stubFinishedListQuery(1);

        // 操作者任务已带 csRoundIndex=0 → 本轮未结束 → 并入当前轮
        when(mockTaskService.getVariableLocal(eq(task.getId()), eq("csRoundIndex"))).thenReturn(0);

        // Q4: determineCurrentRoundIndex 活跃任务查询
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.list()).thenReturn(Collections.singletonList(self));

        // Q5: setVariableLocal 活跃任务查询（加签后）
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q5 = mock(TaskQuery.class);
        when(q5.processInstanceId(anyString())).thenReturn(q5);
        when(q5.taskDefinitionKey(anyString())).thenReturn(q5);
        when(q5.active()).thenReturn(q5);
        when(q5.list()).thenReturn(Arrays.asList(self, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3, qSole, q4, q5);
        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        // 查询顺序：isMIF(边界,finished) → resolveVotedAssigneesInRound(边界,已投票列表)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, finishedQ, boundaryQ, votedQ);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));
        // 新任务并入当前轮 csRoundIndex=0（而非新一轮 1）
        verify(mockTaskService).setVariableLocal(eq("sub-new"), eq("csRoundIndex"), eq(0));
        // 评论不含"开启第 N 轮"
        verify(mockTaskService).addComment(eq(task.getId()), eq("pi-001"),
                eq(CommentType.ADD_SIGN.name()),
                Mockito.argThat(msg -> msg.contains("加签审批人") && !msg.contains("第")));
    }

    /**
     * 模式 B（固定会签）：本轮只剩操作者一人未投（activeCount=1, finishedCount>0, sole==task），
     * 无 countersignInitiator 变量。模式 B 无轮次概念——单执行周期内加签必然并入当前轮，
     * 新加签人 csRoundIndex 归入本轮（0），评论不含"第 N 轮"。
     */
    @Test
    void testAddCounterSignerFixedModeLastUnvotedMerges() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator 未设置（模式 B）→ 活跃审批人校验
        // Q2: resolveCurrentAssignees（权限校验 + 主逻辑各一次）→ [self]
        Task self = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));

        // Q3: isPseudoSingleton — activeCount=1
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);

        // isPseudoSingleton 历史任务数=2（他人已完成投票的任务 + 当前活跃任务）→ 非伪单例
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(2L);
        // 无修复时 isNewRound=true → determineNextRoundIndex 历史任务查询 → 空
        HistoricTaskInstanceQuery hqHistory = mock(HistoricTaskInstanceQuery.class);
        when(hqHistory.processInstanceId(anyString())).thenReturn(hqHistory);
        when(hqHistory.taskDefinitionKey(anyString())).thenReturn(hqHistory);
        when(hqHistory.list()).thenReturn(Collections.emptyList());

        // Q4: 红阶段 = isMultiInstanceFinished active count；绿阶段 = determineCurrentRoundIndex 活跃列表
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.count()).thenReturn(1L);
        when(q4.list()).thenReturn(Collections.singletonList(self));

        // Q5: 红阶段 = isMultiInstanceFinished sole；绿阶段 = setVariableLocal 活跃列表
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q5 = mock(TaskQuery.class);
        when(q5.processInstanceId(anyString())).thenReturn(q5);
        when(q5.taskDefinitionKey(anyString())).thenReturn(q5);
        when(q5.active()).thenReturn(q5);
        when(q5.singleResult()).thenReturn(self);
        when(q5.list()).thenReturn(Arrays.asList(self, taskNew));

        // Q6: 红阶段 setVariableLocal 活跃列表（绿阶段不再使用）
        TaskQuery q6 = mock(TaskQuery.class);
        when(q6.processInstanceId(anyString())).thenReturn(q6);
        when(q6.taskDefinitionKey(anyString())).thenReturn(q6);
        when(q6.active()).thenReturn(q6);
        when(q6.list()).thenReturn(Arrays.asList(self, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q4, q3, q5);

        // 周期边界查询：无历史周期分隔 → 空列表 → 不过滤
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Collections.emptyList());

        // 本轮已投票查重（ADR-0024）：无已完成投票 → 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        // 查询顺序：determineNextRoundIndex(边界,key) → resolveVotedAssigneesInRound(边界,已投票列表) → isPseudoSingleton(count)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, hqHistory, boundaryQ, votedQ, hqFinished);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));
        // 模式 B 并入当前轮 csRoundIndex=0（而非新一轮 1）
        verify(mockTaskService).setVariableLocal(eq("sub-new"), eq("csRoundIndex"), eq(0));
        // 评论不含"开启第 N 轮"
        verify(mockTaskService).addComment(eq(task.getId()), eq("pi-001"),
                eq(CommentType.ADD_SIGN.name()),
                Mockito.argThat(msg -> msg.contains("加签审批人") && !msg.contains("第")));
    }

    /**
     * 折返后轮次编号重置：上一执行周期存在 csRoundIndex=2 的历史任务，
     * 折返（驳回/退回/跳转重新进入会签节点）进入新周期后，轮次应在周期内重新计数。
     * 新周期只剩操作者一人未投时加签应并入当前轮（csRoundIndex=0），
     * 不沿用上一周期的全局 max+1（否则会误归入第 3 轮）。
     */
    @Test
    void testAddCounterSignerAfterRebuildResetsRoundNumbering() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator 未设置（模式 B）→ 活跃审批人校验
        Task self = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));

        // Q3: isPseudoSingleton — activeCount=1
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);

        // isPseudoSingleton 历史任务数=2（上一周期 oldCS + 本周期 newCS）→ 非伪单例，不写 initiator
        HistoricTaskInstanceQuery hqFinished = mock(HistoricTaskInstanceQuery.class);
        when(hqFinished.processInstanceId(anyString())).thenReturn(hqFinished);
        when(hqFinished.taskDefinitionKey(anyString())).thenReturn(hqFinished);
        when(hqFinished.finished()).thenReturn(hqFinished);
        when(hqFinished.count()).thenReturn(2L);

        // 历史任务时间线：上一周期 csTask(旧) → confirmTask → 本周期 csTask(新)
        Date t1 = new Date(1000);
        Date t2 = new Date(2000);
        Date t3 = new Date(3000);
        HistoricTaskInstance oldCS = createMockHistoricTask(
                "old-cs", definitionId, "csTask", "pi-001", "userA", null, t1, t1, null);
        when(oldCS.getStartTime()).thenReturn(t1);
        HistoricTaskInstance confirm = createMockHistoricTask(
                "confirm", definitionId, "confirmTask", "pi-001", "initiator", null, t2, t2, null);
        when(confirm.getStartTime()).thenReturn(t2);
        HistoricTaskInstance newCS = createMockHistoricTask(
                "new-cs", definitionId, "csTask", "pi-001", "userA", null, t3, null, null);
        when(newCS.getStartTime()).thenReturn(t3);

        // 周期边界查询（红阶段 current 代码无此查询，兼作 keyQ 兜底）
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.taskDefinitionKey(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Arrays.asList(oldCS, confirm, newCS));

        // 本节点 key 过滤查询
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Arrays.asList(oldCS, newCS));

        // 上一周期 csRoundIndex=2（应被周期边界排除，不参与本轮计数）
        HistoricVariableInstance oldVar = mock(HistoricVariableInstance.class);
        when(oldVar.getTaskId()).thenReturn("old-cs");
        when(oldVar.getValue()).thenReturn(2);
        HistoricVariableInstanceQuery varQ = mock(HistoricVariableInstanceQuery.class);
        when(varQ.processInstanceId(anyString())).thenReturn(varQ);
        when(varQ.variableName("csRoundIndex")).thenReturn(varQ);
        when(varQ.list()).thenReturn(Collections.singletonList(oldVar));
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);

        // Q4: determineCurrentRoundIndex 活跃列表（操作者无 csRoundIndex → 降级历史推断）
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.list()).thenReturn(Collections.singletonList(self));

        // Q5: setVariableLocal 活跃列表（加签后）
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q5 = mock(TaskQuery.class);
        when(q5.processInstanceId(anyString())).thenReturn(q5);
        when(q5.taskDefinitionKey(anyString())).thenReturn(q5);
        when(q5.active()).thenReturn(q5);
        when(q5.list()).thenReturn(Arrays.asList(self, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q2, q4, q3, q5);
        // 本轮已投票查重（ADR-0024）：本周期无已完成投票 → 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        // 查询顺序：determineNextRoundIndex(边界,key) → resolveVotedAssigneesInRound(边界,已投票列表) → isPseudoSingleton(count)
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyQ, boundaryQ, votedQ, hqFinished);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));
        // 折返后并入当前轮 csRoundIndex=0（而非沿用上一周期 max=2 推断出 3）
        verify(mockTaskService).setVariableLocal(eq("sub-new"), eq("csRoundIndex"), eq(0));
        verify(mockTaskService).addComment(eq(task.getId()), eq("pi-001"),
                eq(CommentType.ADD_SIGN.name()),
                Mockito.argThat(msg -> msg.contains("加签审批人") && !msg.contains("第")));
    }

    /**
     * 建模约束验证（隐患 D）：折返路径<b>无中间节点</b>（会签节点直接环回自己）时，
     * 历史时间线上同 taskDefinitionKey 任务连续，{@code findCurrentCycleBoundary}
     * 边界退化为全历史最早任务，周期切分失效，轮次沿用上一周期全局 max。
     *
     * <p>隐患 C 修复后 isMultiInstanceFinished 在加签场景恒返回 false（并入当前轮），
     * 轮次经 {@code determineCurrentRoundIndex} 降级为 {@code determineNextRoundIndex - 1}：
     * 边界退化 → 上一周期 csRoundIndex=2 未被排除 → next=3 → current=2（而非正确周期内的 0）。
     * 本测试固定该"受约束"行为，提醒建模必须让折返路径经过非本节点 key 的
     * 中间任务（如 confirmTask/回迁节点）。与
     * {@link #testAddCounterSignerAfterRebuildResetsRoundNumbering}（有中间节点 → 边界正确切分）
     * 互为对照。</p>
     */
    @Test
    void testAddCounterSignerDirectLoopKeepsGlobalMaxRound() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        when(mockMultiInstanceDetector.isMultiInstance(any(PlusTask.class))).thenReturn(true);

        // Q1: validateTaskExists
        Task mockExistTask = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // 权限: countersignInitiator_csTask = USER_ID（模式 A，initiator 分支直接放行）
        when(mockRuntimeService.getVariable("pi-001", "countersignInitiator_csTask")).thenReturn(USER_ID);

        // Q2: 主逻辑 resolveCurrentAssignees → [self]
        Task self = createMockTask(task.getId(), definitionId, "csTask", "pi-001", USER_ID);
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(Collections.singletonList(self));

        // Q3: isMultiInstanceFinished — activeCount=1
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.count()).thenReturn(1L);

        // QSole: isMultiInstanceFinished — sole==self（残留路径修复后返回 false → 并入当前轮）
        TaskQuery qSole = mock(TaskQuery.class);
        when(qSole.processInstanceId(anyString())).thenReturn(qSole);
        when(qSole.taskDefinitionKey(anyString())).thenReturn(qSole);
        when(qSole.active()).thenReturn(qSole);
        when(qSole.singleResult()).thenReturn(self);

        // 历史时间线（直接环回：全部为 csTask，同 key 连续，无中间节点）
        Date t1 = new Date(1000);
        Date t2 = new Date(2000);
        Date t3 = new Date(3000);
        HistoricTaskInstance oldCS1 = createMockHistoricTask(
                "old-cs1", definitionId, "csTask", "pi-001", "userA", null, t1, t1, null);
        when(oldCS1.getStartTime()).thenReturn(t1);
        HistoricTaskInstance oldCS2 = createMockHistoricTask(
                "old-cs2", definitionId, "csTask", "pi-001", "userB", null, t2, t2, null);
        when(oldCS2.getStartTime()).thenReturn(t2);
        HistoricTaskInstance newCS = createMockHistoricTask(
                "new-cs", definitionId, "csTask", "pi-001", "userA", null, t3, null, null);
        when(newCS.getStartTime()).thenReturn(t3);

        // 周期边界查询（isMultiInstanceFinished + determineNextRoundIndex 各一次）：
        // 全部同 key 连续 → 边界持续回退至全历史最早 t1
        HistoricTaskInstanceQuery boundaryQ = mock(HistoricTaskInstanceQuery.class);
        when(boundaryQ.processInstanceId(anyString())).thenReturn(boundaryQ);
        when(boundaryQ.orderByHistoricTaskInstanceStartTime()).thenReturn(boundaryQ);
        when(boundaryQ.asc()).thenReturn(boundaryQ);
        when(boundaryQ.list()).thenReturn(Arrays.asList(oldCS1, oldCS2, newCS));

        // 当前周期 finished list（本轮有人已投）
        HistoricTaskInstanceQuery finishedQ = stubFinishedListQuery(1);

        // 本节点 key 过滤查询：全部任务 startTime >= boundary(t1) → 全部计入当前周期
        HistoricTaskInstanceQuery keyQ = mock(HistoricTaskInstanceQuery.class);
        when(keyQ.processInstanceId(anyString())).thenReturn(keyQ);
        when(keyQ.taskDefinitionKey(anyString())).thenReturn(keyQ);
        when(keyQ.list()).thenReturn(Arrays.asList(oldCS1, oldCS2, newCS));

        // 上一周期 csRoundIndex=2（未被边界排除 → 参与计数 → determineNextRoundIndex=3）
        HistoricVariableInstance oldVar = mock(HistoricVariableInstance.class);
        when(oldVar.getTaskId()).thenReturn("old-cs1");
        when(oldVar.getValue()).thenReturn(2);
        HistoricVariableInstanceQuery varQ = mock(HistoricVariableInstanceQuery.class);
        when(varQ.processInstanceId(anyString())).thenReturn(varQ);
        when(varQ.variableName("csRoundIndex")).thenReturn(varQ);
        when(varQ.list()).thenReturn(Collections.singletonList(oldVar));
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);

        // Q4: setVariableLocal 活跃列表（加签后）
        Task taskNew = createMockTask("sub-new", definitionId, "csTask", "pi-001", "newUser");
        TaskQuery q4 = mock(TaskQuery.class);
        when(q4.processInstanceId(anyString())).thenReturn(q4);
        when(q4.taskDefinitionKey(anyString())).thenReturn(q4);
        when(q4.active()).thenReturn(q4);
        when(q4.list()).thenReturn(Arrays.asList(self, taskNew));

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3, qSole, q4);
        // 本轮已投票查重（ADR-0024）：历史已完成任务无 csRoundIndex（roundIndex=2>0 不命中）→ 空列表
        HistoricTaskInstanceQuery votedQ = stubVotedListQuery(Collections.emptyList());
        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(boundaryQ, finishedQ, boundaryQ, keyQ, boundaryQ, votedQ);

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        // 加签成功
        verify(mockRuntimeService).addMultiInstanceExecution(eq("csTask"), eq("pi-001"),
                Mockito.argThat(map -> "newUser".equals(map.get("assignee"))));
        // 直接环回 → 边界退化为全历史最早 → 上一周期 csRoundIndex=2 未被排除 →
        // determineNextRoundIndex=3 → determineCurrentRoundIndex 降级得 2（而非正确周期内的 0）
        verify(mockTaskService).setVariableLocal(eq("sub-new"), eq("csRoundIndex"), eq(2));
        verify(mockTaskService).setVariableLocal(eq("task-001"), eq("csRoundIndex"), eq(2));
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
                Collections.singletonList(failingCb), new EventBus(null), mockProcessEndDetector);

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
        // isMultiInstanceFinished：周期边界查询 + 当前周期 finished list 查询
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery();
        HistoricTaskInstanceQuery finishedQ = stubFinishedListQuery(finishedCount);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(hq, boundaryQ, finishedQ);
        when(hq.processInstanceId(anyString())).thenReturn(hq);
        when(hq.taskDefinitionKey(anyString())).thenReturn(hq);
        when(hq.taskAssignee(anyString())).thenReturn(hq);
        when(hq.finished()).thenReturn(hq);
        when(hq.count()).thenReturn(finishedCount);
    }

    /**
     * 周期边界查询 mock：历史任务列表为空 → 边界为 null → 不过滤（兼容单周期/老数据）。
     */
    private HistoricTaskInstanceQuery stubBoundaryQuery() {
        HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.orderByHistoricTaskInstanceStartTime()).thenReturn(q);
        when(q.asc()).thenReturn(q);
        when(q.list()).thenReturn(Collections.emptyList());
        return q;
    }

    /**
     * 当前周期已完成任务查询 mock：finished().list() 返回 n 个已完成任务。
     */
    private HistoricTaskInstanceQuery stubFinishedListQuery(long count) {
        HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.finished()).thenReturn(q);
        List<HistoricTaskInstance> finished = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HistoricTaskInstance t = mock(HistoricTaskInstance.class);
            when(t.getStartTime()).thenReturn(new Date());
            finished.add(t);
        }
        when(q.list()).thenReturn(finished);
        return q;
    }

    /**
     * resolveVotedAssigneesInRound 的已完成任务查询 mock
     * （finished + includeTaskLocalVariables，ADR-0024）。
     */
    private HistoricTaskInstanceQuery stubVotedListQuery(List<HistoricTaskInstance> finishedTasks) {
        HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.finished()).thenReturn(q);
        when(q.includeTaskLocalVariables()).thenReturn(q);
        when(q.list()).thenReturn(finishedTasks);
        return q;
    }

    /**
     * 创建带 csRoundIndex 任务局部变量的已完成历史任务（roundIndex 为 null 表示无标）。
     */
    private HistoricTaskInstance createMockHistoricTaskWithRoundVar(String id, String definitionId,
            String taskDefKey, String instanceId, String assignee, Date startTime, Date endTime,
            Integer roundIndex) {
        HistoricTaskInstance t = createMockHistoricTask(id, definitionId, taskDefKey, instanceId,
                assignee, null, startTime, endTime, null);
        when(t.getStartTime()).thenReturn(startTime);
        Map<String, Object> locals = new HashMap<>();
        if (roundIndex != null) {
            locals.put("csRoundIndex", roundIndex);
        }
        when(t.getTaskLocalVariables()).thenReturn(locals);
        return t;
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

    // ======================== 事件发布 ========================

    private CounterSignWorkflow createWorkflowWithEventPublisher(EventPublisher ep) {
        EventBus eventBus = new EventBus(ep);
        ProcessEndDetector ped = new ProcessEndDetector(mockRuntimeService, mockHistoryService, eventBus);
        return new CounterSignWorkflow(userContext, mockTaskService,
                mockHistoryService, mockRuntimeService, mockMultiInstanceDetector, mockNodeFinder,
                Collections.emptyList(), eventBus, ped);
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
