package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.vo.RollbackResult;

/**
 * 自动重定向模式的会签回退策略：运行时判断 + MI 节点重定向至前置单例节点。
 *
 * <p>判定+前置解析+文案拼装骨架已收敛至
 * {@link CountersignRollbackStrategies#resolveRedirectOutcome}（ADR-0041），
 * 本策略仅保留：
 * <ol>
 *   <li>无前置时的执行态引导文案（throw InvalidTargetNodeException 引导至
 *       {@code rejectTaskToInitiator} / {@code auto-rebuild}）</li>
 *   <li>执行态措辞「系统已自动重定向至前置准备节点」</li>
 * </ol>
 *
 * <p>三步行为矩阵：
 * <ol>
 *   <li>运行时单例（count &lt;= 1）：直接放行，不产生重定向消息</li>
 *   <li>运行时多实例（count &gt; 1）+ 存在唯一前置单例节点：自动重定向至前置节点</li>
 *   <li>运行时多实例（count &gt; 1）+ 不存在或多个前置单例节点：拦截并引导至
 *       {@code rejectTaskToInitiator} 或配置 {@code auto-rebuild}</li>
 * </ol>
 *
 * <p>此策略为框架默认行为，不依赖 Flowable 版本特定行为，跨版本兼容性最好。</p>
 *
 * <p>包私有实现，通过 {@link CountersignRollbackStrategies#autoRedirect} 创建。</p>
 *
 * @author flowable-plus
 */
class AutoRedirectCountersignRollbackStrategy implements CountersignRollbackStrategy {

    private final NodeFinder nodeFinder;
    private final MultiInstanceDetector multiInstanceDetector;

    AutoRedirectCountersignRollbackStrategy(NodeFinder nodeFinder,
                                            MultiInstanceDetector multiInstanceDetector) {
        if (nodeFinder == null) {
            throw new IllegalArgumentException("NodeFinder 不可为 null");
        }
        if (multiInstanceDetector == null) {
            throw new IllegalArgumentException("MultiInstanceDetector 不可为 null");
        }
        this.nodeFinder = nodeFinder;
        this.multiInstanceDetector = multiInstanceDetector;
    }

    @Override
    public RollbackResult resolveRollbackTarget(
            PlusTask task,
            String targetActivityId) {
        String processDefinitionId = task.getProcessDefinitionId();
        String processInstanceId = task.getProcessInstanceId();

        // 判定+前置解析+文案骨架共享（ADR-0041）；预览态 getJumpableNodes 与本策略共用此助手
        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                processDefinitionId, processInstanceId, targetActivityId,
                nodeFinder, multiInstanceDetector,
                // 执行态措辞
                (targetDisplay, predecessorDisplay) -> String.format(
                        "选择的审批人节点 [%s] 在本次流程中为多人会签，"
                                + "系统已自动重定向至前置准备节点: [%s]",
                        targetDisplay, predecessorDisplay));

        if (result == null) {
            // 运行时 MI 但无前置 → 拦截，引导至备用方案
            String targetName = nodeFinder.getNodeName(processDefinitionId, targetActivityId);
            String targetDisplay = targetName != null
                    ? targetName + "（" + targetActivityId + "）"
                    : targetActivityId;
            throw new InvalidTargetNodeException(
                    "目标节点 " + targetDisplay + " 在本流程实例中为多实例（会签）节点，"
                            + "且 BPMN 中不存在唯一前置单例准备节点。"
                            + "建议驳回至该节点的前置准备节点，重新走完整流程。"
                            + "或使用驳回至发起人（rejectTaskToInitiator）重新提交。"
                            + "若需直接回到会签节点原地重建，请配置 countersign-rollback-strategy=auto-rebuild "
                            + "并确保 Flowable 版本锁定为 6.8.0。");
        }

        return result;
    }
}
