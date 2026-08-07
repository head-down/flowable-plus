package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.AssigneeResolverRegistry;
import io.github.flowable.plus.core.vo.RollbackResult;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * 解析多实例节点的唯一前置单例节点。
     *
     * <p>基于 {@link NodeFinder#findPreviousNodes} 从 MI 节点往回查，
     * 过滤掉同为 MI 的节点后，若剩余节点数 == 1 则返回该节点；
     * 若为 0 或多个则返回 null。</p>
     *
     * <p>此方法为 static，供 AutoRedirect 策略和 getJumpableNodes() 共用，
     * 确保两处的前置节点解析逻辑一致。</p>
     *
     * @param processDefinitionId   流程定义 ID
     * @param processInstanceId     流程实例 ID
     * @param miActivityId          多实例节点 ID
     * @param nodeFinder            节点遍历器
     * @param multiInstanceDetector 多实例检测器（用于过滤 MI 节点）
     * @return 唯一前置单例节点 ID，不存在或多个时返回 null
     */
    static @Nullable String resolveMultiInstancePredecessor(
            String processDefinitionId, String processInstanceId,
            String miActivityId, NodeFinder nodeFinder,
            MultiInstanceDetector multiInstanceDetector) {
        List<String> preds = nodeFinder.findPreviousNodes(
                processDefinitionId, miActivityId, processInstanceId);
        List<String> singlePreds = preds.stream()
                .filter(p -> !multiInstanceDetector.isMultiInstanceNode(processDefinitionId, p))
                .collect(Collectors.toList());
        return singlePreds.size() == 1 ? singlePreds.get(0) : null;
    }
}
