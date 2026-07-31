package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.spi.SkipInitiatorNodeFilter;
import io.github.flowable.plus.core.spi.UserTaskTraversalFilter;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
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
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.DefaultNodeFinder;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.model.DefaultBpmnModelCache;

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
                Mockito.mock(ExpressionManager.class), null);

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

        // count() 查询：resolveExclusiveGateway 按 incomingFlows 顺序逐条查询
        // f4a(taskA) 先 → count=1, f4b(taskB) 后 → count=0
        stubCountQueries("pi-001", 1L, 0L);

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

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService, mockExprMgr, null);

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

    /**
     * 条件兜底：排他网关条件表达式引用了不在 variables 中的变量，
     * 所有条件分支被排除 → 回退到不评估条件，展示所有下一节点。
     * <p>
     * 场景：task1 → egw → taskA (${nextNodeCodeTmp eq 'sealHandler'})
     *                  → taskB (${nextNodeCodeTmp eq 'subsidiaryManager'})
     * nextNodeCodeTmp 不在 variables 中 → 兜底返回 [taskA, taskB]。
     */
    @Test
    public void testFindNextUserTasksConditionFallbackMissingVariable() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        ExclusiveGateway egw = builder.addExclusiveGateway("egw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");

        builder.addSequenceFlow("f1", task1, egw);
        builder.addSequenceFlowWithCondition("f2a", egw, taskA, "${nextNodeCodeTmp eq 'sealHandler'}");
        builder.addSequenceFlowWithCondition("f2b", egw, taskB, "${nextNodeCodeTmp eq 'subsidiaryManager'}");

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-fallback")).thenReturn(model);

        ExpressionManager mockExprMgr = Mockito.mock(ExpressionManager.class);
        Expression exprFalse = Mockito.mock(Expression.class);
        when(exprFalse.getValue(Mockito.any())).thenReturn(false);
        when(mockExprMgr.createExpression("nextNodeCodeTmp eq 'sealHandler'")).thenReturn(exprFalse);
        when(mockExprMgr.createExpression("nextNodeCodeTmp eq 'subsidiaryManager'")).thenReturn(exprFalse);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService, mockExprMgr, null);

        // variables 中不包含 nextNodeCodeTmp
        Map<String, Object> vars = new HashMap<>();

        List<String> result = nodeFinder.findNextUserTasks("proc-fallback", "task1", "pi-001", vars);

        assertThat(result).containsExactlyInAnyOrder("taskA", "taskB");
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

    // ======================== UserTaskTraversalFilter 扩展点 ========================

    /**
     * 无 Filter 时，findNextUserTasks 应收集所有下游节点（向后兼容）。
     */
    @Test
    public void testTraversalFilterNoFilterCollectsAll() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        builder.addSequenceFlow("f3", task2, task3);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-no-filter")).thenReturn(model);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class), Collections.emptyList());

        List<String> result = nodeFinder.findNextUserTasks("proc-no-filter", "task1", "pi-001",
                Collections.emptyMap());

        assertThat(result).containsExactly("task2", "task3");
    }

    /**
     * 单个 Filter 跳过指定节点，其余正常收集。
     */
    @Test
    public void testTraversalFilterSkipSingleNode() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        builder.addSequenceFlow("f3", task2, task3);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-skip-single")).thenReturn(model);

        UserTaskTraversalFilter skipTask2 = (userTask, vars) -> !"task2".equals(userTask.getId());
        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class), Collections.singletonList(skipTask2));

        List<String> result = nodeFinder.findNextUserTasks("proc-skip-single", "task1", "pi-001",
                Collections.emptyMap());

        // task2 被跳过，但遍历继续穿过它，应收集 task3
        assertThat(result).containsExactly("task3");
    }

    /**
     * 多个 Filter 以 AND 逻辑合并：任一返回 false 即跳过。
     */
    @Test
    public void testTraversalFilterAndLogic() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        builder.addSequenceFlow("f3", task2, task3);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-and-filter")).thenReturn(model);

        // Filter1: 不跳过任何节点（始终 true）
        UserTaskTraversalFilter passthrough = (userTask, vars) -> true;
        // Filter2: 跳过 task2
        UserTaskTraversalFilter skipTask2 = (userTask, vars) -> !"task2".equals(userTask.getId());

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class),
                java.util.Arrays.asList(passthrough, skipTask2));

        List<String> result = nodeFinder.findNextUserTasks("proc-and-filter", "task1", "pi-001",
                Collections.emptyMap());

        // AND 逻辑：passthrough 通过、skipTask2 拦截 task2 → 只剩 task3
        assertThat(result).containsExactly("task3");
    }

    /**
     * Filter 与网关条件同时生效：Filter 跳过其中一个分支节点，条件排除了另一个分支。
     */
    @Test
    public void testTraversalFilterWithGatewayCondition() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        ExclusiveGateway gw = builder.addExclusiveGateway("gw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");

        builder.addSequenceFlow("f1", task1, gw);
        builder.addSequenceFlowWithCondition("f2a", gw, taskA, "${amount > 5000}");
        builder.addSequenceFlowWithCondition("f2b", gw, taskB, "${amount <= 5000}");

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-filter-gw")).thenReturn(model);

        // Mock 表达式：amount > 5000 = false, amount <= 5000 = true → 走 taskB
        ExpressionManager mockExprMgr = Mockito.mock(ExpressionManager.class);
        Expression exprFalse = Mockito.mock(Expression.class);
        Expression exprTrue = Mockito.mock(Expression.class);
        when(exprFalse.getValue(Mockito.any())).thenReturn(false);
        when(exprTrue.getValue(Mockito.any())).thenReturn(true);
        when(mockExprMgr.createExpression("amount > 5000")).thenReturn(exprFalse);
        when(mockExprMgr.createExpression("amount <= 5000")).thenReturn(exprTrue);

        // Filter 跳过 taskB——两个条件叠加，最终没有节点可收集
        UserTaskTraversalFilter skipTaskB = (userTask, vars) -> !"taskB".equals(userTask.getId());
        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService, mockExprMgr,
                Collections.singletonList(skipTaskB));

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 3000);

        List<String> result = nodeFinder.findNextUserTasks("proc-filter-gw", "task1", "pi-001", vars);

        // 条件走到 taskB，但 Filter 跳过 taskB → 空列表
        assertThat(result).isEmpty();
    }

    /**
     * Filter 使用 variables 判断：根据流程变量决定是否跳过节点。
     */
    @Test
    public void testTraversalFilterUsesVariables() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);
        builder.addSequenceFlow("f3", task2, task3);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-filter-vars")).thenReturn(model);

        // 当 variables.needApproval == true 时收集 task2，否则跳过
        UserTaskTraversalFilter conditionalFilter = (userTask, vars) -> {
            if ("task2".equals(userTask.getId()) && vars != null) {
                return Boolean.TRUE.equals(vars.get("needApproval"));
            }
            return true;
        };

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class),
                Collections.singletonList(conditionalFilter));

        Map<String, Object> varsFalse = new HashMap<>();
        varsFalse.put("needApproval", false);

        List<String> resultWithout = nodeFinder.findNextUserTasks("proc-filter-vars", "task1",
                "pi-001", varsFalse);

        // needApproval=false → 跳过 task2 → 只剩 task3
        assertThat(resultWithout).containsExactly("task3");

        Map<String, Object> varsTrue = new HashMap<>();
        varsTrue.put("needApproval", true);

        List<String> resultWith = nodeFinder.findNextUserTasks("proc-filter-vars", "task1",
                "pi-002", varsTrue);

        // needApproval=true → 不跳过 task2 → 收集 task2, task3
        assertThat(resultWith).containsExactly("task2", "task3");
    }

    // ======================== SkipInitiatorNodeFilter 默认实现 ========================

    /**
     * isStartTask=true 的节点被跳过，遍历继续收集后续节点。
     */
    @Test
    public void testSkipInitiatorNodeFilterSkipsStartNode() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        setExtensionAttribute(task1, "flowable", "isStartTask", "true");
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f1", task1, task2);
        builder.addSequenceFlow("f2", task2, task3);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-skip-start")).thenReturn(model);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class),
                Collections.singletonList(new SkipInitiatorNodeFilter()));

        List<String> result = nodeFinder.findNextUserTasks("proc-skip-start", "task1", "pi-001",
                Collections.emptyMap());

        // task1 被跳过（isStartTask=true），但遍历穿过它收集 task2, task3
        assertThat(result).containsExactly("task2", "task3");
    }

    /**
     * isStartTask=false 的节点正常收集。
     */
    @Test
    public void testSkipInitiatorNodeFilterCollectsNonStartNode() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        setExtensionAttribute(task1, "flowable", "isStartTask", "false");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", task1, task2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-non-start")).thenReturn(model);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class),
                Collections.singletonList(new SkipInitiatorNodeFilter()));

        List<String> result = nodeFinder.findNextUserTasks("proc-non-start", "task1", "pi-001",
                Collections.emptyMap());

        // isStartTask=false → 不跳过，正常收集 task2
        assertThat(result).containsExactly("task2");
    }

    /**
     * 无扩展属性的节点正常收集（不会误判为发起人节点）。
     */
    @Test
    public void testSkipInitiatorNodeFilterCollectsNodeWithoutAttribute() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", task1, task2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-no-attr")).thenReturn(model);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class),
                Collections.singletonList(new SkipInitiatorNodeFilter()));

        List<String> result = nodeFinder.findNextUserTasks("proc-no-attr", "task1", "pi-001",
                Collections.emptyMap());

        // 无扩展属性 → 不跳过，正常收集 task2
        assertThat(result).containsExactly("task2");
    }

    // ======================== findAdjacentUserTasks ========================

    /**
     * 简单线性：start → taskA → taskB，从 start 紧邻遍历应返回 [taskA]，不穿透 taskA 继续到 taskB。
     */
    @Test
    public void testFindAdjacentUserTasksSimpleLinear() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");
        builder.addSequenceFlow("f1", start, taskA);
        builder.addSequenceFlow("f2", taskA, taskB);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-1")).thenReturn(model);

        List<String> result = nodeFinder.findAdjacentUserTasks("proc-adj-1", "start", null);

        // 仅返回紧邻的 taskA，不包含 taskB
        assertThat(result).containsExactly("taskA");
    }

    /**
     * 并行网关：start → pgw → taskA, pgw → taskB，紧邻遍历应收集同层级的两个节点。
     */
    @Test
    public void testFindAdjacentUserTasksParallelGateway() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        ParallelGateway pgw = builder.addParallelGateway("pgw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");

        builder.addSequenceFlow("f1", start, pgw);
        builder.addSequenceFlow("f2a", pgw, taskA);
        builder.addSequenceFlow("f2b", pgw, taskB);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-par")).thenReturn(model);

        List<String> result = nodeFinder.findAdjacentUserTasks("proc-adj-par", "start", null);

        assertThat(result).containsExactlyInAnyOrder("taskA", "taskB");
    }

    /**
     * 排他网关条件：start → egw → taskA (amount>5000), egw → taskB (amount<=5000)，
     * 紧邻遍历按条件表达式匹配分支。
     */
    @Test
    public void testFindAdjacentUserTasksExclusiveGatewayCondition() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        ExclusiveGateway egw = builder.addExclusiveGateway("egw");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");

        builder.addSequenceFlow("f1", start, egw);
        builder.addSequenceFlowWithCondition("f2a", egw, taskA, "${amount > 5000}");
        builder.addSequenceFlowWithCondition("f2b", egw, taskB, "${amount <= 5000}");

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-gw")).thenReturn(model);

        ExpressionManager mockExprMgr = Mockito.mock(ExpressionManager.class);
        Expression exprTrue = Mockito.mock(Expression.class);
        Expression exprFalse = Mockito.mock(Expression.class);
        when(exprTrue.getValue(Mockito.any())).thenReturn(true);
        when(exprFalse.getValue(Mockito.any())).thenReturn(false);

        // amount > 5000 匹配，amount <= 5000 不匹配
        when(mockExprMgr.createExpression("amount > 5000")).thenReturn(exprTrue);
        when(mockExprMgr.createExpression("amount <= 5000")).thenReturn(exprFalse);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService, mockExprMgr, null);

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 6000);

        List<String> result = nodeFinder.findAdjacentUserTasks("proc-adj-gw", "start", vars);

        assertThat(result).containsExactly("taskA");
    }

    /**
     * 无下游：start → endEvent（无 UserTask），紧邻遍历返回空列表。
     */
    @Test
    public void testFindAdjacentUserTasksNoDownstreamNodes() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        // start 无 outgoing，或 outgoing 只到非 UserTask 节点
        // 对于紧邻遍历，start 后无 UserTask → 空列表

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-none")).thenReturn(model);

        List<String> result = nodeFinder.findAdjacentUserTasks("proc-adj-none", "start", null);

        assertThat(result).isEmpty();
    }

    /**
     * SubProcess 穿透：task1 → Sub(A → B) → taskC，从 task1 紧邻遍历应返回 [A]。
     * 穿透 SubProcess 边界找到内部第一个 UserTask，不继续到 B，也不穿透出 SubProcess 到 taskC。
     */
    @Test
    public void testFindAdjacentUserTasksSubProcessPenetration() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");

        SubProcess sub1 = builder.addSubProcess("sub1");
        builder.buildSubProcessWithChain(sub1, "taskA", "taskB");

        UserTask taskC = builder.addUserTask("taskC");

        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, sub1);
        builder.addSequenceFlow("f3", sub1, taskC);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-sub")).thenReturn(model);

        List<String> result = nodeFinder.findAdjacentUserTasks("proc-adj-sub", "task1", null);

        // 紧邻遍历：穿透 SubProcess 找到 A，不继续到 B，也不穿透到 taskC
        assertThat(result).containsExactly("taskA");
    }

    /**
     * SkipInitiatorNodeFilter 集成：task1(isStartTask=true)→task2→task3，
     * 从 task1 紧邻遍历应返回 [task2]（跳过发起人节点）。
     */
    @Test
    public void testFindAdjacentUserTasksSkipInitiatorFilter() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task1 = builder.addUserTask("task1");
        setExtensionAttribute(task1, "flowable", "isStartTask", "true");
        UserTask task2 = builder.addUserTask("task2");
        UserTask task3 = builder.addUserTask("task3");
        builder.addSequenceFlow("f1", task1, task2);
        builder.addSequenceFlow("f2", task2, task3);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-skip")).thenReturn(model);

        nodeFinder = new DefaultNodeFinder(bpmnModelCache, historyService,
                Mockito.mock(ExpressionManager.class),
                Collections.singletonList(new SkipInitiatorNodeFilter()));

        List<String> result = nodeFinder.findAdjacentUserTasks("proc-adj-skip", "task1", null);

        // task1 被 SkipInitiatorNodeFilter 跳过，紧邻收集 task2
        assertThat(result).containsExactly("task2");
    }

    /**
     * 循环防环：start→task1→egw→svc1→egw→task2（回路含 ServiceTask 非 UserTask），
     * 紧邻遍历不卡死。从 task1 出发，穿越回路后到达 task2。
     */
    @Test
    public void testFindAdjacentUserTasksCycleDetection() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        ExclusiveGateway egw = builder.addExclusiveGateway("egw");
        ServiceTask svc1 = builder.addServiceTask("svc1");
        UserTask task2 = builder.addUserTask("task2");

        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, egw);
        builder.addSequenceFlow("f3", egw, svc1);     // egw → svc1（回路入口）
        builder.addSequenceFlow("f4", svc1, egw);      // svc1 → egw（回到网关）
        builder.addSequenceFlow("f5", egw, task2);     // egw → task2（出口）

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-cycle")).thenReturn(model);

        List<String> result = nodeFinder.findAdjacentUserTasks("proc-adj-cycle", "task1", null);

        // visited 防环截断 svc1→egw 重入，egw→task2 正常收集
        assertThat(result).containsExactly("task2");
    }

    /**
     * 紧邻 ⊆ 全遍历一致性：验证紧邻遍历结果是全遍历结果的子集。
     * 拓扑：start → pgw → taskA
     *                    → sub1(内部: taskSub → taskSub2) → taskB
     *               taskA → pgw_merge
     *               taskB → pgw_merge
     *               pgw_merge → taskC
     */
    @Test
    public void testFindAdjacentUserTasksIsSubsetOfFullTraversal() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        ParallelGateway pgwSplit = builder.addParallelGateway("pgw_split");
        UserTask taskA = builder.addUserTask("taskA");

        SubProcess sub1 = builder.addSubProcess("sub1");
        builder.buildSubProcessWithChain(sub1, "taskSub", "taskSub2");

        UserTask taskB = builder.addUserTask("taskB");
        ParallelGateway pgwMerge = builder.addParallelGateway("pgw_merge");
        UserTask taskC = builder.addUserTask("taskC");

        builder.addSequenceFlow("f1", start, pgwSplit);
        builder.addSequenceFlow("f2a", pgwSplit, taskA);
        builder.addSequenceFlow("f2b", pgwSplit, sub1);
        builder.addSequenceFlow("f3", sub1, taskB);
        builder.addSequenceFlow("f4a", taskA, pgwMerge);
        builder.addSequenceFlow("f4b", taskB, pgwMerge);
        builder.addSequenceFlow("f5", pgwMerge, taskC);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-adj-subset")).thenReturn(model);

        // 全遍历
        List<String> fullResult = nodeFinder.findAllReachableUserTasks("proc-adj-subset", null);

        // 紧邻遍历
        List<String> adjacentResult = nodeFinder.findAdjacentUserTasks("proc-adj-subset", "start", null);

        // 紧邻结果是全遍历结果的子集
        assertThat(fullResult).containsAll(adjacentResult);
        // 紧邻遍历应收集 taskA 和 taskSub（SubProcess 内第一个），不含 taskSub2、taskB、taskC
        assertThat(adjacentResult).containsExactlyInAnyOrder("taskA", "taskSub");
    }

    // ======================== findReachableEndEvents ========================

    /**
     * 简单：task → EndEvent，应返回 EndEvent ID。
     */
    @Test
    public void testFindReachableEndEventsSimple() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        EndEvent end = builder.addEndEvent("end");
        builder.addSequenceFlow("f1", task, end);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-simple")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-simple", "task", null);

        assertThat(result).containsExactly("end");
    }

    /**
     * task → UserTask，应返回空（下游有审批节点）。
     */
    @Test
    public void testFindReachableEndEventsWithUserTask() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        UserTask nextTask = builder.addUserTask("nextTask");
        builder.addSequenceFlow("f1", task, nextTask);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-ut")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-ut", "task", null);

        assertThat(result).isEmpty();
    }

    /**
     * task → Gateway → {UserTask, EndEvent}，EndEvent 分支应被收集。
     * 遇到 UserTask 时仅停止当前分支，不阻断其他分支的 EndEvent 收集。
     */
    @Test
    public void testFindReachableEndEventsWithGatewayBranch() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        ExclusiveGateway egw = builder.addExclusiveGateway("egw");
        UserTask nextTask = builder.addUserTask("nextTask");
        EndEvent end = builder.addEndEvent("end");

        builder.addSequenceFlow("f1", task, egw);
        builder.addSequenceFlow("f2a", egw, nextTask);
        builder.addSequenceFlow("f2b", egw, end);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-gw")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-gw", "task", null);

        // 分支 2b 有 EndEvent → 应被收集
        assertThat(result).containsExactly("end");
    }

    /**
     * task → ParallelGateway → {EndEvent1, EndEvent2}，所有分支均为 EndEvent。
     */
    @Test
    public void testFindReachableEndEventsAllBranchesToEnd() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        ParallelGateway pgw = builder.addParallelGateway("pgw");
        EndEvent end1 = builder.addEndEvent("end1");
        EndEvent end2 = builder.addEndEvent("end2");

        builder.addSequenceFlow("f1", task, pgw);
        builder.addSequenceFlow("f2a", pgw, end1);
        builder.addSequenceFlow("f2b", pgw, end2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-all")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-all", "task", null);

        assertThat(result).containsExactlyInAnyOrder("end1", "end2");
    }

    /**
     * task → SubProcess[内部无 UserTask] → EndEvent。
     * 子流程内部 EndEvent 不收集，流程级 EndEvent 应被收集。
     */
    @Test
    public void testFindReachableEndEventsSubProcess() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        SubProcess sub = builder.addSubProcess("sub");

        // SubProcess 内部: StartEvent → EndEvent（无 UserTask）
        StartEvent subStart = new StartEvent();
        subStart.setId("sub_start");
        sub.addFlowElement(subStart);
        EndEvent subEnd = new EndEvent();
        subEnd.setId("sub_end");
        sub.addFlowElement(subEnd);
        SequenceFlow subFlow = new SequenceFlow();
        subFlow.setId("sub_f1");
        subFlow.setSourceRef("sub_start");
        subFlow.setTargetRef("sub_end");
        sub.addFlowElement(subFlow);

        // 构建 sub 内部 Incoming/Outgoing
        java.util.List<SequenceFlow> subStartOut = new java.util.ArrayList<>();
        subStartOut.add(subFlow);
        subStart.setOutgoingFlows(subStartOut);
        java.util.List<SequenceFlow> subEndIn = new java.util.ArrayList<>();
        subEndIn.add(subFlow);
        subEnd.setIncomingFlows(subEndIn);

        EndEvent procEnd = builder.addEndEvent("proc_end");
        builder.addSequenceFlow("f1", task, sub);
        builder.addSequenceFlow("f2", sub, procEnd);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-sub")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-sub", "task", null);

        // 子流程内部 sub_end 不收集，只收集流程级 proc_end
        assertThat(result).containsExactly("proc_end");
    }

    /**
     * task → SubProcess[内部有 UserTask] → EndEvent。
     * 子流程内部有 UserTask 但不阻断外部 EndEvent 收集。
     */
    @Test
    public void testFindReachableEndEventsSubProcessWithTask() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        SubProcess sub = builder.addSubProcess("sub");
        builder.buildSubProcessWithChain(sub, "taskInner");
        EndEvent procEnd = builder.addEndEvent("proc_end");

        builder.addSequenceFlow("f1", task, sub);
        builder.addSequenceFlow("f2", sub, procEnd);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-sub-task")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-sub-task", "task", null);

        // 子流程内部 UserTask 不阻断外部 EndEvent 收集
        assertThat(result).containsExactly("proc_end");
    }

    /**
     * task → ServiceTask → EndEvent，中间节点应继续穿越。
     */
    @Test
    public void testFindReachableEndEventsServiceTaskMiddle() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        ServiceTask svc = builder.addServiceTask("svc");
        EndEvent end = builder.addEndEvent("end");

        builder.addSequenceFlow("f1", task, svc);
        builder.addSequenceFlow("f2", svc, end);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-svc")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-svc", "task", null);

        assertThat(result).containsExactly("end");
    }

    /**
     * 回环场景：task → Gateway → {UserTask → Gateway(回环), EndEvent}。
     * UserTask 分支停止遍历，EndEvent 分支应被收集。visited 防无限循环。
     */
    @Test
    public void testFindReachableEndEventsCycle() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        ExclusiveGateway egw = builder.addExclusiveGateway("egw");
        UserTask cycleTask = builder.addUserTask("cycleTask");
        EndEvent end = builder.addEndEvent("end");

        builder.addSequenceFlow("f1", task, egw);
        builder.addSequenceFlow("f2a", egw, cycleTask);   // 有 UserTask 的分支
        builder.addSequenceFlow("f2b", egw, end);           // EndEvent 分支
        builder.addSequenceFlow("f3", cycleTask, egw);      // 回环

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-cycle")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-cycle", "task", null);

        // 回环分支 UserTask 已遍历过 → visited 截断；EndEvent 分支仍可到达
        assertThat(result).containsExactly("end");
    }

    /**
     * task 无 outgoing flow → 返回空列表。
     */
    @Test
    public void testFindReachableEndEventsNoOutgoing() {
        TestModelBuilder builder = new TestModelBuilder();
        builder.addUserTask("task");
        // 无 outgoing

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-nof")).thenReturn(model);

        List<String> result = nodeFinder.findReachableEndEvents("proc-end-nof", "task", null);

        assertThat(result).isEmpty();
    }

    /**
     * 排他网关：无变量上下文，所有条件被跳过（无 default），返回空。
     */
    @Test
    public void testFindReachableEndEventsGatewayNoDefault() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        ExclusiveGateway egw = builder.addExclusiveGateway("egw");
        EndEvent end = builder.addEndEvent("end");

        builder.addSequenceFlow("f1", task, egw);
        builder.addSequenceFlowWithCondition("f2", egw, end, "${amount > 5000}");

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-nodflt")).thenReturn(model);

        // variables 为 null → 不评估条件 → 全部展开
        List<String> result1 = nodeFinder.findReachableEndEvents("proc-end-nodflt", "task", null);
        assertThat(result1).containsExactly("end");

        // variables 不匹配且无 default → 不判定
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 100);

        ExpressionManager mockExprMgr = Mockito.mock(ExpressionManager.class);
        Expression exprFalse = Mockito.mock(Expression.class);
        when(exprFalse.getValue(Mockito.any())).thenReturn(false);
        when(mockExprMgr.createExpression("amount > 5000")).thenReturn(exprFalse);

        DefaultNodeFinder nodeFinderWithExpr = new DefaultNodeFinder(bpmnModelCache, historyService,
                mockExprMgr, null);

        List<String> result2 = nodeFinderWithExpr.findReachableEndEvents("proc-end-nodflt", "task", vars);
        assertThat(result2).isEmpty();
    }

    /**
     * 排他网关条件匹配：task → egw → EndEvent(amount>5000)，条件不匹配 → 空列表。
     */
    @Test
    public void testFindReachableEndEventsGatewayConditionMismatch() {
        TestModelBuilder builder = new TestModelBuilder();
        UserTask task = builder.addUserTask("task");
        ExclusiveGateway egw = builder.addExclusiveGateway("egw");
        EndEvent end = builder.addEndEvent("end");
        UserTask altTask = builder.addUserTask("altTask");

        builder.addSequenceFlow("f1", task, egw);
        builder.addSequenceFlowWithCondition("f2a", egw, end, "${amount > 5000}");
        builder.addSequenceFlowWithCondition("f2b", egw, altTask, "${amount <= 5000}");

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-end-gwcond")).thenReturn(model);

        ExpressionManager mockExprMgr = Mockito.mock(ExpressionManager.class);
        Expression exprTrue = Mockito.mock(Expression.class);
        Expression exprFalse = Mockito.mock(Expression.class);
        when(exprTrue.getValue(Mockito.any())).thenReturn(true);
        when(exprFalse.getValue(Mockito.any())).thenReturn(false);
        when(mockExprMgr.createExpression("amount > 5000")).thenReturn(exprTrue);
        when(mockExprMgr.createExpression("amount <= 5000")).thenReturn(exprFalse);

        DefaultNodeFinder nodeFinderWithExpr = new DefaultNodeFinder(bpmnModelCache, historyService,
                mockExprMgr, null);

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 6000);

        // 走 amount>5000 分支 → EndEvent → 终止
        List<String> result = nodeFinderWithExpr.findReachableEndEvents("proc-end-gwcond", "task", vars);
        assertThat(result).containsExactly("end");

        // 走 amount<=5000: 换个条件
        when(mockExprMgr.createExpression("amount > 5000")).thenReturn(exprFalse);
        when(mockExprMgr.createExpression("amount <= 5000")).thenReturn(exprTrue);

        // 走 altTask 分支 → UserTask → 不终止
        List<String> result2 = nodeFinderWithExpr.findReachableEndEvents("proc-end-gwcond", "task", vars);
        assertThat(result2).isEmpty();
    }

    // ======================== 非受控汇合 & 历史过滤 ========================

    /**
     * 非受控汇合：handler 有 4 条入线（无网关），历史仅 chairman 执行过。
     * 验证 findPreviousNodes 通过历史过滤返回单一结果。
     */
    @Test
    public void testFindPreviousNodesUncontrolledMerge() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask chairman = builder.addUserTask("chairman");
        UserTask executive = builder.addUserTask("executive");
        UserTask integratedAdminDept = builder.addUserTask("integratedAdminDept");
        UserTask subsidiaryManager = builder.addUserTask("subsidiaryManager");
        UserTask handler = builder.addUserTask("handler");

        builder.addSequenceFlow("f1", start, chairman);
        builder.addSequenceFlow("f1b", start, executive);
        builder.addSequenceFlow("f1c", start, integratedAdminDept);
        builder.addSequenceFlow("f1d", start, subsidiaryManager);
        builder.addSequenceFlow("f2a", chairman, handler);
        builder.addSequenceFlow("f2b", executive, handler);
        builder.addSequenceFlow("f2c", integratedAdminDept, handler);
        builder.addSequenceFlow("f2d", subsidiaryManager, handler);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-um")).thenReturn(model);

        // filterByHistory 按 result 列表顺序查询：chairman=1，其余=0
        stubCountQueries("pi-um", 1L, 0L, 0L, 0L);

        List<String> result = nodeFinder.findPreviousNodes("proc-um", "handler", "pi-um");

        assertThat(result).containsExactly("chairman");
    }

    /**
     * 排他网关 merge + 历史全部缺失：taskA→gw→task2, taskB→gw→task2，
     * 历史无 taskA/taskB 记录。验证不盲猜首条，而是抛 NoPreviousNodeException。
     */
    @Test
    public void testFindPreviousNodesExclusiveGatewayHistoryAllMissed() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask taskA = builder.addUserTask("taskA");
        UserTask taskB = builder.addUserTask("taskB");
        ExclusiveGateway gwMerge = builder.addExclusiveGateway("gw_merge");
        UserTask task2 = builder.addUserTask("task2");

        builder.addSequenceFlow("f1a", start, taskA);
        builder.addSequenceFlow("f1b", start, taskB);
        builder.addSequenceFlow("f2a", taskA, gwMerge);
        builder.addSequenceFlow("f2b", taskB, gwMerge);
        builder.addSequenceFlow("f3", gwMerge, task2);

        BpmnModel model = builder.build();
        when(repositoryService.getBpmnModel("proc-gw-miss")).thenReturn(model);

        // resolveExclusiveGateway: taskA=0, taskB=0 → 返回全量入边
        // filterByHistory: taskA=0, taskB=0 → 空列表 → NoPreviousNodeException
        stubCountQueries("pi-miss", 0L, 0L, 0L, 0L);

        assertThatThrownBy(() -> nodeFinder.findPreviousNodes("proc-gw-miss", "task2", "pi-miss"))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("task2 无上一审批节点");
    }

    private void stubCountQueries(String processInstanceId, Long... counts) {
        HistoricActivityInstanceQuery query = Mockito.mock(HistoricActivityInstanceQuery.class);
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(processInstanceId)).thenReturn(query);
        when(query.finished()).thenReturn(query);
        when(query.activityId(anyString())).thenReturn(query);
        when(query.count()).thenReturn(counts[0], java.util.Arrays.copyOfRange(counts, 1, counts.length));
    }

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

    private void setExtensionAttribute(UserTask userTask, String namespace, String name, String value) {
        ExtensionAttribute attr = new ExtensionAttribute();
        attr.setNamespace(namespace);
        attr.setName(name);
        attr.setValue(value);
        userTask.addAttribute(attr);
    }
}
