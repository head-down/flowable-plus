package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.BpmnModelCache;
import io.github.flowable.plus.core.DefaultBpmnModelCache;
import io.github.flowable.plus.core.DefaultNodeFinder;
import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.HistoricRepository;
import io.github.flowable.plus.core.TaskRepository;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    private ProcessEngine mockEngine;
    private RepositoryService mockRepoService;
    private RuntimeService mockRuntimeService;
    private TaskRepository mockTaskRepo;
    private HistoricRepository mockHistoricRepo;
    private FlowablePlus flowablePlus;
    private ExpressionManager expressionManager;

    private final AtomicInteger onStartCount = new AtomicInteger();
    private final AtomicInteger onVoteCount = new AtomicInteger();
    private final AtomicInteger onFinishCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mockEngine = mock(ProcessEngine.class);
        mockRepoService = mock(RepositoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskRepo = mock(TaskRepository.class);
        mockHistoricRepo = mock(HistoricRepository.class);

        when(mockEngine.getRuntimeService()).thenReturn(mockRuntimeService);
        when(mockEngine.getHistoryService()).thenReturn(mock(org.flowable.engine.HistoryService.class));

        ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
        expressionManager = mock(ExpressionManager.class);
        when(config.getExpressionManager()).thenReturn(expressionManager);
        when(mockEngine.getProcessEngineConfiguration()).thenReturn(config);

        BpmnModelCache bpmnModelCache = new DefaultBpmnModelCache(mockRepoService);
        UserContext userContext = () -> USER_ID;

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

        flowablePlus = new FlowablePlus(mockEngine, userContext,
                new DefaultNodeFinder(bpmnModelCache, mockEngine.getHistoryService(),
                        config.getExpressionManager()),
                bpmnModelCache, null, mockTaskRepo, mockHistoricRepo,
                Collections.singletonList(trackingCallback));
    }

    // ======================== 会签：全票通过后推进 ========================

    @Test
    void testCounterSignAllApprove() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        // 第 1 人同意
        Task task1 = createMockTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task1, createActiveAssignees(USER_ID, "userB", "userC"), 3L, 0L);

        flowablePlus.counterSign("task-001", true, new HashMap<>(), "同意");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(0);
        verify(mockTaskRepo).claim("task-001", USER_ID);

        // 第 2 人同意（不触发 onStart）
        Task task2 = createMockTask("task-002", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task2, createActiveAssignees(USER_ID, "userC"), 2L, 1L);

        flowablePlus.counterSign("task-002", true, null, "同意");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(2);

        // 第 3 人同意（触发 onFinish）
        Task task3 = createMockTask("task-003", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task3, createActiveAssignees(USER_ID), 0L, 2L);

        flowablePlus.counterSign("task-003", true, null, "同意");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(3);
        assertThat(onFinishCount.get()).isEqualTo(1);
    }

    // ======================== 或签：任一通过后推进 ========================

    @Test
    void testOrSignFirstApprovalCompletes() {
        BpmnModel model = buildMultiInstanceModel("${nrOfCompletedInstances >= 1}");
        when(mockRepoService.getBpmnModel("proc-os")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-os", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID, "userB", "userC"), 0L, 0L);

        flowablePlus.counterSign("task-001", true, null, "通过");

        assertThat(onStartCount.get()).isEqualTo(1);
        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(1);
    }

    @Test
    void testOrSignRejectionDoesNotFinish() {
        BpmnModel model = buildMultiInstanceModel("${nrOfCompletedInstances >= 1}");
        when(mockRepoService.getBpmnModel("proc-os")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-os", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID, "userB", "userC"), 3L, 0L);

        flowablePlus.counterSign("task-001", false, null, "驳回");

        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(0);
        verify(mockTaskRepo).addComment("task-001", null, "COUNTER_SIGN_REJECT", "驳回");
    }

    // ======================== 驳回：部分驳回流程不推进 ========================

    @Test
    void testCounterSignPartialRejection() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID, "userB", "userC"), 3L, 0L);

        flowablePlus.counterSign("task-001", false, null, "不同意");

        assertThat(onVoteCount.get()).isEqualTo(1);
        assertThat(onFinishCount.get()).isEqualTo(0);
    }

    @Test
    void testCounterSignOnStartNotFiredAgain() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);
        onStartCount.set(0);

        Task task1 = createMockTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task1, createActiveAssignees(USER_ID, "userB"), 3L, 0L);
        flowablePlus.counterSign("task-001", true, null, "同意");
        assertThat(onStartCount.get()).isEqualTo(1);

        Task task2 = createMockTask("task-002", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task2, createActiveAssignees(USER_ID), 1L, 1L);
        flowablePlus.counterSign("task-002", true, null, "同意");
        assertThat(onStartCount.get()).isEqualTo(1);
    }

    // ======================== 加签/减签 ========================

    @Test
    void testAddCounterSignerFiresCallback() {
        BpmnModel model = buildAddRemoveModel();
        when(mockRepoService.getBpmnModel("proc-sign")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-sign", "csTask", USER_ID);
        stubSignManageMocks(task, Collections.singletonList(task));

        flowablePlus.addCounterSigner("task-001", Collections.singletonList("newUser"));

        verify(mockRuntimeService).addMultiInstanceExecution(
                "csTask", "pi-001",
                new HashMap<String, Object>() {{ put("assignee", "newUser"); }});
        assertThat(onStartCount.get()).isEqualTo(1);
    }

    @Test
    void testAddCounterSignerAllDuplicateSkips() {
        BpmnModel model = buildAddRemoveModel();
        when(mockRepoService.getBpmnModel("proc-sign")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-sign", "csTask", USER_ID);
        stubSignManageMocks(task, Collections.singletonList(task));

        flowablePlus.addCounterSigner("task-001", Collections.singletonList(USER_ID));

        verify(mockRuntimeService, never()).addMultiInstanceExecution(
                anyString(), anyString(), any());
    }

    @Test
    void testRemoveCounterSignerCallsEngine() {
        BpmnModel model = buildAddRemoveModel();
        when(mockRepoService.getBpmnModel("proc-sign")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-sign", "csTask", USER_ID);
        Task targetTask = createMockTask("task-target", "pi-001", "proc-sign", "csTask", "user2");
        stubSignManageMocks(task, Arrays.asList(task, targetTask));
        when(mockTaskRepo.findActiveTask("pi-001", "csTask", "user2")).thenReturn(targetTask);

        flowablePlus.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution(
                targetTask.getExecutionId(), false);
    }

    // ======================== 错误路径 ========================

    @Test
    void testCounterSignOnNonMultiInstance() {
        BpmnModel model = buildSingleTaskModel();
        when(mockRepoService.getBpmnModel("proc-single")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-single", "task1", USER_ID);
        when(mockTaskRepo.findById("task-001")).thenReturn(task);

        assertThatThrownBy(() -> flowablePlus.counterSign("task-001", true, null, "同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
    }

    @Test
    void testCompleteTaskBlockedOnMultiInstance() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        Task task = createMockTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        when(mockTaskRepo.findById("task-001")).thenReturn(task);

        assertThatThrownBy(() -> flowablePlus.completeTask("task-001", null, "同意"))
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

        FlowablePlus fp = new FlowablePlus(mockEngine, userCtx,
                new DefaultNodeFinder(new DefaultBpmnModelCache(mockRepoService),
                        mockEngine.getHistoryService(), expressionManager),
                new DefaultBpmnModelCache(mockRepoService), null,
                mockTaskRepo, mockHistoricRepo,
                Collections.singletonList(failingCb));

        Task task = createMockTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        stubCounterSignMocks(task, createActiveAssignees(USER_ID), 0L, 0L);

        fp.counterSign("task-001", true, null, "同意");
        verify(mockTaskRepo).complete("task-001", null);
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

    private Task createMockTask(String taskId, String processInstanceId,
            String processDefinitionId, String activityId, String assignee) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(task.getProcessDefinitionId()).thenReturn(processDefinitionId);
        when(task.getTaskDefinitionKey()).thenReturn(activityId);
        when(task.getAssignee()).thenReturn(assignee);
        when(task.getExecutionId()).thenReturn("exec-" + taskId);
        return task;
    }

    private List<Task> createActiveAssignees(String... assignees) {
        List<Task> tasks = new ArrayList<>();
        for (String assignee : assignees) {
            Task task = mock(Task.class);
            when(task.getId()).thenReturn("task-" + assignee);
            when(task.getAssignee()).thenReturn(assignee);
            when(task.getProcessInstanceId()).thenReturn("pi-001");
            when(task.getTaskDefinitionKey()).thenReturn("csTask");
            when(task.getExecutionId()).thenReturn("exec-" + assignee);
            tasks.add(task);
        }
        return tasks;
    }

    private void stubCounterSignMocks(Task task, List<Task> activeList, long activeCount, long finishedCount) {
        when(mockTaskRepo.findById(task.getId())).thenReturn(task);
        when(mockTaskRepo.listActiveTasks(task.getProcessInstanceId(), task.getTaskDefinitionKey()))
                .thenReturn(activeList);
        when(mockTaskRepo.countActiveTasks(task.getProcessInstanceId(), task.getTaskDefinitionKey()))
                .thenReturn(activeCount);
        when(mockHistoricRepo.countFinishedTasks(anyString(), anyString(), anyString()))
                .thenReturn(finishedCount);
    }

    private void stubSignManageMocks(Task task, List<Task> allActiveList) {
        when(mockTaskRepo.findById(task.getId())).thenReturn(task);
        when(mockTaskRepo.listActiveTasks(anyString(), anyString())).thenReturn(allActiveList);
        when(mockTaskRepo.countActiveTasks(anyString(), anyString()))
                .thenReturn((long) allActiveList.size());
        when(mockHistoricRepo.countFinishedTasks(anyString(), anyString(), anyString())).thenReturn(0L);

        org.flowable.task.api.history.HistoricTaskInstance prevTask =
                mock(org.flowable.task.api.history.HistoricTaskInstance.class);
        when(prevTask.getAssignee()).thenReturn(USER_ID);
        when(mockHistoricRepo.findLatestFinishedTask(anyString(), anyString())).thenReturn(prevTask);

        // 减签权限校验回退到流程发起人（fallback）
        org.flowable.engine.history.HistoricProcessInstance historicPi =
                mock(org.flowable.engine.history.HistoricProcessInstance.class);
        when(historicPi.getStartUserId()).thenReturn(USER_ID);
        when(mockHistoricRepo.findProcessInstance(anyString())).thenReturn(historicPi);
    }
}
