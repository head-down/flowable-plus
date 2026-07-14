package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.BpmnModelCache;
import io.github.flowable.plus.core.CounterSignWorkflow;
import io.github.flowable.plus.core.DefaultBpmnModelCache;
import io.github.flowable.plus.core.DefaultNodeFinder;
import io.github.flowable.plus.core.MultiInstanceDetector;
import io.github.flowable.plus.core.NodeFinder;
import io.github.flowable.plus.core.PlusHistoricProcessInstance;
import io.github.flowable.plus.core.PlusHistoricTask;
import io.github.flowable.plus.core.PlusTask;
import io.github.flowable.plus.core.TaskWorkflow;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
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

import java.util.ArrayList;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BPMN 多实例集成测试：通过 mock 引擎验证会签全链路回调与引擎方法调用。
 */
class BpmnMultiInstanceIntegrationTest {

    private static final String USER_ID = "admin";

    private RepositoryService mockRepoService;
    private RuntimeService mockRuntimeService;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private BpmnModelCache bpmnModelCache;
    private MultiInstanceDetector multiInstanceDetector;
    private CounterSignWorkflow counterSignWorkflow;
    private TaskWorkflow taskWorkflow;
    private NodeFinder mockNodeFinder;

    private final AtomicInteger onStartCount = new AtomicInteger();
    private final AtomicInteger onVoteCount = new AtomicInteger();
    private final AtomicInteger onFinishCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mockRepoService = mock(RepositoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);

        bpmnModelCache = new DefaultBpmnModelCache(mockRepoService);
        multiInstanceDetector = new MultiInstanceDetector(bpmnModelCache);
        UserContext userContext = () -> USER_ID;
        mockNodeFinder = mock(NodeFinder.class);

