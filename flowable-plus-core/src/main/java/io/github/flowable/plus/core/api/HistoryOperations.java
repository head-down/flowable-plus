package io.github.flowable.plus.core.api;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;

import java.util.List;

/**
 * 审批历史查询操作接口，提供按流程实例查询完整审批时间线的能力。
 *
 * <p>返回结果按活动实例启动时间升序排列，每一步审批操作的类型
 * 由 {@code Comment → ApprovalAction} 三级推断策略决定（参见 ADR-0009）。
 * 会签节点通过 {@link ApprovalRecordVO#getCountersignRecords()} 携带
 * 每位参与者的投票子记录。</p>
 *
 * @author flowable-plus
 */
public interface HistoryOperations {

    /**
     * 获取指定流程实例的完整审批历史时间线。
     *
     * <p>每条 {@link ApprovalRecordVO} 表示时间线上的一个审批节点记录，
     * 包括 START 发起记录、各节点审批操作、会签投票结果等。
     * 当前活跃节点（未完成）的 endTime 和 duration 均为 null。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null 或空
     * @return 审批历史记录列表，按时间升序排列
     * @throws IllegalArgumentException 如果 processInstanceId 为 null 或空
     * @throws NotFoundException         如果流程实例不存在
     */
    List<ApprovalRecordVO> getApprovalHistory(String processInstanceId);
}
