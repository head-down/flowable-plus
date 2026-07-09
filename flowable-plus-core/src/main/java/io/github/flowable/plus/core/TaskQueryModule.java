package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.TaskService;
import org.flowable.idm.api.Group;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 待办/已办查询模块，封装 Flowable TaskService/HistoryService 的查询逻辑。
 *
 * <p>通过 {@link VOAssembler} 接缝完成 VO 转换和流程信息补全。
 * 分页、过滤、去重逻辑均内聚于此模块。</p>
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
     * 查询指定用户的已办任务列表。
     */
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query) {
        return queryDoneTasks(userId, query, null);
    }

    /**
     * 查询指定用户的已办任务列表，支持自定义过滤条件。
     */
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                                  Consumer<HistoricTaskInstanceQuery> enhancer) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不可为 null 或空");
        }
        if (query == null) {
            query = new TaskQueryDTO();
        }

        HistoricTaskInstanceQuery historicQuery = historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(userId)
                .finished();

        if (query.getProcessDefinitionKey() != null && !query.getProcessDefinitionKey().isEmpty()) {
            historicQuery.processDefinitionKey(query.getProcessDefinitionKey());
        }
        if (query.getTaskName() != null && !query.getTaskName().isEmpty()) {
            historicQuery.taskName(query.getTaskName());
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            historicQuery.processInstanceBusinessKeyLike("%" + query.getKeyword() + "%");
        }
        if (query.getBeginDate() != null) {
            historicQuery.taskCompletedAfter(query.getBeginDate());
        }
        if (query.getEndDate() != null) {
            historicQuery.taskCompletedBefore(query.getEndDate());
        }

        if (enhancer != null) {
            enhancer.accept(historicQuery);
        }

        // 查询全部（多实例去重必须在内存中完成，不能依赖数据库分页）
        List<HistoricTaskInstance> allHistoricTasks = historicQuery
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();

        List<HistoricTaskInstance> deduped = voAssembler.dedupByNode(allHistoricTasks);

        long total = deduped.size();

        int fromIndex = (query.getPageNum() - 1) * query.getPageSize();
        int toIndex = Math.min(fromIndex + query.getPageSize(), deduped.size());
        List<HistoricTaskInstance> pageTasks;
        if (fromIndex >= deduped.size()) {
            pageTasks = Collections.emptyList();
        } else {
            pageTasks = new ArrayList<>(deduped.subList(fromIndex, toIndex));
        }

        List<DoneTaskVO> vos = voAssembler.toDoneVOs(pageTasks);

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
}
