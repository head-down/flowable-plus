package io.github.flowable.plus.starter;

import io.github.flowable.plus.extension.decision.DecisionNodeDeclarationValidator;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ProcessValidatorImpl;
import org.flowable.validation.validator.ValidatorSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 决策接入机制 —— <b>校验与复核</b>片（ADR-0042 第 6 节「三档防御」/ 第 11 节第 3 条）。
 *
 * <p><b>为何与运行组件切两片</b>：ADR-0042 第 11 节第 3 条要求「部署期校验与总闸解耦：
 * validator 始终生效」，且全局启用开关的取值<b>不进入</b>校验判定（「开关关 + 声明非法」仍阻断）
 * ⇒ 校验 / 复核侧<b>不可能</b>随全局开关消失。这也意味着「全局关 ⇒ 类不存在」在结构上不可能实现 ——
 * 故「关闭后无感」只能靠<b>五面可观测结局等价</b>（不是运行时零活动）实现。</p>
 *
 * <p><b>条件</b>：<b>只</b>条件于 extension 存在（取字符串形态的 {@code @ConditionalOnClass}，
 * 沿用主仓 Security 先例；marker = 机制面向 starter 的唯一入口契约类型）。
 * 两个自动配置类<b>互不依赖</b>，故不引 {@code @AutoConfigureBefore} / {@code @AutoConfigureAfter}。</p>
 *
 * <p><b>骨架说明</b>：本骨架落<b>装配声明</b>与<b>引擎级主闸的装配接线</b>；校验规则链本身归
 * {@link DecisionNodeDeclarationValidator}（不实现）。启动期复核
 * （{@code SmartInitializingSingleton#afterSingletonsInstantiated}，行 13）需要两个注册表
 * （决策目标 / 出域策略注册表），属面 7 运行组件，本骨架不落 —— 见 #43 决议。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.github.flowable.plus.extension.decision.SuggestionSubmissionService")
@EnableConfigurationProperties(FlowablePlusDecisionProperties.class)
public class FlowablePlusDecisionValidationAutoConfiguration {

    /**
     * 节点声明校验器（extension 纯类，无 Spring）—— 主闸的规则链载体。
     */
    @Bean
    public DecisionNodeDeclarationValidator decisionNodeDeclarationValidator() {
        return new DecisionNodeDeclarationValidator();
    }

    /**
     * 引擎级装配：把主闸<b>叠加</b>到引擎默认校验器集合上。
     *
     * <p><b>不得整体替换</b>：一手核实 —— {@code setProcessValidator} 是整体替换语义，
     * 而引擎装配期会自动装上默认实现（一个含全部内置语义校验器的 {@code ValidatorSet}）⇒
     * 直接替换会<b>静默摧毁</b>全部内置校验。故：自建默认实例 → 叠加本机制 {@code ValidatorSet} →
     * 最后整体赋值。</p>
     *
     * <p>该路径 = {@code ProcessEngineConfigurationConfigurer}，回调发生在引擎
     * {@code buildProcessEngine()} <b>之前</b>。</p>
     *
     * <p><b>骨架说明（见 #43 决议的发现③）</b>：本方法的手法必须是
     * {@code (ProcessValidatorImpl)} 下转型 —— 一手核实 {@code createDefaultProcessValidator()}
     * 返回的是<b>接口</b> {@code ProcessValidator}，而 {@code addValidatorSet} 只住在
     * {@code ProcessValidatorImpl} 上；且 {@code ValidatorSet#addValidator} 返回 {@code void}、
     * 不可链式。故实现形态文件中那段链式伪码<b>不可直接落地</b>。</p>
     */
    @Bean
    public ProcessEngineConfigurationConfigurer decisionProcessEngineConfigurationConfigurer(
            DecisionNodeDeclarationValidator decisionNodeDeclarationValidator) {
        return (SpringProcessEngineConfiguration configuration) -> {
            ProcessValidator defaultValidator = new ProcessValidatorFactory().createDefaultProcessValidator();

            ValidatorSet validatorSet = new ValidatorSet(DecisionNodeDeclarationValidator.VALIDATOR_SET_NAME);
            validatorSet.addValidator(decisionNodeDeclarationValidator);

            // 唯一可达的叠加入口在实现类上（接口 ProcessValidator 只有 validate / getValidatorSets）
            ((ProcessValidatorImpl) defaultValidator).addValidatorSet(validatorSet);

            configuration.setProcessValidator(defaultValidator);
        };
    }
}
