package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
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
import java.util.function.Consumer;

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
 * CounterSignWorkflow 单元测试：覆盖会签投票、加签、减签的
 * 正常路径和所有异常路径。
 */
public class CounterSignWorkflowTest {

    private static final String USER_ID = "user1";

    private UserContext userContext;
    private TaskRepository mockTaskRepo;
    private HistoricRepository mockHistoricRepo;
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
        mockTaskRepo = mock(TaskRepository.class);
        mockHistoricRepo = mock(HistoricRepository.class);
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

        counterSignWorkflow = new CounterSignWorkflow(userContext, mockTaskRepo,
                mockHistoricRepo, mockRuntimeService, mockMultiInstanceDetector, mockNodeFinder,
                Collections.singletonList(trackingCallback));
    }

    // ======================== 会签：首次投票 ========================

    @Test
    void testCounterSignFirstVote() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        // 未投票过，活跃人数 2 人
        PlusTask assignee1 = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        PlusTask assignee2 = createTask("sub-2", definitionId, "csTask", "pi-001", "user2");
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Arrays.asList(assignee1, assignee2));
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", USER_ID)).thenReturn(0L);

        // 投票后未全部完成
        when(mockTaskRepo.countActiveTasks("pi-001", "csTask")).thenReturn(1L);

        counterSignWorkflow.counterSign("task-001", true, null, "同意");

        verify(mockTaskRepo).claim("task-001", USER_ID);
        verify(mockTaskRepo).addComment("task-001", null, "AGREE", "同意");
        verify(mockTaskRepo).complete("task-001", null);
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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        // 之前已投过票（hasVoted == true），不触发 onStart
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", USER_ID)).thenReturn(1L);

        // 只剩当前人活跃
        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));

        // 投票后全部完成
        when(mockTaskRepo.countActiveTasks("pi-001", "csTask")).thenReturn(0L);

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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        PlusTask assignee1 = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        PlusTask assignee2 = createTask("sub-2", definitionId, "csTask", "pi-001", "user2");
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Arrays.asList(assignee1, assignee2));
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", USER_ID)).thenReturn(0L);
        when(mockTaskRepo.countActiveTasks("pi-001", "csTask")).thenReturn(2L);

        counterSignWorkflow.counterSign("task-001", false, null, "不同意");

        verify(mockTaskRepo).addComment("task-001", null, "COUNTER_SIGN_REJECT", "不同意");
        verify(mockTaskRepo).complete("task-001", null);
    }

    // ======================== 会签：无评论 ========================

    @Test
    void testCounterSignWithoutComment() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", USER_ID)).thenReturn(0L);
        when(mockTaskRepo.countActiveTasks("pi-001", "csTask")).thenReturn(1L);

        counterSignWorkflow.counterSign("task-001", true, null, null);

        verify(mockTaskRepo, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    // ======================== 会签：带变量 ========================

    @Test
    void testCounterSignWithVariables() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExistsWithAssignee(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", USER_ID)).thenReturn(0L);
        when(mockTaskRepo.countActiveTasks("pi-001", "csTask")).thenReturn(1L);

        HashMap<String, Object> vars = new HashMap<>();
        vars.put("amount", 5000);

        counterSignWorkflow.counterSign("task-001", true, vars, null);

        verify(mockTaskRepo).complete("task-001", vars);
    }

    // ======================== 会签：错误路径 ========================

    @Test
    void testCounterSignRejectsNonMultiInstance() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

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
        when(mockTaskRepo.findById("task-001")).thenReturn(null);
        when(mockHistoricRepo.findTaskById("task-001"))
                .thenReturn(new PlusHistoricTask("task-001", "leave:1:abc", "csTask", "pi-001",
                        USER_ID, null, new Date(), new Date(), null));

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, null))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已完成");
    }

    // ======================== 加签 ========================

    @Test
    void testAddCounterSigner() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);
        stubCounterSignPermission(task);

        // 当前审批人列表
        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));

        counterSignWorkflow.addCounterSigner("task-001", Collections.singletonList("newUser"));

        verify(mockRuntimeService).addMultiInstanceExecution("csTask", "pi-001",
                new HashMap<String, Object>() {{ put("assignee", "newUser"); }});
        verify(mockTaskRepo).addComment(anyString(), eq("pi-001"), eq("ADD_SIGN"), anyString());
        assertThat(onStartCount.get()).isEqualTo(1);
    }

    @Test
    void testAddCounterSignerMultipleNewUsers() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);
        stubCounterSignPermission(task);

        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));

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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);
        stubCounterSignPermission(task);

        // 当前审批人包含 USER_ID
        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));

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

    @Test
    void testRemoveCounterSigner() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);
        stubCounterSignPermission(task);

        // user2 未投票
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", "user2")).thenReturn(0L);

        // 当前活跃人数 > 1（user2 + user3，减掉 user2 后还有 user3）
        PlusTask assignee1 = createTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        PlusTask assignee2 = createTask("sub-2", definitionId, "csTask", "pi-001", "user3");
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Arrays.asList(assignee1, assignee2));

        // 找到 user2 的子任务
        PlusTask targetTask = createTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        when(mockTaskRepo.findActiveTask("pi-001", "csTask", "user2")).thenReturn(targetTask);

        counterSignWorkflow.removeCounterSigner("task-001", "user2");

        verify(mockRuntimeService).deleteMultiInstanceExecution("exec-sub-1", false);
        verify(mockTaskRepo).addComment(anyString(), eq("pi-001"), eq("DELETE_SIGN"), anyString());
    }

    @Test
    void testRemoveCounterSignerRejectsAlreadyVoted() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);
        stubCounterSignPermission(task);

        // user2 已投票
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", "user2")).thenReturn(1L);

        assertThatThrownBy(() -> counterSignWorkflow.removeCounterSigner("task-001", "user2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已投票");
    }

    @Test
    void testRemoveCounterSignerRejectsInsufficientUnvoted() {
        String definitionId = "leave:1:abc";
        PlusTask task = createTask("task-001", definitionId, "csTask", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);
        stubCounterSignPermission(task);

        // 所有用户都未投票，但总共只有 1 人（user2），减签后为 0
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", "user2")).thenReturn(0L);

        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", "user2");
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));

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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        // 上一节点审批人是 otherUser，不是当前用户
        when(mockNodeFinder.findPreviousNodes(definitionId, "csTask", "pi-001"))
                .thenReturn(Collections.singletonList("prevTask"));
        PlusHistoricTask prevTask = new PlusHistoricTask(
                "ht-prev", definitionId, "prevTask", "pi-001",
                "otherUser", null, new Date(), new Date(), null);
        when(mockHistoricRepo.findLatestFinishedTask("pi-001", "prevTask")).thenReturn(prevTask);

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
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        PlusTask assignee = createTask("sub-1", definitionId, "csTask", "pi-001", USER_ID);
        when(mockTaskRepo.listActiveTasks("pi-001", "csTask")).thenReturn(Collections.singletonList(assignee));
        when(mockHistoricRepo.countFinishedTasks("pi-001", "csTask", USER_ID)).thenReturn(0L);
        when(mockTaskRepo.countActiveTasks("pi-001", "csTask")).thenReturn(1L);

        // 使用会抛出异常的回调
        CounterSignCallback failingCb = new CounterSignCallback() {
            @Override
            public void onStart(String pid, String tid, List<String> assignees) {
                throw new RuntimeException("模拟异常");
            }
        };
        CounterSignWorkflow fp = new CounterSignWorkflow(userContext, mockTaskRepo,
                mockHistoricRepo, mockRuntimeService, mockMultiInstanceDetector, mockNodeFinder,
                Collections.singletonList(failingCb));

        // 不应抛异常，应继续完成
        fp.counterSign("task-001", true, null, "同意");

        verify(mockTaskRepo).complete("task-001", null);
    }

    // ======================== 会签非多实例拒绝 ========================

    @Test
    void testCounterSignRejectsSingleInstanceTask() {
        PlusTask task = createTask("task-001", "leave:1:abc", "task1", "pi-001", USER_ID);
        stubTaskExists(task);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

        assertThatThrownBy(() -> counterSignWorkflow.counterSign("task-001", true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
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
        // assignee 就是自身，validateCurrentUserIsAssignee 不抛异常
    }

    private void stubCounterSignPermission(PlusTask task) {
        // 模拟当前用户就是上一节点审批人，拥有加签/减签权限
        when(mockNodeFinder.findPreviousNodes(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(),
                task.getProcessInstanceId()))
                .thenReturn(Collections.singletonList("prevTask"));

        PlusHistoricTask prevTask = new PlusHistoricTask(
                "ht-prev", task.getProcessDefinitionId(), "prevTask",
                task.getProcessInstanceId(), USER_ID, null, new Date(), new Date(), null);
        when(mockHistoricRepo.findLatestFinishedTask(
                task.getProcessInstanceId(), "prevTask")).thenReturn(prevTask);
    }
}
