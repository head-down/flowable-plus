package io.github.flowable.plus.core.api;

import io.github.flowable.plus.core.enums.TraversalMode;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApprovalPersonnelVO;
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
     * @param mode       遍历深度：{@link TraversalMode#FULL} 返回完整审批链路，
     *                   {@link TraversalMode#ADJACENT} 仅返回第一个审批层级
     * @return 初始审批节点列表，每个节点包含审批人列表
     * @see #getNextNodeApprovers(String, TraversalMode, Map)
     */
    List<NodeApproverVO> getNextNodeApprovers(String processKey, TraversalMode mode);

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人（支持可选变量评估网关条件）。
     *
     * @param processKey 流程定义 Key
     * @param mode       遍历深度：{@link TraversalMode#FULL} 返回完整审批链路，
     *                   {@link TraversalMode#ADJACENT} 仅返回第一个审批层级
     * @param variables  变量上下文，为 null 时不评估条件，全部展开
     * @return 初始审批节点列表，每个节点包含审批人列表
     */
    List<NodeApproverVO> getNextNodeApprovers(String processKey, TraversalMode mode,
                                              Map<String, Object> variables);

    /**
     * 获取当前任务可流转至的下游节点列表。
     *
     * <p>{@link TraversalMode#FULL} 返回所有可达下游节点（完整链路），
     * {@link TraversalMode#ADJACENT} 仅返回紧邻的下一个审批层级
     * （遇 UserTask 即停止深入，不穿越其 outgoing 序列流）。
     * 若下游存在 EndEvent 分支，结果中附带
     * {@link NextTaskNodeVO#END_TASK_CODE} 节点。</p>
     *
     * <p>典型场景：审批页面展示「下一步可选的审批分支」。</p>
     *
     * @param taskId 当前任务 ID，不可为 null 或空
     * @param mode   遍历深度
     * @return 下游节点列表
     */
    List<NextTaskNodeVO> getNextTaskNodes(String taskId, TraversalMode mode);

    /**
     * 获取当前任务下游节点的审批人（扁平列表）。
     *
     * <p>{@link TraversalMode#FULL} 返回所有可达下游审批人，
     * {@link TraversalMode#ADJACENT} 仅返回紧邻节点的审批人。
     * 同一节点内的审批人已按优先级去重（assignee &gt; candidateUser &gt;
     * candidateGroup），跨节点不作去重——同一用户出现在多个节点时列表中出现多次
     * （各携带对应 nodeId）。调用方应根据业务场景自行聚合（预览场景按 userId 去重，
     * 指派场景按 nodeId 分组）；如需查询指定节点的审批人，按
     * {@link ApproverInfoVO#getNodeId()} 过滤即可。</p>
     *
     * @param taskId 当前任务 ID，不可为 null 或空
     * @param mode   遍历深度
     * @return 下游节点审批人扁平列表
     */
    List<ApproverInfoVO> getNextTaskApprovers(String taskId, TraversalMode mode);

    // ======================== 流程追踪 ========================

    /**
     * 获取单个流程实例的运行时摘要信息。
     *
     * <p>内部复用 {@link #batchQueryProcessSummaries(List)} 逻辑，
     * 适用于详情页、状态检查等单条查询场景。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null 或空
     * @return 流程摘要信息，流程实例不存在时返回 null
     */
    ProcessSummaryVO getProcessSummary(String processInstanceId);

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
     * 获取流程实例的审批人员详情（已审批/未审批分组，含用户信息补全）。
     *
     * <p>已审批 = 流程实例中已完成的所有历史任务的处理人（按审批时间升序，同 userId 去重）。
     * 未审批 = 当前活跃的运行时任务的 assignee（同 userId 去重）。</p>
     *
     * <p>返回的 PersonnelInfo 中 nickName/deptId/deptName 字段由
     * {@link io.github.flowable.plus.core.spi.UserInfoResolver} 补全。
     * 若应用未注入 UserInfoResolver Bean，相应字段为 null（降级兼容）。</p>
     *
     * <p>与 {@link HistoryOperations#getApprovalHistory(String)} 的区别：
     * 本方法按"人员"维度分组输出，审批历史按"节点时间线"维度输出。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null 或空
     * @return 审批人员分组（各列表为空时 size=0 而非 null）
     * @throws IllegalArgumentException 如果 processInstanceId 为 null 或空
     */
    ApprovalPersonnelVO getApprovalPersonnel(String processInstanceId);

    /**
     * 根据流程实例 ID 获取 businessKey。
     *
     * <p><b>背景</b>：Flowable 6+ 出于 task-service 模块解耦的设计考量，
     * 从 {@link org.flowable.task.service.delegate.DelegateTask} 接口上移除了
     * {@code getExecution()} 方法，TaskEntity 内部仅持有 {@code executionId}
     * 字符串而非 Execution 对象。这使得无法像 Activiti 6 那样通过
     * {@code delegateTask.getExecution().getProcessInstanceBusinessKey()} 直接获取
     * businessKey。</p>
     *
     * <p>本方法封装了从 processInstanceId → businessKey 的查询路径：先查运行时
     * （{@code RuntimeService}），未命中则查历史（{@code HistoryService}）。
     * 在 TaskListener 等 CommandContext 内部执行时，Flowable 的一级 entity cache
     * 通常已持有目标 ProcessInstance，因此一般为缓存命中，无额外数据库 I/O。</p>
     *
     * <p><b>极低延时场景</b>：如果您的 Listener 对每次 Service 调用都敏感，
     * 可在流程启动时额外将 businessKey 作为流程变量传入
     * （{@code variables.put("businessKey", businessKey)}），在 Listener 中通过
     * {@code delegateTask.getVariable("businessKey")} 直接获取。注意：此方案存在
     * 数据冗余和不一致风险，不推荐作为默认方案。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null 或空
     * @return businessKey，未设置或流程实例不存在时返回 null
     * @see #batchQueryProcessSummaries(List)
     */
    String getBusinessKeyByProcessInstanceId(String processInstanceId);
}
