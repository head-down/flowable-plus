package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.support.AssigneeResolverRegistry;
import io.github.flowable.plus.core.vo.RollbackResult;

/**
 * 严格模式的会签回退策略：静态 BPMN 模型检查 + 遇 MI 节点全拦截。
 *
 * <p>行为与旧代码 {@code executeRollback()} 中的硬编码 MI 拦截完全一致：
 * 只要 BPMN 模型层面配置了 {@code multiInstanceLoopCharacteristics}，
 * 无论运行时是否真正为多实例（如 count=1），一律拒绝回退，抛出
 * {@link InvalidTargetNodeException}。</p>
 *
 * @author flowable-plus
 */
public class StrictCountersignRollbackStrategy implements CountersignRollbackStrategy {

    private final MultiInstanceDetector multiInstanceDetector;

    public StrictCountersignRollbackStrategy(MultiInstanceDetector multiInstanceDetector) {
        if (multiInstanceDetector == null) {
            throw new IllegalArgumentException("MultiInstanceDetector 不可为 null");
        }
        this.multiInstanceDetector = multiInstanceDetector;
    }

    @Override
    public RollbackResult resolveRollbackTarget(
            PlusTask task,
            String targetActivityId,
            AssigneeResolverRegistry assigneeResolverRegistry) {
        if (multiInstanceDetector.isMultiInstanceNode(task.getProcessDefinitionId(), targetActivityId)) {
            throw new InvalidTargetNodeException(
                    "目标节点 " + targetActivityId + " 是会签（多实例）节点，"
                    + "驳回/撤回/跳转至已完成的会签节点会破坏多实例计数器，不支持此操作");
        }
        return RollbackResult.direct(targetActivityId);
    }
}
