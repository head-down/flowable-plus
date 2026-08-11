package io.github.flowable.plus.core.enums;

/**
 * 节点预览的遍历深度模式。
 *
 * <p>定义「从起点出发收集下游 UserTask」的范围，语义源自 ADR-0018
 * （紧邻遍历使用 stopAtUserTask 参数复用现有遍历引擎）：</p>
 *
 * <ul>
 *   <li>{@link #FULL} — 全遍历：穿越网关、子流程等中间节点，收集沿途所有可达
 *       UserTask，遇到 UserTask 后继续穿越其 outgoing 探索下游，直至收集完整条审批链路。</li>
 *   <li>{@link #ADJACENT} — 紧邻遍历：收集「紧邻」的第一个 UserTask 层级，
 *       遇到 UserTask 即停止，不继续深入下级 UserTask。适合「下一步审批人」
 *       这类单层展示场景。</li>
 * </ul>
 */
public enum TraversalMode {

    /** 全遍历：收集从起点出发的所有可达 UserTask（完整审批链路） */
    FULL,

    /** 紧邻遍历：仅收集紧邻的第一个 UserTask 层级，遇 UserTask 即停止深入 */
    ADJACENT

}
