package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.AssigneeResolverRegistry;
import io.github.flowable.plus.core.vo.RollbackResult;
import org.flowable.engine.HistoryService;

import java.util.List;

/**
 * 原地重建模式的会签回退策略：运行时判断 + SPI 获取新审批人列表原地重建 MI。
 *
 * <p>三步行为矩阵：
 * <ol>
 *   <li>运行时单例（count &lt;= 1）：直接放行，不产生重定向消息</li>
 *   <li>运行时多实例（count &gt; 1）+ SPI 有审批人列表：
 *       原地重建多实例节点（setVariable("assigneeList") + moveActivityIdTo）</li>
 *   <li>运行时多实例（count &gt; 1）+ SPI 无审批人或无 SPI：
 *       降级至 auto-redirect（复用 {@code resolveMultiInstancePredecessor()}）</li>
 * </ol>
 *
 * <p>0 实例死锁防御：当 SPI 返回空列表且无前置节点时，硬拦截并抛出
 * {@link InvalidTargetNodeException}，防止 Flowable 静默跳过 MI 节点。</p>
 *
 * <p>包私有实现，通过 {@link CountersignRollbackStrategies#autoRebuild} 创建。</p>
 *
 * @author flowable-plus
 */
class AutoRebuildCountersignRollbackStrategy implements CountersignRollbackStrategy {

    private final NodeFinder nodeFinder;
    private final HistoryService historyService;
    private final MultiInstanceDetector multiInstanceDetector;
    private final AssigneeResolverRegistry assigneeResolverRegistry;

    AutoRebuildCountersignRollbackStrategy(NodeFinder nodeFinder,
                                            HistoryService historyService,
                                            MultiInstanceDetector multiInstanceDetector,
                                            AssigneeResolverRegistry assigneeResolverRegistry) {
        if (nodeFinder == null) {
            throw new IllegalArgumentException("NodeFinder 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (multiInstanceDetector == null) {
            throw new IllegalArgumentException("MultiInstanceDetector 不可为 null");
        }
        if (assigneeResolverRegistry == null) {
            throw new IllegalArgumentException("AssigneeResolverRegistry 不可为 null");
        }
        this.nodeFinder = nodeFinder;
        this.historyService = historyService;
        this.multiInstanceDetector = multiInstanceDetector;
        this.assigneeResolverRegistry = assigneeResolverRegistry;
    }

    @Override
    public RollbackResult resolveRollbackTarget(
            PlusTask task,
            String targetActivityId) {
        String processDefinitionId = task.getProcessDefinitionId();
        String processInstanceId = task.getProcessInstanceId();

        // Step 1: 运行时判断是否为多实例
        long count = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(targetActivityId)
                .count();

        if (count <= 1) {
            // 运行时单例，直接放行
            return RollbackResult.direct(targetActivityId);
        }

        // Step 2: 通过 SPI 获取新审批人列表
        List<String> assigneeList = this.assigneeResolverRegistry.resolve(
                processInstanceId, targetActivityId);

        if (!assigneeList.isEmpty()) {
            // SPI 有结果 → 原地重建
            return RollbackResult.rebuild(targetActivityId, assigneeList);
        }

        // Step 3: SPI 无结果 → 0 实例死锁防御 → 降级判断
        String predecessorId = CountersignRollbackStrategies.resolveMultiInstancePredecessor(
                processDefinitionId, processInstanceId, targetActivityId,
                nodeFinder, multiInstanceDetector);

        if (predecessorId == null) {
            // 无前置节点 → 硬拦截（0 实例不可放行）
            String targetName = nodeFinder.getNodeName(processDefinitionId, targetActivityId);
            String targetDisplay = targetName != null
                    ? targetName + "（" + targetActivityId + "）"
                    : targetActivityId;
            throw new InvalidTargetNodeException(
                    "目标节点 " + targetDisplay + " 在本流程实例中为多实例（会签）节点，"
                    + "且未配置 AssigneeResolver SPI 或 SPI 返回空审批人列表，"
                    + "同时 BPMN 中不存在唯一前置单例节点（0 实例不可放行）。"
                    + "建议驳回至该节点的前置准备节点，重新走完整流程。"
                    + "或使用驳回至发起人（rejectTaskToInitiator）重新提交。"
                    + "或注册 AssigneeResolver SPI 实现以提供会签审批人解析能力。");
        }

        // Step 4: 降级重定向（SPI 无结果但有前置节点）
        String targetName = nodeFinder.getNodeName(processDefinitionId, targetActivityId);
        String predecessorName = nodeFinder.getNodeName(processDefinitionId, predecessorId);
        String redirectMsg = String.format(
                "选择的审批人节点 [%s] 在本次流程中为多人会签，"
                + "但 SPI 未返回审批人列表，系统已降级重定向至前置准备节点: [%s]",
                targetName != null ? targetName : targetActivityId,
                predecessorName != null ? predecessorName : predecessorId);
        return RollbackResult.redirect(predecessorId, redirectMsg);
    }
}
