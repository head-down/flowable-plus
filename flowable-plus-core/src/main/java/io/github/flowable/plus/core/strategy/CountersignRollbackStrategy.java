package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.support.AssigneeResolverRegistry;
import io.github.flowable.plus.core.vo.RollbackResult;

/**
 * 会签节点回退策略，在驳回/撤回/跳转时决定如何处理会签（多实例）目标节点。
 *
 * <p>实现类通过 {@link #resolveRollbackTarget(PlusTask, String, AssigneeResolverRegistry)}
 * 判断目标节点是否为会签节点，并返回对应的回退结果：
 * <ul>
 *   <li>严格模式（Strict）：静态 BPMN 模型检查 + 遇 MI 全拦截</li>
 *   <li>自动重定向（Auto-Redirect）：运行时判断 + MI 节点自动重定向至前置单例节点</li>
 *   <li>原地重建（Auto-Rebuild）：运行时判断 + SPI 获取新审批人列表原地重建 MI</li>
 * </ul>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface CountersignRollbackStrategy {

    /**
     * 判断目标节点的回退方式并返回结果。
     *
     * @param task                       当前任务
     * @param targetActivityId           回退目标节点 ID
     * @param assigneeResolverRegistry   审批人解析注册表
     * @return RollbackResult，描述最终回退目标和回退方式
     */
    RollbackResult resolveRollbackTarget(
            PlusTask task,
            String targetActivityId,
            AssigneeResolverRegistry assigneeResolverRegistry);
}
