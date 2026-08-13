package io.github.flowable.plus.core.model;

import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CountersignRoundResolver 单元测试（C1 会签轮次状态机收敛，2026-08-13）。
 *
 * <p>对准 5 个公共查询接口 + package-private {@code resolveCycleBoundary}，
 * 参照 {@code CounterSignWorkflowTest} 的 mock 服务模式。覆盖：多实例结束判定、
 * 轮次索引解析（下一轮/当前轮/降级）、已投票人解析（周期限定 + 剔除被删除任务）、
 * 轮次映射查询、执行周期边界切分。</p>
 *
 * <p><b>stub 注意</b>：所有 mock helper 必须<b>先构造、后 {@code when(...)}</b>，
 * 不得在 {@code thenReturn(...)} 参数内嵌套调用会执行 {@code when()} 的 helper
 * （Mockito 严格 stub 状态机不支持嵌套）。</p>
 */
class CountersignRoundResolverTest {

    private static final String PROCESS_INST_ID = "pi-001";
    private static final String ACTIVITY_ID = "csTask";

    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private CountersignRoundResolver resolver;

    @BeforeEach
    void setUp() {
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        resolver = new CountersignRoundResolver(mockHistoryService, mockTaskService);
    }

    // ======================== 构造函数空值校验 ========================

    @Test
    void testConstructorNullHistoryService() {
        assertThatThrownBy(() -> new CountersignRoundResolver(null, mockTaskService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HistoryService");
    }

    @Test
    void testConstructorNullTaskService() {
        assertThatThrownBy(() -> new CountersignRoundResolver(mockHistoryService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskService");
    }

    // ======================== isRoundFinished ========================

    @Test
    void testIsRoundFinishedNoActiveTasks() {
        TaskQuery activeCount = stubActiveCount(0);
        when(mockTaskService.createTaskQuery()).thenReturn(activeCount);

        assertThat(resolver.isRoundFinished(PROCESS_INST_ID, ACTIVITY_ID, "task-001")).isTrue();
    }

    @Test
    void testIsRoundFinishedSingleActiveNoFinished() {
        // activeCount=1, finishedCount=0 → 伪单例/未投票 → 未结束
        TaskQuery activeCount = stubActiveCount(1);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery finishedQ = stubFinishedList(Collections.emptyList());
        when(mockTaskService.createTaskQuery()).thenReturn(activeCount);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, finishedQ);

        assertThat(resolver.isRoundFinished(PROCESS_INST_ID, ACTIVITY_ID, "task-001")).isFalse();
    }

    @Test
    void testIsRoundFinishedSingleActiveSoleIsSelf() {
        // activeCount=1, finishedCount>0, 唯一活跃任务==操作者自己（加签场景）→ 未结束
        HistoricTaskInstance finished = createHistoricTask("h-1", "userA", 100L, 200L, null, 0);
        Task sole = createMockTask("task-001", "user1");
        TaskQuery activeCount = stubActiveCount(1);
        TaskQuery soleQuery = stubActiveSingle(sole);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery finishedQ = stubFinishedList(Collections.singletonList(finished));
        when(mockTaskService.createTaskQuery()).thenReturn(activeCount, soleQuery);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, finishedQ);

        assertThat(resolver.isRoundFinished(PROCESS_INST_ID, ACTIVITY_ID, "task-001")).isFalse();
    }

    @Test
    void testIsRoundFinishedSingleActiveSoleNotSelf() {
        // activeCount=1, finishedCount>0, 唯一活跃任务不是操作者自己（counterSign 场景）→ 未结束
        HistoricTaskInstance finished = createHistoricTask("h-1", "userA", 100L, 200L, null, 0);
        Task sole = createMockTask("task-other", "userB");
        TaskQuery activeCount = stubActiveCount(1);
        TaskQuery soleQuery = stubActiveSingle(sole);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery finishedQ = stubFinishedList(Collections.singletonList(finished));
        when(mockTaskService.createTaskQuery()).thenReturn(activeCount, soleQuery);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, finishedQ);

        assertThat(resolver.isRoundFinished(PROCESS_INST_ID, ACTIVITY_ID, "task-001")).isFalse();
    }

    @Test
    void testIsRoundFinishedSingleActiveSoleNull() {
        // activeCount=1, finishedCount>0, 唯一活跃任务查询返回 null（数据异常兜底）→ 未结束
        HistoricTaskInstance finished = createHistoricTask("h-1", "userA", 100L, 200L, null, 0);
        TaskQuery activeCount = stubActiveCount(1);
        TaskQuery soleQuery = stubActiveSingle(null);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery finishedQ = stubFinishedList(Collections.singletonList(finished));
        when(mockTaskService.createTaskQuery()).thenReturn(activeCount, soleQuery);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, finishedQ);

        assertThat(resolver.isRoundFinished(PROCESS_INST_ID, ACTIVITY_ID, "task-001")).isFalse();
    }

