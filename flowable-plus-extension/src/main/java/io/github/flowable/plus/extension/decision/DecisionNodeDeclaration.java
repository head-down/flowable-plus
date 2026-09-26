package io.github.flowable.plus.extension.decision;

/**
 * 节点声明的常量类（ADR-0042 第 7 节 / 第 11 节）。
 *
 * <p>BPMN 扩展属性的<b>符号名（prefix）与 URI 必须成对定义在本类</b>，读侧 / 写侧 / 建模侧共用同一常量；
 * <b>禁止</b>任何一侧出现裸字面量（命名宪章 §2.E.1）。</p>
 *
 * <p>命名空间 = <b>框架级单空间</b>（开独立命名空间，不塞进 {@code flowable:} / {@code activiti:}
 * 引擎保留名）；识别锚点是 <b>URI</b> 而非前缀。四个属性名与其值字面量归一后<b>同形</b>（恒等而非屈折）。</p>
 *
 * <p>属性取值域（部署期由 {@link DecisionNodeDeclarationValidator} 主闸校验）：</p>
 *
 * <ul>
 *   <li>{@link #DECISION_ENABLED}：只认小写字面量 {@code true} / {@code false}；</li>
 *   <li>{@link #DECISION_DATA_SOURCES}：逗号分隔、大小写敏感、仅 token 两侧空白容忍，
 *       token 取自闭集枚举 {@code DecisionContextSource}；重复 token 与空 token 一律阻断；</li>
 *   <li>{@link #DECISION_TARGET}：单值 bean key；{@code decisionEnabled=true} 时<b>必填</b>，无全局回退；</li>
 *   <li>{@link #DECISION_POLICY}：单值 bean key；{@code decisionEnabled=true} 时<b>必填</b>。</li>
 * </ul>
 */
public final class DecisionNodeDeclaration {

    /** 本机制的 BPMN 扩展命名空间 URI（框架级单空间；不得等于引擎保留命名空间） */
    public static final String NAMESPACE_URI = "http://flowable.plus/bpmn";

    /** 本机制的 BPMN 扩展命名空间前缀 */
    public static final String NAMESPACE_PREFIX = "fp";

    /** 属性名：启用开关（仅小写 true / false） */
    public static final String DECISION_ENABLED = "decisionEnabled";

    /** 属性名：业务数据上下文来源枚举（有效数据源声明；逗号分隔的闭集 token） */
    public static final String DECISION_DATA_SOURCES = "decisionDataSources";

    /** 属性名：外部物理调用源的 bean key（单值） */
    public static final String DECISION_TARGET = "decisionTarget";

    /** 属性名：出域策略的 bean key（单值） */
    public static final String DECISION_POLICY = "decisionPolicy";

    private DecisionNodeDeclaration() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}
