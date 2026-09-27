package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionContextSource;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.validation.ValidationError;
import org.flowable.validation.validator.ProcessLevelValidator;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 节点声明校验器 —— 部署期「主闸」（ADR-0042 第 7 节严格校验 / 第 11 节第 3 条）。
 *
 * <p><b>无 Spring</b>（extension 侧零 Spring 坐标）：引擎级装配由 starter 侧承担，且必须<b>叠加式注册、
 * 不得整体替换</b> —— 引擎装配期自带 26 个内置 {@code Validator}，整体替换会静默摧毁全部内置语义校验。
 * 本类只提供规则链本体，装配与开关门控不住本类。</p>
 *
 * <p><b>命中式（presence-based）</b>：只要元素上<b>出现</b>本机制 URI 下的声明，下列规则一律适用；声明
 * 内容全部合法则不阻断。不构成部署失败的情形（穷举）：① 本机制 URI 下无任何声明（未声明节点）；
 * ② 全局启用开关关闭（取值本就不进入判定）；③ 声明内容合法（含显式禁用、启用但无有效数据源、声明齐全）。</p>
 *
 * <p><b>取值无关性</b>：{@link DecisionNodeDeclaration#DECISION_ENABLED} 的取值<b>不决定</b>规则是否适用
 * （写作 {@code false} 不豁免合法性审查 —— 休眠错配的代价是「打开总闸当天才在部署期炸」），它只决定
 * 「启用态才成立」的两条必填规则（决策目标 / 出域策略）是否生效。</p>
 *
 * <p><b>阻断条件（逐条）</b>：</p>
 *
 * <ul>
 *   <li>扫描域 = 每个 {@code Process} 自身（含）与其全部 {@code FlowElement}（<b>含嵌套子流程</b>）；</li>
 *   <li>仅 {@code UserTask} 可承载 —— 声明出现在其它元素类型上 ⇒ 阻断；</li>
 *   <li>本机制 URI 下的<b>未知属性名</b>与<b>未知自定义扩展元素</b>一律阻断 ——
 *       收口为一条闭合式「本机制命名空间下的一切声明皆须合法」，元素级扩展属性是本机制唯一被定义的形态；</li>
 *   <li>启用开关取值非小写字面量 {@code true} / {@code false} ⇒ 阻断；</li>
 *   <li>写了其它声明属性却<b>缺</b>启用开关 ⇒ 阻断（<b>绝不</b>静默降级成「未声明」）；</li>
 *   <li>数据源声明的 <b>未知 token / 重复 token / 空 token</b> ⇒ 阻断（不静默去重、不容忍空位；
 *       整值空白的「显式空集」不算空 token，它是合法的一态）；</li>
 *   <li>启用态下决策目标 / 出域策略<b>必填</b>（无全局默认回退）⇒ 缺失即阻断；</li>
 *   <li>决策目标 / 出域策略引用的 bean key <b>不在</b>已注册面上 ⇒ 阻断。</li>
 * </ul>
 *
 * <p><b>不新增公开校验入口</b>：本类只实现引擎的 {@code Validator} 契约，校验的唯一触发路径是引擎的部署期
 * 校验 —— 另开一个「直接传模型进来判」的公开方法会造出第二真相来源。</p>
 *
 * <p><b>「引用不存在的 key」的输入缝</b>：注册表（决策目标 / 出域策略）是 starter 的包内类型，住 extension 的
 * 本类不可能引用它们，故两个 key 集由构造期注入（starter 收集去重后传入）。空集是<b>合法</b>输入，含义是
 * 「本应用没有注册任何目标 / 策略」—— 此时任何引用都阻断，与 fail-closed 同向；{@code null} <b>不合法</b>
 * （它与空集含义不同：null 说明装配根本没收集注册面）。</p>
 */
public class DecisionNodeDeclarationValidator extends ProcessLevelValidator {

    /** 本校验器的 {@code ValidatorSet} 组名（生产者自带组名、单一来源） */
    public static final String VALIDATOR_SET_NAME = "flowable-plus-decision";

    /**
     * 部署失败信息里的问题码。
     *
     * <p>该字面量会经 {@code ValidationError.problem} 出现在部署失败信息里，属契约面可见字面量：下游按它
     * 机械识别「本机制阻断的部署」。本机制的全部声明违规共用<b>一个</b>问题码，逐条规则的区别落在描述里
     * （描述必带现场值：元素、属性名、实际取值）。</p>
     */
    public static final String INVALID_DECLARATION_PROBLEM = "flowable-plus-decision-node-declaration-invalid";

    /** 本机制定义的全部属性名（未知属性名的判据 = 不在本集合内） */
    private static final Set<String> DECLARED_ATTRIBUTES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            DecisionNodeDeclaration.DECISION_ENABLED,
            DecisionNodeDeclaration.DECISION_DATA_SOURCES,
            DecisionNodeDeclaration.DECISION_TARGET,
            DecisionNodeDeclaration.DECISION_POLICY)));

    /** 启用开关之外的三个声明属性：出现它们却没有启用开关 ⇒ 阻断 */
    private static final List<String> ATTRIBUTES_EXCLUDING_ENABLED = Collections.unmodifiableList(Arrays.asList(
            DecisionNodeDeclaration.DECISION_DATA_SOURCES,
            DecisionNodeDeclaration.DECISION_TARGET,
            DecisionNodeDeclaration.DECISION_POLICY));

    /**
     * 数据源 token 的取值域（错误信息里给建模者照抄的闭集清单）。
     *
     * <p>按<b>枚举常量名原文</b>生成（token 口径的唯一理由见 {@link DecisionNodeDeclarationReader#parseDataSourceToken}
     * 的偏离说明）；遍历取 {@code EnumSet.allOf(...)} 而非 {@code values()}（不复制数组）。</p>
     */
    private static final String DATA_SOURCE_TOKEN_DOMAIN = EnumSet.allOf(DecisionContextSource.class).stream()
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    /** 子流程递归标记：扫描域必须含嵌套子流程内的元素 */
    private static final boolean INCLUDE_SUB_PROCESS_CONTENTS = true;

    /** 已注册的决策目标 key 集（启动期由 starter 收集去重后注入） */
    private final Set<String> decisionTargetKeys;

    /** 已注册的出域策略 key 集（同上） */
    private final Set<String> decisionPolicyKeys;

    /**
     * 构造校验器。
     *
     * @param decisionTargetKeys 已注册的决策目标 key 集，不得为 null（空集合法，见类 javadoc）
     * @param decisionPolicyKeys 已注册的出域策略 key 集，不得为 null（空集合法）
     */
    public DecisionNodeDeclarationValidator(final Set<String> decisionTargetKeys, final Set<String> decisionPolicyKeys) {
        this.decisionTargetKeys = copyOfRegisteredKeys(decisionTargetKeys,
                "已注册的决策目标 key 集不得为 null：空集与 null 含义不同，null 说明装配未收集注册面");
        this.decisionPolicyKeys = copyOfRegisteredKeys(decisionPolicyKeys,
                "已注册的出域策略 key 集不得为 null：空集与 null 含义不同，null 说明装配未收集注册面");
    }

    /**
     * 逐流程执行校验：先判流程元素自身，再判其全部流元素（含嵌套子流程）。
     *
     * @param bpmnModel 本次部署已完整解析的 BPMN 模型
     * @param process   当前流程
     * @param errors    错误收集表（就地追加，本方法不抛异常 —— 阻断由引擎按非 warning 条目触发）
     */
    @Override
    protected void executeValidation(final BpmnModel bpmnModel, final Process process, final List<ValidationError> errors) {
        validateElement(errors, process, process);
        for (final FlowElement flowElement : process.findFlowElementsOfType(FlowElement.class, INCLUDE_SUB_PROCESS_CONTENTS)) {
            validateElement(errors, process, flowElement);
        }
    }

    /**
     * 校验单个元素上的本机制声明（presence-based 入口）。
     *
     * <p>本机制 URI 下没有任何声明时直接返回：未声明节点既不触发、也不阻断 —— 这是「关闭后无感」在全图中
     * 唯一例外<b>之外</b>的常态。</p>
     */
    private void validateElement(final List<ValidationError> errors, final Process process, final BaseElement element) {
        final List<ExtensionAttribute> attributes = DecisionNodeDeclarationReader.mechanismAttributes(element);
        final List<ExtensionElement> extensionElements = DecisionNodeDeclarationReader.mechanismExtensionElements(element);
        if (attributes.isEmpty() && extensionElements.isEmpty()) {
            return;
        }
        reportExtensionElements(errors, process, element, extensionElements);
        reportUnknownAttributes(errors, process, element, attributes);
        reportUnsupportedCarrier(errors, process, element);
        validateValues(errors, process, element);
    }

    /**
     * 报告本机制命名空间下的自定义扩展元素（声明形态越界）。
     */
    private void reportExtensionElements(final List<ValidationError> errors, final Process process,
                                         final BaseElement element, final List<ExtensionElement> extensionElements) {
        for (final ExtensionElement extensionElement : extensionElements) {
            addDeclarationError(errors, process, element, String.format(
                    "本机制命名空间下不允许自定义扩展元素，元素级扩展属性是本机制唯一被定义的声明形态：元素=%s，扩展元素名=%s",
                    elementId(element), extensionElement.getName()));
        }
    }

    /**
     * 报告本机制命名空间下的未知属性名（闭合式：本机制命名空间下的一切声明皆须合法）。
     */
    private void reportUnknownAttributes(final List<ValidationError> errors, final Process process,
                                         final BaseElement element, final List<ExtensionAttribute> attributes) {
        for (final ExtensionAttribute attribute : attributes) {
            if (!DECLARED_ATTRIBUTES.contains(attribute.getName())) {
                addDeclarationError(errors, process, element, String.format(
                        "本机制命名空间下的未知属性名：元素=%s，属性名=%s，允许的属性名=%s",
                        elementId(element), attribute.getName(), DECLARED_ATTRIBUTES));
            }
        }
    }

    /**
     * 报告承载元素类型越界（声明只能写在 {@link UserTask} 上）。
     */
    private void reportUnsupportedCarrier(final List<ValidationError> errors, final Process process, final BaseElement element) {
        if (!(element instanceof UserTask)) {
            addDeclarationError(errors, process, element, String.format(
                    "本机制节点声明只能写在 %s 上：元素=%s，实际承载类型=%s",
                    UserTask.class.getSimpleName(), elementId(element), element.getClass().getSimpleName()));
        }
    }

    /**
     * 校验取值面（与承载元素类型无关 —— presence-based 之下，写在任何元素上都要逐条校验）。
     */
    private void validateValues(final List<ValidationError> errors, final Process process, final BaseElement element) {
        final String enabledValue = DecisionNodeDeclarationReader.declaredValue(element,
                DecisionNodeDeclaration.DECISION_ENABLED);
        final String dataSourcesValue = DecisionNodeDeclarationReader.declaredValue(element,
                DecisionNodeDeclaration.DECISION_DATA_SOURCES);
        final String targetValue = DecisionNodeDeclarationReader.declaredKey(element,
                DecisionNodeDeclaration.DECISION_TARGET);
        final String policyValue = DecisionNodeDeclarationReader.declaredKey(element,
                DecisionNodeDeclaration.DECISION_POLICY);

        final Boolean enabled = DecisionNodeDeclarationReader.parseEnabled(enabledValue);
        if (enabledValue != null && enabled == null) {
            addDeclarationError(errors, process, element, String.format(
                    "启用开关只认小写字面量 true / false：元素=%s，实际取值=%s",
                    elementId(element), enabledValue));
        }
        if (enabledValue == null) {
            reportMissingEnabled(errors, process, element);
        }
        if (dataSourcesValue != null) {
            validateDataSourceTokens(errors, process, element, dataSourcesValue);
        }
        if (Boolean.TRUE.equals(enabled)) {
            requireKey(errors, process, element, DecisionNodeDeclaration.DECISION_TARGET, targetValue);
            requireKey(errors, process, element, DecisionNodeDeclaration.DECISION_POLICY, policyValue);
        }
        if (targetValue != null) {
            validateKeyReference(errors, process, element, DecisionNodeDeclaration.DECISION_TARGET,
                    targetValue, decisionTargetKeys);
        }
        if (policyValue != null) {
            validateKeyReference(errors, process, element, DecisionNodeDeclaration.DECISION_POLICY,
                    policyValue, decisionPolicyKeys);
        }
    }

    /**
     * 报告「写了其它声明属性却缺启用开关」—— 本机制绝不把这种声明降级成「未声明」。
     */
    private void reportMissingEnabled(final List<ValidationError> errors, final Process process, final BaseElement element) {
        final List<String> presentWithoutEnabled = ATTRIBUTES_EXCLUDING_ENABLED.stream()
                .filter(attributeName -> DecisionNodeDeclarationReader.declaredValue(element, attributeName) != null)
                .collect(Collectors.toList());
        if (!presentWithoutEnabled.isEmpty()) {
            addDeclarationError(errors, process, element, String.format(
                    "写了本机制的其他声明属性却没有 %s：元素=%s，已出现的属性名=%s；本机制不把该声明降级为「未声明」",
                    DecisionNodeDeclaration.DECISION_ENABLED, elementId(element), presentWithoutEnabled));
        }
    }

    /**
     * 校验数据源 token：空 token / 重复 token / 未知 token 三类一律阻断。
     */
    private void validateDataSourceTokens(final List<ValidationError> errors, final Process process,
                                          final BaseElement element, final String dataSourcesValue) {
        final List<String> tokens = DecisionNodeDeclarationReader.splitDataSourceTokens(dataSourcesValue);
        if (tokens.stream().anyMatch(StringUtils::isBlank)) {
            addDeclarationError(errors, process, element, String.format(
                    "数据源声明含空 token（逗号造成的空位不静默忽略、也不静默去重）：元素=%s，声明值=%s",
                    elementId(element), dataSourcesValue));
        }
        final Map<String, Long> tokenCounts = tokens.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        final List<String> duplicatedTokens = tokenCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1L)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (!duplicatedTokens.isEmpty()) {
            addDeclarationError(errors, process, element, String.format(
                    "数据源声明含重复 token：元素=%s，重复的 token=%s",
                    elementId(element), duplicatedTokens));
        }
        for (final String token : tokens) {
            if (!StringUtils.isBlank(token) && DecisionNodeDeclarationReader.parseDataSourceToken(token) == null) {
                addDeclarationError(errors, process, element, String.format(
                        "数据源声明含未知 token（取值域为枚举常量名原文、大小写敏感）：元素=%s，token=%s，取值域=%s",
                        elementId(element), token, DATA_SOURCE_TOKEN_DOMAIN));
            }
        }
    }

    /**
     * 校验启用态下的必填项（缺失即阻断，无全局默认回退）。
     */
    private void requireKey(final List<ValidationError> errors, final Process process, final BaseElement element,
                            final String attributeName, final String key) {
        if (StringUtils.isBlank(key)) {
            addDeclarationError(errors, process, element, String.format(
                    "启用开关取值为 %s 时 %s 必填，本机制不提供全局默认回退：元素=%s，实际取值=%s",
                    Boolean.TRUE, attributeName, elementId(element), key));
        }
    }

    /**
     * 校验单值 key 的引用面：声明的 key 必须在已注册面上（空值天然不在注册面上）。
     */
    private void validateKeyReference(final List<ValidationError> errors, final Process process, final BaseElement element,
                                      final String attributeName, final String key, final Set<String> registeredKeys) {
        if (!registeredKeys.contains(key)) {
            addDeclarationError(errors, process, element, String.format(
                    "%s 引用的 bean key 不在已注册面上：元素=%s，key=%s，已注册的 key=%s",
                    attributeName, elementId(element), key, registeredKeys));
        }
    }

    /**
     * 追加一条非 warning 的声明违规（非 warning 才阻断部署：引擎按条目分拣，只有 warning 会放行）。
     *
     * <p>归属定位：元素是流元素时把「活动」与「XML 位置」都指向它；{@code Process} 自身不是流元素，只带
     * XML 位置。</p>
     */
    private void addDeclarationError(final List<ValidationError> errors, final Process process,
                                     final BaseElement element, final String description) {
        if (element instanceof FlowElement) {
            addError(errors, INVALID_DECLARATION_PROBLEM, process, (FlowElement) element, element, description);
        } else {
            addError(errors, INVALID_DECLARATION_PROBLEM, process, element, description);
        }
    }

    /**
     * 元素标识（错误信息里的现场值；无 id 的模型元素不静默留空）。
     */
    private static String elementId(final BaseElement element) {
        return StringUtils.defaultIfBlank(element.getId(), "(无 id 元素)");
    }

    /**
     * 收下已注册的 key 集：非空校验 + <b>防御性拷贝</b>（校验结论不得随调用方后续改动而变）。
     *
     * @param registeredKeys 已注册的 key 集
     * @param nullMessage    为 null 时的错误信息
     * @return 只读副本
     */
    private static Set<String> copyOfRegisteredKeys(final Set<String> registeredKeys, final String nullMessage) {
        final Set<String> keys = Objects.requireNonNull(registeredKeys, nullMessage);
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }
}
