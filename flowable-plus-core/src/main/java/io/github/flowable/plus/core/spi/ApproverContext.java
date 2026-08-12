package io.github.flowable.plus.core.spi;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

/**
 * 审批人解析上下文，承载运行上下文信息，供 {@link ApproverResolver} SPI 实现感知。
 *
 * <p>两个锚点的上下文不对称（全字段可空）：</p>
 * <ul>
 *   <li>定义锚点（发起前预览）：无 processInstanceId / taskId，variables 来自调用方（可为 null）；</li>
 *   <li>任务锚点（审批中）：三者都有值（variables 为运行时全量流程变量）。</li>
 * </ul>
 *
 * <p>SPI 实现可基于 variables 做动态审批人计算、基于 currentUserId 做过滤，
 * 或对 {@code ${applyUserId}} / {@code ${nextApprover}} 等依赖运行上下文的表达式求值。</p>
 *
 * @author flowable-plus
 */
@Getter
@AllArgsConstructor
public class ApproverContext {

    /** 空上下文常量：全字段为 null。 */
    public static final ApproverContext EMPTY = new ApproverContext(null, null, null, null);

    /** 流程变量，可为 null（定义锚点由调用方传入，任务锚点为运行时全量）。 */
    private final Map<String, Object> variables;

    /** 当前操作用户 ID（来自 {@link UserContext} SPI），可为 null。 */
    private final String currentUserId;

    /** 流程实例 ID，运行时有值（任务锚点）；定义锚点无值。 */
    private final String processInstanceId;

    /** 任务 ID，运行时有值（任务锚点）；定义锚点无值。 */
    private final String taskId;
}
