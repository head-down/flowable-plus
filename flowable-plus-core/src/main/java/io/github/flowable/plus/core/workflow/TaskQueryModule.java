package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import io.github.flowable.plus.core.support.VOAssembler;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.history.NativeHistoricProcessInstanceQuery;
import org.flowable.idm.api.Group;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 待办/已办查询模块，封装 Flowable TaskService/HistoryService 的查询逻辑。
 *
 * <p>通过 {@link VOAssembler} 接缝完成 VO 转换和流程信息补全。
 * 分页、过滤逻辑均内聚于此模块。</p>
 *
 * @author flowable-plus
 */
public class TaskQueryModule {

    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;
    private final VOAssembler voAssembler;

    public TaskQueryModule(TaskService taskService, HistoryService historyService,
                           IdentityService identityService, VOAssembler voAssembler) {
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (identityService == null) {
            throw new IllegalArgumentException("IdentityService 不可为 null");
        }
        if (voAssembler == null) {
            throw new IllegalArgumentException("VOAssembler 不可为 null");
        }
        this.taskService = taskService;
        this.historyService = historyService;
        this.identityService = identityService;
        this.voAssembler = voAssembler;
    }

    /**
     * 查询指定用户的待办任务列表。
     */
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query) {
        return queryTodoTasks(userId, query, null);
    }

    /**
     * 查询指定用户的待办任务列表，支持自定义过滤条件。
     */
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query,
                                                  Consumer<TaskQuery> enhancer) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不可为 null 或空");
        }
        if (query == null) {
            query = new TaskQueryDTO();
        }

        List<String> groupIds = getGroupIds(userId);

        TaskQuery taskQuery = taskService.createTaskQuery();
        if (groupIds.isEmpty()) {
            taskQuery.or()
                    .taskAssignee(userId)
                    .taskCandidateUser(userId)
                    .endOr();
        } else {
            taskQuery.or()
                    .taskAssignee(userId)
                    .taskCandidateUser(userId)
                    .taskCandidateGroupIn(groupIds)
                    .endOr();
        }

        applyTodoFilters(taskQuery, query);

        if (enhancer != null) {
            enhancer.accept(taskQuery);
        }

        long total = taskQuery.count();

        int firstResult = (query.getPageNum() - 1) * query.getPageSize();
        List<Task> tasks = taskQuery
                .orderByTaskCreateTime().desc()
                .listPage(firstResult, query.getPageSize());

        List<TodoTaskVO> vos = voAssembler.toTodoVOs(tasks);

        return new PageResult<>(total, query.getPageNum(), query.getPageSize(), vos);
    }

    /**
     * 查询指定用户的已办任务列表（每流程实例 1 条记录）。
     *
     * <p>采用两阶段查询：Phase 1 按流程实例分页，Phase 2 批量取任务详情。
     * 使用 Flowable 公开 API，不直查内部表。</p>
     *
     * @apiNote {@link PageResult#getTotal()} 为近似值，来源于 Phase 1 的流程实例计数
     *          ({@code involvedUser} + {@code startedBy})，可能大于实际有已办任务的流程数。
     *          前端建议用"加载更多"模式或通过 {@code records.size() < pageSize} 判断
     *          是否有下一页。如需精确分页，参见
     *          {@link #queryDoneTasks(String, TaskQueryDTO, Consumer)} 的 apiNote。
     */
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query) {
        return queryDoneTasks(userId, query, null);
    }

    /**
     * 查询指定用户的已办任务列表，支持自定义过滤条件。
     *
     * <p>两阶段查询：</p>
     * <ol>
     *   <li>{@code HistoricProcessInstanceQuery.involvedUser(userId)}
     *       按流程实例分页。involvedUser 匹配所有身份链接类型
     *       (participant/starter/candidate/owner)，<b>无需</b>额外叠加
     *       {@code startedBy} 查询，因为 Flowable 在启动流程时会自动创建
     *       {@code starter} 类型身份链接</li>
     *   <li>{@code HistoricTaskInstanceQuery.processInstanceIdIn(ids).taskAssignee(userId)}
     *       批量取任务，每流程实例取 endTime 最新的那条</li>
     * </ol>
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
     *                在 SQL 层实现精确的 task-count 精确分页。
     *                注意：此方案依赖 Flowable 内部表结构，版本升级时需验证兼容性。</li>
     *          </ol>
     */
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                                  Consumer<HistoricProcessInstanceQuery> enhancer) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不可为 null 或空");
        }
        if (query == null) {
            query = new TaskQueryDTO();
        }

        // Phase 1: 流程维度分页（involvedUser 匹配所有身份链接类型，包含 starter/participant/candidate 等）
        HistoricProcessInstanceQuery procQuery = historyService.createHistoricProcessInstanceQuery()
                .involvedUser(userId);

        if (query.getProcessDefinitionKey() != null && !query.getProcessDefinitionKey().isEmpty()) {
            procQuery.processDefinitionKey(query.getProcessDefinitionKey());
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            procQuery.processInstanceBusinessKeyLike("%" + query.getKeyword() + "%");
        }
        if (query.getBeginDate() != null) {
            procQuery.finishedAfter(query.getBeginDate());
        }
        if (query.getEndDate() != null) {
            procQuery.finishedBefore(query.getEndDate());
        }

        if (enhancer != null) {
            enhancer.accept(procQuery);
        }

        long total = procQuery.count();

        int firstResult = (query.getPageNum() - 1) * query.getPageSize();
        List<HistoricProcessInstance> processes = procQuery
                .orderByProcessInstanceEndTime().desc()
                .listPage(firstResult, query.getPageSize());

        if (processes.isEmpty()) {
            return new PageResult<>(total, query.getPageNum(), query.getPageSize(), Collections.emptyList());
        }

        // Phase 2: 批量取任务详情
        List<String> procInstIds = processes.stream()
                .map(HistoricProcessInstance::getId)
                .collect(Collectors.toList());

        List<HistoricTaskInstance> allTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceIdIn(procInstIds)
                .taskAssignee(userId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();

        // 每个流程实例取 endTime 最新的那条任务
        Map<String, HistoricTaskInstance> latestPerProcInst = new LinkedHashMap<>();
        for (HistoricTaskInstance task : allTasks) {
            latestPerProcInst.putIfAbsent(task.getProcessInstanceId(), task);
        }

        List<HistoricTaskInstance> orderedTasks = new ArrayList<>(latestPerProcInst.values());

        Map<String, HistoricProcessInstance> procInstMap = processes.stream()
                .collect(Collectors.toMap(HistoricProcessInstance::getId, p -> p, (a, b) -> a));

        List<DoneTaskVO> vos = voAssembler.toDoneVOs(orderedTasks, procInstMap);

        return new PageResult<>(total, query.getPageNum(), query.getPageSize(), vos);
    }

    /**
     * 查询指定用户的已办任务列表（精确分页）。
     *
     * <p>Phase 1 使用 {@link NativeHistoricProcessInstanceQuery} 写精确 SQL
     * 直接查出有已完成任务的流程实例，获取精确的 total 和当前页流程列表。
     * Phase 2 复用现有批量取任务 + 去重逻辑。</p>
     *
     * <p>SQL 使用 Java String 拼接构造完整查询，参数值在拼接前经
     * {@link #escapeSql(String)} 转义，避免 SQL 注入。日期格式化为
     * {@code yyyy-MM-dd HH:mm:ss}。</p>
     *
     * @param userId 用户 ID，不可为 null 或空
     * @param query  查询条件，不可为 null
     * @return 分页已办列表，total 精确
     */
    public PageResult<DoneTaskVO> queryDoneTasksPrecise(String userId, TaskQueryDTO query) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不可为 null 或空");
        }
        if (query == null) {
            query = new TaskQueryDTO();
        }

        // Phase 1: Native SQL 精确查询流程实例
        StringBuilder sql = new StringBuilder(512);
        sql.append("SELECT RES.* FROM ACT_HI_PROCINST RES WHERE EXISTS (")
           .append("SELECT 1 FROM ACT_HI_TASKINST T WHERE ")
           .append("T.PROC_INST_ID_ = RES.ID_ AND T.ASSIGNEE_ = '")
           .append(escapeSql(userId))
           .append("' AND T.END_TIME_ IS NOT NULL)");

        // 动态过滤条件
        if (query.getProcessDefinitionKey() != null && !query.getProcessDefinitionKey().isEmpty()) {
            sql.append(" AND RES.PROC_DEF_ID_ LIKE '")
               .append(escapeSql(query.getProcessDefinitionKey()))
               .append(":%'");
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            sql.append(" AND RES.BUSINESS_KEY_ LIKE '%")
               .append(escapeLike(query.getKeyword()))
               .append("%' ESCAPE '\\'");
        }
        if (query.getBeginDate() != null) {
            sql.append(" AND RES.END_TIME_ >= '")
               .append(formatDate(query.getBeginDate()))
               .append("'");
        }
        if (query.getEndDate() != null) {
            sql.append(" AND RES.END_TIME_ <= '")
               .append(formatDate(query.getEndDate()))
               .append("'");
        }

        sql.append(" ORDER BY RES.END_TIME_ DESC");

        NativeHistoricProcessInstanceQuery nativeQuery = historyService
                .createNativeHistoricProcessInstanceQuery()
                .sql(sql.toString());

        long total = nativeQuery.count();

        int firstResult = (query.getPageNum() - 1) * query.getPageSize();
        List<HistoricProcessInstance> processes = nativeQuery.listPage(firstResult, query.getPageSize());

        if (processes.isEmpty()) {
            return new PageResult<>(total, query.getPageNum(), query.getPageSize(), Collections.emptyList());
        }

        // Phase 2: 批量取任务详情（复用现有逻辑）
        List<String> procInstIds = processes.stream()
                .map(HistoricProcessInstance::getId)
                .collect(Collectors.toList());

        List<HistoricTaskInstance> allTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceIdIn(procInstIds)
                .taskAssignee(userId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();

        // 每个流程实例取 endTime 最新的那条任务
        Map<String, HistoricTaskInstance> latestPerProcInst = new LinkedHashMap<>();
        for (HistoricTaskInstance task : allTasks) {
            latestPerProcInst.putIfAbsent(task.getProcessInstanceId(), task);
        }

        List<HistoricTaskInstance> orderedTasks = new ArrayList<>(latestPerProcInst.values());

        Map<String, HistoricProcessInstance> procInstMap = processes.stream()
                .collect(Collectors.toMap(HistoricProcessInstance::getId, p -> p, (a, b) -> a));

        List<DoneTaskVO> vos = voAssembler.toDoneVOs(orderedTasks, procInstMap);

        return new PageResult<>(total, query.getPageNum(), query.getPageSize(), vos);
    }

    // ======================== 内部辅助方法 ========================

    private List<String> getGroupIds(String userId) {
        return identityService.createGroupQuery().groupMember(userId).list()
                .stream().map(Group::getId).collect(Collectors.toList());
    }

    private void applyTodoFilters(TaskQuery taskQuery, TaskQueryDTO query) {
        if (query.getProcessDefinitionKey() != null && !query.getProcessDefinitionKey().isEmpty()) {
            taskQuery.processDefinitionKey(query.getProcessDefinitionKey());
        }
        if (query.getTaskName() != null && !query.getTaskName().isEmpty()) {
            taskQuery.taskName(query.getTaskName());
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            taskQuery.processInstanceBusinessKeyLike("%" + query.getKeyword() + "%");
        }
        if (query.getBeginDate() != null) {
            taskQuery.taskCreatedAfter(query.getBeginDate());
        }
        if (query.getEndDate() != null) {
            taskQuery.taskCreatedBefore(query.getEndDate());
        }
    }

    /**
     * 转义 SQL 字符串中的单引号（{@code ' → ''}）。
     *
     * <p>用于 Native SQL 拼接时防止 SQL 注入。</p>
     */
    private static String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    /**
     * 转义 SQL LIKE 表达式中的特殊字符。
     *
     * <p>在 {@link #escapeSql(String)} 基础上额外转义 {@code %} 和 {@code _}，
     * 防止用户输入被误解为 SQL LIKE 通配符。</p>
     */
    private static String escapeLike(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                    .replace("'", "''");
    }

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    private static String formatDate(Date date) {
        return DATE_FORMAT.get().format(date);
    }
}
