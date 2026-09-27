package io.github.flowable.plus.extension.decision;

/**
 * 节点声明的常量类（ADR-0042 第 7 节 / 第 11 节第 3 条）。
 *
 * <p>BPMN 扩展属性的<b>符号名（prefix）与 URI 成对定义在本类</b>，读侧 / 写侧 / 建模侧共用同一常量；
 * <b>禁止</b>任何一侧出现裸字面量（命名宪章 §2.E.1）。</p>
 *
 * <p><b>命名空间 = 框架级单空间</b>（不塞进 {@code flowable:} / {@code activiti:} 两个引擎保留名）。
 * 引擎底层按 <b>URI</b> 存储元素级自定义属性，故<b>识别锚点是 URI、前缀不承重</b>（前缀只是建模侧的书写
 * 约定）。四个属性名与其值字面量归一后<b>同形</b>（恒等而非屈折，命名宪章 §2.C.1）。</p>
 *
 * <p><b>取值域</b>（部署期由 {@link DecisionNodeDeclarationValidator} 主闸校验）：</p>
 *
 * <ul>
 *   <li>{@link #DECISION_ENABLED}：只认小写字面量 {@code true} / {@code false}；</li>
 *   <li>{@link #DECISION_DATA_SOURCES}：逗号分隔、大小写敏感、仅 token 两侧空白容忍，token 取
 *       {@code DecisionContextSource} 的<b>枚举常量名原文</b>；重复 token 与空 token 一律阻断；</li>
 *   <li>{@link #DECISION_TARGET}：单值 bean key（须存在于决策目标注册面）；启用时<b>必填</b>，无全局回退；</li>
 *   <li>{@link #DECISION_POLICY}：单值 bean key（须存在于出域策略注册面）；启用时<b>必填</b>。</li>
 * </ul>
 */
public final class DecisionNodeDeclaration {

    /** 本机制的 BPMN 扩展命名空间 URI（框架级单空间；不得等于引擎保留命名空间） */
    public static final String NAMESPACE_URI = "http://flowable.plus/bpmn";

    /** 本机制的 BPMN 扩展命名空间前缀（书写约定；引擎按 URI 匹配，前缀不承重） */
    public static final String NAMESPACE_PREFIX = "fp";

    /** 属性名：启用开关（只认小写字面量 true / false） */
    public static final String DECISION_ENABLED = "decisionEnabled";

    /** 属性名：有效数据源声明（逗号分隔的闭集 token，token = 枚举常量名原文） */
    public static final String DECISION_DATA_SOURCES = "decisionDataSources";

    /** 属性名：决策目标的 bean key（单值；启用时必填，无全局回退） */
    public static final String DECISION_TARGET = "decisionTarget";

    /** 属性名：出域策略的 bean key（单值；启用时必填） */
    public static final String DECISION_POLICY = "decisionPolicy";

    private DecisionNodeDeclaration() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}
