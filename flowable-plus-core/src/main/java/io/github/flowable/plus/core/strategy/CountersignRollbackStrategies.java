package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.AssigneeResolverRegistry;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会签回退策略的装配入口。
 *
 * <p>三个策略实现（Strict / Auto-Redirect / Auto-Rebuild）为包私有类，
 * 统一由此工厂创建，调用方只面对 {@link CountersignRollbackStrategy} 接口。
 * 同时承载共享的前置单例节点解析工具 {@link #resolveMultiInstancePredecessor}，
 * 供 AutoRedirect / AutoRebuild 策略与 {@code TaskExecutionWorkflow.getJumpableNodes()} 共用，
 * 确保两处的前置节点解析逻辑一致。</p>
 *
 * @author flowable-plus
 */
public final class CountersignRollbackStrategies {

    private CountersignRollbackStrategies() {
        // 工具 + 工厂类，禁止实例化
    }

    /**
     * 创建严格模式策略：静态 BPMN 模型检查 + 遇 MI 全拦截（旧行为）。
     *
     * @param multiInstanceDetector 多实例检测器
     * @return CountersignRollbackStrategy 实例
     */
    public static CountersignRollbackStrategy strict(MultiInstanceDetector multiInstanceDetector) {
        return new StrictCountersignRollbackStrategy(multiInstanceDetector);
    }

    /**
     * 创建自动重定向策略：运行时判断 + MI 节点自动重定向至前置单例节点（默认行为）。
     *
     * <p>运行时判定口径已收敛至
     * {@link MultiInstanceDetector#isRuntimeMultiInstanceNode}（ADR-0040），
     * 本策略不再直接依赖 HistoryService。</p>
     *
     * @param nodeFinder            BPMN 节点遍历器
     * @param multiInstanceDetector 多实例检测器
     * @return CountersignRollbackStrategy 实例
     */
    public static CountersignRollbackStrategy autoRedirect(
            NodeFinder nodeFinder, MultiInstanceDetector multiInstanceDetector) {
        return new AutoRedirectCountersignRollbackStrategy(
                nodeFinder, multiInstanceDetector);
    }

    /**
     * 创建原地重建策略：运行时判断 + SPI 获取新审批人列表原地重建 MI。
     *
     * <p>运行时判定口径已收敛至
     * {@link MultiInstanceDetector#isRuntimeMultiInstanceNode}（ADR-0040），
     * 本策略不再直接依赖 HistoryService。</p>
     *
     * @param nodeFinder               BPMN 节点遍历器
     * @param multiInstanceDetector    多实例检测器
     * @param assigneeResolverRegistry 审批人解析注册表（SPI）
     * @return CountersignRollbackStrategy 实例
     */
    public static CountersignRollbackStrategy autoRebuild(
            NodeFinder nodeFinder,
            MultiInstanceDetector multiInstanceDetector,
            AssigneeResolverRegistry assigneeResolverRegistry) {
        return new AutoRebuildCountersignRollbackStrategy(
                nodeFinder, multiInstanceDetector, assigneeResolverRegistry);
    }

    /**
     * 解析多实例节点的唯一前置单例节点。
     *
     * <p>基于 {@link NodeFinder#findPreviousNodes} 从 MI 节点往回查，
     * 过滤掉同为 MI 的节点后，若剩余节点数 == 1 则返回该节点；
     * 若为 0 或多个则返回 null。</p>
     *
     * @param processDefinitionId   流程定义 ID
     * @param processInstanceId     流程实例 ID
     * @param miActivityId          多实例节点 ID
     * @param nodeFinder            节点遍历器
     * @param multiInstanceDetector 多实例检测器（用于过滤 MI 节点）
     * @return 唯一前置单例节点 ID，不存在或多个时返回 null
     */
    public static @Nullable String resolveMultiInstancePredecessor(
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
