package io.github.flowable.plus.core;

import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;

import java.util.List;

/**
 * 历史数据仓储接口，封装 Flowable HistoryService 的查询操作。
 *
 * <p>将链式查询 API 简化为明确的单方法调用，为业务模块提供
 * 对 Flowable 历史查询 API 的接缝隔离。</p>
 *
 * @author flowable-plus
 */
public interface HistoricRepository {

    /**
     * 按任务 ID 查找历史任务（已完成的）。
     *
     * @param taskId 任务 ID
     * @return 历史任务，不存在时返回 null
     */
    HistoricTaskInstance findTaskById(String taskId);

    /**
     * 统计指定审批人在指定节点上已完成的历史任务数。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskDefinitionKey 任务定义 KEY
     * @param userId            审批人 ID
     * @return 已完成的历史任务数
     */
    long countFinishedTasks(String processInstanceId, String taskDefinitionKey, String userId);

    /**
     * 查询某个节点上最近完成的历史任务。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskDefinitionKey 任务定义 KEY
     * @return 最近完成的历史任务，不存在时返回 null
     */
    HistoricTaskInstance findLatestFinishedTask(String processInstanceId, String taskDefinitionKey);

    /**
     * 按流程实例 ID 查找历史流程实例。
     *
     * @param processInstanceId 流程实例 ID
     * @return 历史流程实例，不存在时返回 null
     */
    HistoricProcessInstance findProcessInstance(String processInstanceId);

    /**
     * 查询流程实例中所有已完成的历史活动实例，按结束时间倒序排列。
     *
     * <p>用于排他网关解析：根据历史活动实例数据判定实际执行路径。
     * 不指定 processInstanceId 时返回空列表。</p>
     *
     * @param processInstanceId 流程实例 ID，可为 null（返回空列表）
     * @return 已完成的历史活动实例列表
     */
    List<HistoricActivityInstance> findFinishedHistoricActivityInstances(String processInstanceId);
}
