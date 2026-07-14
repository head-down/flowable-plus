package io.github.flowable.plus.core;

import org.flowable.engine.history.HistoricActivityInstance;

import java.util.List;
import java.util.Set;

/**
 * 历史数据仓储接口，封装 Flowable HistoryService 的查询操作。
 *
 * <p>所有查询方法返回领域对象（{@link PlusHistoricTask}、{@link PlusHistoricProcessInstance}），
 * Flowable 原生类型仅存在于适配器实现内部。{@link HistoricActivityInstance}
 * 保留 Flowable 类型，因其仅在 {@link DefaultNodeFinder} 内部使用，
 * 属于深层引擎交互的一部分。</p>
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
    PlusHistoricTask findTaskById(String taskId);

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
    PlusHistoricTask findLatestFinishedTask(String processInstanceId, String taskDefinitionKey);

    /**
     * 按流程实例 ID 查找历史流程实例。
     *
     * @param processInstanceId 流程实例 ID
     * @return 历史流程实例，不存在时返回 null
     */
    PlusHistoricProcessInstance findProcessInstance(String processInstanceId);

    /**
     * 查询流程实例中所有已完成的历史活动实例，按结束时间倒序排列。
     *
     * <p>用于排他网关解析：根据历史活动实例数据判定实际执行路径。
     * 保留 {@link HistoricActivityInstance} 类型——该对象仅在
     * {@link DefaultNodeFinder} 内部使用，属于引擎级图形遍历逻辑的一部分。</p>
     *
     * @param processInstanceId 流程实例 ID，可为 null（返回空列表）
     * @return 已完成的历史活动实例列表
     */
    List<HistoricActivityInstance> findFinishedHistoricActivityInstances(String processInstanceId);

    /**
     * 按流程实例 ID 集合批量查询历史流程实例。
     *
     * @param processInstanceIds 流程实例 ID 集合，不可为 null 或空
     * @return 历史流程实例列表，无结果返回空列表
     */
    List<PlusHistoricProcessInstance> findProcessInstancesByIds(Set<String> processInstanceIds);

    /**
     * 按流程实例 ID 查询所有已结束的历史任务，按开始时间升序排列。
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @return 历史任务列表，无结果返回空列表
     */
    List<PlusHistoricTask> findHistoricTasksByProcessInstanceId(String processInstanceId);

    /**
     * 检查流程实例是否有历史任务。
     *
     * <p>用于自动提交的 isFirstStart 守卫：历史任务为空表示流程首次启动，
     * 允许触发自动提交；非空则表示流程已被重新触发，不应再次自动提交。</p>
     *
     * @param processInstanceId 流程实例 ID
     * @return 有历史任务返回 true，否则 false
     */
    boolean hasHistoricTasks(String processInstanceId);
}
