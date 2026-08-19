package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.AssigneeResolverRegistry;
import io.github.flowable.plus.core.spi.AssigneeResolver;
import io.github.flowable.plus.core.vo.RollbackResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AutoRebuildCountersignRollbackStrategy 单元测试。
 * 覆盖原地重建、降级重定向、0 实例死锁防御及边界情况。
 */
public class AutoRebuildCountersignRollbackStrategyTest {

    private static final String PROCESS_DEF_ID = "leave:1:abc";
    private static final String PROCESS_INST_ID = "pi-001";
    private static final String MI_ACTIVITY_ID = "csTask";
    private static final String PREDECESSOR_ID = "task1";

    private NodeFinder mockNodeFinder;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private AssigneeResolverRegistry assigneeResolverRegistry;
    private AutoRebuildCountersignRollbackStrategy strategy;

    @BeforeEach
    void setUp() {
        mockNodeFinder = mock(NodeFinder.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        assigneeResolverRegistry = new AssigneeResolverRegistry();
        strategy = new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector,
                assigneeResolverRegistry);
    }

    // ======================== 运行时单例 → 直接放行 ========================

    @Test
    void testRuntimeSingleCountZeroDirectPass() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

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

        stubRuntimeMultiInstance(false);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).isNull();
    }

    // ======================== SPI 有审批人 → 原地重建 ========================

    @Test
    void testWithSpiRebuildsInPlace() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        List<String> assigneeList = Arrays.asList("user1", "user2", "user3");
        AssigneeResolver resolver = (procInstId, taskDefKey) -> assigneeList;
        assigneeResolverRegistry = new AssigneeResolverRegistry(
                Collections.singletonList(resolver));
        strategy = new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector,
                assigneeResolverRegistry);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).isNull();
        assertThat(result.getNewAssigneeList()).isEqualTo(assigneeList);
    }

    @Test
    void testWithSpiSingleAssigneeRebuildsInPlace() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        List<String> assigneeList = Collections.singletonList("user1");
        AssigneeResolver resolver = (procInstId, taskDefKey) -> assigneeList;
        assigneeResolverRegistry = new AssigneeResolverRegistry(
                Collections.singletonList(resolver));
        strategy = new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector,
                assigneeResolverRegistry);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getNewAssigneeList()).containsExactly("user1");
    }

    // ======================== SPI 无结果 + 有前置 → 降级重定向 ========================

    @Test
    void testWithoutSpiRedirectsWhenPredecessorExists() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        // SPI 无实现 → 降级
        // 有前置单例节点
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
                .contains("降级重定向")
                .contains("前置准备节点");
        assertThat(result.getNewAssigneeList()).isNull();
    }

    @Test
    void testWithSpiEmptyAndPredecessorRedirects() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        // SPI 返回空列表
        AssigneeResolver resolver = (procInstId, taskDefKey) -> Collections.emptyList();
        assigneeResolverRegistry = new AssigneeResolverRegistry(
                Collections.singletonList(resolver));
        strategy = new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector,
                assigneeResolverRegistry);

        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));
        when(mockNodeFinder.getNodeName(anyString(), anyString()))
                .thenReturn(null);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(PREDECESSOR_ID);
        assertThat(result.getRedirectMessage()).contains("降级重定向");
    }

    // ======================== SPI 无结果 + 无前置 → 硬拦截 ========================

    @Test
    void testWithoutSpiNoPredecessorThrows() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");

        assertThatThrownBy(() -> strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("0 实例不可放行")
                .hasMessageContaining("AssigneeResolver");
    }

    @Test
    void testWithSpiNullAndNoPredecessorThrows() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        // SPI 返回 null
        AssigneeResolver resolver = (procInstId, taskDefKey) -> null;
        assigneeResolverRegistry = new AssigneeResolverRegistry(
                Collections.singletonList(resolver));
        strategy = new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector,
                assigneeResolverRegistry);

        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");

        assertThatThrownBy(() -> strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID))
                .isInstanceOf(InvalidTargetNodeException.class)
                .hasMessageContaining("0 实例不可放行");
    }

    // ======================== 构造函数空值校验 ========================

    @Test
    void testConstructorNullNodeFinder() {
        assertThatThrownBy(() -> new AutoRebuildCountersignRollbackStrategy(
                null, mockMultiInstanceDetector, assigneeResolverRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder");
    }

    @Test
    void testConstructorNullMultiInstanceDetector() {
        assertThatThrownBy(() -> new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, null, assigneeResolverRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MultiInstanceDetector");
    }

    @Test
    void testConstructorNullAssigneeResolverRegistry() {
        assertThatThrownBy(() -> new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AssigneeResolverRegistry");
    }

    // ======================== AssigneeResolverRegistry 多实现 ========================

    @Test
    void testRegistryPicksFirstNonEmptyResolver() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        // 第一个返回空，第二个有结果
        AssigneeResolver emptyResolver = (procInstId, taskDefKey) -> Collections.emptyList();
        AssigneeResolver validResolver = (procInstId, taskDefKey) ->
                Arrays.asList("user1", "user2");
        assigneeResolverRegistry = new AssigneeResolverRegistry(
                Arrays.asList(emptyResolver, validResolver));
        strategy = new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector,
                assigneeResolverRegistry);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getNewAssigneeList()).containsExactly("user1", "user2");
    }

    @Test
    void testRegistryAllEmptyFallsBack() {
        PlusTask task = createTask("task-001", PROCESS_DEF_ID, "task2", PROCESS_INST_ID);

        stubRuntimeMultiInstance(true);

        // 全部返回空
        AssigneeResolver resolver1 = (procInstId, taskDefKey) -> Collections.emptyList();
        AssigneeResolver resolver2 = (procInstId, taskDefKey) -> null;
        assigneeResolverRegistry = new AssigneeResolverRegistry(
                Arrays.asList(resolver1, resolver2));
        strategy = new AutoRebuildCountersignRollbackStrategy(
                mockNodeFinder, mockMultiInstanceDetector,
                assigneeResolverRegistry);

        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));
        when(mockNodeFinder.getNodeName(anyString(), anyString()))
                .thenReturn(null);

        RollbackResult result = strategy.resolveRollbackTarget(
                task, MI_ACTIVITY_ID);

        assertThat(result.getTargetActivityId()).isEqualTo(PREDECESSOR_ID);
        assertThat(result.getRedirectMessage()).contains("降级重定向");
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
