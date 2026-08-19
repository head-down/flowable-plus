package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.vo.RollbackResult;
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
    private MultiInstanceDetector mockMultiInstanceDetector;
    private AutoRedirectCountersignRollbackStrategy strategy;

    @BeforeEach
    void setUp() {
        mockNodeFinder = mock(NodeFinder.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        strategy = new AutoRedirectCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector);
    }

    // ======================== 运行时单例 → 直接放行 ========================

    @Test
    void testRuntimeSingleDirectPass() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        // 运行时判定：目标节点非运行时多实例（从未执行过）
        stubRuntimeMultiInstance(false);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).isNull();
        assertThat(result.getNewAssigneeList()).isNull();
    }

    @Test
    void testRuntimeSingleCountOneDirectPass() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        // 运行时判定：只有一个人执行过（全局历史 == 1）
        stubRuntimeMultiInstance(false);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).isNull();
    }

    // ======================== 运行时多实例 + 有前置 → 重定向 ========================

    @Test
    void testRuntimeMultiWithPredecessorRedirects() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        // 运行时判定：真正的多实例（全局历史 > 1）
        stubRuntimeMultiInstance(true);

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

        stubRuntimeMultiInstance(true);
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

        stubRuntimeMultiInstance(true);

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

        stubRuntimeMultiInstance(true);

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

        stubRuntimeMultiInstance(true);

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

        stubRuntimeMultiInstance(true);
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

        stubRuntimeMultiInstance(true);
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
                null, mockMultiInstanceDetector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder");
    }

    @Test
    void testConstructorNullMultiInstanceDetector() {
        assertThatThrownBy(() -> new AutoRedirectCountersignRollbackStrategy(
                mockNodeFinder, null))
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

    /**
     * Stub 运行时 MI 判定（复合判据已收敛至 MultiInstanceDetector，ADR-0040）。
     */
    private void stubRuntimeMultiInstance(boolean runtimeMultiInstance) {
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID))
                .thenReturn(runtimeMultiInstance);
    }
}
