package io.github.flowable.plus.extension.decision;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3 —— 部署期校验的守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E3}，真引擎部署）。
 *
 * <p><b>断言对象 = 部署这件事本身</b>：非法声明必须在部署期以<b>非 warning</b> 条目阻断（引擎据此抛
 * {@code FlowableException}），合法声明必须照常部署成功。不走任何新增的公开校验入口 —— 那会造出第二真相
 * 来源（ADR-0042 第 11 节第 3 条的命中式只有「出现本机制扩展属性 ∧ 不满足校验规则」一条通路）。</p>
 *
 * <p><b>六条具名断言</b>（落点表 §3.2 逐字给出的关键形态）：{@code #blocksOnUnknownAttributeUnderMechanismUri} /
 * {@code #blocksOnUnknownExtensionElement} / {@code #blocksOnDuplicateOrBlankToken} /
 * {@code #blocksOnMissingDecisionPolicy} / {@code #doesNotBlockWhenDeclarationAbsent} /
 * {@code #doesNotBlockWhenGlobalSwitchOffButDeclarationLegal}。<b>其余断言覆盖六条之外的分支</b>：承载元素
 * 类型越界、启用开关取值非法、「写了其它属性却缺启用开关」、未知 token、key 不在注册面、启用态缺目标、
 * 嵌套子流程内的非法声明，以及三条合法态（显式禁用 / 启用但无有效数据源 / 显式空集）。逐分支对账表见
 * 探索工作区的靶子⑧ 判定表达（ADR-0042 第 11 节第 3 条的阻断条件与三条穷举）。</p>
 *
 * <p><b>「全局开关不进入判定」在本落点如何表达</b>：{@link DecisionNodeDeclarationValidator} 是纯类，
 * <b>没有</b>全局开关入参 —— 开关取值既不参与装配条件、也不进入规则判定。故本类的第 6 条断言在<b>同一台
 * 已注册主闸的引擎</b>上部署合法声明：它同时证明「开关关闭时合法声明不被误伤」与「阻断与否只由声明内容
 * 决定」。开关的 Spring 侧一半（validator 不随开关消失）由 starter 的装配断言承担。</p>
 *
 * <p><b>两个 key 集是测试注入值</b>：本类构造校验器时给两个已注册 key 集的<b>测试自定值</b>，不复制任何框架
 * 默认数值（防第二处真相）。</p>
 */
class DecisionNodeDeclarationValidatorTest {

    /** 锚点节点 id（断言消息里必现的现场值） */
    private static final String ANCHOR_NODE_ID = "userTask-decide";

    /** 流程定义 id（声明写在流程元素自身时，它就是现场值） */
    private static final String PROCESS_ID = "decisionDeclarationProcess";

    /** 启动事件 id（承载越界反例的现场值） */
    private static final String START_EVENT_ID = "start";

    /** 部署资源名（须以 BPMN 资源后缀结尾，否则引擎不当 BPMN 处理） */
    private static final String RESOURCE_NAME = "decision-declaration.bpmn20.xml";

    /** 目标命名空间（与声明命名空间无关的普通命名空间） */
    private static final String TARGET_NAMESPACE = "http://flowable.plus/extension/test";

    /** 已注册的决策目标 key（构造缝注入的测试值） */
    private static final String REGISTERED_TARGET_KEY = "stubDecisionTarget";

    /** 已注册的出域策略 key（构造缝注入的测试值） */
    private static final String REGISTERED_POLICY_KEY = "stubDecisionPolicy";

    /** 未注册的 key（引用面反例） */
    private static final String UNREGISTERED_KEY = "unregisteredBeanKey";

    private static final Set<String> REGISTERED_TARGET_KEYS = Collections.singleton(REGISTERED_TARGET_KEY);

    private static final Set<String> REGISTERED_POLICY_KEYS = Collections.singleton(REGISTERED_POLICY_KEY);

    /** 合法声明片段：启用开关 */
    private static final String ENABLED_TRUE = mechanismAttribute(DecisionNodeDeclaration.DECISION_ENABLED, "true");

    /** 合法声明片段：显式禁用 */
    private static final String ENABLED_FALSE = mechanismAttribute(DecisionNodeDeclaration.DECISION_ENABLED, "false");

    /** 合法声明片段：数据源（一个合法 token） */
    private static final String DATA_SOURCES_TASK_VARIABLES =
            mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES, "TASK_VARIABLES");

    /** 合法声明片段：已注册的决策目标 key */
    private static final String TARGET_REGISTERED =
            mechanismAttribute(DecisionNodeDeclaration.DECISION_TARGET, REGISTERED_TARGET_KEY);

    /** 合法声明片段：已注册的出域策略 key */
    private static final String POLICY_REGISTERED =
            mechanismAttribute(DecisionNodeDeclaration.DECISION_POLICY, REGISTERED_POLICY_KEY);

    private static ProcessEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = ExtensionTestEngine.build(
                new DecisionNodeDeclarationValidator(REGISTERED_TARGET_KEYS, REGISTERED_POLICY_KEYS));
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @Test
    @DisplayName("本机制命名空间下的未知属性名 ⇒ 部署期阻断")
    void blocksOnUnknownAttributeUnderMechanismUri() {
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED,
                        mechanismAttribute("decisionMode", "ai")),
                "未知属性名", "decisionMode");
    }

    @Test
    @DisplayName("本机制命名空间下的自定义扩展元素 ⇒ 部署期阻断（只许元素级扩展属性一种形态）")
    void blocksOnUnknownExtensionElement() {
        assertAnchorBlocked(anchorUserTaskContaining("<extensionElements><fp:decisionConfig/></extensionElements>",
                        ENABLED_TRUE, DATA_SOURCES_TASK_VARIABLES, TARGET_REGISTERED, POLICY_REGISTERED),
                "自定义扩展元素", "decisionConfig");
    }

    @Test
    @DisplayName("数据源声明的重复 token 与空 token ⇒ 部署期阻断（不静默去重、不容忍空位）")
    void blocksOnDuplicateOrBlankToken() {
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED,
                        mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES, "TASK_VARIABLES,TASK_VARIABLES")),
                "重复 token", "TASK_VARIABLES");
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED,
                        mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES, ",TASK_VARIABLES")),
                "空 token");
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED,
                        mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES, "TASK_VARIABLES,")),
                "空 token");
    }

    @Test
    @DisplayName("启用态缺出域策略 ⇒ 部署期阻断（无全局默认回退）")
    void blocksOnMissingDecisionPolicy() {
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, DATA_SOURCES_TASK_VARIABLES, TARGET_REGISTERED),
                DecisionNodeDeclaration.DECISION_POLICY, "必填");
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, DATA_SOURCES_TASK_VARIABLES, TARGET_REGISTERED,
                        mechanismAttribute(DecisionNodeDeclaration.DECISION_POLICY, "")),
                DecisionNodeDeclaration.DECISION_POLICY, "必填");
    }

    @Test
    @DisplayName("本机制属性下无任何声明 ⇒ 不阻断（未声明节点）")
    void doesNotBlockWhenDeclarationAbsent() {
        assertAccepted(anchorUserTaskWith());
        assertAccepted(anchorUserTaskWith("name=\"未声明节点\""));
    }

    @Test
    @DisplayName("全局开关关闭 + 声明内容合法 ⇒ 不阻断（开关取值不进入判定）")
    void doesNotBlockWhenGlobalSwitchOffButDeclarationLegal() {
        assertAccepted(legalAnchorUserTask());
    }

    @Test
    @DisplayName("声明写在非 UserTask 元素上 ⇒ 部署期阻断（取值 false 亦不豁免）")
    void blocksOnDeclarationOnNonUserTaskElement() {
        // 流程元素自身：Process 不是 UserTask ⇒ 承载越界
        assertBlocked(PROCESS_ID, () -> deploy(ENABLED_FALSE, singleAnchorFlow(anchorUserTaskWith())),
                "只能写在", "Process");
        // 流元素：启动事件同样不是 UserTask（显式禁用不豁免合法性审查）
        assertBlocked(START_EVENT_ID, () -> deploy("", flowBody(
                        "<startEvent id=\"" + START_EVENT_ID + "\" " + ENABLED_FALSE + "/>", anchorUserTaskWith())),
                "只能写在", "StartEvent");
    }

    @Test
    @DisplayName("启用开关取值非小写字面量 ⇒ 部署期阻断（大小写敏感、不做空白容忍）")
    void blocksOnInvalidDecisionEnabledLiteral() {
        for (final String illegalValue : Arrays.asList("TRUE", "True", "1", "yes")) {
            assertAnchorBlocked(anchorUserTaskWith(
                            mechanismAttribute(DecisionNodeDeclaration.DECISION_ENABLED, illegalValue),
                            TARGET_REGISTERED, POLICY_REGISTERED),
                    "小写字面量", illegalValue);
        }
        assertAnchorBlocked(anchorUserTaskWith(
                        mechanismAttribute(DecisionNodeDeclaration.DECISION_ENABLED, " true "),
                        TARGET_REGISTERED, POLICY_REGISTERED),
                "小写字面量");
    }

    @Test
    @DisplayName("写了其它声明属性却缺启用开关 ⇒ 部署期阻断（绝不降级成「未声明」）")
    void blocksOnOtherDeclarationAttributesWithoutDecisionEnabled() {
        assertAnchorBlocked(anchorUserTaskWith(TARGET_REGISTERED, POLICY_REGISTERED),
                DecisionNodeDeclaration.DECISION_ENABLED, "已出现的属性名");
        assertAnchorBlocked(anchorUserTaskWith(DATA_SOURCES_TASK_VARIABLES),
                DecisionNodeDeclaration.DECISION_ENABLED, "已出现的属性名");
    }

    @Test
    @DisplayName("数据源声明的未知 token ⇒ 部署期阻断（token = 枚举常量名原文）")
    void blocksOnUnknownDataSourceToken() {
        for (final String illegalToken : Arrays.asList("process_variables", "TASK_VARIABLE", "PROCESS_VARIABLES,UNKNOWN")) {
            assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED,
                            mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES, illegalToken)),
                    "未知 token");
        }
    }

    @Test
    @DisplayName("决策目标 / 出域策略引用的 key 不在注册面 ⇒ 部署期阻断")
    void blocksOnReferenceToUnregisteredTargetOrPolicyKey() {
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, DATA_SOURCES_TASK_VARIABLES,
                        mechanismAttribute(DecisionNodeDeclaration.DECISION_TARGET, UNREGISTERED_KEY),
                        POLICY_REGISTERED),
                DecisionNodeDeclaration.DECISION_TARGET, "不在已注册面上", UNREGISTERED_KEY);
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, DATA_SOURCES_TASK_VARIABLES, TARGET_REGISTERED,
                        mechanismAttribute(DecisionNodeDeclaration.DECISION_POLICY, UNREGISTERED_KEY)),
                DecisionNodeDeclaration.DECISION_POLICY, "不在已注册面上", UNREGISTERED_KEY);
    }

    @Test
    @DisplayName("启用态缺决策目标 ⇒ 部署期阻断（无全局回退）")
    void blocksOnMissingDecisionTargetWhenEnabled() {
        assertAnchorBlocked(anchorUserTaskWith(ENABLED_TRUE, DATA_SOURCES_TASK_VARIABLES, POLICY_REGISTERED),
                DecisionNodeDeclaration.DECISION_TARGET, "必填");
    }

    @Test
    @DisplayName("嵌套子流程内的非法声明同样被阻断（扫描域含子流程）")
    void blocksOnIllegalDeclarationInsideNestedSubProcess() {
        assertBlocked(ANCHOR_NODE_ID, () -> deploy("", nestedSubProcessFlow(
                        anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED,
                                mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES, ",TASK_VARIABLES")))),
                "空 token");
    }

    @Test
    @DisplayName("显式禁用 ⇒ 不阻断（其余属性照逐条校验，此处全部合法）")
    void doesNotBlockWhenDeclarationIsExplicitlyDisabled() {
        assertAccepted(anchorUserTaskWith(ENABLED_FALSE, DATA_SOURCES_TASK_VARIABLES, TARGET_REGISTERED, POLICY_REGISTERED));
        // 显式禁用时必填项不适用：既不启用、也就不需要接线（运行期「关」与「未声明」由此可分）
        assertAccepted(anchorUserTaskWith(ENABLED_FALSE, DATA_SOURCES_TASK_VARIABLES));
    }

    @Test
    @DisplayName("启用但无有效数据源 ⇒ 不阻断（数据源缺席与显式空集都合法）")
    void doesNotBlockWhenEnabledWithoutEffectiveDataSources() {
        // 缺席：运行期取应用级默认，无默认才落「未声明数据源」
        assertAccepted(anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED));
        // 显式空集：运行期恒「未声明数据源」（显式否决，不取默认）
        assertAccepted(anchorUserTaskWith(ENABLED_TRUE, TARGET_REGISTERED, POLICY_REGISTERED,
                mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES, "")));
    }

    /**
     * 断言锚点 UserTask 上的声明被部署期阻断。
     *
     * @param anchorUserTaskXml    锚点 UserTask 的完整元素 XML
     * @param expectedMessageParts 失败信息里必须出现的现场值片段
     */
    private static void assertAnchorBlocked(final String anchorUserTaskXml, final String... expectedMessageParts) {
        assertBlocked(ANCHOR_NODE_ID, () -> deploy("", singleAnchorFlow(anchorUserTaskXml)), expectedMessageParts);
    }

    /**
     * 断言某次部署被<b>本机制</b>的校验阻断，且失败部署零痕迹。
     *
     * <p>「被本机制阻断」的判据 = 失败信息里同时出现本校验器的 {@code ValidatorSet} 组名与问题码 —— 这样
     * 断言不会因为「撞上引擎内置校验的失败」而误绿。</p>
     *
     * @param expectedElementId    现场元素标识
     * @param deployment           触发部署的调用
     * @param expectedMessageParts 失败信息里必须出现的片段
     */
    private static void assertBlocked(final String expectedElementId, final ThrowingCallable deployment,
                                      final String... expectedMessageParts) {
        final long deploymentsBefore = repositoryService().createDeploymentQuery().count();
        assertThatThrownBy(deployment)
                .as("非法声明必须在部署期阻断")
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining(DecisionNodeDeclarationValidator.VALIDATOR_SET_NAME)
                .hasMessageContaining(DecisionNodeDeclarationValidator.INVALID_DECLARATION_PROBLEM)
                .hasMessageContaining(expectedElementId)
                .hasMessageContainingAll(expectedMessageParts);
        assertThat(repositoryService().createDeploymentQuery().count())
                .as("阻断必须零痕迹：失败部署不得落库")
                .isEqualTo(deploymentsBefore);
    }

    /**
     * 断言锚点 UserTask 上的声明被接受（部署成功）。
     *
     * @param anchorUserTaskXml 锚点 UserTask 的完整元素 XML
     */
    private static void assertAccepted(final String anchorUserTaskXml) {
        assertThatCode(() -> deploy("", singleAnchorFlow(anchorUserTaskXml))).doesNotThrowAnyException();
    }

    /**
     * 渲染一条本机制扩展属性（属性名取自常量类，测试里不另抄字面量）。
     */
    private static String mechanismAttribute(final String attributeName, final String value) {
        return String.format("%s:%s=\"%s\"", DecisionNodeDeclaration.NAMESPACE_PREFIX, attributeName, value);
    }

    /**
     * 锚点 UserTask（自闭合形态）。
     */
    private static String anchorUserTaskWith(final String... attributeFragments) {
        return "<userTask id=\"" + ANCHOR_NODE_ID + "\" " + String.join(" ", attributeFragments) + "/>";
    }

    /**
     * 锚点 UserTask（含子元素形态，如 {@code extensionElements}）。
     */
    private static String anchorUserTaskContaining(final String childXml, final String... attributeFragments) {
        return "<userTask id=\"" + ANCHOR_NODE_ID + "\" " + String.join(" ", attributeFragments) + ">"
                + childXml + "</userTask>";
    }

    /**
     * 四属性齐全的合法锚点声明（本机制的四态里「声明齐全」那一态的 fixture）。
     */
    private static String legalAnchorUserTask() {
        return anchorUserTaskWith(ENABLED_TRUE, DATA_SOURCES_TASK_VARIABLES, TARGET_REGISTERED, POLICY_REGISTERED);
    }

    /**
     * 单锚点流程体：起 → 锚点（给定元素 XML）→ 止，启动事件取普通形态。
     */
    private static String singleAnchorFlow(final String anchorElementXml) {
        return flowBody("<startEvent id=\"" + START_EVENT_ID + "\"/>", anchorElementXml);
    }

    /**
     * 流程体：起（给定启动事件 XML）→ 锚点（给定元素 XML）→ 止。
     */
    private static String flowBody(final String startEventXml, final String anchorElementXml) {
        return startEventXml
                + "<sequenceFlow id=\"to-anchor\" sourceRef=\"" + START_EVENT_ID + "\" targetRef=\"" + ANCHOR_NODE_ID + "\"/>"
                + anchorElementXml
                + "<sequenceFlow id=\"to-end\" sourceRef=\"" + ANCHOR_NODE_ID + "\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/>";
    }

    /**
     * 流程体：起 → <b>两层</b>嵌套子流程（内层子流程含锚点 UserTask）→ 止。
     *
     * <p>两层而非一层：单层只能证明「进了子流程」，两层才能证明扫描是<b>递归</b>的。</p>
     */
    private static String nestedSubProcessFlow(final String anchorElementXml) {
        return "<startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"to-sub\" sourceRef=\"start\" targetRef=\"sub-process\"/>"
                + "<subProcess id=\"sub-process\">"
                + "<startEvent id=\"outer-start\"/>"
                + "<sequenceFlow id=\"outer-to-inner\" sourceRef=\"outer-start\" targetRef=\"inner-sub-process\"/>"
                + "<subProcess id=\"inner-sub-process\">"
                + "<startEvent id=\"inner-start\"/>"
                + "<sequenceFlow id=\"inner-to-anchor\" sourceRef=\"inner-start\" targetRef=\"" + ANCHOR_NODE_ID + "\"/>"
                + anchorElementXml
                + "<sequenceFlow id=\"inner-to-end\" sourceRef=\"" + ANCHOR_NODE_ID + "\" targetRef=\"inner-end\"/>"
                + "<endEvent id=\"inner-end\"/>"
                + "</subProcess>"
                + "<sequenceFlow id=\"outer-to-end\" sourceRef=\"inner-sub-process\" targetRef=\"outer-end\"/>"
                + "<endEvent id=\"outer-end\"/>"
                + "</subProcess>"
                + "<sequenceFlow id=\"to-end\" sourceRef=\"sub-process\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/>";
    }

    /**
     * 部署一份 BPMN（声明命名空间在 {@code definitions} 上声明一次，与建模者的书写形态一致）。
     *
     * @param processAttributes 写在流程元素自身的属性片段
     * @param processBody       流程体
     */
    private static void deploy(final String processAttributes, final String processBody) {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"" + BpmnXMLConstants.BPMN2_NAMESPACE + "\""
                + " xmlns:" + DecisionNodeDeclaration.NAMESPACE_PREFIX
                + "=\"" + DecisionNodeDeclaration.NAMESPACE_URI + "\""
                + " targetNamespace=\"" + TARGET_NAMESPACE + "\">"
                + "<process id=\"" + PROCESS_ID + "\" isExecutable=\"true\" " + processAttributes + ">"
                + processBody
                + "</process>"
                + "</definitions>";
        repositoryService().createDeployment().addString(RESOURCE_NAME, xml).deploy();
    }

    /**
     * 仓库服务（真引擎实例）。
     */
    private static RepositoryService repositoryService() {
        return engine.getRepositoryService();
    }
}
