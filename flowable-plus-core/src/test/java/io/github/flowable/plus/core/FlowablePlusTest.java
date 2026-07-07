package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
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

    private ProcessEngine mockEngine;
    private BpmnModelCache bpmnModelCache;
    private UserContext userContext;
    private NodeFinder mockNodeFinder;

    @BeforeEach
    public void setUp() {
        mockEngine = mock(ProcessEngine.class);
        RepositoryService mockRepoService = mock(RepositoryService.class);
        when(mockEngine.getRepositoryService()).thenReturn(mockRepoService);

        ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
        when(config.getExpressionManager()).thenReturn(mock(ExpressionManager.class));
        when(mockEngine.getProcessEngineConfiguration()).thenReturn(config);

        userContext = () -> "testUser";
        mockNodeFinder = mock(NodeFinder.class);
        bpmnModelCache = new DefaultBpmnModelCache(mockRepoService);
    }

    // ======================== 构造注入 ========================

    @Test
    public void testConstructorInjectsProcessEngine() {
        FlowablePlus fp = new FlowablePlus(mockEngine, userContext, mockNodeFinder, bpmnModelCache, null);
        assertThat(fp.getProcessEngine()).isSameAs(mockEngine);
    }

    @Test
    public void testConstructorRejectsNullProcessEngine() {
        assertThatThrownBy(() -> new FlowablePlus(null, userContext, mockNodeFinder, bpmnModelCache, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProcessEngine 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullUserContext() {
        assertThatThrownBy(() -> new FlowablePlus(mockEngine, null, mockNodeFinder, bpmnModelCache, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserContext 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullNodeFinder() {
        assertThatThrownBy(() -> new FlowablePlus(mockEngine, userContext, null, bpmnModelCache, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullBpmnModelCache() {
        assertThatThrownBy(() -> new FlowablePlus(mockEngine, userContext, mockNodeFinder, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BpmnModelCache 不可为 null");
    }

    // ======================== getter ========================

    @Test
    public void testGetUserContext() {
        FlowablePlus fp = new FlowablePlus(mockEngine, userContext, mockNodeFinder, bpmnModelCache, null);
        assertThat(fp.getUserContext()).isSameAs(userContext);
    }
}
