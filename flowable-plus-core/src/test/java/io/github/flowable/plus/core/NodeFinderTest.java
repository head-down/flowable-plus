package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // 默认返回空列表，让不需要历史数据的测试正常通过
        stubHistoricActivityInstances("any-pi", Collections.emptyList());
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
        HistoricActivityInstance instanceTaskA = createMockInstance("taskA", new Date(10000), new Date(20000));
        HistoricActivityInstance instanceTask1 = createMockInstance("task1", new Date(0), new Date(10000));
        List<HistoricActivityInstance> instances = new ArrayList<>();
        instances.add(instanceTaskA);
        instances.add(instanceTask1);
        stubHistoricActivityInstances("pi-001", instances);

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

    // ======================== 正向查找下游节点 (findNextUserTasks) ========================

    /**
     * 简单顺序：task1 → task2，从 task1 正向查找应返回 [task2]
     */
    @Test
    public void testFindNextUserTasksSimpleSequential() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-next-1")).thenReturn(model);

        List<String> result = nodeFinder.findNextUserTasks("proc-next-1", "task1", "pi-001",
                Collections.emptyMap());

        assertThat(result).containsExactly("task2");
    }

    /**
     * 排他网关：条件分别为 ${amount>5000} 和 ${amount<=5000}，运行时应走匹配的分支
     */
    @Test
    public void testFindNextUserTasksExclusiveGatewayMatchingCondition() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        ExclusiveGateway gw = builder.addExclusiveGateway("gw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");

        builder.addSequenceFlow("f1", task1, gw);
        builder.addSequenceFlowWithCondition("f2a", gw, taskA, "${amount > 5000}");
        builder.addSequenceFlowWithCondition("f2b", gw, taskB, "${amount <= 5000}");

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-next-gw")).thenReturn(model);

        // Mock ExpressionManager：amount > 5000 返回 false，amount <= 5000 返回 true
        ExpressionManager mockExprMgr = Mockito.mock(ExpressionManager.class);
        Expression exprFalse = Mockito.mock(Expression.class);
        Expression exprTrue = Mockito.mock(Expression.class);
        when(exprFalse.getValue(Mockito.any())).thenReturn(false);
        when(exprTrue.getValue(Mockito.any())).thenReturn(true);
        when(mockExprMgr.createExpression("amount > 5000")).thenReturn(exprFalse);
        when(mockExprMgr.createExpression("amount <= 5000")).thenReturn(exprTrue);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService, mockExprMgr);

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 3000);

        List<String> result = nodeFinder.findNextUserTasks("proc-next-gw", "task1", "pi-001", vars);

        assertThat(result).containsExactly("taskB");
    }

    /**
     * 并行网关：当前在 task1，task1 → pgw → taskA
     *                                  → taskB
     * 应返回两个分支的所有 UserTask
     */
    @Test
    public void testFindNextUserTasksParallelGateway() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        ParallelGateway pgw = builder.addParallelGateway("pgw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");

        builder.addSequenceFlow("f1", task1, pgw);
        builder.addSequenceFlow("f2a", pgw, taskA);
        builder.addSequenceFlow("f2b", pgw, taskB);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-next-par")).thenReturn(model);

        List<String> result = nodeFinder.findNextUserTasks("proc-next-par", "task1", "pi-001",
                Collections.emptyMap());

        assertThat(result).containsExactlyInAnyOrder("taskA", "taskB");
    }

    /**
     * 无下游节点：task1 是最后一个 UserTask，后面无任何节点
     */
    @Test
    public void testFindNextUserTasksNoDownstreamNodes() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-next-end")).thenReturn(model);

        List<String> result = nodeFinder.findNextUserTasks("proc-next-end", "task1", "pi-001",
                Collections.emptyMap());

        assertThat(result).isEmpty();
    }

    /**
     * 子流程递归：task1 → subProcess(内部: startSub → taskSub) → taskAfter
     * 从 task1 应返回 subProcess 内的 taskSub 和后续的 taskAfter
     */
    @Test
    public void testFindNextUserTasksSubProcess() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");

        SubProcess subProcess = builder.addSubProcess("sub1");
        StartEvent subStart = new StartEvent();
        subStart.setId("subStart");
        subProcess.addFlowElement(subStart);
        UserTask taskSub = new UserTask();
        taskSub.setId("taskSub");
        subProcess.addFlowElement(taskSub);
        // subProcess 内部连线
        SequenceFlow subFlow = new SequenceFlow();
        subFlow.setId("subFlow");
        subFlow.setSourceRef("subStart");
        subFlow.setTargetRef("taskSub");
        subStart.setOutgoingFlows(new ArrayList<>());
        subStart.getOutgoingFlows().add(subFlow);
        taskSub.setIncomingFlows(new ArrayList<>());
        taskSub.getIncomingFlows().add(subFlow);

        UserTask taskAfter = builder.addUserTask("taskAfter");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, subProcess);
        builder.addSequenceFlow("f3", subProcess, taskAfter);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-next-sub")).thenReturn(model);

        List<String> result = nodeFinder.findNextUserTasks("proc-next-sub", "task1", "pi-001",
                Collections.emptyMap());

        assertThat(result).containsExactlyInAnyOrder("taskSub", "taskAfter");
    }

    /**
     * 不存在的节点 ID 抛出 NotFoundException
     */
    @Test
    public void testFindNextUserTasksUnknownNode() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent s = builder.addStartEvent("start");
        UserTask t = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", s, t);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-unknown-node")).thenReturn(model);

        assertThatThrownBy(() -> nodeFinder.findNextUserTasks("proc-unknown-node", "nonexistent", "pi-001",
                Collections.emptyMap()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    /**
     * 不存在的流程定义 ID 抛出 NotFoundException
     */
    @Test
    public void testFindNextUserTasksUnknownProcessDefinition() {
        when(repositoryService.getBpmnModel("unknown-proc")).thenReturn(null);

        assertThatThrownBy(() -> nodeFinder.findNextUserTasks("unknown-proc", "task1", "pi-001",
                Collections.emptyMap()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    // ======================== findCompletedUserTasks ========================

    @Test
    public void testFindCompletedUserTasksSingleChain() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        builder.addSequenceFlow("f3", task2, task3);
        BpmnModel model = builder.build();

        when(repositoryService.getBpmnModel("proc-single")).thenReturn(model);

        // 所有三个节点都有历史记录
        HistoricActivityInstance t1 = createMockInstance("task1", new Date(1000), new Date(2000));
        HistoricActivityInstance t2 = createMockInstance("task2", new Date(3000), new Date(4000));
        HistoricActivityInstance t3 = createMockInstance("task3", new Date(5000), new Date(6000));
        stubHistoricActivityInstances("pi-001", java.util.Arrays.asList(t3, t2, t1));

        List<String> result = nodeFinder.findCompletedUserTasks("proc-single", "task3", "pi-001");

        assertThat(result).containsExactlyInAnyOrder("task1", "task2");
    }

    @Test
    public void testFindCompletedUserTasksEmptyChain() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);
        BpmnModel model = builder.build();

        when(repositoryService.getBpmnModel("proc-start")).thenReturn(model);

        List<String> result = nodeFinder.findCompletedUserTasks("proc-start", "task1", "pi-001");

        // task1 是首节点，无上游
        assertThat(result).isEmpty();
    }

    @Test
    public void testFindCompletedUserTasksFiltersHistory() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        BpmnModel model = builder.build();

        when(repositoryService.getBpmnModel("proc-filter")).thenReturn(model);

        // 只有 task1 有历史记录，task2 也有（当前节点）
        HistoricActivityInstance t1 = createMockInstance("task1", new Date(1000), new Date(2000));
        HistoricActivityInstance t2 = createMockInstance("task2", new Date(3000), new Date(4000));
        // task1 在并行分支中无历史记录
        stubHistoricActivityInstances("pi-001", java.util.Arrays.asList(t2, t1));

        List<String> result = nodeFinder.findCompletedUserTasks("proc-filter", "task2", "pi-001");

        assertThat(result).containsExactly("task1");
    }

    @Test
    public void testFindCompletedUserTasksParallelGateway() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask taskA = builder.addUserTask("taskA");
        ParallelGateway gwFork = builder.addParallelGateway("gw_fork");
        UserTask taskB = builder.addUserTask("taskB");
        UserTask taskC = builder.addUserTask("taskC");
        ParallelGateway gwJoin = builder.addParallelGateway("gw_join");
        UserTask taskD = builder.addUserTask("taskD");
        builder.addSequenceFlow("f1", start, taskA);
        builder.addSequenceFlow("f2", taskA, gwFork);
        builder.addSequenceFlow("f3", gwFork, taskB);
        builder.addSequenceFlow("f4", gwFork, taskC);
        builder.addSequenceFlow("f5", taskB, gwJoin);
        builder.addSequenceFlow("f6", taskC, gwJoin);
        builder.addSequenceFlow("f7", gwJoin, taskD);
        BpmnModel model = builder.build();

        when(repositoryService.getBpmnModel("proc-parallel")).thenReturn(model);

        // B、C 都有历史记录
        HistoricActivityInstance tA = createMockInstance("taskA", new Date(1000), new Date(2000));
        HistoricActivityInstance tB = createMockInstance("taskB", new Date(3000), new Date(4000));
        HistoricActivityInstance tC = createMockInstance("taskC", new Date(3000), new Date(4000));
        HistoricActivityInstance tD = createMockInstance("taskD", new Date(5000), new Date(6000));
        stubHistoricActivityInstances("pi-001", java.util.Arrays.asList(tD, tC, tB, tA));

        List<String> result = nodeFinder.findCompletedUserTasks("proc-parallel", "taskD", "pi-001");

        // 回溯应收集 taskB、taskC（并行分支）和 taskA（上游），排除 taskD（当前节点）
        assertThat(result).containsExactlyInAnyOrder("taskA", "taskB", "taskC");
    }

    @Test
    public void testFindCompletedUserTasksExclusiveGateway() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        ExclusiveGateway gw = builder.addExclusiveGateway("gw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, gw);
        builder.addSequenceFlow("f3", gw, taskA);
        builder.addSequenceFlow("f4", gw, taskB);
        builder.addSequenceFlow("f5", taskA, task2);
        builder.addSequenceFlow("f6", taskB, task2);
        BpmnModel model = builder.build();

        when(repositoryService.getBpmnModel("proc-ex")).thenReturn(model);

        // 历史数据只有 taskA 分支执行过
        HistoricActivityInstance t1 = createMockInstance("task1", new Date(1000), new Date(2000));
        HistoricActivityInstance tA = createMockInstance("taskA", new Date(3000), new Date(4000));
        HistoricActivityInstance t2 = createMockInstance("task2", new Date(5000), new Date(6000));
        stubHistoricActivityInstances("pi-001", java.util.Arrays.asList(t2, tA, t1));

        List<String> result = nodeFinder.findCompletedUserTasks("proc-ex", "task2", "pi-001");

        // 应排除 taskB（不在历史中）
        assertThat(result).containsExactlyInAnyOrder("task1", "taskA");
    }

    @Test
    public void testFindCompletedUserTasksUnknownProcessDefinition() {
        when(repositoryService.getBpmnModel("unknown-proc")).thenReturn(null);

        assertThatThrownBy(() -> nodeFinder.findCompletedUserTasks("unknown-proc", "task1", "pi-001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    // ======================== getNodeName ========================

    @Test
    public void testGetNodeName() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        task1.setName("发起人审批");
        builder.addSequenceFlow("f1", start, task1);
        BpmnModel model = builder.build();

        when(repositoryService.getBpmnModel("proc-name")).thenReturn(model);

        String name = nodeFinder.getNodeName("proc-name", "task1");
        assertThat(name).isEqualTo("发起人审批");
    }

    @Test
    public void testGetNodeNameUnknownProcessDefinition() {
        when(repositoryService.getBpmnModel("unknown")).thenReturn(null);

        String name = nodeFinder.getNodeName("unknown", "task1");
        assertThat(name).isNull();
    }

    @Test
    public void testGetNodeNameUnknownNodeId() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);
        BpmnModel model = builder.build();

        when(repositoryService.getBpmnModel("proc-name")).thenReturn(model);

        String name = nodeFinder.getNodeName("proc-name", "nonexistent");
        assertThat(name).isNull();
    }

    // ======================== 辅助方法 ========================

    private void stubHistoricActivityInstances(String processInstanceId,
                                                List<HistoricActivityInstance> instances) {
        HistoricActivityInstanceQuery query = Mockito.mock(HistoricActivityInstanceQuery.class);
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
        if (processInstanceId != null) {
            when(query.processInstanceId(processInstanceId)).thenReturn(query);
        } else {
            when(query.processInstanceId(anyString())).thenReturn(query);
        }
        when(query.finished()).thenReturn(query);
        when(query.orderByHistoricActivityInstanceEndTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        when(query.list()).thenReturn(instances != null ? instances : Collections.emptyList());
    }

    private HistoricActivityInstance createMockInstance(String activityId, Date startTime, Date endTime) {
        HistoricActivityInstance instance = Mockito.mock(HistoricActivityInstance.class);
        when(instance.getActivityId()).thenReturn(activityId);
        when(instance.getStartTime()).thenReturn(startTime);
        when(instance.getEndTime()).thenReturn(endTime);
        return instance;
    }
}
