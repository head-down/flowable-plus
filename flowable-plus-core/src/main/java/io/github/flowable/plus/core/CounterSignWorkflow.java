package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会签工作流模块，封装多实例审批任务的投票与人员管理逻辑。
 *
 * @author flowable-plus
 */
class CounterSignWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CounterSignWorkflow.class);

    private final UserContext userContext;
    private final TaskRepository taskRepository;
    private final HistoricRepository historicRepository;
    private final RuntimeService runtimeService;
    private final BpmnModelCache bpmnModelCache;
    private final NodeFinder nodeFinder;
    private final List<CounterSignCallback> counterSignCallbacks;

    CounterSignWorkflow(UserContext userContext, TaskRepository taskRepository,
                        HistoricRepository historicRepository, RuntimeService runtimeService,
                        BpmnModelCache bpmnModelCache, NodeFinder nodeFinder,
                        List<CounterSignCallback> counterSignCallbacks) {
        this.userContext = userContext;
        this.taskRepository = taskRepository;
        this.historicRepository = historicRepository;
        this.runtimeService = runtimeService;
        this.bpmnModelCache = bpmnModelCache;
        this.nodeFinder = nodeFinder;
        this.counterSignCallbacks = counterSignCallbacks;
    }

    void counterSign(String taskId, boolean approved, Map<String, Object> variables, String comment) {
        Task task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "会签");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "会签");

        if (!bpmnModelCache.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 不是多实例子任务，请使用审批操作(completeTask)");
        }

        String userId = userContext.getCurrentUserId();
        String processInstanceId = task.getProcessInstanceId();

        if (!hasVoted(task, userId)) {
            List<String> assignees = resolveCurrentAssignees(task);
            invokeCallbacks(cb -> cb.onStart(processInstanceId, taskId, assignees));
        }

        taskRepository.claim(taskId, userId);

        if (StrUtil.isNotBlank(comment)) {
            String commentType = approved ? "AGREE" : "COUNTER_SIGN_REJECT";
            taskRepository.addComment(taskId, null, commentType, comment);
        }

        invokeCallbacks(cb -> cb.onVote(processInstanceId, taskId, userId, approved, comment));

        taskRepository.complete(taskId, variables);

        if (isMultiInstanceFinished(task)) {
            invokeCallbacks(cb -> cb.onFinish(processInstanceId, taskId, "finished"));
        }
    }

    void addCounterSigner(String taskId, List<String> assignees) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }
        if (assignees == null || assignees.isEmpty()) {
            throw new IllegalArgumentException("assignees 不可为 null 或空");
        }

        Task task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "加签");

        if (!bpmnModelCache.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 不是多实例子任务，无法加签");
        }

        validateCounterSignPermission(task, "加签");

        String processInstanceId = task.getProcessInstanceId();
        String activityId = task.getTaskDefinitionKey();

        List<String> currentAssignees = resolveCurrentAssignees(task);

        List<String> newAssignees = new ArrayList<>();
        List<String> skippedAssignees = new ArrayList<>();
        for (String assignee : assignees) {
            if (StrUtil.isBlank(assignee)) {
                continue;
            }
            if (currentAssignees.contains(assignee)) {
                skippedAssignees.add(assignee);
            } else {
                newAssignees.add(assignee);
            }
        }

        if (newAssignees.isEmpty()) {
            return;
        }

        for (String assignee : newAssignees) {
            HashMap<String, Object> executionVariables = new HashMap<>();
            executionVariables.put("assignee", assignee);
            runtimeService.addMultiInstanceExecution(activityId, processInstanceId, executionVariables);
        }

        StringBuilder commentMsg = new StringBuilder("新增审批人: ")
                .append(String.join(", ", newAssignees));
        if (!skippedAssignees.isEmpty()) {
            commentMsg.append("；跳过已存在: ").append(String.join(", ", skippedAssignees));
        }
        taskRepository.addComment(taskId, processInstanceId, "ADD_SIGN", commentMsg.toString());

        invokeCallbacks(cb -> cb.onStart(processInstanceId, taskId, newAssignees));
    }

    void removeCounterSigner(String taskId, String assignee) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }
        if (StrUtil.isBlank(assignee)) {
            throw new IllegalArgumentException("assignee 不可为 null 或空");
        }

        Task task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "减签");

        if (!bpmnModelCache.isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 不是多实例子任务，无法减签");
        }

        validateCounterSignPermission(task, "减签");

        String processInstanceId = task.getProcessInstanceId();

        if (hasVoted(task, assignee)) {
            throw new IllegalArgumentException(
                    "审批人 " + assignee + " 已投票，无法减签");
        }

        List<String> currentAssignees = resolveCurrentAssignees(task);
        long unvotedCount = currentAssignees.stream()
                .filter(a -> !hasVoted(task, a))
                .count();
        if (unvotedCount <= 1) {
            throw new IllegalArgumentException(
                    "减签后剩余未投票审批人不足，当前未投票人数: " + unvotedCount);
        }

        Task targetTask = taskRepository.findActiveTask(
                processInstanceId, task.getTaskDefinitionKey(), assignee);

        if (targetTask == null) {
            throw new NotFoundException(
                    "未找到审批人 " + assignee + " 的活跃会签任务");
        }

        runtimeService.deleteMultiInstanceExecution(targetTask.getExecutionId(), false);

        taskRepository.addComment(taskId, processInstanceId, "DELETE_SIGN",
                "移除审批人: " + assignee);
    }

    // ======================== 内部辅助 ========================

    private boolean hasVoted(Task task, String userId) {
        return historicRepository.countFinishedTasks(
                task.getProcessInstanceId(), task.getTaskDefinitionKey(), userId) > 0;
    }

    private List<String> resolveCurrentAssignees(Task task) {
        return taskRepository.listActiveTasks(task.getProcessInstanceId(), task.getTaskDefinitionKey())
                .stream()
                .map(Task::getAssignee)
                .filter(Objects::nonNull)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private boolean isMultiInstanceFinished(Task task) {
        return taskRepository.countActiveTasks(task.getProcessInstanceId(), task.getTaskDefinitionKey()) == 0;
    }

    private void invokeCallbacks(java.util.function.Consumer<CounterSignCallback> action) {
        for (CounterSignCallback cb : counterSignCallbacks) {
            try {
                action.accept(cb);
            } catch (Exception e) {
                log.warn("CounterSignCallback 回调异常: {}", cb.getClass().getName(), e);
            }
        }
    }

    private void validateCounterSignPermission(Task task, String operation) {
        String currentUserId = userContext.getCurrentUserId();
        String processInstanceId = task.getProcessInstanceId();

        List<String> prevNodes;
        try {
            prevNodes = nodeFinder.findPreviousNodes(
                    task.getProcessDefinitionId(), task.getTaskDefinitionKey(), processInstanceId);
        } catch (NoPreviousNodeException e) {
            prevNodes = Collections.emptyList();
        }

        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法确定" + operation + "权限");
        }

        String authorizedUserId;

        if (prevNodes.isEmpty()) {
            HistoricProcessInstance historicPi = historicRepository.findProcessInstance(processInstanceId);
            if (historicPi == null) {
                throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
            }
            authorizedUserId = historicPi.getStartUserId();
        } else {
            String prevNodeId = prevNodes.get(0);
            HistoricTaskInstance prevTask = historicRepository.findLatestFinishedTask(processInstanceId, prevNodeId);

            if (prevTask == null) {
                throw new NotFoundException("未找到上一节点 " + prevNodeId + " 的历史任务");
            }
            authorizedUserId = prevTask.getAssignee();
        }

        if (!currentUserId.equals(authorizedUserId)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 无权" + operation + "，仅上一节点审批人可操作");
        }
    }
}
