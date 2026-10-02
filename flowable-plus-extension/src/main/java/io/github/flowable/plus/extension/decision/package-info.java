/**
 * 决策接入机制（ADR-0042 建议通道）。
 *
 * <p>本子包承载机制的全部公开契约与实现：节点声明与部署期校验、决策证据的写入与读取、
 * 出域控制与拉管线、位点服务与建议提交、观测事实与其三面消费。取<b>机制子包</b>而非根包，
 * 使 extension 的储备位定位不因第一个机制入住而失效（根包留给后续机制）。</p>
 *
 * <p><b>零 Spring 坐标</b>：本子包不得依赖 Spring —— 装配、条件与配置载体全部住 starter，
 * 机制侧只提供纯类型与纯实现（依赖方向 <code>core → extension → starter</code> 单向）。</p>
 *
 * <p><b>观测面</b>（本子包的观测一族）：一次已触发的决策尝试 / 一次建议提交在结局落定时构造一条
 * {@link io.github.flowable.plus.extension.decision.DecisionObservation}，由
 * {@link io.github.flowable.plus.extension.decision.DecisionObservationEmitter} 喂日志、指标、
 * 观测回调三面；结局落位见
 * {@link io.github.flowable.plus.extension.decision.DecisionOutcomeMapping}，
 * 信号与维度契约见 {@link io.github.flowable.plus.extension.decision.DecisionMetrics}。</p>
 */
package io.github.flowable.plus.extension.decision;
