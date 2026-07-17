package io.github.flowable.plus.core;

import io.github.flowable.plus.core.api.HistoryOperations;
import io.github.flowable.plus.core.workflow.DiagramWorkflow;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import io.github.flowable.plus.core.workflow.NodePreviewWorkflow;
import io.github.flowable.plus.core.workflow.ProcessQueryWorkflow;
import io.github.flowable.plus.core.workflow.TaskQueryModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * FlowablePlus 构造注入测试：验证构造参数校验。
 *
 * <p>常规任务操作下沉至 ProcessLifecycleWorkflow 和 TaskExecutionWorkflow，
 * 待办/已办查询委托给 {@link TaskQueryModule}，
 * 节点预览委托给 {@link NodePreviewWorkflow}，
 * 流程追踪委托给 {@link ProcessQueryWorkflow}，
 * 流程图委托给 {@link DiagramWorkflow}，
 * 审批历史委托给 {@link HistoryWorkflow}（{@link HistoryOperations}）。</p>
 */
public class FlowablePlusTest {

    private TaskQueryModule mockTaskQueryModule;
    private ProcessQueryWorkflow mockProcessQueryWorkflow;
    private NodePreviewWorkflow mockNodePreviewWorkflow;
    private DiagramWorkflow mockDiagramWorkflow;
    private HistoryWorkflow mockHistoryWorkflow;

    @BeforeEach
    public void setUp() {
        mockTaskQueryModule = mock(TaskQueryModule.class);
        mockProcessQueryWorkflow = mock(ProcessQueryWorkflow.class);
        mockNodePreviewWorkflow = mock(NodePreviewWorkflow.class);
        mockDiagramWorkflow = mock(DiagramWorkflow.class);
        mockHistoryWorkflow = mock(HistoryWorkflow.class);
    }

    @Test
    public void testConstructorRejectsNullTaskQueryModule() {
        assertThatThrownBy(() -> new FlowablePlus(null, mockProcessQueryWorkflow, mockNodePreviewWorkflow,
                mockDiagramWorkflow, mockHistoryWorkflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskQueryModule 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullProcessQueryWorkflow() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, null, mockNodePreviewWorkflow,
                mockDiagramWorkflow, mockHistoryWorkflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProcessQueryWorkflow 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullNodePreviewWorkflow() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockProcessQueryWorkflow, null,
                mockDiagramWorkflow, mockHistoryWorkflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodePreviewWorkflow 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullDiagramWorkflow() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockProcessQueryWorkflow,
                mockNodePreviewWorkflow, null, mockHistoryWorkflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DiagramWorkflow 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullHistoryWorkflow() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockProcessQueryWorkflow,
                mockNodePreviewWorkflow, mockDiagramWorkflow, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HistoryWorkflow 不可为 null");
    }
}
