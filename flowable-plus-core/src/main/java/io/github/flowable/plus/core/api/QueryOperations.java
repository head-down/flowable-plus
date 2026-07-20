package io.github.flowable.plus.core.api;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.task.api.TaskQuery;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 查询操作接口，统一定义待办/已办列表、节点预览和流程追踪操作。
 *
 * <p>合并了原 TaskListOperations、NodePreviewOperations 和 ProcessQueryOperations
 * 三个接口，对调用方提供一个统一的查询能力入口。</p>
 *
 * <p><b>关于业务条件过滤与精确分页</b>：待办/已办查询基于 Flowable 原生
 * TaskQuery / HistoricQuery API，查询条件仅限于引擎字段（assignee、processKey、
 * 时间范围等）。无法 JOIN 业务表做条件过滤（如"部门=销售部"、
 * "订单金额>1万"），也无法实现业务过滤后的精确分页 total。</p>
 *
 * <p>根据业务需求，接入方可选择以下方案：</p>
 * <ol>
 *   <li><b>轻量场景</b> — 直接使用当前 API。适用于首页摘要、我的待办小卡片等
 *       无精确分页要求的场景。</li>
 *   <li><b>精确分页</b> — 自行实现 MyBatis-Plus Mapper XML 直查 Flowable
 *       内部表（{@code ACT_HI_TASKINST} JOIN {@code ACT_HI_PROCINST}）
 *       + DataScope 注入，配合 {@link #batchQueryProcessSummaries(List)}
 *       批量补充流程信息。</li>
 *   <li><b>大数据量 / 高并发</b> — 基于 CQRS 数据异构思路，实现
 *       {@link io.github.flowable.plus.core.spi.ProcessEventListener} 监听
 *       任务完成/流程结束事件，异步写入业务侧审批宽表（含流程摘要 + 业务字段）。
 *       查询"待办/已办"直接走业务表，彻底解耦 Flowable 引擎表，
 *       支持任意业务条件过滤 + 亿级精确分页。</li>
 * </ol>
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
     * 查询指定用户的已办任务列表（每流程实例 1 条记录）。
     *
     * <p>采用流程实例维度的两阶段查询：Phase 1 按 {@code involvedUser} + {@code startedBy}
     * 获取候选流程集并分页，Phase 2 按 {@code taskAssignee} 精确过滤任务。</p>
     *
     * @apiNote <b>PageResult.total 为近似值。</b> Phase 1 的 {@code involvedUser}
     *          覆盖范围比 Phase 2 的 {@code taskAssignee} 更宽，因此 total 可能大于
     *          实际有已办任务的流程数。建议前端使用"加载更多"模式或通过
     *          {@code records.size() < pageSize} 判断是否有下一页，而非基于 total
     *          计算精确总页数。如需精确分页，参见
     *          {@link #queryDoneTasks(String, TaskQueryDTO, Consumer)}。
     *
     * @param userId 用户 ID，不可为 null
     * @param query  查询条件
     * @return 分页已办列表，total 为近似值
     */
    PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query);

    /**
     * 查询指定用户的已办任务列表，支持自定义过滤条件。
     *
     * @apiNote <b>关于分页 total：</b>Phase 1 使用 {@code involvedUser}（不指定 TYPE）
     *          作为候选流程集，范围比 Phase 2 的 {@code taskAssignee} 更宽。
     *          因此 {@link PageResult#getTotal()} 是近似值，实际展示的记录数可能少于 total。
     *          <p><b>替代方案：</b>如果您的业务需要精确分页（total 必须等于实际记录数），
     *          推荐以下方案之一：</p>
     *          <ol>
     *            <li><b>前端方案（推荐）</b> — 使用"加载更多"模式，不暴露精确总页数。
     *                通过 {@code records.size() < pageSize} 判断是否还有下一页。</li>
     *            <li><b>自定义查询</b> — 编写 MyBatis Mapper XML 直查 Flowable 内部表
     *                ({@code ACT_HI_TASKINST} JOIN {@code ACT_HI_PROCINST})，
     *                在 SQL 层实现精确的 task-level 分页和 count。
     *                注意：此方案依赖 Flowable 内部表结构，版本升级时需验证兼容性。</li>
     *          </ol>
     *
     * @param userId   用户 ID，不可为 null
     * @param query    查询条件
     * @param enhancer 可选的自定义过滤条件（作用于 Phase 1 的 {@code HistoricProcessInstanceQuery}）
     * @return 分页已办列表，total 为近似值
     */
    PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                          Consumer<HistoricProcessInstanceQuery> enhancer);

    /**
     * 查询指定用户的已办任务列表（精确分页）。
     *
     * <p>与 {@link #queryDoneTasks(String, TaskQueryDTO)} 不同，此方法 Phase 1
     * 使用 {@code NativeHistoricProcessInstanceQuery} 直接查询只有已完成任务的
     * 流程实例，从而获得精确的 {@code total}。</p>
     *
     * <p><b>限制：</b>Native SQL 无法使用 enhancer 进行链式扩展。
     * 需要自定义过滤的场景请使用原有的
     * {@link #queryDoneTasks(String, TaskQueryDTO, Consumer)} 方法。</p>
     *
     * @param userId 用户 ID，不可为 null
     * @param query  查询条件
     * @return 分页已办列表，total 精确
     * @see #queryDoneTasks(String, TaskQueryDTO)
     */
    PageResult<DoneTaskVO> queryDoneTasksPrecise(String userId, TaskQueryDTO query);

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
