package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FlowablePlus 集成测试：验证构造注入、服务访问、业务委托和异常包装。
 */
public class FlowablePlusTest {

    private ProcessEngine mockEngine;
    private RepositoryService mockRepoService;
    private RuntimeService mockRuntimeService;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private FlowablePlus flowablePlus;

    @BeforeEach
    public void setUp() {
        mockEngine = mock(ProcessEngine.class);
        mockRepoService = mock(RepositoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);

        when(mockEngine.getRepositoryService()).thenReturn(mockRepoService);
        when(mockEngine.getRuntimeService()).thenReturn(mockRuntimeService);
        when(mockEngine.getTaskService()).thenReturn(mockTaskService);
        when(mockEngine.getHistoryService()).thenReturn(mockHistoryService);

        flowablePlus = new FlowablePlus(mockEngine);
    }

    // ======================== 构造注入 ========================

    @Test
    public void testConstructorInjectsProcessEngine() {
        assertThat(flowablePlus.getProcessEngine()).isSameAs(mockEngine);
    }

    @Test
    public void testConstructorRejectsNull() {
        assertThatThrownBy(() -> new FlowablePlus(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProcessEngine 不可为 null");
    }

    // ======================== Service getter ========================

    @Test
    public void testGetRepositoryService() {
        assertThat(flowablePlus.getRepositoryService()).isSameAs(mockRepoService);
    }

    @Test
    public void testGetRuntimeService() {
        assertThat(flowablePlus.getRuntimeService()).isSameAs(mockRuntimeService);
    }

    @Test
    public void testGetTaskService() {
        assertThat(flowablePlus.getTaskService()).isSameAs(mockTaskService);
    }

    @Test
    public void testGetHistoryService() {
        assertThat(flowablePlus.getHistoryService()).isSameAs(mockHistoryService);
    }

    // ======================== findPreviousNodes ========================

    /**
     * 正常委托：start → task1 → task2，从 task2 回溯应找到 [task1]
     */
    @Test
    public void testFindPreviousNodesDelegation() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        List<String> result = flowablePlus.findPreviousNodes("proc-1", "task2", null);

        assertThat(result).containsExactly("task1");
    }

    @Test
    public void testFindPreviousNodesModelNotFound() {
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(null);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "task1", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 proc-1 不存在");
    }

    @Test
    public void testFindPreviousNodesNodeNotFound() {
        TestModelBuilder builder = new TestModelBuilder();
        builder.addStartEvent("start");
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "nonexistent", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("节点 nonexistent 不存在");
    }

    @Test
    public void testFindPreviousNodesNoPreviousNode() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "task1", null))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("节点 task1 无上一审批节点");
    }

    @Test
    public void testFindPreviousNodesNullProcDefId() {
        assertThatThrownBy(() -> flowablePlus.findPreviousNodes(null, "task1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    @Test
    public void testFindPreviousNodesNullActivityId() {
        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentActivityId");
    }

    // ======================== findInitiatorNode ========================

    /**
     * 正常委托：start → task1，应返回 task1
     */
    @Test
    public void testFindInitiatorNodeDelegation() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        String result = flowablePlus.findInitiatorNode("proc-1");

        assertThat(result).isEqualTo("task1");
    }

    @Test
    public void testFindInitiatorNodeModelNotFound() {
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(null);

        assertThatThrownBy(() -> flowablePlus.findInitiatorNode("proc-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 proc-1 不存在");
    }

    @Test
    public void testFindInitiatorNodeNullProcDefId() {
        assertThatThrownBy(() -> flowablePlus.findInitiatorNode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }
}
