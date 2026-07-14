package io.github.flowable.plus.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.flowable.engine.task.Comment;

/**
 * 任务仓储接口，封装 Flowable TaskService 的查询与写操作。
 *
 * <p>所有查询方法返回领域对象 {@link PlusTask}，Flowable 原生类型
 * 仅存在于适配器实现内部，不透出到接口层。</p>
 *
 * @author flowable-plus
 */
public interface TaskRepository {

    // ======================== 查询 ========================

    /**
     * 按任务 ID 查找运行时任务。
     *
     * @param taskId 任务 ID
     * @return 运行时任务，不存在时返回 null
     */
    PlusTask findById(String taskId);

    /**
     * 列出指定流程实例中活跃的指定定义节点任务。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskDefinitionKey 任务定义 KEY
     * @return 活跃任务列表，无结果返回空列表
     */
    List<PlusTask> listActiveTasks(String processInstanceId, String taskDefinitionKey);

    /**
     * 统计指定流程实例中活跃的指定定义节点任务数。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskDefinitionKey 任务定义 KEY
     * @return 活跃任务数
     */
    long countActiveTasks(String processInstanceId, String taskDefinitionKey);

    /**
     * 按流程实例 ID 查找唯一的活跃任务。
     *
     * @param processInstanceId 流程实例 ID
     * @return 活跃任务，不存在时返回 null
     */
    PlusTask findActiveByProcessInstance(String processInstanceId);

    /**
     * 按流程实例 ID 查找所有活跃任务。
     *
     * @param processInstanceId 流程实例 ID
     * @return 活跃任务列表，无结果返回空列表
     */
    List<PlusTask> findActiveTasksByProcessInstance(String processInstanceId);

    /**
     * 按流程实例、定义节点和审批人查找活跃任务。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskDefinitionKey 任务定义 KEY
     * @param assignee          审批人 ID
     * @return 活跃任务，不存在时返回 null
     */
    PlusTask findActiveTask(String processInstanceId, String taskDefinitionKey, String assignee);

    // ======================== 写操作 ========================

    /**
     * 认领任务。
     *
     * @param taskId 任务 ID
     * @param userId 认领人 ID
     */
    void claim(String taskId, String userId);

    /**
     * 添加评论。
     *
     * @param taskId            任务 ID
     * @param processInstanceId 流程实例 ID，可为 null
     * @param type              评论类型
     * @param message           评论内容
     */
    void addComment(String taskId, String processInstanceId, String type, String message);

    /**
     * 完成任务。
     *
     * @param taskId    任务 ID
     * @param variables 流程变量，可为 null
     */
    void complete(String taskId, Map<String, Object> variables);

    // ======================== 批量查询 ========================

    /**
     * 按流程实例 ID 集合批量查询活跃任务。
     *
     * @param processInstanceIds 流程实例 ID 列表，不可为 null 或空
     * @return 活跃任务列表，无结果返回空列表
     */
    List<PlusTask> findActiveTasksByProcessInstanceIds(Collection<String> processInstanceIds);

    // ======================== 审批意见查询 ========================

    /**
     * 查询流程实例下的所有审批意见（Comment），按时间升序排列。
     *
     * @param processInstanceId 流程实例 ID
     * @return 审批意见列表，无结果返回空列表
     */
    List<Comment> getProcessInstanceComments(String processInstanceId);
}
