package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.function.Consumer;

/**
 * 待办/已办列表查询操作接口。
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface TaskListOperations {

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
     * @param enhancer 可选的自定义过滤条件（如 processVariableValueXXX）
     * @return 分页待办列表
     */
    PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query, Consumer<TaskQuery> enhancer);

    /**
     * 查询指定用户的已办任务列表。
     *
     * @param userId 用户 ID，不可为 null
     * @param query  查询条件（分页、流程定义Key筛选、时间范围等）
     * @return 分页已办列表
     */
    PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query);

    /**
     * 查询指定用户的已办任务列表，支持自定义过滤条件。
     *
     * @param userId   用户 ID，不可为 null
     * @param query    查询条件
     * @param enhancer 可选的自定义过滤条件（如 processVariableValueXXX）
     * @return 分页已办列表
     */
    PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                          Consumer<HistoricTaskInstanceQuery> enhancer);
}
