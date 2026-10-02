package io.github.flowable.plus.extension.decision;

/**
 * 决策目标（ADR-0042 第 7 节「决策目标 ≠ 决策源」，拒绝合并）：框架配置面上<b>可被框架发起调用</b>的
 * 接线单元。应用声明一个 Bean 实现本接口，节点以 {@code decisionTarget} 引用其 {@link #key()}，
 * 部署期由主闸校验该 key 存在（无全局默认 target 回退）。
 *
 * <p><b>与决策源的分界线 = 可调用性</b>：决策源是「谁做出了这个决定」（语义出处、归因维度），
 * 决策目标只是「框架能往哪里发起一次出站调用」。v1 允许一一对应，但二者<b>永不合并</b>
 * —— 合并会让「未来是否出现没有决策目标的决策源」这一问题无法表述。</p>
 *
 * <p><b>接入信息内聚在 Bean 内部</b>：框架只把本对象原样交给 Provider 缝
 * （{@link DecisionProviderRequest#getTarget()}），<b>不解释</b>其内容 —— 语义由替换 Provider 缝表达。
 * 默认方言（{@link DefaultDecisionProvider}）只读 {@link #url()} 作为出站地址，故它是「默认方言的接入信息」，
 * 不是动作语义的一部分。</p>
 */
public interface DecisionTarget {

    /**
     * 唯一 key（节点声明以单值 {@code decisionTarget} 引用它）。
     *
     * <p>注册表按 key 收集，<b>重复 key ⇒ 启动期 fail-fast</b>。</p>
     *
     * @return 唯一 key
     */
    String key();

    /**
     * 接入信息：默认方言下本次出站调用的目标地址。
     *
     * <p>替换 Provider 缝后本值可被忽略（厂商 SDK 的连接信息由应用自建 Provider 自理）。</p>
     *
     * @return 出站调用地址
     */
    String url();
}
