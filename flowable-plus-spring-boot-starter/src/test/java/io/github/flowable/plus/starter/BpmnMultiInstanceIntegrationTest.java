package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.BpmnModelCache;
import io.github.flowable.plus.core.CounterSignWorkflow;
import io.github.flowable.plus.core.DefaultBpmnModelCache;
import io.github.flowable.plus.core.DefaultNodeFinder;
import io.github.flowable.plus.core.HistoricRepository;
import io.github.flowable.plus.core.NodeFinder;
import io.github.flowable.plus.core.PlusHistoricProcessInstance;
import io.github.flowable.plus.core.PlusHistoricTask;
import io.github.flowable.plus.core.PlusTask;
import io.github.flowable.plus.core.TaskRepository;
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
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
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
    private TaskRepository mockTaskRepo;
    private HistoricRepository mockHistoricRepo;
    private BpmnModelCache bpmnModelCache;
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
        mockTaskRepo = mock(TaskRepository.class);
        mockHistoricRepo = mock(HistoricRepository.class);

        bpmnModelCache = new DefaultBpmnModelCache(mockRepoService);
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

        counterSignWorkflow = new CounterSignWorkflow(userContext, mockTaskRepo,
                mockHistoricRepo, mockRuntimeService, bpmnModelCache, mockNodeFinder,
                Collections.singletonList(trackingCallback));

        taskWorkflow = new TaskWorkflow(userContext, mockTaskRepo, mockHistoricRepo,
                mockRuntimeService, mock(org.flowable.engine.IdentityService.class),
                mockNodeFinder, bpmnModelCache);
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
        verify(mockTaskRepo).claim("task-001", USER_ID);

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
        verify(mockTaskRepo).addComment("task-001", null, "COUNTER_SIGN_REJECT", "驳回");
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
        stubSignManageMocks(task, Arrays.asList(task, targetTask));
        when(mockTaskRepo.findActiveTask("pi-001", "csTask", "user2")).thenReturn(targetTask);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution(
                targetTask.getExecutionId(), false);
    }

    // ======================== 错误路径 ========================

    @Test
    void testCounterSignOnNonMultiInstance() {
        BpmnModel model = buildSingleTaskModel();
        when(mockRepoService.getBpmnModel("proc-single")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-single", "task1", USER_ID);
        when(mockTaskRepo.findById("task-001")).thenReturn(task);

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, "同意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
    }

    @Test
    void testCompleteTaskBlockedOnMultiInstance() {
        BpmnModel model = buildMultiInstanceModel(null);
        when(mockRepoService.getBpmnModel("proc-cs")).thenReturn(model);

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
        when(mockTaskRepo.findById("task-001")).thenReturn(task);

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

        CounterSignWorkflow fp = new CounterSignWorkflow(userCtx, mockTaskRepo,
                mockHistoricRepo, mockRuntimeService, bpmnModelCache, mockNodeFinder,
                Collections.singletonList(failingCb));

        PlusTask task = createPlusTask("task-001", "pi-001", "proc-cs", "csTask", USER_ID);
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

    private PlusTask createPlusTask(String taskId, String processInstanceId,
            String processDefinitionId, String activityId, String assignee) {
        return new PlusTask(taskId, processDefinitionId, activityId, processInstanceId,
                assignee, null, "exec-" + taskId, new Date());
    }

    private List<PlusTask> createActiveAssignees(String... assignees) {
        List<PlusTask> tasks = new ArrayList<>();
        for (String assignee : assignees) {
            tasks.add(new PlusTask("task-" + assignee, "proc-cs", "csTask", "pi-001",
                    assignee, null, "exec-" + assignee, new Date()));
        }
        return tasks;
    }

    private void stubCounterSignMocks(PlusTask task, List<PlusTask> activeList, long activeCount, long finishedCount) {
        when(mockTaskRepo.findById(task.getId())).thenReturn(task);
        when(mockTaskRepo.listActiveTasks(task.getProcessInstanceId(), task.getTaskDefinitionKey()))
                .thenReturn(activeList);
        when(mockTaskRepo.countActiveTasks(task.getProcessInstanceId(), task.getTaskDefinitionKey()))
                .thenReturn(activeCount);
        when(mockHistoricRepo.countFinishedTasks(anyString(), anyString(), anyString()))
                .thenReturn(finishedCount);
    }

    private void stubSignManageMocks(PlusTask task, List<PlusTask> allActiveList) {
        when(mockTaskRepo.findById(task.getId())).thenReturn(task);
        when(mockTaskRepo.listActiveTasks(anyString(), anyString())).thenReturn(allActiveList);
        when(mockTaskRepo.countActiveTasks(anyString(), anyString()))
                .thenReturn((long) allActiveList.size());
        when(mockHistoricRepo.countFinishedTasks(anyString(), anyString(), anyString())).thenReturn(0L);

        PlusHistoricTask prevTask = new PlusHistoricTask("ht-prev", task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getProcessInstanceId(), USER_ID,
                null, new Date(), new Date(), null);
        when(mockHistoricRepo.findLatestFinishedTask(anyString(), anyString())).thenReturn(prevTask);

        // 减签权限校验回退到流程发起人（fallback）
        PlusHistoricProcessInstance historicPi = new PlusHistoricProcessInstance(
                task.getProcessInstanceId(), null, task.getProcessDefinitionId(),
                null, null, USER_ID, new Date(), null, null);
        when(mockHistoricRepo.findProcessInstance(anyString())).thenReturn(historicPi);
    }
}
