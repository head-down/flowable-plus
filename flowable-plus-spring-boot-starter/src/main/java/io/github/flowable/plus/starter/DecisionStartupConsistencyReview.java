package io.github.flowable.plus.starter;

import io.github.flowable.plus.extension.decision.DecisionNodeDeclarationValidator;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.validation.ValidationError;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 启动期一致性复核（ADR-0042 §11 第 3 条的「唯一显式例外」；starter 包内实现细节）。
 *
 * <p><b>时机</b>：{@link SmartInitializingSingleton#afterSingletonsInstantiated()} —— 上下文 refresh
 * 之内、Bean 齐备之后；判不一致即抛异常 ⇒ <b>刷新失败、应用不启动</b>（用 {@code ApplicationRunner} /
 * {@code ApplicationReadyEvent} 会先起后炸，留下半启动上下文）。</p>
 *
 * <p><b>判据</b> = <i>出现本机制扩展属性 ∧ 不满足校验规则</i>：复用与主闸（引擎级装配）<b>同一构造缝</b>
 * （{@code DecisionNodeDeclarationValidator} 的两 key 集构造）与<b>同一份收口 key 集</b> ——
 * 不在 starter 侧重写一份规则链（第二真相）。校验器对「无本机制声明的模型」产出零错误 ⇒
 * 校验错误非空 ⇔ 判据命中；未声明的遗留定义不被误伤。</p>
 *
 * <p><b>扫描域</b> = 全部已部署流程定义（含历史版本 —— 运行中的实例仍引用其定义；移除一个仍被引用的
 * 策略 / 目标 Bean ⇒ 应用起不来，运维须知，ADR-0042 §6 已披露）。新部署的校验由部署期主闸承担
 * （叠加式注册的 validator 始终生效），本复核只兜「装配态漂移」。</p>
 */
final class DecisionStartupConsistencyReview implements SmartInitializingSingleton {

    /** 引擎读取面（扫描已部署流程定义的已解析模型） */
    private final RepositoryService repositoryService;

    /** 与主闸同源的决策目标 key 集 */
    private final Set<String> decisionTargetKeys;

    /** 与主闸同源的出域策略 key 集 */
    private final Set<String> decisionPolicyKeys;

    DecisionStartupConsistencyReview(final RepositoryService repositoryService,
                                     final Set<String> decisionTargetKeys, final Set<String> decisionPolicyKeys) {
        this.repositoryService = repositoryService;
        this.decisionTargetKeys = decisionTargetKeys;
        this.decisionPolicyKeys = decisionPolicyKeys;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 与主闸同一构造缝：两 key 集由注册表收口后注入（空集合法，null 非法）
        final DecisionNodeDeclarationValidator validator =
                new DecisionNodeDeclarationValidator(decisionTargetKeys, decisionPolicyKeys);
        final List<String> violations = new ArrayList<>();
        for (final ProcessDefinition definition : repositoryService.createProcessDefinitionQuery().list()) {
            final BpmnModel model = repositoryService.getBpmnModel(definition.getId());
            final List<ValidationError> errors = new ArrayList<>();
            validator.validate(model, errors);
            for (final ValidationError error : errors) {
                violations.add(String.format("processDefinition=%s problem=%s: %s",
                        definition.getId(), error.getProblem(), error.getDefaultDescription()));
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "启动期一致性复核失败：已部署流程定义中存在本机制扩展属性且不满足校验规则"
                            + "（判据 = 出现本机制扩展属性 ∧ 不满足校验规则；未声明的遗留定义不受影响）。共 "
                            + violations.size() + " 处：" + String.join("；", violations));
        }
    }
}
