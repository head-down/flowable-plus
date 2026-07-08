package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FlowablePlus 构造注入测试：验证构造参数校验和字段注入。
 *
 * <p>常规任务推进与驳回操作已下沉至 {@link TaskWorkflow}（{@link TaskOperations}/
 * {@link RejectionOperations}/{@link ProcessLifecycle}），会签操作下沉至
 * {@link CounterSignWorkflow}（{@link CounterSignOperations}），应在对应测试类中验证。</p>
 */
public class FlowablePlusTest {

    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private RuntimeService mockRuntimeService;
    private RepositoryService mockRepositoryService;
    private IdentityService mockIdentityService;
    private BpmnModelCache bpmnModelCache;
    private UserContext userContext;
    private NodeFinder mockNodeFinder;
    private ApproverResolver mockApproverResolver;

    @BeforeEach
    public void setUp() {
        ProcessEngine mockEngine = mock(ProcessEngine.class);
        mockRepositoryService = mock(RepositoryService.class);
        when(mockEngine.getRepositoryService()).thenReturn(mockRepositoryService);

        ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
        when(config.getExpressionManager()).thenReturn(mock(ExpressionManager.class));
        when(mockEngine.getProcessEngineConfiguration()).thenReturn(config);

        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockIdentityService = mock(IdentityService.class);
        userContext = () -> "testUser";
        mockNodeFinder = mock(NodeFinder.class);
        mockApproverResolver = mock(ApproverResolver.class);
        bpmnModelCache = new DefaultBpmnModelCache(mockRepositoryService);
    }

    // ======================== 构造注入 ========================

    @Test
    public void testConstructorInjectsTaskService() {
        FlowablePlus fp = new FlowablePlus(mockTaskService, mockHistoryService, mockRuntimeService,
                mockRepositoryService, mockIdentityService, userContext, mockNodeFinder,
                bpmnModelCache, mockApproverResolver);
        assertThat(fp.getTaskService()).isSameAs(mockTaskService);
    }

    @Test
    public void testConstructorRejectsNullTaskService() {
        assertThatThrownBy(() -> new FlowablePlus(null, mockHistoryService, mockRuntimeService,
                mockRepositoryService, mockIdentityService, userContext, mockNodeFinder,
                bpmnModelCache, mockApproverResolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskService 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullUserContext() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskService, mockHistoryService, mockRuntimeService,
                mockRepositoryService, mockIdentityService, null, mockNodeFinder,
                bpmnModelCache, mockApproverResolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserContext 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullNodeFinder() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskService, mockHistoryService, mockRuntimeService,
                mockRepositoryService, mockIdentityService, userContext, null,
                bpmnModelCache, mockApproverResolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullBpmnModelCache() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskService, mockHistoryService, mockRuntimeService,
                mockRepositoryService, mockIdentityService, userContext, mockNodeFinder,
                null, mockApproverResolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BpmnModelCache 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullApproverResolver() {
        assertThatThrownBy(() -> new FlowablePlus(mockTaskService, mockHistoryService, mockRuntimeService,
                mockRepositoryService, mockIdentityService, userContext, mockNodeFinder,
                bpmnModelCache, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ApproverResolver 不可为 null");
    }

    // ======================== getter ========================

    @Test
    public void testGetUserContext() {
        FlowablePlus fp = new FlowablePlus(mockTaskService, mockHistoryService, mockRuntimeService,
                mockRepositoryService, mockIdentityService, userContext, mockNodeFinder,
                bpmnModelCache, mockApproverResolver);
        assertThat(fp.getUserContext()).isSameAs(userContext);
    }
}
