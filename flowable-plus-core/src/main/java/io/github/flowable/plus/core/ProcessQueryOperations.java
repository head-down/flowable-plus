package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;

import java.util.List;
import java.util.Map;

/**
 * 流程查询操作接口，定义流程实例的批量查询和审批轨迹查询。
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface ProcessQueryOperations {

    /**
     * 批量获取流程实例的运行时摘要信息。
     *
     * <p>内部按固定批次（500）分片查询，解决列表页 N+1 查询问题。
     * 不存在或已结束的实例通过返回 VO 的字段表达（不抛异常），
     * 如果所有 instanceId 都不存在则打 warn 日志。
     * 返回 Map 按输入顺序排列（内部使用 {@link java.util.LinkedHashMap}）。</p>
     *
     * @param processInstanceIds 流程实例 ID 列表，不可为 null 或空
     * @return instanceId → ProcessSummaryVO 的映射，不存在的实例不在 map 中
     * @throws IllegalArgumentException 如果 instanceIds 为 null 或空
     */
    Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds);

    /**
     * 获取流程实例的审批轨迹，按时间升序展示完整的审批链路。
     *
     * <p>包含已完成的历史任务和当前活跃的运行时任务。
     * 会签节点聚合展示，每个会签审批人作为子详情嵌套。
     * 每个节点包含审批人、时间、意见和耗时。
     * 审批状态由 deleteReason 推断。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @return 按 startTime 升序排列的审批轨迹节点列表，若无任务节点返回空列表
     * @throws NotFoundException 如果流程实例不存在
     */
    List<ApprovalTraceVO> getApprovalTrace(String processInstanceId);
}
