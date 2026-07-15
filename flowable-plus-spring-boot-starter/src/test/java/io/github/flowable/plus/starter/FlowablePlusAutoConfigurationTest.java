package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.api.QueryOperations;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.GroupResolver;

import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FlowablePlusAutoConfiguration 集成测试：
 * 验证自动配置的条件装配、属性开关、UserContext 回退行为、
 * 会签回调注册以及健康检查。
 */
class FlowablePlusAutoConfigurationTest {

    private static ProcessEngine mockProcessEngine() {
        ProcessEngine engine = mock(ProcessEngine.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        TaskService taskService = mock(TaskService.class);

        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.count()).thenReturn(3L);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.count()).thenReturn(7L);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);

        ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
        when(config.getExpressionManager()).thenReturn(mock(ExpressionManager.class));
        when(engine.getProcessEngineConfiguration()).thenReturn(config);

        when(engine.getRepositoryService()).thenReturn(mock(RepositoryService.class));
        when(engine.getRuntimeService()).thenReturn(runtimeService);
        when(engine.getTaskService()).thenReturn(taskService);
        when(engine.getHistoryService()).thenReturn(mock(HistoryService.class));
        when(engine.getIdentityService()).thenReturn(mock(IdentityService.class));
        return engine;
    }

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        ProcessEngine engine = mockProcessEngine();
        contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(FlowablePlusAutoConfiguration.class,
                        FlowablePlusHealthContributorAutoConfiguration.class)
                .withBean(ProcessEngine.class, () -> engine)
                .withBean(TaskService.class, engine::getTaskService)
                .withBean(HistoryService.class, engine::getHistoryService)
                .withBean(RuntimeService.class, engine::getRuntimeService)
                .withBean(RepositoryService.class, engine::getRepositoryService)
                .withBean(IdentityService.class, engine::getIdentityService);
    }

    @Test
    void testFlowablePlusBeanCreated() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(FlowablePlus.class);
            assertThat(ctx).hasSingleBean(UserContext.class);
        });
    }

    @Test
    void testFlowablePlusDisabled() {
        contextRunner
                .withPropertyValues("flowable.plus.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(FlowablePlus.class);
                });
    }

    @Test
    void testUserDefinedFlowablePlusNotOverridden() {
        FlowablePlus custom = mock(FlowablePlus.class);
        contextRunner
                .withBean(FlowablePlus.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FlowablePlus.class);
                    assertThat(ctx.getBean(FlowablePlus.class)).isSameAs(custom);
                });
    }

    @Test
    void testNoFlowablePlusWhenEngineDisabled() {
        // classpath 上 ProcessEngine 类始终存在，因此 @ConditionalOnClass 条件满足。
        // 但实际不存在 ProcessEngine Bean 时，FlowablePlus Bean 创建会因缺少依赖而失败，
        // 这本身验证了"没有 ProcessEngine 就没有 FlowablePlus"的设计意图。
        new ApplicationContextRunner()
                .withUserConfiguration(FlowablePlusAutoConfiguration.class,
                        FlowablePlusHealthContributorAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).isNotNull();
                });
    }

    @Test
    void testUserContextReturnsAnonymousWhenNoAuthentication() {
        SecurityContextHolder.clearContext();
        contextRunner.run(ctx -> {
            UserContext userContext = ctx.getBean(UserContext.class);
            assertThat(userContext.getCurrentUserId()).isEqualTo("anonymous");
        });
    }

    // ======================== CounterSignCallback 自动注册测试 ========================

    @Test
    void testCounterSignCallbackAutoRegistration() {
        CounterSignCallback callback = mock(CounterSignCallback.class);
        contextRunner
                .withBean(CounterSignCallback.class, () -> callback)
                .run(ctx -> {
                    // 验证 FlowablePlus 正常创建（回调被注入）
                    assertThat(ctx).hasSingleBean(FlowablePlus.class);
                    assertThat(ctx).hasSingleBean(CounterSignCallback.class);
                });
    }

    @Test
    void testCounterSignDisabled() {
        contextRunner
                .withPropertyValues("flowable.plus.counter-sign.enabled=false")
                .run(ctx -> {
                    // FlowablePlus 仍然创建，但会签回调被禁用
                    assertThat(ctx).hasSingleBean(FlowablePlus.class);
                    // 健康检查应反映会签已禁用
                    HealthIndicator indicator = ctx.getBean(HealthIndicator.class);
                    assertThat(indicator.health().getDetails())
                            .containsEntry("counter-sign-enabled", false);
                });
    }

    @Test
    void testCounterSignCallbackRegisteredWhenEnabled() {
        CounterSignCallback callback = mock(CounterSignCallback.class);
        contextRunner
                .withBean(CounterSignCallback.class, () -> callback)
                .run(ctx -> {
                    // 会签启用时，回调 Bean 和 FlowablePlus 共存
                    assertThat(ctx.getBean(CounterSignCallback.class)).isSameAs(callback);
                    assertThat(ctx).hasSingleBean(FlowablePlus.class);
                    // 健康检查应反映会签已启用
                    HealthIndicator indicator = ctx.getBean(HealthIndicator.class);
                    assertThat(indicator.health().getDetails())
                            .containsEntry("counter-sign-enabled", true);
                });
    }

    // ======================== 健康检查测试 ========================

    @Test
    void testHealthIndicatorReportsUp() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(HealthIndicator.class);
            HealthIndicator indicator = ctx.getBean(HealthIndicator.class);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(indicator.health().getDetails())
                    .containsEntry("component", "flowable-plus")
                    .containsEntry("counter-sign-enabled", true)
                    .containsEntry("active-process-instances", 3L)
                    .containsEntry("active-tasks", 7L);
        });
    }

    @Test
    void testHealthIndicatorDisabledWhenFlowablePlusDisabled() {
        contextRunner
                .withPropertyValues("flowable.plus.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(FlowablePlus.class);
                    assertThat(ctx).doesNotHaveBean(HealthIndicator.class);
                });
    }

    // ======================== 三期自动配置 Bean 验证 ========================

    @Test
    void testGroupResolverDefaultBeanRegistered() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(GroupResolver.class);
            assertThat(ctx.getBean(GroupResolver.class)).isInstanceOf(IdentityGroupResolver.class);
        });
    }

    @Test
    void testFlowablePlusBeanImplementsQueryOperations() {
        contextRunner.run(ctx -> {
            FlowablePlus bean = ctx.getBean(FlowablePlus.class);
            assertThat(bean).isInstanceOf(QueryOperations.class);
        });
    }

    @Test
    void testUserDefinedGroupResolverNotOverridden() {
        GroupResolver custom = groupId -> Collections.emptyList();
        contextRunner
                .withBean(GroupResolver.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx.getBean(GroupResolver.class)).isSameAs(custom);
                });
    }

}
