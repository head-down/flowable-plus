package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * NodeFinder 单元测试：验证 BPMN 模型 + 历史数据混合查找逻辑。
 */
public class NodeFinderTest {

    private RepositoryService repositoryService;
    private HistoryService historyService;
    private BpmnModelCache bpmnModelCache;
    private DefaultNodeFinder nodeFinder;

    @BeforeEach
    public void setUp() {
        repositoryService = Mockito.mock(RepositoryService.class);
        historyService = Mockito.mock(HistoryService.class);
        bpmnModelCache = new DefaultBpmnModelCache(repositoryService);
        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class));
    }

    // ======================== 向后查找 ========================

    /**
     * 简单顺序：start → task1 → task2，从 task2 回溯应找到 [task1]
     */
    @Test
    public void testFindPreviousNodesSimpleSequential() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("flow1", start, task1);
        builder.addSequenceFlow("flow2", task1, task2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-1")).thenReturn(model);

        List<String> result = nodeFinder.findPreviousNodes("proc-1", "task2", null);

        assertThat(result).containsExactly("task1");
    }

    /**
     * 排他网关：start → task1 → gw_split → taskA → gw_merge → task2
     *                                    → taskB → gw_merge
     * 历史记录显示 taskA 执行过，回溯应找到 [taskA]
     */
    @Test
    public void testFindPreviousNodesExclusiveGateway() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        ExclusiveGateway gwSplit = builder.addExclusiveGateway("gw_split");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");
        ExclusiveGateway gwMerge = builder.addExclusiveGateway("gw_merge");
        UserTask task2 = builder.addUserTask("task2");

        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, gwSplit);
        builder.addSequenceFlow("f3a", gwSplit, taskA);
        builder.addSequenceFlow("f3b", gwSplit, taskB);
        builder.addSequenceFlow("f4a", taskA, gwMerge);
        builder.addSequenceFlow("f4b", taskB, gwMerge);
        builder.addSequenceFlow("f5", gwMerge, task2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-ex")).thenReturn(model);

        // Mock 历史数据：taskA 在历史中出现过，taskB 没有
        HistoricActivityInstanceQuery mockQuery = Mockito.mock(HistoricActivityInstanceQuery.class);
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(mockQuery);
        when(mockQuery.processInstanceId(anyString())).thenReturn(mockQuery);
        when(mockQuery.finished()).thenReturn(mockQuery);
        when(mockQuery.orderByHistoricActivityInstanceEndTime()).thenReturn(mockQuery);
        when(mockQuery.desc()).thenReturn(mockQuery);

        HistoricActivityInstance instanceTaskA = createMockInstance("taskA", new Date(10000), new Date(20000));
        HistoricActivityInstance instanceTask1 = createMockInstance("task1", new Date(0), new Date(10000));
        List<HistoricActivityInstance> instances = new ArrayList<>();
        instances.add(instanceTaskA);
        instances.add(instanceTask1);
        when(mockQuery.list()).thenReturn(instances);

        List<String> result = nodeFinder.findPreviousNodes("proc-ex", "task2", "pi-001");

        assertThat(result).containsExactly("taskA");
    }

    /**
     * 并行网关：start → task1 → pgw_split → taskA → pgw_merge → task2
     *                                     → taskB →
     * 回溯应找到全部上游节点 [taskA, taskB]
     */
    @Test
    public void testFindPreviousNodesParallelGateway() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        ParallelGateway pgwSplit = builder.addParallelGateway("pgw_split");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");
        ParallelGateway pgwMerge = builder.addParallelGateway("pgw_merge");
        UserTask task2 = builder.addUserTask("task2");

        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, pgwSplit);
        builder.addSequenceFlow("f3a", pgwSplit, taskA);
        builder.addSequenceFlow("f3b", pgwSplit, taskB);
        builder.addSequenceFlow("f4a", taskA, pgwMerge);
        builder.addSequenceFlow("f4b", taskB, pgwMerge);
        builder.addSequenceFlow("f5", pgwMerge, task2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-par")).thenReturn(model);

        List<String> result = nodeFinder.findPreviousNodes("proc-par", "task2", null);

        assertThat(result).containsExactlyInAnyOrder("taskA", "taskB");
    }

    /**
     * 无上一节点：start → task1，从 task1 回溯应抛出 {@link NoPreviousNodeException}
     */
    @Test
    public void testFindPreviousNodesNoPreviousNode() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("flow1", start, task1);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-none")).thenReturn(model);

        assertThatThrownBy(() -> nodeFinder.findPreviousNodes("proc-none", "task1", null))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("task1 无上一审批节点");
    }

    /**
     * 模型不存在时抛出 {@link NotFoundException}
     */
    @Test
    public void testFindPreviousNodesNoModelReturnsEmpty() {
        when(repositoryService.getBpmnModel("nonexistent")).thenReturn(null);

        assertThatThrownBy(() -> nodeFinder.findPreviousNodes("nonexistent", "task1", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 nonexistent 不存在");
    }

    // ======================== 向前查找 ========================

    /**
     * 向前查找：start → task1，应返回 task1
     */
    @Test
    public void testFindInitiatorNodeSimple() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-init")).thenReturn(model);

        String result = nodeFinder.findInitiatorNode("proc-init");

        assertThat(result).isEqualTo("task1");
    }

    /**
     * 向前查找，start 经过排他网关后找到第一个 UserTask
     * start → gw → taskA
     *             → taskB
     */
    @Test
    public void testFindInitiatorNodeThroughGateway() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        ExclusiveGateway gw = builder.addExclusiveGateway("gw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");

        builder.addSequenceFlow("f1", start, gw);
        builder.addSequenceFlow("f2a", gw, taskA);
        builder.addSequenceFlow("f2b", gw, taskB);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-init-gw")).thenReturn(model);

        String result = nodeFinder.findInitiatorNode("proc-init-gw");

        // 返回遍历过程中遇到的第一个 UserTask
        assertThat(result).isIn("taskA", "taskB");
    }

    /**
     * 模型不存在时抛出 {@link NotFoundException}
     */
    @Test
    public void testFindInitiatorNodeNoModelReturnsNull() {
        when(repositoryService.getBpmnModel("nonexistent")).thenReturn(null);

        assertThatThrownBy(() -> nodeFinder.findInitiatorNode("nonexistent"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 nonexistent 不存在");
    }

    // ======================== 辅助方法 ========================

    private HistoricActivityInstance createMockInstance(String activityId, Date startTime, Date endTime) {
        HistoricActivityInstance instance = Mockito.mock(HistoricActivityInstance.class);
        when(instance.getActivityId()).thenReturn(activityId);
        when(instance.getStartTime()).thenReturn(startTime);
        when(instance.getEndTime()).thenReturn(endTime);
        return instance;
    }
}
