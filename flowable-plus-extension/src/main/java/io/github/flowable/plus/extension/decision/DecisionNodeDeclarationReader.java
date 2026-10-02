package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionContextSource;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 节点声明的读取器（extension <b>包内实现细节</b>，不入公开命名表）。
 *
 * <p><b>职责 = 读侧 / 校验侧共用的解析单一来源</b>：本机制命名空间下的声明怎么枚举、取值怎么解析，
 * 只在本类实现一次。判「声明是否合法」不归本类（归 {@link DecisionNodeDeclarationValidator}），
 * 本类只把<b>声明读成可判的值</b>，未知 / 非法取值以 {@code null} 或原样 token 交给调用方裁决。</p>
 *
 * <p><b>识别锚点 = 命名空间 URI</b>（非前缀）：元素级扩展属性与自定义扩展元素都按
 * {@link DecisionNodeDeclaration#NAMESPACE_URI} 过滤 —— 引擎按 URI 存储自定义属性，前缀不承重。</p>
 *
 * <p><b>解析口径（建模面 token 与观测面 tag value 有意不同族）</b>：</p>
 *
 * <ul>
 *   <li>{@link DecisionNodeDeclaration#DECISION_ENABLED}：<b>只认小写字面量</b>
 *       {@code true} / {@code false}，<b>不做</b>两侧空白容忍（取值域未授予，故 {@code " true "} 属非法，
 *       部署期即阻断 —— 宁可当场地响，不静默降级成「未声明」）；</li>
 *   <li>{@link DecisionNodeDeclaration#DECISION_DATA_SOURCES}：逗号分隔、<b>大小写敏感</b>、
 *       <b>仅 token 两侧</b>空白容忍；token 取枚举常量名<b>原文</b>，不引映射表、不做大小写或下划线归一；</li>
 *   <li>{@link DecisionNodeDeclaration#DECISION_TARGET} / {@link DecisionNodeDeclaration#DECISION_POLICY}：
 *       单值 bean key，两侧空白容忍。</li>
 * </ul>
 *
 * <p><b>不抛异常</b>：声明是<b>外部输入</b>（建模者写的 BPMN），非法取值要变成部署期的可读错误信息，
 * 而不是读取期的异常 —— 故非法一律回 {@code null}，由调用方决定处置。</p>
 */
final class DecisionNodeDeclarationReader {

    /** 数据源 token 的分隔符（契约：逗号分隔；取字符形态，交给成熟库的切分入口） */
    private static final char TOKEN_SEPARATOR = ',';

    /** 启用开关的合法取值之一（由 JDK 的字面量派生，避免在源码里另拼一份） */
    private static final String ENABLED_TRUE = Boolean.TRUE.toString();

    /** 启用开关的合法取值之二 */
    private static final String ENABLED_FALSE = Boolean.FALSE.toString();

    private DecisionNodeDeclarationReader() {
    }

    /**
     * 枚举元素上<b>本机制 URI 下</b>的全部元素级扩展属性（按引擎解析序）。
     *
     * <p>引擎属性（引擎保留命名空间下的）与其它命名空间的扩展属性<b>不在</b>结果内 —— 本机制的
     * 收口式规则只覆盖自己的命名空间。</p>
     *
     * @param element 任意 BPMN 元素（{@code Process} 与各 {@code FlowElement} 同型），不得为 null
     * @return 本机制 URI 下的扩展属性；无声明时为空列表
     */
    static List<ExtensionAttribute> mechanismAttributes(final BaseElement element) {
        return underMechanismNamespace(element.getAttributes(), ExtensionAttribute::getNamespace);
    }

    /**
     * 枚举元素上<b>本机制 URI 下</b>的全部自定义扩展元素（{@code <extensionElements>} 子元素）。
     *
     * <p>本机制<b>只定义元素级扩展属性一种声明形态</b>，故该列表非空即属声明形态越界 —— 本方法只负责
     * 把越界的元素读出来供校验侧报错。</p>
     *
     * @param element 任意 BPMN 元素，不得为 null
     * @return 本机制 URI 下的自定义扩展元素；无声明时为空列表
     */
    static List<ExtensionElement> mechanismExtensionElements(final BaseElement element) {
        return underMechanismNamespace(element.getExtensionElements(), ExtensionElement::getNamespace);
    }

    /**
     * 按本机制命名空间过滤「按名字分组」的扩展体表 —— 元素级扩展属性与自定义扩展元素在引擎里是
     * <b>同一形状</b>（{@code Map<名字, List<元素>>}），差异只在元素类型与命名空间取值器，故两处共用本方法。
     *
     * @param extensionBodiesByName 「名字 → 扩展体列表」表
     * @param namespaceOf           取单个扩展体的命名空间（URI）
     * @param <T>                   扩展体类型
     * @return 本机制 URI 下的扩展体；无命中时为空列表
     */
    private static <T> List<T> underMechanismNamespace(final Map<String, List<T>> extensionBodiesByName,
                                                       final Function<T, String> namespaceOf) {
        return extensionBodiesByName.values().stream()
                .flatMap(List::stream)
                .filter(extensionBody -> StringUtils.equals(DecisionNodeDeclaration.NAMESPACE_URI,
                        namespaceOf.apply(extensionBody)))
                .collect(Collectors.toList());
    }

    /**
     * 读取本机制某个属性的<b>声明原值</b>（不裁剪、不解释）。
     *
     * <p>本方法是「属性是否出现」的唯一判据：返回 {@code null} 即<b>未声明</b>。声明缺席与声明为空值是
     * <b>两态</b>（数据源声明上二者含义不同：缺席取应用级默认、显式空集恒「未声明数据源」），故本方法
     * <b>不</b>把空值折叠成 {@code null}。</p>
     *
     * @param element       任意 BPMN 元素，不得为 null
     * @param attributeName 属性名字面量，取自 {@link DecisionNodeDeclaration} 的常量
     * @return 声明原值；未声明时返回 null
     */
    static String declaredValue(final BaseElement element, final String attributeName) {
        return element.getAttributeValue(DecisionNodeDeclaration.NAMESPACE_URI, attributeName);
    }

    /**
     * 读取本机制某个<b>单值 bean key</b> 属性的声明值（两侧空白容忍）。
     *
     * @param element       任意 BPMN 元素，不得为 null
     * @param attributeName 属性名字面量，取自 {@link DecisionNodeDeclaration} 的常量
     * @return 裁剪两侧空白后的 key；未声明时返回 null（声明了空值返回空串，两态可区分）
     */
    static String declaredKey(final BaseElement element, final String attributeName) {
        return StringUtils.trim(declaredValue(element, attributeName));
    }

    /**
     * 解析启用开关的取值。
     *
     * <p>只认<b>小写字面量</b> {@code true} / {@code false}，大小写敏感、不做空白裁剪 —— 取值域未授予
     * 空白容忍，故 {@code "TRUE"} / {@code " true "} / {@code "1"} 一律判非法。</p>
     *
     * @param rawValue 声明原值，可为 null
     * @return 合法取值返回对应的 {@link Boolean}；未声明或非小写字面量返回 null
     */
    static Boolean parseEnabled(final String rawValue) {
        if (StringUtils.equals(ENABLED_TRUE, rawValue)) {
            return Boolean.TRUE;
        }
        if (StringUtils.equals(ENABLED_FALSE, rawValue)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 切分数据源声明为 token 列表（两侧空白裁剪，<b>保留空 token</b>）。
     *
     * <p>切分规则：① 整值空白（含显式空串）⇒ <b>零 token</b>，即契约里的「显式空集」，与「属性缺席」
     * 是两态；② 其余按逗号切分、逐个裁剪两侧空白，<b>空位保留</b> —— 否则 {@code ",x"} / {@code "x,"}
     * 这类空 token 会被静默吃掉，而它们是本机制的部署期阻断项。</p>
     *
     * <p>用 {@code StringUtils} 的<b>保留空 token</b>切分入口（非 {@code String.split}）：分隔符是普通字符
     * 不是正则，且该入口保留相邻 / 尾随分隔符造成的空位 —— 契约要的正是「空位可见」。</p>
     *
     * @param rawValue 声明原值，可为 null
     * @return 裁剪后的 token 列表（可能含空串 token）；整值空白或未声明时为空列表
     */
    static List<String> splitDataSourceTokens(final String rawValue) {
        if (StringUtils.isBlank(rawValue)) {
            return Collections.emptyList();
        }
        return Arrays.stream(StringUtils.splitPreserveAllTokens(rawValue, TOKEN_SEPARATOR))
                .map(StringUtils::trim)
                .collect(Collectors.toList());
    }

    /**
     * 解析单个数据源 token 为闭集枚举。
     *
     * <p>token = <b>枚举常量名原文</b>（大写下划线），大小写敏感 —— {@code "process_variables"} 一类
     * 抄写变体一律判未知，不静默归一。</p>
     *
     * <p><b>有意偏离个人规范「禁止 name()」</b>：ADR-0042 第 7 节把 token 冻结为<b>枚举常量名原文且不引
     * 映射表</b>，故此处必须按 {@code name()} 匹配（自定义序列化属性等于给该契约另造一份映射，与「不引映射表」
     * 相反）。代价如实记录：枚举常量改名即改 BPMN 契约（属破坏性变更，与 ADR 的「不引映射表」取向一致 ——
     * 改名本就不该发生）。遍历取 {@code EnumSet.allOf(...)} 而非 {@code values()}（不复制数组）。</p>
     *
     * @param token 已裁剪的 token，可为 null
     * @return 命中的枚举成员；空白 token 或非闭集取值返回 null
     */
    static DecisionContextSource parseDataSourceToken(final String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        final String constantName = StringUtils.trim(token);
        // 枚举常量名在单个枚举内唯一，故按名取首个命中即是唯一命中（findFirst 的理由）
        return EnumSet.allOf(DecisionContextSource.class).stream()
                .filter(source -> StringUtils.equals(source.name(), constantName))
                .findFirst()
                .orElse(null);
    }
}
