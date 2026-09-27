package io.github.flowable.plus.starter;

import io.github.flowable.plus.extension.decision.DecisionNodeDeclarationValidator;
import org.flowable.engine.RepositoryService;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ProcessValidatorImpl;
import org.flowable.validation.validator.ValidatorSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 决策接入的校验与复核装配（ADR-0042 §11 第 3 条「部署期校验与总闸解耦：validator 始终生效」；
 * {@code docs/impl/0042-module-and-build.md} §2.5）。
 *
 * <p><b>装配条件只有一条</b>：extension 在 classpath 上（与运行组件配置类同 marker、同字符串形态）。
 * <b>不随全局开关消失</b>：全局启用开关的取值不进入校验判定（「开关关 + 声明非法」仍阻断），故
 * 「全局关 ⇒ 类不存在」在结构上不可能实现 —— 这是「关闭后无感 = 五面可观测结局等价」的既定路径。</p>
 *
 * <p><b>主闸 = 叠加式注册，不得整体替换</b>：引擎装配期自动装 26 个内置 validator，直接替换
 * {@code setProcessValidator} 会静默摧毁全部内置语义校验。可达形态（一手核实
 * {@code flowable-process-validation-6.8.0.jar}：{@code addValidatorSet} / {@code addValidator}
 * 均返回 {@code void}，不可链式）= 自建默认实例 → 下转型 {@code ProcessValidatorImpl} →
 * {@code ValidatorSet} 具名 {@code flowable-plus-decision} → 两处分行调用 → 最后整体赋值。</p>
 *
 * <p><b>配置器接口</b> = 非废弃父接口 {@code EngineConfigurationConfigurer<SpringProcessEngineConfiguration>}
 * （不用 6.8.0 已废弃的子接口 {@code ProcessEngineConfigurationConfigurer}；收集点字段类型就是父接口，
 * standalone 与 app 两条装配路径都继承它 ⇒ 零行为差异、零编译告警）。</p>
 */
@Configuration
@ConditionalOnClass(name = "io.github.flowable.plus.extension.decision.SuggestionSubmissionService")
public class FlowablePlusDecisionValidationAutoConfiguration {

    /**
     * 引擎级主闸：把本机制的节点声明校验器<b>叠加</b>进引擎默认的 process validator。
     *
     * <p>回调发生在引擎 {@code buildProcessEngine()} 之前（{@code initProcessValidator()} 只在字段为
     * {@code null} 时补默认值）。两 key 集与启动期复核同源（同一注册表收口），空集合法。</p>
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> decisionNodeDeclarationEngineConfigurationConfigurer(
            final DecisionTargetRegistry targetRegistry, final DecisionPolicyRegistry policyRegistry) {
        return configuration -> {
            // 自建默认实例（返回接口 ProcessValidator；工厂为实例方法）
            final ProcessValidator defaultValidator =
                    new ProcessValidatorFactory().createDefaultProcessValidator();
            // 下转型：addValidatorSet 只住实现类（返回 void，不可链式）
            final ProcessValidatorImpl impl = (ProcessValidatorImpl) defaultValidator;
            final ValidatorSet set =
                    new ValidatorSet(DecisionNodeDeclarationValidator.VALIDATOR_SET_NAME);
            set.addValidator(new DecisionNodeDeclarationValidator(
                    targetRegistry.keys(), policyRegistry.keys()));
            impl.addValidatorSet(set);
            // 最后整体赋值（本机制 validator 是叠加，26 个内置 validator 原样保留）
            configuration.setProcessValidator(defaultValidator);
        };
    }

    /** 启动期一致性复核（refresh 内 fail-fast；判据与构造缝见 {@link DecisionStartupConsistencyReview}）。 */
    @Bean
    public DecisionStartupConsistencyReview decisionStartupConsistencyReview(
            final RepositoryService repositoryService,
            final DecisionTargetRegistry targetRegistry, final DecisionPolicyRegistry policyRegistry) {
        return new DecisionStartupConsistencyReview(repositoryService, targetRegistry.keys(), policyRegistry.keys());
    }
}
