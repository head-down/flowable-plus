package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.event.EventBus;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「新就绪任务」发射器：把 {@link EventBus#taskCreated} 的发射点收敛成「引擎状态变更调用前后
 * 活跃任务集之差」一处判定，供三个写侧 workflow 共用（ADR-0042 第 2 节定案 9）。
 *
 * <p><b>为何按差集判</b>：受控入口只承诺「使<b>新</b>待办就绪」，而同一流程实例的活跃任务集在调用
 * 前后必然包含旧任务 —— 按 ID 差集取出新增者，改派类操作（只换 assignee、不换 ID）自然被排除，
 * 无需为每种操作另立白名单。</p>
 *
 * <p><b>未启用时零副作用</b>：{@link EventBus#isEnabled()} 为假（无发布者）时，快照与发射<b>都直接短路</b>
 * —— 既不做查询、也不构造事件对象。这正是 ADR-0042 第 11 节第 4 条残留账本第 ① 行的无数据行为。</p>
 */
final class NewlyReadyTaskEmitter {

    private final TaskService taskService;
    private final EventBus eventBus;

    NewlyReadyTaskEmitter(TaskService taskService, EventBus eventBus) {
        this.taskService = taskService;
        this.eventBus = eventBus;
    }

    /**
     * 取引擎状态变更<b>之前</b>的活跃任务 ID 快照。
     *
     * @param processInstanceId 流程实例 ID
     * @return 活跃任务 ID 集；事件面未启用时返回空集（与差集判定的语义一致）
     */
    Set<String> snapshotActiveTaskIds(String processInstanceId) {
        if (!eventBus.isEnabled()) {
            return Collections.emptySet();
        }
        return activeTasks(processInstanceId).stream()
                .map(Task::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 取引擎状态变更<b>之后</b>的活跃任务，对不在快照内的逐个发射 {@link EventBus#taskCreated}。
     *
     * @param processInstanceId 流程实例 ID
     * @param activeBefore      {@link #snapshotActiveTaskIds} 的返回值
     */
    void emitNewlyReadyTasks(String processInstanceId, Set<String> activeBefore) {
        if (!eventBus.isEnabled()) {
            return;
        }
        for (Task task : activeTasks(processInstanceId)) {
            if (!activeBefore.contains(task.getId())) {
                PlusTask plusTask = PlusTask.from(task);
                eventBus.taskCreated(plusTask, plusTask.getCreateTime());
            }
        }
    }

    private List<Task> activeTasks(String processInstanceId) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list();
    }
}
