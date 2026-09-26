package io.github.flowable.plus.starter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 决策接入机制 —— <b>运行组件</b>片（ADR-0042 第 6 / 7 / 10 节）。
 *
 * <p><b>条件</b>：<b>只</b>条件于 extension 存在（字符串形态的 {@code @ConditionalOnClass}；
 * marker = 机制面向 starter 的唯一入口契约类型）。</p>
 *
 * <p><b>全局启用开关是运行期门控、不是装配条件</b>：extension 存在时机制 Bean <b>一律注册</b>，
 * 开关只在两个判定点被读 —— 拉面 = 闸门链 stage 1（事件回调内纯读门控）；
 * 推面 = 位点服务入口（全局关时 {@code submit} 为 <b>no-op 且非失败</b>）。
 * 若把 listener 条件装配掉，stage 1 就成了死代码。</p>
 *
 * <p><b>「不引 extension ⇒ 零 Bean、零行为变化」是结构保证</b>（接口与实现全住 extension，
 * 类不存在即写不出来），由断言的空集一侧承担，不由运行期判断承担。</p>
 *
 * <p><b>骨架说明（见 #43 决议的发现②）</b>：本片应承载运行组件 Bean 行 1–10
 * （五个替换点 + 位点服务 + 内部件 + 两个注册表 + 专属线程池 + 拉管线）。本骨架<b>不落</b>任何
 * {@code @Bean}：行的类型全部是面 3–7 的产物（{@code DecisionProvider} / {@code DecisionTransport} /
 * {@code DecisionPipeline} …），而位点服务的<b>实现类名在任何决议中均未登记</b>。落空壳 {@code @Bean}
 * 会违反命名宪章的「先登记后使用」，故留白并如实登记。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.github.flowable.plus.extension.decision.SuggestionSubmissionService")
@EnableConfigurationProperties(FlowablePlusDecisionProperties.class)
public class FlowablePlusDecisionAutoConfiguration {
}
