package io.github.flowable.plus.core.strategy;

import org.flowable.engine.HistoryService;

import java.util.List;

/**
 * 多候选节点的选择策略，用于在驳回/撤回时从多个上一审批节点中确定目标节点。
 *
 * <p>安全性保障：传入的 candidates 已由 NodeFinder 内部的 filterByHistory 清洗，
 * 仅包含在当前流程实例中真实执行过的节点，不包含静态拓扑中的"幽灵分支"。</p>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface PreviousNodeResolutionStrategy {

    /**
     * 从多个候选节点中选择一个。
     *
     * @param candidates         候选节点 ID 列表（已通过历史过滤，全部有实际执行记录）
     * @param processInstanceId  流程实例 ID
     * @param historyService     历史服务，可用于查询节点时间信息辅助决策
     * @return 选中的节点 ID
     */
    String resolve(List<String> candidates, String processInstanceId, HistoryService historyService);
}
