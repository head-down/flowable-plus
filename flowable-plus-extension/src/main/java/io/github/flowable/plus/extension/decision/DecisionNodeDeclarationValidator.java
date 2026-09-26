package io.github.flowable.plus.extension.decision;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.validation.ValidationError;
import org.flowable.validation.validator.ProcessLevelValidator;

import java.util.List;

/**
 * 节点声明校验器（ADR-0042 第 6 节「主闸」/ 第 11 节第 3 条）。
 *
 * <p><b>无 Spring</b>（extension 侧零 Spring 依赖）—— 引擎级装配由 starter 侧
 * {@code ProcessEngineConfigurationConfigurer} 承担，且必须<b>叠加式注册、不得整体替换</b>
 * （{@code setProcessValidator} 是整体替换语义，直接替换会静默摧毁全部内置语义校验）。</p>
 *
 * <p><b>阻断条件（唯一显式例外）</b>：BPMN 出现本机制扩展属性 <b>且</b>该声明不满足校验规则。
 * 取值无关 —— {@code decisionEnabled} 的取值、全局启用开关的取值、声明是否属残留，
 * 均<b>不进入</b>命中式（故 validator 始终生效，不随全局开关消失）。</p>
 *
 * <p>校验规则（部署期 fail-fast）：仅 {@code UserTask} 可承载；扫描域 = 全部 {@code Process}
 * （含嵌套子流程）的全部 {@code FlowElement}；本机制 URI 下的未知属性名与未知扩展元素一律阻断；
 * {@code decisionEnabled} 非小写字面量 ⇒ 阻断；写了其他决策属性却没有 {@code decisionEnabled} ⇒ 阻断
 * （绝不静默降级成「未声明」）；未知 / 重复 / 空数据源 token ⇒ 阻断；{@code target} / {@code policy}
 * 指向不存在的 key ⇒ 阻断；{@code decisionEnabled=true} 时 {@code decisionTarget} 与 {@code decisionPolicy}
 * <b>均必填</b>。</p>
 *
 * <p><b>骨架说明</b>：规则链属机制行为，本骨架不实现。</p>
 */
public class DecisionNodeDeclarationValidator extends ProcessLevelValidator {

    /**
     * 本校验器的 {@code ValidatorSet} 组名。
     *
     * <p>生产者自带自己的组名、单一来源；该字面量会经 {@code ValidationError.validatorSetName}
     * 出现在<b>部署失败信息</b>里，属契约面可见字面量。</p>
     */
    public static final String VALIDATOR_SET_NAME = "flowable-plus-decision";

    @Override
    protected void executeValidation(BpmnModel bpmnModel, Process process, List<ValidationError> errors) {
        throw new UnsupportedOperationException("骨架：节点声明规则链归实现期（见 #43）");
    }
}
