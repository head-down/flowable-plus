package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.model.MultiInstanceDetector;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 装配面测试的共享支撑（mock 引擎基座 + 固定闭集常量；三个装配测试类共用，防漂移）。
 *
 * <p><b>一条对个人规范的有意偏离（如实登记，同 {@code E2}/{@code E3} 的 {@code name()} 判例形态）</b>：
 * 测试树的字符串谓词不用 Commons {@code StringUtils} —— starter 的 pom 账本闭集（S3 断言）不含
 * {@code commons-lang3} 坐标，测试源码直接使用传递面坐标须先入账，为守住账本断言的机械闭集，
 * 测试侧保留 JDK 谓词。</p>
 */
final class DecisionAssemblyTestSupport {

    /** T1 绝对禁词（命名宪章 §4.1 首版清单的唯一测试住所；S2 按切词比对、S3 按坐标包含比对） */
    static final List<String> T1_BANNED_WORDS = Collections.unmodifiableList(Arrays.asList(
            "openai", "anthropic", "google", "meta", "microsoft", "nvidia", "mistral", "cohere",
            "alibaba", "baidu", "deepseek", "gpt", "claude", "gemini", "llama", "qwen",
            "ernie", "palm", "bedrock", "azureopenai", "vertexai"));

    /** 有 extension 时的机制 Bean 名闭集（S2 恒等断言的显式常量集；S4 的全局关注册集为其真子集） */
    static final List<String> DECLARED_MECHANISM_BEAN_NAMES = Collections.unmodifiableList(Arrays.asList(
            "decisionTargetRegistry",
            "decisionPolicyRegistry",
            "decisionDeclaredNodeIndex",
            "decisionExecutor",
            "decisionRuntimeControl",
            "decisionTransport",
            "decisionProvider",
            "decisionMetricsRecorder",
            "decisionObservationEmitter",
            "decisionContextAssembler",
            "suggestionSubmissionService",
            "decisionPipeline",
            "decisionTaskCreatedListener",
            "decisionNodeDeclarationEngineConfigurationConfigurer",
            "decisionStartupConsistencyReview",
            "flowablePlusDecisionAutoConfiguration",
            "flowablePlusDecisionValidationAutoConfiguration",
            "flowable.plus.decision-io.github.flowable.plus.starter.FlowablePlusDecisionProperties"));

    private DecisionAssemblyTestSupport() {
    }

    /** bean 名是否落在机制命名面（决策 / 位点服务提交；命名约定的机械面） */
    static boolean isMechanismNamed(final String beanName) {
        final String lower = beanName.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("decision") || lower.contains("submission");
    }

    /** 装配面 runner 基座：mock 引擎 + 既有自动配置 + 两个决策配置类（调用方按需追加 Bean / 属性 / 类加载器过滤）。 */
    static ApplicationContextRunner baseRunner() {
        final RepositoryService repositoryService = mock(RepositoryService.class);
        final ProcessDefinitionQuery definitionQuery = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.latestVersion()).thenReturn(definitionQuery);
        when(definitionQuery.list()).thenReturn(Collections.<ProcessDefinition>emptyList());
        final ProcessEngine engine = mock(ProcessEngine.class);
        when(engine.getRepositoryService()).thenReturn(repositoryService);
        when(engine.getRuntimeService()).thenReturn(mock(RuntimeService.class));
        when(engine.getTaskService()).thenReturn(mock(TaskService.class));
        when(engine.getHistoryService()).thenReturn(mock(HistoryService.class));
        when(engine.getIdentityService()).thenReturn(mock(IdentityService.class));
        when(engine.getManagementService()).thenReturn(mock(ManagementService.class));
        final org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl configuration =
                mock(org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl.class);
        when(configuration.getExpressionManager()).thenReturn(
                mock(org.flowable.common.engine.impl.el.ExpressionManager.class));
        when(engine.getProcessEngineConfiguration()).thenReturn(configuration);
        return new ApplicationContextRunner()
                .withUserConfiguration(FlowablePlusAutoConfiguration.class,
                        FlowablePlusDecisionAutoConfiguration.class,
                        FlowablePlusDecisionValidationAutoConfiguration.class)
                .withBean(ProcessEngine.class, () -> engine)
                .withBean(TaskService.class, engine::getTaskService)
                .withBean(RuntimeService.class, engine::getRuntimeService)
                .withBean(RepositoryService.class, engine::getRepositoryService)
                .withBean(ManagementService.class, engine::getManagementService)
                .withBean(HistoryService.class, engine::getHistoryService)
                .withBean(IdentityService.class, engine::getIdentityService)
                .withBean(MultiInstanceDetector.class, () -> mock(MultiInstanceDetector.class));
    }
}
