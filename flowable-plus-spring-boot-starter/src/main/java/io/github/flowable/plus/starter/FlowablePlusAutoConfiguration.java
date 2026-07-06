package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.BpmnModelCache;
import io.github.flowable.plus.core.DefaultBpmnModelCache;
import io.github.flowable.plus.core.DefaultNodeFinder;
import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.NodeFinder;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.engine.ProcessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;

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
@EnableConfigurationProperties(FlowablePlusCounterSignProperties.class)
public class FlowablePlusAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlowablePlusAutoConfiguration.class);

    /**
     * 启动时输出自动配置激活日志，便于排查配置是否生效。
     */
    @PostConstruct
    void logActivation() {
        log.info("FlowablePlus auto-configuration activated");
    }

    /**
     * 注册默认 BpmnModelCache Bean。
     *
     * <p>基于 ConcurrentHashMap，BPMN 模型部署后不可变，永不过期。
     * 应用可通过声明同名 Bean 替换缓存策略（如 LRU、TTL 等）。</p>
     *
     * @param processEngine Flowable 流程引擎
     * @return DefaultBpmnModelCache 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public BpmnModelCache bpmnModelCache(ProcessEngine processEngine) {
        return new DefaultBpmnModelCache(processEngine.getRepositoryService());
    }

    /**
     * 注册默认 NodeFinder Bean。
     *
     * <p>应用可通过声明同名 Bean 替换默认的 BPMN 节点遍历策略。</p>
     *
     * @param bpmnModelCache BPMN 模型缓存
     * @param processEngine  Flowable 流程引擎
     * @return DefaultNodeFinder 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NodeFinder nodeFinder(BpmnModelCache bpmnModelCache, ProcessEngine processEngine) {
        return new DefaultNodeFinder(bpmnModelCache, processEngine.getHistoryService());
    }

    /**
     * 注册 FlowablePlus Bean。
     *
     * <p>当 {@code flowable.plus.enabled=true}（默认）时生效，
     * 且允许用户通过自定义同类型 Bean 覆盖。</p>
     *
     * <p>当 {@code flowable.plus.counter-sign.enabled=false} 时，
     * 会签回调列表为空，CounterSignCallback 不会触发，但核心 API 仍可调用。</p>
     *
     * @param processEngine        Flowable 流程引擎（由 flowable-spring-boot-starter 提供）
     * @param userContext           用户上下文（可被应用覆盖）
     * @param nodeFinder            BPMN 节点遍历策略（可被应用覆盖）
     * @param bpmnModelCache        BPMN 模型缓存（可被应用覆盖）
     * @param counterSignCallbacks  会签回调列表（可选，无实现时为空列表）
     * @param counterSignProps     会签配置属性
     * @return FlowablePlus 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "flowable.plus.enabled", havingValue = "true", matchIfMissing = true)
    public FlowablePlus flowablePlus(ProcessEngine processEngine, UserContext userContext,
                                     NodeFinder nodeFinder, BpmnModelCache bpmnModelCache,
                                     @Autowired(required = false) List<CounterSignCallback> counterSignCallbacks,
                                     FlowablePlusCounterSignProperties counterSignProps) {
        List<CounterSignCallback> callbacks = counterSignProps.isEnabled() && counterSignCallbacks != null
                ? counterSignCallbacks : Collections.emptyList();
        return new FlowablePlus(processEngine, userContext, nodeFinder, bpmnModelCache, callbacks);
    }

    /**
     * Spring Security 环境的 UserContext Bean，从认证上下文读取当前用户。
     *
     * <p>仅在 classpath 上存在 Spring Security 时生效。
     * 应用可通过声明同名 Bean 覆盖此实现。</p>
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.security.core.Authentication")
    @ConditionalOnMissingBean
    public UserContext securityContextUserContext() {
        return new SecurityContextUserContext();
    }

    /**
     * 非 Spring Security 环境的 UserContext 兜底 Bean。
     *
     * <p>当 classpath 上不存在 Spring Security 且未自定义 UserContext 时生效。
     * 从系统属性 {@code flowable.plus.user-id} 读取当前用户 ID，
     * 未设置时返回 {@code "system"}。生产环境建议注入认证框架对应的实现。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public UserContext systemPropertyUserContext() {
        return new SystemPropertyUserContext();
    }
}
