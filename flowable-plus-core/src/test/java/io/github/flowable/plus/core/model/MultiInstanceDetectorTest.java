package io.github.flowable.plus.core.model;

import io.github.flowable.plus.core.domain.PlusTask;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MultiInstanceDetector 运行时判定（ADR-0034）单元测试。
 *
 * <p>覆盖 {@link MultiInstanceDetector#isRuntimeMultiInstance} /
 * {@link MultiInstanceDetector#isPseudoSingleton} 的伪单例/真多实例/最后 1 人未投
 * 三种运行时判据，以及模型判定短路（普通节点零查询）。</p>
 */
class MultiInstanceDetectorTest {

    private static final String PROCESS_DEF_ID = "leave:1:abc";
    private static final String PROCESS_INST_ID = "pi-001";
    private static final String MI_ACTIVITY_ID = "csTask";

    private BpmnModelCache mockBpmnModelCache;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private MultiInstanceDetector detector;

    @BeforeEach
    void setUp() {
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        detector = new MultiInstanceDetector(mockBpmnModelCache, mockTaskService, mockHistoryService);
    }

    // ======================== 构造函数空值校验 ========================

    @Test
    void testConstructorNullBpmnModelCache() {
        assertThatThrownBy(() -> new MultiInstanceDetector(null, mockTaskService, mockHistoryService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BpmnModelCache");
    }

    @Test
    void testConstructorNullTaskService() {
        assertThatThrownBy(() -> new MultiInstanceDetector(mockBpmnModelCache, null, mockHistoryService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskService");
    }

    @Test
    void testConstructorNullHistoryService() {
        assertThatThrownBy(() -> new MultiInstanceDetector(mockBpmnModelCache, mockTaskService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HistoryService");
    }

    // ======================== isRuntimeMultiInstance ========================

    @Test
    void testRuntimeMultiInstanceOnNormalNodeShortCircuitsWithoutQueries() {
        stubModel(false);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isRuntimeMultiInstance(task)).isFalse();

        // 普通节点模型短路：不产生任何运行时查询
        verify(mockTaskService, never()).createTaskQuery();
        verify(mockHistoryService, never()).createHistoricTaskInstanceQuery();
    }

    @Test
    void testRuntimeMultiInstancePseudoSingletonAllowed() {
        stubModel(true);
        stubActiveCount(1L);
        stubHistoryCount(1L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isRuntimeMultiInstance(task)).isFalse();
    }

    @Test
    void testRuntimeMultiInstanceRealMultiBlocked() {
        stubModel(true);
        stubActiveCount(2L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isRuntimeMultiInstance(task)).isTrue();
    }

    @Test
    void testRuntimeMultiInstanceLastUnvotedBlocked() {
        // 会签剩最后 1 人未投：活跃任务数==1，但历史任务数>1 → 非伪单例 → 运行时多实例
        stubModel(true);
        stubActiveCount(1L);
        stubHistoryCount(2L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isRuntimeMultiInstance(task)).isTrue();
    }

    @Test
    void testRuntimeMultiInstanceHistoryCountOneButActiveCountZeroBlocked() {
        // 活跃任务数为 0（异常态）→ 非伪单例 → 运行时多实例拦截（安全侧）
        stubModel(true);
        stubActiveCount(0L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isRuntimeMultiInstance(task)).isTrue();
    }

    // ======================== isPseudoSingleton ========================

    @Test
    void testPseudoSingletonActiveOneHistoryOne() {
        stubActiveCount(1L);
        stubHistoryCount(1L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isPseudoSingleton(task)).isTrue();
    }

    @Test
    void testPseudoSingletonActiveCountNotOne() {
        stubActiveCount(2L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isPseudoSingleton(task)).isFalse();
        // activeCount != 1 时短路，不查询历史
        verify(mockHistoryService, never()).createHistoricTaskInstanceQuery();
    }

    @Test
    void testPseudoSingletonHistoryCountNotOne() {
        // 会签剩最后 1 人未投（他人已完成）或减签后 1 人 → 历史任务数 > 1 → 非伪单例
        stubActiveCount(1L);
        stubHistoryCount(2L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isPseudoSingleton(task)).isFalse();
    }

    // ======================== isInitiatorDecisionTask（ADR-0035） ========================

    @Test
    void testInitiatorDecisionTaskOnNormalNodeShortCircuitsWithoutQueries() {
        stubModel(false);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isInitiatorDecisionTask(task)).isFalse();

        // 普通节点模型短路：不产生任何运行时查询
        verify(mockTaskService, never()).createTaskQuery();
        verify(mockHistoryService, never()).createHistoricTaskInstanceQuery();
    }

    @Test
    void testInitiatorDecisionTaskActiveCountNotOne() {
        stubModel(true);
        stubActiveCount(2L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isInitiatorDecisionTask(task)).isFalse();

        // activeCount != 1 时短路，不查询历史、不读变量
        verify(mockHistoryService, never()).createHistoricTaskInstanceQuery();
        verify(mockTaskService, never()).getVariable(anyString(), anyString());
    }

    @Test
    void testInitiatorDecisionTaskHistoryCountOneNotRecognized() {
        // 伪单例（活跃 1 人、历史 1 人）→ 不是折返决策任务，不读变量
        stubModel(true);
        stubActiveCount(1L);
        stubHistoryCount(1L);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isInitiatorDecisionTask(task)).isFalse();

        verify(mockTaskService, never()).getVariable(anyString(), anyString());
    }

    @Test
    void testInitiatorDecisionTaskMissingVariableNotRecognized() {
        // 折返后 1 人持任务但无 countersignInitiator 变量（模式B/未加签）→ 不识别，保持拦截
        stubModel(true);
        stubActiveCount(1L);
        stubHistoryCount(2L);
        when(mockTaskService.getVariable("task-001", "countersignInitiator_csTask")).thenReturn(null);
        PlusTask task = createTask(MI_ACTIVITY_ID);

        assertThat(detector.isInitiatorDecisionTask(task)).isFalse();
    }

    @Test
    void testInitiatorDecisionTaskAssigneeMismatchNotRecognized() {
        // "会签剩最后 1 人未投"：assignee 是投票人而非发起人 → 不识别，保持拦截
        stubModel(true);
        stubActiveCount(1L);
        stubHistoryCount(2L);
        when(mockTaskService.getVariable("task-001", "countersignInitiator_csTask")).thenReturn("initiator");
        PlusTask task = createTask(MI_ACTIVITY_ID); // assignee = "user1" ≠ initiator

        assertThat(detector.isInitiatorDecisionTask(task)).isFalse();
    }

    @Test
    void testInitiatorDecisionTaskRecognized() {
        // 折返后发起人单持 MI 决策任务：assignee == countersignInitiator_<key> 变量 → 识别
        stubModel(true);
        stubActiveCount(1L);
        stubHistoryCount(3L);
        when(mockTaskService.getVariable("task-001", "countersignInitiator_csTask")).thenReturn("user1");
        PlusTask task = createTask(MI_ACTIVITY_ID); // assignee = "user1"

        assertThat(detector.isInitiatorDecisionTask(task)).isTrue();
    }

    // ======================== isRuntimeMultiInstanceNode（ADR-0040 重定向口径） ========================

    @Test
    void testRuntimeMultiInstanceNodeOnNormalNodeShortCircuitsWithoutQueries() {
        stubModel(false);

        assertThat(detector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).isFalse();

        // 普通节点模型短路：不产生任何运行时查询
        verify(mockTaskService, never()).createTaskQuery();
        verify(mockHistoryService, never()).createHistoricTaskInstanceQuery();
    }

    @Test
    void testRuntimeMultiInstanceNodeHistoryCountOneIsRuntimeSingleton() {
        // 全局历史 == 1（伪单例/单实例运行）：非运行时多实例 → 回退直连放行
        stubModel(true);
        stubHistoryCount(1L);

        assertThat(detector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).isFalse();
    }

    @Test
    void testRuntimeMultiInstanceNodeHistoryCountZeroIsRuntimeSingleton() {
        // 节点无历史记录（AutoRedirect direct 放行路径）
        stubModel(true);
        stubHistoryCount(0L);

        assertThat(detector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).isFalse();
    }

    @Test
    void testRuntimeMultiInstanceNodeHistoryCountGreaterThanOne() {
        stubModel(true);
        stubHistoryCount(2L);

        assertThat(detector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).isTrue();
    }

    @Test
    void testRuntimeMultiInstanceNodeIgnoresActiveCount() {
        // 口径分叉点（ADR-0040）：重定向判定只看全局历史数，不看活跃数——
        // 即使活跃为 0（与拦截口径 isRuntimeMultiInstance 的保守行为不同），
        // 历史 > 1 仍判运行时多实例；且全程不发起活跃任务查询
        stubModel(true);
        stubHistoryCount(2L);

        assertThat(detector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).isTrue();

        verify(mockTaskService, never()).createTaskQuery();
    }

    // ======================== Helpers ========================

    private void stubModel(boolean multiInstance) {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("testProcess");
        model.addProcess(process);

        Activity activity = new UserTask();
        activity.setId(MI_ACTIVITY_ID);
        if (multiInstance) {
            activity.setLoopCharacteristics(new MultiInstanceLoopCharacteristics());
        }
        process.addFlowElement(activity);

        when(mockBpmnModelCache.getBpmnModel(PROCESS_DEF_ID)).thenReturn(model);
    }

    private void stubActiveCount(long count) {
        TaskQuery query = mock(TaskQuery.class);
        when(query.processInstanceId(PROCESS_INST_ID)).thenReturn(query);
        when(query.taskDefinitionKey(MI_ACTIVITY_ID)).thenReturn(query);
        when(query.active()).thenReturn(query);
        when(query.count()).thenReturn(count);
        when(mockTaskService.createTaskQuery()).thenReturn(query);
    }

    private void stubHistoryCount(long count) {
        HistoricTaskInstanceQuery query = mock(HistoricTaskInstanceQuery.class);
        when(query.processInstanceId(PROCESS_INST_ID)).thenReturn(query);
        when(query.taskDefinitionKey(MI_ACTIVITY_ID)).thenReturn(query);
        when(query.count()).thenReturn(count);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(query);
    }

    private PlusTask createTask(String taskDefinitionKey) {
        return new PlusTask("task-001", PROCESS_DEF_ID, taskDefinitionKey, PROCESS_INST_ID,
                "user1", null, "测试任务", "exec-001", new Date());
    }
}
