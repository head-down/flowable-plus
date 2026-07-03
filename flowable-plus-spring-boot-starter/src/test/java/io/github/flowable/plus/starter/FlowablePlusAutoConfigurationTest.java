package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FlowablePlusAutoConfiguration 集成测试：
 * 验证自动配置的条件装配、属性开关、UserContext 回退行为以及健康检查。
 */
class FlowablePlusAutoConfigurationTest {

    private static ProcessEngine mockProcessEngine() {
        ProcessEngine engine = mock(ProcessEngine.class);
        when(engine.getRepositoryService()).thenReturn(mock(RepositoryService.class));
        when(engine.getRuntimeService()).thenReturn(mock(RuntimeService.class));
        when(engine.getTaskService()).thenReturn(mock(TaskService.class));
        when(engine.getHistoryService()).thenReturn(mock(HistoryService.class));
        when(engine.getIdentityService()).thenReturn(mock(IdentityService.class));
        return engine;
    }

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(FlowablePlusAutoConfiguration.class,
                        FlowablePlusHealthContributorAutoConfiguration.class)
                .withBean(ProcessEngine.class, FlowablePlusAutoConfigurationTest::mockProcessEngine);
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
    void testUserContextReturnsAnonymousWhenNoAuthentication() {
        SecurityContextHolder.clearContext();
        contextRunner.run(ctx -> {
            UserContext userContext = ctx.getBean(UserContext.class);
            assertThat(userContext.getCurrentUserId()).isEqualTo("anonymous");
        });
    }

    @Test
    void testHealthIndicatorReportsUp() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(HealthIndicator.class);
            HealthIndicator indicator = ctx.getBean(HealthIndicator.class);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(indicator.health().getDetails())
                    .containsEntry("component", "flowable-plus");
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
}