        onStartCount.set(0);
        onVoteCount.set(0);
        onFinishCount.set(0);

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
                mockHistoryService, mockRuntimeService, multiInstanceDetector, mockNodeFinder,
                Collections.singletonList(trackingCallback));

        taskWorkflow = new TaskWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mock(org.flowable.engine.IdentityService.class),
                mockNodeFinder, multiInstanceDetector, null);
    }

    // ======================== 会签：全票通过后推进 ========================

    @Test
    void testCounterSignAllApprove() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        // 第 1 人同意
        PlusTask task1 = createPlusTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task1, createActiveAssignees(USER_ID, "userB", "userC"), 3L, 0L);

        counterSignWorkflow.counterSign("task-001", true, new HashMap<>(), "同意");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(0);
        verify(mockTaskService).claim("task-001", USER_ID);

        // 第 2 人同意（不触发 onStart）
        PlusTask task2 = createPlusTask("task-002", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task2, createActiveAssignees(USER_ID, "userC"), 2L, 1L);

        counterSignWorkflow.counterSign("task-002", true, null, "同意");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(2);

        // 第 3 人同意（触发 onFinish）
        PlusTask task3 = createPlusTask("task-003", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task3, createActiveAssignees(USER_ID), 0L, 2L);

        counterSignWorkflow.counterSign("task-003", true, null, "同意");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(3);
        assertThat(onFinishCount.get()).isEqualTo(1);
    }

    // ======================== 或签：任一通过后推进 ========================

    @Test
    void testOrSignFirstApprovalCompletes() {
        BpmnModel model = buildMultiInstanceModel("${nrOfCompletedInstances >= 1}");
        when(mockRepoService.getBpmnModel("proc-os")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-os", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID, "userB", "userC"), 0L, 0L);

        counterSignWorkflow.counterSign("task-001", true, null, "通过");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(1);
    }

    @Test
    void testOrSignRejectionDoesNotFinish() {
        BpmnModel model = buildMultiInstanceModel("${nrOfCompletedInstances >= 1}");
        when(mockRepoService.getBpmnModel("proc-os")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-os", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID, "userB", "userC"), 3L, 0L);

        counterSignWorkflow.counterSign("task-001", false, null, "驳回");

        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(0);
        verify(mockTaskService).addComment("task-001", null, "COUNTER_SIGN_REJECT", "驳回");
    }

    // ======================== 驳回：部分驳回流程不推进 ========================

    @Test
    void testCounterSignPartialRejection() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID, "userB", "userC"), 3L, 0L);

        counterSignWorkflow.counterSign("task-001", false, null, "不同意");

        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(0);
    }

    @Test
    void testCounterSignOnStartNotFiredAgain() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);
        onStartCount.set(0);

        PlusTask task1 = createPlusTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task1, createActiveAssignees(USER_ID, "userB"), 3L, 0L);
        counterSignWorkflow.counterSign("task-001", true, null, "同意");
        assertThat(onStartCount.get()).isEqualTo(1);

        PlusTask task2 = createPlusTask("task-002", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task2, createActiveAssignees(USER_ID), 1L, 1L);
        counterSignWorkflow.counterSign("task-002", true, null, "同意");
        assertThat(onStartCount.get()).isEqualTo(1);
    }

    // ======================== 加签/减签 ========================

    @Test
    void testAddCounterSignerFiresCallback() {
        BpmnModel model = buildAddRemoveModel();
        when(mockRepoService.getBpmnModel("proc-sign")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-sign", "csTask", USER_ID);
        stubSignManageMocks(task, Collections.singletonList(task));

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        verify(mockRuntimeService).addMultiInstanceExecution(
                "csTask", "pi-001",
                new HashMap<String, Object>() {{ put("assignee", "newUser"); }});
        assertThat(onStartCount.get()).isEqualTo(1);
    }

    @Test
    void testAddCounterSignerAllDuplicateSkips() {
        BpmnModel model = buildAddRemoveModel();
        when(mockRepoService.getBpmnModel("proc-sign")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-sign", "csTask", USER_ID);
        stubSignManageMocks(task, Collections.singletonList(task));

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList(USER_ID));

        verify(mockRuntimeService, never()).addMultiInstanceExecution(
                anyString(), anyString(), any());
    }

    @Test
    void testRemoveCounterSignerCallsEngine() {
        BpmnModel model = buildAddRemoveModel();
        when(mockRepoService.getBpmnModel("proc-sign")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-sign", "csTask", USER_ID);
        PlusTask targetTask = createPlusTask("task-target", "pi-001", "proc-sign", "csTask", "user2");

        // Pre-create mock tasks
        Task mockExistTask = toMockTask(task);
        Task mockTargetTask = toMockTask(targetTask);
        List<Task> mockActiveList = Arrays.asList(mockExistTask, mockTargetTask);

        // Q1: validateTaskExists
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: resolveCurrentAssignees
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(mockActiveList);
        when(q2.count()).thenReturn(2L);

        // Q3: findActiveTask for user2
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.taskAssignee(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.singleResult()).thenReturn(mockTargetTask);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3);

        // HQ1: validateCounterSignPermission → findLatestFinishedTask for prevTask
        HistoricTaskInstance prevTask = mock(HistoricTaskInstance.class);
        when(prevTask.getAssignee()).thenReturn(USER_ID);
        HistoricTaskInstanceQuery permQ = mock(HistoricTaskInstanceQuery.class);
        when(permQ.processInstanceId(anyString())).thenReturn(permQ);
        when(permQ.taskDefinitionKey(anyString())).thenReturn(permQ);
        when(permQ.finished()).thenReturn(permQ);
        when(permQ.orderByHistoricTaskInstanceEndTime()).thenReturn(permQ);
        when(permQ.desc()).thenReturn(permQ);
        when(permQ.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        // HQ2: hasVoted → count=0 for all
        HistoricTaskInstanceQuery histQ = mock(HistoricTaskInstanceQuery.class);
        when(histQ.processInstanceId(anyString())).thenReturn(histQ);
        when(histQ.taskDefinitionKey(anyString())).thenReturn(histQ);
        when(histQ.taskAssignee(anyString())).thenReturn(histQ);
        when(histQ.finished()).thenReturn(histQ);
        when(histQ.count()).thenReturn(0L);

        when(mockHistoryService.createHistoricTaskInstanceQuery())
                .thenReturn(permQ).thenReturn(histQ);

        // validateCounterSignPermission needs findPreviousNodes → ["prevTask"]
        when(mockNodeFinder.findPreviousNodes(anyString(), anyString(), anyString()))
                .thenReturn(Collections.singletonList("prevTask"));

        // findProcessInstance fallback (not needed if prevNodes non-empty, but safe)
        HistoricProcessInstance mockHpi = mock(HistoricProcessInstance.class);
        when(mockHpi.getStartUserId()).thenReturn(USER_ID);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(histPiQuery.processInstanceId(anyString())).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(mockHpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution(
                "exec-task-target", false);
    }

    // ======================== 错误路径 ========================

    @Test
    void testCounterSignOnNonMultiInstance() {
        BpmnModel model = buildSingleTaskModel();
        when(mockRepoService.getBpmnModel("proc-single")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-single", "task1", USER_ID);
        Task mockTask = toMockTask(task);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId("task-001")).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(mockTask);

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, "同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
    }

    @Test
    void testCompleteTaskBlockedOnMultiInstance() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        Task mockTask = toMockTask(task);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId("task-001")).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(mockTask);

        assertThatThrownBy(() -> taskWorkflow.completeTask("task-001", null, "同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    // ======================== 回调异常隔离 ========================

    @Test
    void testCallbackExceptionIsolated() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        UserContext userCtx = () -> USER_ID;
        CounterSignCallback failingCb = new CounterSignCallback() {
            @Override
            public void onStart(String pid, String tid, List<String> assignees) {
                throw new RuntimeException("模拟回调异常");
            }
        };

        CounterSignWorkflow fp = new CounterSignWorkflow(userCtx, mockTaskService,
                mockHistoryService, mockRuntimeService, multiInstanceDetector, mockNodeFinder,
                Collections.singletonList(failingCb));

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID), 0L, 0L);

        fp.counterSign("task-001", true, null, "同意");
        verify(mockTaskService).complete("task-001", null);
    }

    // ======================== Test Model Builders ========================

    private static BpmnModel buildMultiInstanceModel(String completionCondition) {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("testProcess");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask task1 = new UserTask();
        task1.setId("task1");
        process.addFlowElement(task1);
        addFlow(process, "f1", "start", "task1");

        UserTask csTask = new UserTask();
        csTask.setId("csTask");
        MultiInstanceLoopCharacteristics mic = new MultiInstanceLoopCharacteristics();
        mic.setSequential(false);
        if (completionCondition != null) {
            mic.setCompletionCondition(completionCondition);
        }
        csTask.setLoopCharacteristics(mic);
        process.addFlowElement(csTask);
        addFlow(process, "f2", "task1", "csTask");

        return model;
    }

    private static BpmnModel buildAddRemoveModel() {
        return buildMultiInstanceModel(null);
    }

    private static BpmnModel buildSingleTaskModel() {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("testProcess");
        model.addProcess(process);

        StartEvent start = new StartEvent();
        start.setId("start");
        process.addFlowElement(start);

        UserTask task = new UserTask();
        task.setId("task1");
        process.addFlowElement(task);
        addFlow(process, "f1", "start", "task1");

        return model;
    }

    private static void addFlow(Process process, String id, String source, String target) {
        SequenceFlow flow = new SequenceFlow();
        flow.setId(id);
        flow.setSourceRef(source);
        flow.setTargetRef(target);
        process.addFlowElement(flow);
    }

    // ======================== Test Helpers ========================

    private PlusTask createPlusTask(String taskId, String processInstanceId,
            String processDefinitionId, String activityId, String assignee) {
        return new PlusTask(taskId, processDefinitionId, activityId, processInstanceId,
                assignee, null, null, "exec-" + taskId, new Date());
    }

    private List<PlusTask> createActiveAssignees(String... assignees) {
        List<PlusTask> tasks = new ArrayList<>();
        for (String assignee : assignees) {
            tasks.add(new PlusTask("task-" + assignee, "proc-cs", "csTask", "pi-001",
                    assignee, null, null, "exec-" + assignee, new Date()));
        }
        return tasks;
    }

    private Task toMockTask(PlusTask pt) {
        Task mockTask = mock(Task.class);
        when(mockTask.getId()).thenReturn(pt.getId());
        when(mockTask.getProcessDefinitionId()).thenReturn(pt.getProcessDefinitionId());
        when(mockTask.getTaskDefinitionKey()).thenReturn(pt.getTaskDefinitionKey());
        when(mockTask.getProcessInstanceId()).thenReturn(pt.getProcessInstanceId());
        when(mockTask.getAssignee()).thenReturn(pt.getAssignee());
        when(mockTask.getName()).thenReturn(pt.getName());
        when(mockTask.getExecutionId()).thenReturn(pt.getExecutionId());
        when(mockTask.getCreateTime()).thenReturn(pt.getCreateTime());
        return mockTask;
    }

    private void stubCounterSignMocks(PlusTask task, List<PlusTask> activeList, long activeCount, long finishedCount) {
        // Pre-create all mock tasks to avoid nested stubbing
        Task mockExistTask = toMockTask(task);
        List<Task> mockActiveList = new ArrayList<>();
        for (PlusTask pt : activeList) {
            mockActiveList.add(toMockTask(pt));
        }

        // Q1: validateTaskExists → createTaskQuery().taskId().singleResult()
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: resolveCurrentAssignees (may be skipped if hasVoted=true)
        // Also serves as isMultiInstanceFinished if chain runs short
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(mockActiveList);
        when(q2.count()).thenReturn(activeCount);  // also support count for safety

        // Q3: isMultiInstanceFinished → count
        TaskQuery q3 = mock(TaskQuery.class);
        when(q3.processInstanceId(anyString())).thenReturn(q3);
        when(q3.taskDefinitionKey(anyString())).thenReturn(q3);
        when(q3.active()).thenReturn(q3);
        when(q3.list()).thenReturn(mockActiveList);  // also support list for safety
        when(q3.count()).thenReturn(activeCount);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2, q3);

        // HQ: hasVoted → createHistoricTaskInstanceQuery()...count()
        HistoricTaskInstanceQuery histQ = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histQ);
        when(histQ.processInstanceId(anyString())).thenReturn(histQ);
        when(histQ.taskDefinitionKey(anyString())).thenReturn(histQ);
        when(histQ.taskAssignee(anyString())).thenReturn(histQ);
        when(histQ.finished()).thenReturn(histQ);
        when(histQ.count()).thenReturn(finishedCount);
    }

    private void stubSignManageMocks(PlusTask task, List<PlusTask> allActiveList) {
        // Pre-create all mock tasks
        Task mockExistTask = toMockTask(task);
        List<Task> mockList = new ArrayList<>();
        for (PlusTask pt : allActiveList) {
            mockList.add(toMockTask(pt));
        }

        // Q1: validateTaskExists → createTaskQuery().taskId().singleResult()
        TaskQuery q1 = mock(TaskQuery.class);
        when(q1.taskId(task.getId())).thenReturn(q1);
        when(q1.singleResult()).thenReturn(mockExistTask);

        // Q2: resolveCurrentAssignees → createTaskQuery()...list()
        TaskQuery q2 = mock(TaskQuery.class);
        when(q2.processInstanceId(anyString())).thenReturn(q2);
        when(q2.taskDefinitionKey(anyString())).thenReturn(q2);
        when(q2.active()).thenReturn(q2);
        when(q2.list()).thenReturn(mockList);

        when(mockTaskService.createTaskQuery()).thenReturn(q1, q2);

        // HQ: hasVoted → count = 0
        HistoricTaskInstanceQuery histQ = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histQ);
        when(histQ.processInstanceId(anyString())).thenReturn(histQ);
        when(histQ.taskDefinitionKey(anyString())).thenReturn(histQ);
        when(histQ.taskAssignee(anyString())).thenReturn(histQ);
        when(histQ.finished()).thenReturn(histQ);
        when(histQ.count()).thenReturn(0L);

        // validateCounterSignPermission → findLatestFinishedTask for prevTask
        HistoricTaskInstance prevTask = mock(HistoricTaskInstance.class);
        when(prevTask.getAssignee()).thenReturn(USER_ID);
        HistoricTaskInstanceQuery permQ = mock(HistoricTaskInstanceQuery.class);
        when(permQ.processInstanceId(anyString())).thenReturn(permQ);
        when(permQ.taskDefinitionKey(anyString())).thenReturn(permQ);
        when(permQ.finished()).thenReturn(permQ);
        when(permQ.orderByHistoricTaskInstanceEndTime()).thenReturn(permQ);
        when(permQ.desc()).thenReturn(permQ);
        when(permQ.listPage(0, 1)).thenReturn(Collections.singletonList(prevTask));

        // Chain: histQ first (hasVoted), then permQ (permission)
        // Actually validateCounterSignPermission is called BEFORE hasVoted in addCounterSigner
        // Order: permQ, histQ
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(permQ).thenReturn(histQ);

        // findProcessInstance fallback (startUserId)
        HistoricProcessInstance mockHpi = mock(HistoricProcessInstance.class);
        when(mockHpi.getStartUserId()).thenReturn(USER_ID);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(histPiQuery.processInstanceId(anyString())).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(mockHpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
    }

}
