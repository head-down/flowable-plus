package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.engine.ProcessEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flowable Plus auto-configuration.
 *
 * <p>当 classpath 上存在 {@link ProcessEngine} 时自动激活，
 * 注册 {@link FlowablePlus} Bean 作为工作流增强的统一入口。</p>
 *
 * <p>可通过 {@code flowable.plus.enabled=false} 禁用。</p>
 *
 * @author flowable-plus
 */
@Configuration
@ConditionalOnClass(name = "org.flowable.engine.ProcessEngine")
@EnableConfigurationProperties(FlowablePlusProperties.class)
public class FlowablePlusAutoConfiguration {

    /**
     * 注册 FlowablePlus Bean。
     *
     * <p>当 {@code flowable.plus.enabled=true}（默认）时生效，
     * 且允许用户通过自定义同类型 Bean 覆盖。</p>
     *
     * @param processEngine Flowable 流程引擎（由 flowable-spring-boot-starter 提供）
     * @param userContext   用户上下文（可被应用覆盖）
     * @return FlowablePlus 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "flowable.plus.enabled", havingValue = "true", matchIfMissing = true)
    public FlowablePlus flowablePlus(ProcessEngine processEngine, UserContext userContext) {
        return new FlowablePlus(processEngine, userContext);
    }

    /**
     * 默认 UserContext Bean，从 Spring Security 上下文读取当前用户。
     *
     * <p>应用可通过声明同名 Bean 覆盖此默认实现。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public UserContext userContext() {
        return () -> {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return "anonymous";
            }
            return auth.getName();
        };
    }
}
