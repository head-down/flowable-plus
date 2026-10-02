/**
 * flowable-plus-extension - 储备位模块（reserved slot）。
 * <p>
 * 定位与边界见 ADR-0029：等待「依赖隔离」或「真正可选的领域能力」类功能入住；
 * 禁止薄壳包装 core 已有功能（双轨），禁止「最佳实践集合」类无行为内容。
 * 已有多实例处理、高级审批模式等在 {@code flowable-plus-core} 中实现，此处不再重复。
 * <p>
 * 已入住的机制各取自己的子包（本包不为单个机制铺满）：首个为
 * {@code io.github.flowable.plus.extension.decision}（决策接入，ADR-0042）。
 */
package io.github.flowable.plus.extension;
