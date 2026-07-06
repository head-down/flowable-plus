package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FlowablePlus 健康检查自动配置。
 *
 * <p>当以下条件全部满足时生效：
 * <ul>
 *   <li>classpath 上存在 Spring Boot Actuator（{@link HealthIndicator}）</li>
 *   <li>classpath 上存在 {@link FlowablePlus}</li>
 *   <li>{@code flowable.plus.enabled=true}（默认值）</li>
 * </ul>
 * 向 Actuator 注册一个 health indicator，用于在 /actuator/health 端点
 * 中暴露 flowable-plus 组件的激活状态及多实例任务统计。</p>
 *
 * @author flowable-plus
 */
@Configuration
@ConditionalOnClass({FlowablePlus.class, HealthIndicator.class})
@ConditionalOnProperty(name = "flowable.plus.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlowablePlusCounterSignProperties.class)
public class FlowablePlusHealthContributorAutoConfiguration {

    /**
     * 注册 FlowablePlus 健康指示器，包含多实例任务统计。
     *
     * <p>当 FlowablePlus 自动配置已激活时，此 indicator 报告 UP 状态及以下详情：
     * <ul>
     *   <li>{@code component} — 组件标识</li>
     *   <li>{@code counter-sign-enabled} — 会签功能是否启用</li>
     *   <li>{@code active-process-instances} — 活跃流程实例数</li>
     *   <li>{@code active-tasks} — 活跃任务数</li>
     * </ul>
     * 用户可通过声明同名 Bean 覆盖。</p>
     *
     * @param processEngine    Flowable 流程引擎
     * @param counterSignProps 会签配置属性
     * @return HealthIndicator 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "flowablePlusHealthIndicator")
    public HealthIndicator flowablePlusHealthIndicator(ProcessEngine processEngine,
                                                        FlowablePlusCounterSignProperties counterSignProps) {
        return () -> {
            RuntimeService runtimeService = processEngine.getRuntimeService();
            TaskService taskService = processEngine.getTaskService();

            Health.Builder builder = Health.up()
                    .withDetail("component", "flowable-plus")
                    .withDetail("counter-sign-enabled", counterSignProps.isEnabled())
                    .withDetail("active-process-instances",
                            runtimeService.createProcessInstanceQuery().count())
                    .withDetail("active-tasks",
                            taskService.createTaskQuery().count());

            return builder.build();
        };
    }
}
