package io.github.flowable.plus.core;

import io.github.flowable.plus.core.api.ApprovalOperations;
import io.github.flowable.plus.core.api.CounterSignOperations;
import io.github.flowable.plus.core.workflow.CounterSignWorkflow;
import io.github.flowable.plus.core.workflow.NodePreviewWorkflow;
import io.github.flowable.plus.core.workflow.ProcessQueryWorkflow;
import io.github.flowable.plus.core.workflow.TaskQueryModule;
import io.github.flowable.plus.core.workflow.TaskWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * FlowablePlus 构造注入测试：验证构造参数校验。
 *
 * <p>常规任务推进与驳回操作已下沉至 {@link TaskWorkflow}（{@link ApprovalOperations}），
 * 会签操作下沉至 {@link CounterSignWorkflow}（{@link CounterSignOperations}），
 * 待办/已办查询委托给 {@link TaskQueryModule}，
 * 节点预览委托给 {@link NodePreviewWorkflow}，
 * 流程追踪委托给 {@link ProcessQueryWorkflow}。</p>
 */
public class FlowablePlusTest {

    private TaskQueryModule mockTaskQueryModule;
    private ProcessQueryWorkflow mockProcessQueryWorkflow;
    private NodePreviewWorkflow mockNodePreviewWorkflow;

    @BeforeEach
    public void setUp() {
        mockTaskQueryModule = mock(TaskQueryModule.class);
        mockProcessQueryWorkflow = mock(ProcessQueryWorkflow.class);
        mockNodePreviewWorkflow = mock(NodePreviewWorkflow.class);
    }

    @Test
    public void testConstructorRejectsNullTaskQueryModule() {
        assertThatThrownBy(() -> new FlowablePlus(null, mockProcessQueryWorkflow, mockNodePreviewWorkflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskQueryModule 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullProcessQueryWorkflow() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, null, mockNodePreviewWorkflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProcessQueryWorkflow 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullNodePreviewWorkflow() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockProcessQueryWorkflow, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodePreviewWorkflow 不可为 null");
    }
}
