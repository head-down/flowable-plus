package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.event.EventBus;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.extension.decision.DecisionContextAssembler;
import io.github.flowable.plus.extension.decision.DecisionCredentialResolver;
import io.github.flowable.plus.extension.decision.DecisionDefaultContextSources;
import io.github.flowable.plus.extension.decision.DecisionMetricsRecorder;
import io.github.flowable.plus.extension.decision.DecisionObservationEmitter;
import io.github.flowable.plus.extension.decision.DecisionObserver;
import io.github.flowable.plus.extension.decision.DecisionPipeline;
import io.github.flowable.plus.extension.decision.DecisionPolicy;
import io.github.flowable.plus.extension.decision.DecisionProvider;
import io.github.flowable.plus.extension.decision.DecisionRuntimeControl;
import io.github.flowable.plus.extension.decision.DecisionTarget;
import io.github.flowable.plus.extension.decision.DecisionTransport;
import io.github.flowable.plus.extension.decision.DefaultDecisionProvider;
import io.github.flowable.plus.extension.decision.DefaultDecisionRuntimeControl;
import io.github.flowable.plus.extension.decision.DefaultSuggestionSubmissionService;
import io.github.flowable.plus.extension.decision.HttpDecisionTransport;
import io.github.flowable.plus.extension.decision.SuggestionSubmissionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 决策接入的运行组件装配（ADR-0042 §7 / {@code docs/impl/0042-module-and-build.md} §2）。
 *
 * <p><b>装配条件只有一条</b>：extension 在 classpath 上（类级 {@code @ConditionalOnClass} 字符串形态，
 * marker = 机制面向 starter 的唯一入口契约类型 {@code SuggestionSubmissionService}）。
 * <b>全局开关是运行期门控、不是装配条件</b>（三层关闭矩阵，{@code module-and-build} §3）：extension
 * 存在时机制 Bean 一律注册，{@code flowable.plus.decision.enabled} 只在拉面闸门链 stage 1 与位点服务
 * 入口两个判定点被读。</p>
 *
 * <p><b>替换点只开五处</b>（{@code @ConditionalOnMissingBean}）：{@code DecisionRuntimeControl} /
 * {@code DecisionCredentialResolver} / {@code DecisionTransport} / {@code DecisionProvider} /
 * {@code DecisionMetricsRecorder}；内部件（位点服务、装配器、管线、监听器、注册表、池、索引）
 * <b>一律不开</b>替换点 —— 没有替换需求的 Bean 开替换点会让「不引 extension ⇒ 零 Bean」的集合恒等
 * 在运行期失去意义。</p>
 *
 * <p><b>凭据解析器无默认 Bean</b>：extension 交付的契约是「Bean 缺席 = 不需认证材料，合法装配态」
 * （{@code DecisionCredentialResolver} javadoc），{@code DefaultDecisionProvider} 对 {@code null}
 * resolver 与「解析返回 {@code null}」同判 —— 故本类不注册默认实现，应用 Bean 经
 * {@code ObjectProvider} 缺席透传为 {@code null}。</p>
 *
 * <p><b>指标装配</b>（§2.6）：方法级 {@code @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")}
 * + {@code ObjectProvider} 懒注册；本配置类的 <b>Bean 方法签名不出现 {@code MeterRegistry}</b>
 * （字节码安全：无 micrometer 时反射读方法签名不得 {@code NoClassDefFoundError}；实现类另置）。
 * 启动期零 meter（懒注册，首次观测才取）。</p>
 */
@Configuration
@ConditionalOnClass(name = "io.github.flowable.plus.extension.decision.SuggestionSubmissionService")
@EnableConfigurationProperties(FlowablePlusDecisionProperties.class)
public class FlowablePlusDecisionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlowablePlusDecisionAutoConfiguration.class);

    /** 专属池空闲线程存活秒数（主仓事件执行器同款量级；非配置项，属装配面既有形态） */
    private static final long EXECUTOR_KEEP_ALIVE_SECONDS = 60L;

    /**
     * 专属池的 Bean 名（本类内<b>定义面与取用面的单一来源</b>）：{@code @Bean} 名与限定符各写一次字面量
     * 即两处真相，常量把「池是哪一个」钉在一处。
     */
    private static final String DECISION_EXECUTOR_BEAN_NAME = "decisionExecutor";

    // ======================== 注册表（收集去重 + 重复 key fail-fast） ========================

    /**
     * 决策目标注册表：从应用声明的 {@link DecisionTarget} Bean 收集去重，重复 key ⇒ 启动期 fail-fast。
     *
     * @param collected 应用注册的决策目标；缺省 = 应用未注册任何目标（合法，任何引用被部署期校验阻断）
     */
    @Bean
    public DecisionTargetRegistry decisionTargetRegistry(
            @Autowired(required = false) final List<DecisionTarget> collected) {
        return new DecisionTargetRegistry(collected);
    }

    /** 出域策略注册表：与决策目标同型（ADR-0042 第 6 节「策略与决策目标同型」）。 */
    @Bean
    public DecisionPolicyRegistry decisionPolicyRegistry(
            @Autowired(required = false) final List<DecisionPolicy> collected) {
        return new DecisionPolicyRegistry(collected);
    }

    // ======================== 声明面索引（首次到点事件时预热） ========================

    /** 声明面索引（空表起步；预热由 {@link DecisionTaskCreatedListenerAdapter} 在首次到点事件时执行）。 */
    @Bean
    public DecisionDeclaredNodeIndex decisionDeclaredNodeIndex() {
        return new DecisionDeclaredNodeIndex();
    }

    // ======================== 专属有界池（不复用事件执行器） ========================

    /**
     * 专属有界线程池：{@code AbortPolicy}（池满 ⇒ OVERLOADED，不落证据行）、尺寸取属性并按
     * {@link DecisionGuardrails} 收口（{@code Math.min} + 每键一条启动期 WARN，不 fail-fast）。
     *
     * <p><b>不复用事件执行器</b>：事件执行器的 {@code CallerRunsPolicy} 会使回调可能落在流程事务内，
     * 而拉管线整段必须在流程事务之外。</p>
     *
     * <p><b>显式 Bean 名</b>（{@link #DECISION_EXECUTOR_BEAN_NAME}）：池是机制<b>内部件</b>、不开替换点，
     * 取用面按名 / 按限定符认它，不按通用 JDK 类型认（见
     * {@link #decisionTaskCreatedListener} 的限定符说明）。</p>
     */
    @Bean(name = DECISION_EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolExecutor decisionExecutor(final FlowablePlusDecisionProperties properties) {
        final int coreSize = clampToLimit("executor.core-size", properties.getExecutor().getCoreSize(),
                DecisionGuardrails.EXECUTOR_CORE_SIZE);
        final int maxSize = clampToLimit("executor.max-size", properties.getExecutor().getMaxSize(),
                DecisionGuardrails.EXECUTOR_MAX_SIZE);
        final int queueCapacity = clampToLimit("executor.queue-capacity", properties.getExecutor().getQueueCapacity(),
                DecisionGuardrails.EXECUTOR_QUEUE_CAPACITY);
        final String threadNamePrefix = properties.getExecutor().getThreadNamePrefix();
        final ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable, threadNamePrefix + counter.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            }
        };
        return new ThreadPoolExecutor(coreSize, maxSize, EXECUTOR_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    // ======================== 五个替换点（@ConditionalOnMissingBean） ========================

    /** 替换点 1：运行暂停控制面（状态由应用持有的进程内内存态）。 */
    @Bean
    @ConditionalOnMissingBean
    public DecisionRuntimeControl decisionRuntimeControl() {
        return new DefaultDecisionRuntimeControl();
    }

    /*
     * 替换点 2：凭据解析 SPI —— 无默认 Bean（extension 契约：Bean 缺席 = 不需认证材料，合法装配态）；
     * 应用 Bean 经类型注入被 Provider 消费（ObjectProvider 缺席透传 null）。
     */

    /** 替换点 3：出站传输缝（默认 HTTP 实现，超时按护栏收口；{@code close} 关闭连接池）。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public DecisionTransport decisionTransport(final FlowablePlusDecisionProperties properties) {
        return new HttpDecisionTransport(
                (int) clampToLimit("outbound-connect-timeout", properties.getOutboundConnectTimeout(),
                        DecisionGuardrails.OUTBOUND_CONNECT_TIMEOUT_MS),
                (int) clampToLimit("outbound-read-timeout", properties.getOutboundReadTimeout(),
                        DecisionGuardrails.OUTBOUND_READ_TIMEOUT_MS));
    }

    /** 替换点 4：出站提供方缝（默认实现；凭据解析器缺席 = 不需认证材料）。 */
    @Bean
    @ConditionalOnMissingBean
    public DecisionProvider decisionProvider(final DecisionTransport transport,
            final ObjectProvider<DecisionCredentialResolver> credentialResolverProvider) {
        return new DefaultDecisionProvider(transport, credentialResolverProvider.getIfAvailable());
    }

    /**
     * 替换点 5：指标记录器（starter 包内默认实现，取中立词）。方法级条件 + 懒注册（启动期零 meter）；
     * 本方法签名不出现 {@code MeterRegistry}（字节码安全纪律，§2.6）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    public DecisionMetricsRecorder decisionMetricsRecorder(
            final ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new DefaultDecisionMetricsRecorder(meterRegistryProvider);
    }

    // ======================== 内部件（一律不开替换点） ========================

    /** 观测分发点（三面分发：日志 → 指标 → 回调；指标消费者可空 —— 无 micrometer 即无）。 */
    @Bean
    public DecisionObservationEmitter decisionObservationEmitter(
            final ObjectProvider<DecisionMetricsRecorder> metricsRecorderProvider,
            @Autowired(required = false) final List<DecisionObserver> observers) {
        return new DecisionObservationEmitter(metricsRecorderProvider.getIfAvailable(), observers);
    }

    /** 上下文装配器（应用级默认数据源集：单一可选依赖，缺失 / 空集一律按空集，禁隐式全集兜底）。 */
    @Bean
    public DecisionContextAssembler decisionContextAssembler(final ManagementService managementService,
            final RuntimeService runtimeService, final TaskService taskService,
            @Autowired(required = false) final DecisionDefaultContextSources defaultContextSources) {
        return new DecisionContextAssembler(managementService, runtimeService, taskService, defaultContextSources);
    }

    /** 位点服务（推面入口；全局关 = 构造期定值，{@code submit} 为 no-op 非失败）。 */
    @Bean
    public SuggestionSubmissionService suggestionSubmissionService(final MultiInstanceDetector multiInstanceDetector,
            final TaskService taskService, final RuntimeService runtimeService,
            final DecisionObservationEmitter observationEmitter, final FlowablePlusDecisionProperties properties) {
        return new DefaultSuggestionSubmissionService(multiInstanceDetector, taskService, runtimeService,
                observationEmitter, properties.isEnabled());
    }

    /** 拉管线（数值经 {@link DecisionGuardrails} 收口后注入；写入器 / clamp / 入站加工由管线自持）。 */
    @Bean
    public DecisionPipeline decisionPipeline(final DecisionContextAssembler assembler, final TaskService taskService,
            final RuntimeService runtimeService, final DecisionTargetRegistry targetRegistry,
            final DecisionPolicyRegistry policyRegistry, final DecisionProvider provider,
            final DecisionRuntimeControl runtimeControl, final SuggestionSubmissionService submissionService,
            final DecisionObservationEmitter observationEmitter, final FlowablePlusDecisionProperties properties) {
        return new DecisionPipeline(assembler, taskService, runtimeService,
                targetRegistry.values(), policyRegistry.values(), provider, runtimeControl, submissionService,
                observationEmitter,
                clampToLimit("total-budget", properties.getTotalBudget(), DecisionGuardrails.TOTAL_BUDGET_MS),
                clampToLimit("max-attempts", properties.getMaxAttempts(), DecisionGuardrails.MAX_ATTEMPTS),
                clampToLimit("backoff.initial", properties.getBackoff().getInitial(),
                        DecisionGuardrails.BACKOFF_INITIAL_MS),
                clampToLimit("backoff.multiplier", properties.getBackoff().getMultiplier(),
                        DecisionGuardrails.BACKOFF_MULTIPLIER),
                clampToLimit("backoff.max", properties.getBackoff().getMax(), DecisionGuardrails.BACKOFF_MAX_MS));
    }

    /**
     * 到点信号订阅（懒初始化适配器：首次到点事件时预热索引并构建真身 —— 见适配器 javadoc）。
     *
     * <p><b>专属池按限定符取用</b>（issue #102）：{@code ThreadPoolExecutor} 是<b>通用 JDK 类型</b>，
     * 而适配器在首次到点事件上以 {@code ObjectProvider#getIfAvailable()} 取池 —— 该入口的语义是
     * 「唯一候选」，应用自带任意一个 {@code ThreadPoolExecutor} Bean（带定时线程池的 Spring Boot
     * 工程极常见）即抛 {@code NoUniqueBeanDefinitionException}，被适配器的宽捕获接成一条 WARN、
     * 拉面静默整体 fail-closed。池是机制内部件，故只认自己的 Bean 名、不认类型。</p>
     *
     * <p><b>{@code @Qualifier} 与 {@code @Bean} 名是一对承重的引用</b>：它按 Spring 的「Bean 名即默认
     * 限定符」口径命中 {@link #DECISION_EXECUTOR_BEAN_NAME}，二者是消除歧义的<b>同一个决定</b> ——
     * 任一侧单独改名或移除，歧义即回归（各自单看都仍能装配成功，故障只在运行期露头）。</p>
     */
    @Bean
    public DecisionTaskCreatedListenerAdapter decisionTaskCreatedListener(
            final ObjectProvider<FlowablePlusDecisionProperties> propertiesProvider,
            final ObjectProvider<EventBus> eventBusProvider,
            @Qualifier(DECISION_EXECUTOR_BEAN_NAME)
            final ObjectProvider<ThreadPoolExecutor> executorProvider,
            final ObjectProvider<DecisionPipeline> pipelineProvider,
            final ObjectProvider<DecisionDeclaredNodeIndex> indexProvider,
            final ObjectProvider<DecisionObservationEmitter> emitterProvider,
            final ObjectProvider<RepositoryService> repositoryServiceProvider) {
        return new DecisionTaskCreatedListenerAdapter(propertiesProvider, eventBusProvider, executorProvider,
                pipelineProvider, indexProvider, emitterProvider, repositoryServiceProvider);
    }

    // ======================== 护栏收口（Math.min + 每键一条启动期 WARN，不 fail-fast） ========================

    private static long clampToLimit(final String key, final long value, final long limit) {
        if (value > limit) {
            log.warn("配置项超出框架硬上限，已收口为上限值（只许调低、不可放大）：{}={} -> {}", key, value, limit);
            return limit;
        }
        return value;
    }

    private static double clampToLimit(final String key, final double value, final double limit) {
        if (value > limit) {
            log.warn("配置项超出框架硬上限，已收口为上限值（只许调低、不可放大）：{}={} -> {}", key, value, limit);
            return limit;
        }
        return value;
    }

    private static int clampToLimit(final String key, final int value, final int limit) {
        return (int) clampToLimit(key, (long) value, (long) limit);
    }
}
