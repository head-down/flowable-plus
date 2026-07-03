package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 中暴露 flowable-plus 组件的激活状态。</p>
 *
 * @author flowable-plus
 */
@Configuration
@ConditionalOnClass({FlowablePlus.class, HealthIndicator.class})
@ConditionalOnProperty(name = "flowable.plus.enabled", havingValue = "true", matchIfMissing = true)
public class FlowablePlusHealthContributorAutoConfiguration {

    /**
     * 注册 FlowablePlus 健康指示器。
     *
     * <p>当 FlowablePlus 自动配置已激活时，此 indicator 报告 UP 状态。
     * 用户可通过声明同名 Bean 覆盖。</p>
     *
     * @return HealthIndicator 实例，始终返回 UP
     */
    @Bean
    @ConditionalOnMissingBean(name = "flowablePlusHealthIndicator")
    public HealthIndicator flowablePlusHealthIndicator() {
        return () -> Health.up()
                .withDetail("component", "flowable-plus")
                .withDetail("description", "FlowablePlus 工作流增强引擎已激活")
                .build();
    }
}
