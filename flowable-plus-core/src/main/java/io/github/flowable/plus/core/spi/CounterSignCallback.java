package io.github.flowable.plus.core.spi;

import java.util.List;

/**
 * 会签生命周期回调 SPI，业务系统实现此接口以在会签关键节点接收通知。
 *
 * <p>三个钩子覆盖完整的会签生命周期：发起 → 逐人投票 → 整轮结束。
 * 所有回调方法默认空实现，业务系统按需覆盖。</p>
 *
 * @author flowable-plus
 */
public interface CounterSignCallback {

    /**
     * 会签发起或加签时触发，传入本轮审批人列表。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskId            当前任务 ID
     * @param assignees         本轮所有审批人 ID 列表
     */
    default void onStart(String processInstanceId, String taskId, List<String> assignees) {
    }

    /**
     * 单个会签人投票完成时触发。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskId            当前任务 ID
     * @param assignee          投票人 ID
     * @param approved          true 为同意，false 为驳回
     * @param comment           审批意见，可为 null
     */
    default void onVote(String processInstanceId, String taskId, String assignee,
                        boolean approved, String comment) {
    }

    /**
     * 整轮会签结束（全部通过或达成结果）时触发。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskId            最后一个子任务 ID
     * @param result            汇总结果，如 "finished"
     */
    default void onFinish(String processInstanceId, String taskId, String result) {
    }
}
