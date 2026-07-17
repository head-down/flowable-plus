package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.spi.ProcessEventListener;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事件自动配置测试。
 */
class EventAutoConfigurationTest {

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        RuntimeService rs = mock(RuntimeService.class);
        HistoryService hs = mock(HistoryService.class);
        TaskService ts = mock(TaskService.class);
        IdentityService is = mock(IdentityService.class);
        RepositoryService reps = mock(RepositoryService.class);
        ManagementService ms = mock(ManagementService.class);

        ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
        when(config.getExpressionManager()).thenReturn(mock(ExpressionManager.class));

        ProcessEngine engine = mock(ProcessEngine.class);
        when(engine.getRuntimeService()).thenReturn(rs);
        when(engine.getIdentityService()).thenReturn(is);
        when(engine.getRepositoryService()).thenReturn(reps);
        when(engine.getHistoryService()).thenReturn(hs);
        when(engine.getTaskService()).thenReturn(ts);
        when(engine.getManagementService()).thenReturn(ms);
        when(engine.getProcessEngineConfiguration()).thenReturn(config);

        contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlowablePlusAutoConfiguration.class))
                .withBean(ProcessEngine.class, () -> engine)
                .withBean(RuntimeService.class, () -> rs)
                .withBean(HistoryService.class, () -> hs)
                .withBean(TaskService.class, () -> ts)
                .withBean(IdentityService.class, () -> is)
                .withBean(RepositoryService.class, () -> reps);
    }

    @Configuration
    static class CustomListenerConfig {
        @Bean
        ProcessEventListener customListener() {
            return new ProcessEventListener() {};
        }
    }

    @Configuration
    static class TwoListenersConfig {
        @Bean
        ProcessEventListener listener1() {
            return new ProcessEventListener() {};
        }
        @Bean
        ProcessEventListener listener2() {
            return new ProcessEventListener() {};
        }
    }

    @Test
    void shouldRegisterEventPublisherWhenEnabled() {
        contextRunner
                .withUserConfiguration(CustomListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(EventPublisher.class);
                });
    }

    @Test
    void shouldRegisterAsyncPublisherWhenAsyncEnabled() {
        contextRunner
                .withUserConfiguration(CustomListenerConfig.class)
                .withPropertyValues("flowable.plus.event.async=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(EventPublisher.class);
                    assertThat(context.getBean(EventPublisher.class)).isInstanceOf(AsyncEventPublisher.class);
                });
    }

    @Test
    void shouldRegisterSyncPublisherWhenAsyncDisabled() {
        contextRunner
                .withUserConfiguration(CustomListenerConfig.class)
                .withPropertyValues("flowable.plus.event.async=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(EventPublisher.class);
                    assertThat(context.getBean(EventPublisher.class))
                            .isInstanceOf(io.github.flowable.plus.core.event.DefaultEventPublisher.class);
                });
    }

    @Test
    void shouldNotRegisterEventPublisherWhenDisabled() {
        contextRunner
                .withPropertyValues("flowable.plus.event.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EventPublisher.class);
                });
    }

    @Test
    void shouldCollectMultipleListeners() {
        contextRunner
                .withUserConfiguration(TwoListenersConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(EventPublisher.class);
                    assertThat(context.getBeansOfType(ProcessEventListener.class)).hasSize(2);
                });
    }

    @Test
    void shouldRegisterEventExecutorWithCorrectPrefix() {
        contextRunner
                .withUserConfiguration(CustomListenerConfig.class)
                .withPropertyValues("flowable.plus.event.async=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("flowable-plus-event-");
                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(4);
                });
    }
}
