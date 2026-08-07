package io.github.flowable.plus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会签回退策略配置属性。
 *
 * <p>对应 {@code flowable.plus.countersign-rollback-strategy} 配置项。
 * 控制驳回/撤回/跳转至会签（多实例）节点时的处理策略。</p>
 *
 * <ul>
 *   <li>{@code strict} — 静态 BPMN 模型检查 + 遇 MI 节点全拦截（默认）</li>
 *   <li>{@code auto-redirect} — 运行时判断 + MI 节点自动重定向至前置单例节点（待实现）</li>
 *   <li>{@code auto-rebuild} — 运行时判断 + SPI 获取新审批人列表原地重建 MI（待实现）</li>
 * </ul>
 *
 * @author flowable-plus
 */
@ConfigurationProperties(prefix = "flowable.plus")
public class FlowablePlusCountersignRollbackProperties {

    /**
     * 会签回退策略，默认 {@code auto-redirect}。
     *
     * <p>可选值：{@code strict}、{@code auto-redirect}、{@code auto-rebuild}。
     * 配置项名称为 {@code flowable.plus.countersign-rollback-strategy}。</p>
     */
    private CountersignRollbackStrategyType countersignRollbackStrategy = CountersignRollbackStrategyType.AUTO_REDIRECT;

    public CountersignRollbackStrategyType getCountersignRollbackStrategy() {
        return countersignRollbackStrategy;
    }

    public void setCountersignRollbackStrategy(CountersignRollbackStrategyType countersignRollbackStrategy) {
        this.countersignRollbackStrategy = countersignRollbackStrategy;
    }

    /**
     * 会签回退策略类型枚举。
     */
    public enum CountersignRollbackStrategyType {

        /** 严格模式：遇 MI 节点全拦截 */
        STRICT,

        /** 自动重定向模式：MI 节点重定向至前置单例节点（待实现） */
        AUTO_REDIRECT,

        /** 原地重建模式：SPI 获取新审批人列表原地重建 MI（待实现） */
        AUTO_REBUILD
    }
}
