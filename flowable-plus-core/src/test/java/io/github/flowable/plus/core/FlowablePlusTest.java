package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.ApproverResolver;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * FlowablePlus 构造注入测试：验证构造参数校验。
 *
 * <p>常规任务推进与驳回操作已下沉至 {@link TaskWorkflow}（{@link TaskOperations}/
 * {@link RejectionOperations}/{@link ProcessLifecycle}），会签操作下沉至
 * {@link CounterSignWorkflow}（{@link CounterSignOperations}），应在对应测试类中验证。
 * 待办/已办查询委托给 {@link TaskQueryModule}，节点预览逻辑内聚于本模块。</p>
 */
public class FlowablePlusTest {

    private TaskQueryModule mockTaskQueryModule;
    private RuntimeService mockRuntimeService;
    private RepositoryService mockRepositoryService;
    private TaskService mockTaskService;
    private NodeFinder mockNodeFinder;
    private BpmnModelCache mockBpmnModelCache;
    private ApproverResolver mockApproverResolver;
    private BpmnFormDataHelper bpmnFormDataHelper;

    @BeforeEach
    public void setUp() {
        mockTaskQueryModule = mock(TaskQueryModule.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockRepositoryService = mock(RepositoryService.class);
        mockTaskService = mock(TaskService.class);
        mockNodeFinder = mock(NodeFinder.class);
        mockBpmnModelCache = mock(BpmnModelCache.class);
        mockApproverResolver = mock(ApproverResolver.class);
        bpmnFormDataHelper = new BpmnFormDataHelper();
    }

    // ======================== 构造注入 ========================

    @Test
    public void testConstructorRejectsNullTaskQueryModule() {
        assertThatThrownBy(() -> new FlowablePlus(null, mockRuntimeService, mockRepositoryService,
                mockTaskService, mockNodeFinder, mockBpmnModelCache, mockApproverResolver, bpmnFormDataHelper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskQueryModule 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullRuntimeService() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, null, mockRepositoryService,
                mockTaskService, mockNodeFinder, mockBpmnModelCache, mockApproverResolver, bpmnFormDataHelper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RuntimeService 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullRepositoryService() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockRuntimeService, null,
                mockTaskService, mockNodeFinder, mockBpmnModelCache, mockApproverResolver, bpmnFormDataHelper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RepositoryService 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullTaskService() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockRuntimeService, mockRepositoryService,
                null, mockNodeFinder, mockBpmnModelCache, mockApproverResolver, bpmnFormDataHelper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskService 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullNodeFinder() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockRuntimeService, mockRepositoryService,
                mockTaskService, null, mockBpmnModelCache, mockApproverResolver, bpmnFormDataHelper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullBpmnModelCache() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockRuntimeService, mockRepositoryService,
                mockTaskService, mockNodeFinder, null, mockApproverResolver, bpmnFormDataHelper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BpmnModelCache 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullApproverResolver() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockRuntimeService, mockRepositoryService,
                mockTaskService, mockNodeFinder, mockBpmnModelCache, null, bpmnFormDataHelper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ApproverResolver 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullBpmnFormDataHelper() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskQueryModule, mockRuntimeService, mockRepositoryService,
                mockTaskService, mockNodeFinder, mockBpmnModelCache, mockApproverResolver, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BpmnFormDataHelper 不可为 null");
    }
}