    @Test
    void testIsRoundFinishedMultipleActiveTasks() {
        TaskQuery activeCount = stubActiveCount(2);
        when(mockTaskService.createTaskQuery()).thenReturn(activeCount);

        assertThat(resolver.isRoundFinished(PROCESS_INST_ID, ACTIVITY_ID, "task-001")).isFalse();
    }

    // ======================== nextRoundIndex ========================

    @Test
    void testNextRoundIndexNoHistoryReturnsOne() {
        // 无历史任务 → taskIds 空 → 1
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery keyListQ = stubKeyTaskList(Collections.emptyList());
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyListQ);

        assertThat(resolver.nextRoundIndex(PROCESS_INST_ID, ACTIVITY_ID)).isEqualTo(1);
    }

    @Test
    void testNextRoundIndexMaxPlusOne() {
        // 历史存在 csRoundIndex=2 → next = 3
        HistoricTaskInstance task1 = createHistoricTask("t-1", "userA", 100L, 200L, null, null);
        HistoricVariableInstance var1 = createHistoricVar("t-1", 2);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery keyListQ = stubKeyTaskList(Collections.singletonList(task1));
        HistoricVariableInstanceQuery varQ = stubRoundIndexVars(Collections.singletonList(var1));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyListQ);
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);

        assertThat(resolver.nextRoundIndex(PROCESS_INST_ID, ACTIVITY_ID)).isEqualTo(3);
    }

    @Test
    void testNextRoundIndexZeroRoundReturnsOne() {
        // 历史 csRoundIndex=0 → maxRound=0 → 1（非 0+1）
        HistoricTaskInstance task1 = createHistoricTask("t-1", "userA", 100L, 200L, null, null);
        HistoricVariableInstance var1 = createHistoricVar("t-1", 0);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery keyListQ = stubKeyTaskList(Collections.singletonList(task1));
        HistoricVariableInstanceQuery varQ = stubRoundIndexVars(Collections.singletonList(var1));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyListQ);
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);

        assertThat(resolver.nextRoundIndex(PROCESS_INST_ID, ACTIVITY_ID)).isEqualTo(1);
    }

    @Test
    void testNextRoundIndexCycleLimited() {
        // 折返后周期限定：上一周期 csRoundIndex=2 被排除，本周期无标 → next=1
        // 历史时间线: 上一周期 csTask(100) → confirmTask(200) → 本周期 csTask(300)
        HistoricTaskInstance prevCs = createHistoricTask("t-prev", "userA", 100L, 200L, null, null);
        HistoricTaskInstance confirm = createHistoricTask("t-confirm", "userB", 200L, 300L, null, null);
        HistoricTaskInstance curCs = createHistoricTask("t-cur", "userC", 300L, null, null, null);
        when(confirm.getTaskDefinitionKey()).thenReturn("confirmTask");
        HistoricTaskInstance[] timeline = {prevCs, confirm, curCs};
        HistoricVariableInstance prevVar = createHistoricVar("t-prev", 2);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Arrays.asList(timeline));
        // 本节点历史任务查询按 taskDefinitionKey 过滤，不含 confirmTask（stub 保真）
        HistoricTaskInstanceQuery keyListQ = stubKeyTaskList(Arrays.asList(prevCs, curCs));
        HistoricVariableInstanceQuery varQ = stubRoundIndexVars(Collections.singletonList(prevVar));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyListQ);
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);

        assertThat(resolver.nextRoundIndex(PROCESS_INST_ID, ACTIVITY_ID)).isEqualTo(1);
    }

    // ======================== currentRoundIndex ========================

    @Test
    void testCurrentRoundIndexFromActiveTaskVar() {
        // 活跃任务带 csRoundIndex=0 → 直接返回 0
        Task active = createMockTask("t-1", "user1");
        TaskQuery activeList = stubActiveList(Collections.singletonList(active));
        when(mockTaskService.createTaskQuery()).thenReturn(activeList);
        when(mockTaskService.getVariableLocal("t-1", CountersignRoundResolver.CS_ROUND_INDEX_VAR)).thenReturn(0);

        assertThat(resolver.currentRoundIndex(PROCESS_INST_ID, ACTIVITY_ID)).isEqualTo(0);
    }

    @Test
    void testCurrentRoundIndexFallbackToNextMinusOne() {
        // 活跃任务无 csRoundIndex → 降级 nextRoundIndex - 1 = 0
        Task active = createMockTask("t-1", "user1");
        TaskQuery activeList = stubActiveList(Collections.singletonList(active));
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery keyListQ = stubKeyTaskList(Collections.emptyList());
        when(mockTaskService.createTaskQuery()).thenReturn(activeList);
        when(mockTaskService.getVariableLocal("t-1", CountersignRoundResolver.CS_ROUND_INDEX_VAR)).thenReturn(null);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, keyListQ);

        assertThat(resolver.currentRoundIndex(PROCESS_INST_ID, ACTIVITY_ID)).isEqualTo(0);
    }

    // ======================== votedAssigneesInRound ========================

    @Test
    void testVotedAssigneesInRoundPositiveRound() {
        // roundIndex=1: 仅匹配 csRoundIndex==1 且 deleteReason 为 null 的任务
        HistoricTaskInstance t1 = createHistoricTask("t-1", "userA", 100L, 200L, null, 1);
        HistoricTaskInstance t2 = createHistoricTask("t-2", "userB", 100L, 200L, null, 0);
        HistoricTaskInstance t3 = createHistoricTask("t-3", "userC", 100L, 200L, "deleted", 1);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery votedQ = stubVotedList(Arrays.asList(t1, t2, t3));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ);

        Set<String> voted = resolver.votedAssigneesInRound(PROCESS_INST_ID, ACTIVITY_ID, 1);

        assertThat(voted).containsExactly("userA");
    }

    @Test
    void testVotedAssigneesInRoundRoundZeroImplicit() {
        // roundIndex=0: 无标任务与 csRoundIndex==0 均视为隐式轮次 0
        HistoricTaskInstance t1 = createHistoricTask("t-1", "userA", 100L, 200L, null, null);
        HistoricTaskInstance t2 = createHistoricTask("t-2", "userB", 100L, 200L, null, 0);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery votedQ = stubVotedList(Arrays.asList(t1, t2));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ);

        Set<String> voted = resolver.votedAssigneesInRound(PROCESS_INST_ID, ACTIVITY_ID, 0);

        assertThat(voted).containsExactlyInAnyOrder("userA", "userB");
    }

    @Test
    void testVotedAssigneesInRoundCycleLimited() {
        // 漏洞 B 回归：上一周期已投票人（csRoundIndex 撞号）按 startTime 限定周期后不参与匹配
        HistoricTaskInstance prevVoter = createHistoricTask("t-prev", "userA", 100L, 200L, null, 0);
        HistoricTaskInstance confirm = createHistoricTask("t-confirm", "userB", 200L, 300L, null, null);
        HistoricTaskInstance curVoter = createHistoricTask("t-cur", "userC", 300L, 400L, null, 0);
        when(confirm.getTaskDefinitionKey()).thenReturn("confirmTask");
        HistoricTaskInstance[] timeline = {prevVoter, confirm, curVoter};
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Arrays.asList(timeline));
        HistoricTaskInstanceQuery votedQ = stubVotedList(Arrays.asList(prevVoter, curVoter));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ);

        Set<String> voted = resolver.votedAssigneesInRound(PROCESS_INST_ID, ACTIVITY_ID, 0);

        assertThat(voted).containsExactly("userC");
    }

    @Test
    void testVotedAssigneesInRoundNullAssigneeFiltered() {
        HistoricTaskInstance t1 = createHistoricTask("t-1", null, 100L, 200L, null, 0);
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        HistoricTaskInstanceQuery votedQ = stubVotedList(Collections.singletonList(t1));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ, votedQ);

        assertThat(resolver.votedAssigneesInRound(PROCESS_INST_ID, ACTIVITY_ID, 0)).isEmpty();
    }

    // ======================== roundIndexByTaskId ========================

    @Test
    void testRoundIndexByTaskId() {
        HistoricVariableInstance v1 = createHistoricVar("t-1", 0);
        HistoricVariableInstance v2 = createHistoricVar("t-2", 1);
        HistoricVariableInstanceQuery varQ = stubRoundIndexVars(Arrays.asList(v1, v2));
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);

        Map<String, Integer> roundByTaskId = resolver.roundIndexByTaskId(PROCESS_INST_ID);

        assertThat(roundByTaskId).containsEntry("t-1", 0).containsEntry("t-2", 1);
    }

    @Test
    void testRoundIndexByTaskIdFiltersInvalid() {
        // taskId 为 null 或值非 Integer 的变量被过滤
        HistoricVariableInstance v1 = createHistoricVar("t-1", "abc");
        HistoricVariableInstance v2 = createHistoricVar(null, 0);
        HistoricVariableInstance v3 = createHistoricVar("t-3", 2);
        HistoricVariableInstanceQuery varQ = stubRoundIndexVars(Arrays.asList(v1, v2, v3));
        when(mockHistoryService.createHistoricVariableInstanceQuery()).thenReturn(varQ);

        Map<String, Integer> roundByTaskId = resolver.roundIndexByTaskId(PROCESS_INST_ID);

        assertThat(roundByTaskId).containsExactly(entry("t-3", 2));
    }

    // ======================== resolveCycleBoundary ========================

    @Test
    void testResolveCycleBoundaryNoTasks() {
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Collections.emptyList());
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ);

        assertThat(resolver.resolveCycleBoundary(PROCESS_INST_ID, ACTIVITY_ID)).isNull();
    }

    @Test
    void testResolveCycleBoundaryLastRunEarliestStart() {
        // 时间线: csTask(100) → confirmTask(200) → csTask(300) → csTask(400)
        // 本周期 run = csTask(300~400)，边界 = 300
        HistoricTaskInstance cs1 = createHistoricTask("t-1", "userA", 100L, 200L, null, null);
        HistoricTaskInstance confirm = createHistoricTask("t-confirm", "userB", 200L, 300L, null, null);
        HistoricTaskInstance cs2 = createHistoricTask("t-2", "userC", 300L, 400L, null, null);
        HistoricTaskInstance cs3 = createHistoricTask("t-3", "userD", 400L, 500L, null, null);
        when(confirm.getTaskDefinitionKey()).thenReturn("confirmTask");
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Arrays.asList(cs1, confirm, cs2, cs3));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ);

        Date boundary = resolver.resolveCycleBoundary(PROCESS_INST_ID, ACTIVITY_ID);

        assertThat(boundary).isEqualTo(new Date(300L));
    }

    @Test
    void testResolveCycleBoundaryNullStartTimeSkipped() {
        // startTime 为 null 的历史任务被跳过，不污染边界
        HistoricTaskInstance nullStart = createHistoricTask("t-null", "userA", null, 200L, null, null);
        HistoricTaskInstance confirm = createHistoricTask("t-confirm", "userB", 200L, 300L, null, null);
        HistoricTaskInstance cs = createHistoricTask("t-cs", "userC", 300L, 400L, null, null);
        when(confirm.getTaskDefinitionKey()).thenReturn("confirmTask");
        HistoricTaskInstanceQuery boundaryQ = stubBoundaryQuery(Arrays.asList(nullStart, confirm, cs));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(boundaryQ);

        Date boundary = resolver.resolveCycleBoundary(PROCESS_INST_ID, ACTIVITY_ID);

        assertThat(boundary).isEqualTo(new Date(300L));
    }

    // ======================== Test Helpers ========================

    private TaskQuery stubActiveCount(long count) {
        TaskQuery q = mock(TaskQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.active()).thenReturn(q);
        when(q.count()).thenReturn(count);
        return q;
    }

    private TaskQuery stubActiveList(List<Task> tasks) {
        TaskQuery q = mock(TaskQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.active()).thenReturn(q);
        when(q.list()).thenReturn(tasks);
        return q;
    }

    private TaskQuery stubActiveSingle(Task sole) {
        TaskQuery q = mock(TaskQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.active()).thenReturn(q);
        when(q.singleResult()).thenReturn(sole);
        return q;
    }

    /** 周期边界查询 mock：按开始时间升序返回完整历史时间线 */
    private HistoricTaskInstanceQuery stubBoundaryQuery(List<HistoricTaskInstance> tasks) {
        HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.orderByHistoricTaskInstanceStartTime()).thenReturn(q);
        when(q.asc()).thenReturn(q);
        when(q.list()).thenReturn(tasks);
        return q;
    }

    /** 本节点历史任务查询 mock（无 finished 过滤） */
    private HistoricTaskInstanceQuery stubKeyTaskList(List<HistoricTaskInstance> tasks) {
        HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.list()).thenReturn(tasks);
        return q;
    }

    /** 当前周期已完成任务查询 mock（finished 过滤，无局部变量） */
    private HistoricTaskInstanceQuery stubFinishedList(List<HistoricTaskInstance> tasks) {
        HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.finished()).thenReturn(q);
        when(q.list()).thenReturn(tasks);
        return q;
    }

    /** 已投票人解析查询 mock（finished + includeTaskLocalVariables） */
    private HistoricTaskInstanceQuery stubVotedList(List<HistoricTaskInstance> tasks) {
        HistoricTaskInstanceQuery q = mock(HistoricTaskInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.taskDefinitionKey(anyString())).thenReturn(q);
        when(q.finished()).thenReturn(q);
        when(q.includeTaskLocalVariables()).thenReturn(q);
        when(q.list()).thenReturn(tasks);
        return q;
    }

    /** csRoundIndex 变量查询 mock */
    private HistoricVariableInstanceQuery stubRoundIndexVars(List<HistoricVariableInstance> vars) {
        HistoricVariableInstanceQuery q = mock(HistoricVariableInstanceQuery.class);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.variableName(CountersignRoundResolver.CS_ROUND_INDEX_VAR)).thenReturn(q);
        when(q.list()).thenReturn(vars);
        return q;
    }

    private Task createMockTask(String id, String assignee) {
        Task t = mock(Task.class);
        when(t.getId()).thenReturn(id);
        when(t.getAssignee()).thenReturn(assignee);
        return t;
    }

    /** 创建带 csRoundIndex 任务局部变量的历史任务（roundIndex 为 null 表示无标） */
    private HistoricTaskInstance createHistoricTask(String id, String assignee, Long startTime,
                                                    Long endTime, String deleteReason, Integer roundIndex) {
        HistoricTaskInstance t = mock(HistoricTaskInstance.class);
        when(t.getId()).thenReturn(id);
        when(t.getTaskDefinitionKey()).thenReturn(ACTIVITY_ID);
        when(t.getAssignee()).thenReturn(assignee);
        when(t.getStartTime()).thenReturn(startTime != null ? new Date(startTime) : null);
        when(t.getEndTime()).thenReturn(endTime != null ? new Date(endTime) : null);
        when(t.getDeleteReason()).thenReturn(deleteReason);
        Map<String, Object> locals = new HashMap<>();
        if (roundIndex != null) {
            locals.put(CountersignRoundResolver.CS_ROUND_INDEX_VAR, roundIndex);
        }
        when(t.getTaskLocalVariables()).thenReturn(locals);
        return t;
    }

    private HistoricVariableInstance createHistoricVar(String taskId, Object value) {
        HistoricVariableInstance v = mock(HistoricVariableInstance.class);
        when(v.getTaskId()).thenReturn(taskId);
        when(v.getValue()).thenReturn(value);
        return v;
    }

    private static Map.Entry<String, Integer> entry(String key, Integer value) {
        return new java.util.AbstractMap.SimpleEntry<>(key, value);
    }
}
