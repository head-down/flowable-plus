package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 查询操作接口，统一定义待办/已办列表、节点预览和流程追踪操作。
 *
 * <p>合并了原 TaskListOperations、NodePreviewOperations 和 ProcessQueryOperations
 * 三个接口，对调用方提供一个统一的查询能力入口。</p>
 *
 * <p><b>关于数据权限</b>：待办/已办查询基于 Flowable 原生 TaskQuery API，
 * 返回结果<b>不包含业务级数据权限过滤</b>。PageResult.total 为引擎原始计数，
 * 无法反映业务权限过滤后的实际条数。如需精确分页 + 数据权限过滤，
 * 推荐接入方自行实现 SQL 查询（MyBatis-Plus Mapper XML 直查 Flowable 内部表
 * + DataScope 注入），配合 {@link #batchQueryProcessSummaries(List)}
 * 批量补充流程信息。当前的待办/已办 API 适用于无精确分页要求的场景
 * （首页摘要、我的待办小卡片等）。</p>
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface QueryOperations {

    // ======================== 待办/已办列表 ========================

    /**
     * 查询指定用户的待办任务列表。
     *
     * @param userId 用户 ID，不可为 null
     * @param query  查询条件（分页、流程定义Key筛选等）
     * @return 分页待办列表
     */
    PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query);

    /**
     * 查询指定用户的待办任务列表，支持自定义过滤条件。
     *
     * @param userId   用户 ID，不可为 null
     * @param query    查询条件
     * @param enhancer 可选的自定义过滤条件
     * @return 分页待办列表
     */
    PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query, Consumer<TaskQuery> enhancer);

    /**
     * 查询指定用户的已办任务列表。
     *
     * @param userId 用户 ID，不可为 null
     * @param query  查询条件
     * @return 分页已办列表
     */
    PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query);

    /**
     * 查询指定用户的已办任务列表，支持自定义过滤条件。
     *
     * @param userId   用户 ID，不可为 null
     * @param query    查询条件
     * @param enhancer 可选的自定义过滤条件
     * @return 分页已办列表
     */
    PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                          Consumer<HistoricTaskInstanceQuery> enhancer);

    // ======================== 节点预览 ========================

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人（不评估网关条件，全部展开）。
     *
     * @param processKey 流程定义 Key
     * @return 初始审批节点列表，每个节点包含审批人列表
     */
    List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey);

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人（支持可选变量评估网关条件）。
     *
     * @param processKey 流程定义 Key
     * @param variables  变量上下文，为 null 时不评估条件，全部展开
     * @return 初始审批节点列表
     */
    List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey, Map<String, Object> variables);

    /**
     * 获取当前任务所有下一节点的审批人（扁平列表）。
     *
     * @param taskId 当前任务 ID
     * @return 所有下一节点的审批人列表
     */
    List<ApproverInfoVO> getNextTaskApprovers(String taskId);

    /**
     * 获取当前任务指定目标节点的审批人。
     *
     * @param taskId       当前任务 ID
     * @param targetNodeId 目标节点 definitionKey
     * @return 指定目标节点的审批人列表
     */
    List<ApproverInfoVO> getNextTaskApprovers(String taskId, String targetNodeId);

    /**
     * 获取当前任务可流转至的下游节点列表。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskId            当前任务 ID
     * @return 下一节点列表
     */
    List<NextTaskNodeVO> getNextTaskNodes(String processInstanceId, String taskId);

    // ======================== 流程追踪 ========================

    /**
     * 批量获取流程实例的运行时摘要信息。
     *
     * <p>内部按固定批次（500）分片查询，解决列表页 N+1 查询问题。
     * 返回 Map 按输入顺序排列。</p>
     *
     * @param processInstanceIds 流程实例 ID 列表，不可为 null 或空
     * @return instanceId → ProcessSummaryVO 的映射
     */
    Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds);

    /**
     * 获取流程实例的审批轨迹，按时间升序展示完整的审批链路。
     *
     * <p>包含已完成的历史任务和当前活跃的运行时任务。
     * 会签节点聚合展示。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @return 审批轨迹节点列表
     * @throws NotFoundException 如果流程实例不存在
     */
    List<ApprovalTraceVO> getApprovalTrace(String processInstanceId);
}
