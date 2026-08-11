package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.vo.RollbackResult;
import org.flowable.engine.HistoryService;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AutoRedirectCountersignRollbackStrategy 单元测试。
 * 覆盖三步行为矩阵及边界情况。
 */
public class AutoRedirectCountersignRollbackStrategyTest {

    private static final String PROCESS_DEF_ID = "leave:1:abc";
    private static final String PROCESS_INST_ID = "pi-001";
    private static final String MI_ACTIVITY_ID = "csTask";
    private static final String PREDECESSOR_ID = "task1";

    private NodeFinder mockNodeFinder;
    private HistoryService mockHistoryService;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private AutoRedirectCountersignRollbackStrategy strategy;

    @BeforeEach
    void setUp() {
        mockNodeFinder = mock(NodeFinder.class);
        mockHistoryService = mock(HistoryService.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        strategy = new AutoRedirectCountersignRollbackStrategy(
                mockNodeFinder, mockHistoryService, mockMultiInstanceDetector);
    }

    // ======================== 运行时单例 → 直接放行 ========================

    @Test
    void testRuntimeSingleDirectPass() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        // count = 0（目标节点从未执行过）
        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 0L);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).isNull();
        assertThat(result.getNewAssigneeList()).isNull();
    }

    @Test
    void testRuntimeSingleCountOneDirectPass() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        // count = 1（只有一个人执行过）
        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 1L);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).isNull();
    }

    // ======================== 运行时多实例 + 有前置 → 重定向 ========================

    @Test
    void testRuntimeMultiWithPredecessorRedirects() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        // count > 1（真正的多实例）
        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 3L);

        // 有唯一前置单例节点
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn("前置准备节点");

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(PREDECESSOR_ID);
        assertThat(result.getRedirectMessage())
                .contains("会签节点")
                .contains("多人会签")
                .contains("自动重定向")
                .contains("前置准备节点");
        assertThat(result.getNewAssigneeList()).isNull();
    }

    @Test
    void testRedirectMessageContainsFallbackNames() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 5L);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));
        // nodeName 为 null 时使用 nodeId 作为 fallback
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID)).thenReturn(null);
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, PREDECESSOR_ID)).thenReturn(null);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getRedirectMessage()).contains(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).contains(PREDECESSOR_ID);
    }

    // ======================== 运行时多实例 + 无前置 → 拦截 ========================

    @Test
    void testRuntimeMultiWithoutPredecessorThrows() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 3L);

        // 无前置节点
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");

        assertThatThrownBy(() -> strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("多实例（会签）节点")
                .hasMessageContaining("rejectTaskToInitiator")
                .hasMessageContaining("auto-rebuild");
    }

    @Test
    void testRuntimeMultiWithMultiplePredecessorsThrows() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 3L);

        // 多个前置节点（都不是 MI）
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "taskA"))
                .thenReturn(false);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "taskB"))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Arrays.asList("taskA", "taskB"));
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");

        assertThatThrownBy(() -> strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("不存在唯一前置单例准备节点");
    }

    @Test
    void testRuntimeMultiWithAllMIPredecessorsThrows() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 3L);

        // 前置节点全是 MI 节点，过滤后为空
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "miNode1"))
                .thenReturn(true);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList("miNode1"));
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");

        assertThatThrownBy(() -> strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("不存在唯一前置单例准备节点");
    }

    // ======================== 错误消息增强：含 rejectTaskToInitiator 引导 ========================

    @Test
    void testBlockedErrorMessageContainsGuidance() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 3L);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("领导会签");

        assertThatThrownBy(() -> strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("领导会签")
                .hasMessageContaining("多实例（会签）节点")
                .hasMessageContaining("rejectTaskToInitiator")
                .hasMessageContaining("auto-rebuild");
    }

    @Test
    void testBlockedErrorMessageWithoutNodeName() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubHistoryCount(PROCESS_INST_ID, MI_ACTIVITY_ID, 3L);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID)).thenReturn(null);

        assertThatThrownBy(() -> strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("csTask");
    }

    // ======================== 构造函数空值校验 ========================

    @Test
    void testConstructorNullNodeFinder() {
        assertThatThrownBy(() -> new AutoRedirectCountersignRollbackStrategy(
                null, mockHistoryService, mockMultiInstanceDetector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder");
    }

    @Test
    void testConstructorNullHistoryService() {
        assertThatThrownBy(() -> new AutoRedirectCountersignRollbackStrategy(
                mockNodeFinder, null, mockMultiInstanceDetector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HistoryService");
    }

    @Test
    void testConstructorNullMultiInstanceDetector() {
        assertThatThrownBy(() -> new AutoRedirectCountersignRollbackStrategy(
                mockNodeFinder, mockHistoryService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MultiInstanceDetector");
    }

    // ======================== 静态方法 resolveMultiInstancePredecessor ========================

    @Test
    void testResolveMultiInstancePredecessorSingle() {
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));

        String result = CountersignRollbackStrategies.resolveMultiInstancePredecessor(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector);

        assertThat(result).isEqualTo(PREDECESSOR_ID);
    }

    @Test
    void testResolveMultiInstancePredecessorMultiple() {
        when(mockMultiInstanceDetector.isMultiInstanceNode(anyString(), anyString()))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Arrays.asList("taskA", "taskB"));

        String result = CountersignRollbackStrategies.resolveMultiInstancePredecessor(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector);

        assertThat(result).isNull();
    }

    @Test
    void testResolveMultiInstancePredecessorEmpty() {
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.emptyList());

        String result = CountersignRollbackStrategies.resolveMultiInstancePredecessor(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector);

        assertThat(result).isNull();
    }

    @Test
    void testResolveMultiInstancePredecessorFiltersMiPreds() {
        // 前置中有 MI 节点需过滤
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "miNode"))
                .thenReturn(true);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Arrays.asList("miNode", PREDECESSOR_ID));

        String result = CountersignRollbackStrategies.resolveMultiInstancePredecessor(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector);

        assertThat(result).isEqualTo(PREDECESSOR_ID);
    }

    // ======================== Helpers ========================

    private PlusTask createTask(String taskId, String processDefinitionId,
            String taskDefinitionKey, String processInstanceId) {
        return new PlusTask(taskId, processDefinitionId, taskDefinitionKey, processInstanceId,
                "user1", null, "测试任务", "exec-" + taskId, new Date());
    }

    private void stubHistoryCount(String processInstanceId, String taskDefinitionKey, long count) {
        HistoricTaskInstanceQuery histQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histQuery);
        when(histQuery.processInstanceId(processInstanceId)).thenReturn(histQuery);
        when(histQuery.taskDefinitionKey(taskDefinitionKey)).thenReturn(histQuery);
        when(histQuery.count()).thenReturn(count);
    }
}
