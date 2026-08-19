package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.AssigneeResolverRegistry;
import io.github.flowable.plus.core.vo.RollbackResult;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 会签回退策略的装配入口。
 *
 * <p>三个策略实现（Strict / Auto-Redirect / Auto-Rebuild）为包私有类，
 * 统一由此工厂创建，调用方只面对 {@link CountersignRollbackStrategy} 接口。
 * 同时承载共享的判定+前置解析骨架：</p>
 * <ul>
 *   <li>{@link #resolveMultiInstancePredecessor} — 仅解析前置单例节点
 *       （AutoRedirect / AutoRebuild 策略与 {@code TaskExecutionWorkflow.getJumpableNodes()} 共用）</li>
 *   <li>{@link #resolveRedirectOutcome} — 「判定 + 前置解析 + 文案拼装」完整骨架
 *       （getJumpableNodes 与 AutoRedirect 策略共用，ADR-0041）</li>
 * </ul>
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

    /**
     * 解析会签回退重定向目标（判定 + 前置解析 + 文案拼装骨架单点，ADR-0041）。
     *
     * <p><b>三态语义</b>：</p>
     * <ol>
     *   <li>非运行时多实例（模型非 MI 或全局历史数 &le; 1）→
     *       返回 {@link RollbackResult#direct(String) direct(targetActivityId)}</li>
     *   <li>运行时多实例 + 存在唯一前置单例节点 →
     *       返回 {@link RollbackResult#redirect(String, String) redirect(predecessorId, message)}，
     *       其中 message 由 messageBuilder 拼装</li>
     *   <li>运行时多实例 + 无前置单例节点 → 返回 {@code null}，
     *       消费方按语义各自处置（预览态 {@code continue} 跳过，
     *       执行态 {@code throw} 引导至 rejectTaskToInitiator / auto-rebuild）</li>
     * </ol>
     *
     * <p><b>文案拼装单点</b>（ADR-0041）：本助手内统一通过 {@link NodeFinder#getNodeName}
     * 解析目标节点名与前置节点名，做 null fallback（用 ID 兜底）后交由
     * {@code messageBuilder} 拼装最终消息——措辞参数化。
     * 预览态（{@code getJumpableNodes}）传「将重定向至」措辞，
     * 执行态（{@code AutoRedirect}）传「已自动重定向至」措辞。</p>
     *
     * <p><b>护栏</b>：</p>
     * <ul>
     *   <li>共享的只是判定+前置解析+文案骨架，不是全部回退操作样板（非 ADR-0037 模板方法）</li>
     *   <li>不拆策略工厂（非 ADR-0038）；{@link #resolveMultiInstancePredecessor} 保持现落点</li>
     *   <li>{@code AutoRebuild} 策略不调用本助手——其判定时序与 SPI 重建分支纠缠，
     *       强行同构会引入「判定时序」歧义。AutoRebuild 的降级段继续直接调用
     *       {@link #resolveMultiInstancePredecessor}</li>
     * </ul>
     *
     * @param processDefinitionId   流程定义 ID
     * @param processInstanceId      流程实例 ID
     * @param targetActivityId       候选目标节点 ID
     * @param nodeFinder             BPMN 节点遍历器
     * @param multiInstanceDetector  多实例检测器
     * @param messageBuilder         文案回调 (targetDisplay, predecessorDisplay) → message，
     *                               入参均已做 null fallback，必非 null
     * @return 三态 {@link RollbackResult}（含 {@code null}），语义见上
     */
    public static @Nullable RollbackResult resolveRedirectOutcome(
            String processDefinitionId, String processInstanceId,
            String targetActivityId, NodeFinder nodeFinder,
            MultiInstanceDetector multiInstanceDetector,
            BiFunction<String, String, String> messageBuilder) {
        if (multiInstanceDetector == null) {
            throw new IllegalArgumentException("MultiInstanceDetector 不可为 null");
        }
        if (nodeFinder == null) {
            throw new IllegalArgumentException("NodeFinder 不可为 null");
        }
        if (messageBuilder == null) {
            throw new IllegalArgumentException("messageBuilder 不可为 null");
        }

        // Step 1: 运行时 MI 判定（模型 + 历史计数复合判据单点：MultiInstanceDetector，ADR-0040）
        if (!multiInstanceDetector.isRuntimeMultiInstanceNode(
                processDefinitionId, processInstanceId, targetActivityId)) {
            return RollbackResult.direct(targetActivityId);
        }

        // Step 2: 解析前置单例节点（骨架共享自此处起，护栏：保持 resolveMultiInstancePredecessor 现落点）
        String predecessorId = resolveMultiInstancePredecessor(
                processDefinitionId, processInstanceId, targetActivityId,
                nodeFinder, multiInstanceDetector);
        if (predecessorId == null) {
            return null;
        }

        // Step 3: 文案拼装（助手内统一解析节点名 + null fallback，措辞交由 messageBuilder）
        String targetName = nodeFinder.getNodeName(processDefinitionId, targetActivityId);
        String predecessorName = nodeFinder.getNodeName(processDefinitionId, predecessorId);
        String targetDisplay = targetName != null ? targetName : targetActivityId;
        String predecessorDisplay = predecessorName != null ? predecessorName : predecessorId;
        String message = messageBuilder.apply(targetDisplay, predecessorDisplay);
        return RollbackResult.redirect(predecessorId, message);
    }
}
