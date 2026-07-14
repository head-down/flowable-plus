package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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
import java.util.stream.Collectors;

/**
 * 会签工作流模块，封装多实例审批任务的投票与人员管理逻辑。
 *
 * @author flowable-plus
 */
public class CounterSignWorkflow implements CounterSignOperations {

    private static final Logger log = LoggerFactory.getLogger(CounterSignWorkflow.class);

    private final UserContext userContext;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final MultiInstanceDetector multiInstanceDetector;
    private final NodeFinder nodeFinder;
    private final List<CounterSignCallback> counterSignCallbacks;

    public CounterSignWorkflow(UserContext userContext, TaskService taskService,
                        HistoryService historyService, RuntimeService runtimeService,
                        MultiInstanceDetector multiInstanceDetector, NodeFinder nodeFinder,
                        List<CounterSignCallback> counterSignCallbacks) {
        this.userContext = userContext;
        this.taskService = taskService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.multiInstanceDetector = multiInstanceDetector;
        this.nodeFinder = nodeFinder;
        this.counterSignCallbacks = counterSignCallbacks;
    }

    @Override
    public void counterSign(String taskId, boolean approved, Map<String, Object> variables, String comment) {
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "会签");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "会签");
        TaskValidation.validateMultiInstance(multiInstanceDetector, task, taskId, "会签");

        String userId = userContext.getCurrentUserId();
        String processInstanceId = task.getProcessInstanceId();

        if (!hasVoted(task, userId)) {
            List<String> assignees = resolveCurrentAssignees(task);
            invokeCallbacks(cb -> cb.onStart(processInstanceId, taskId, assignees));
        }

        taskService.claim(taskId, userId);

        if (StrUtil.isNotBlank(comment)) {
            String commentType = approved ? "AGREE" : "COUNTER_SIGN_REJECT";
            taskService.addComment(taskId, null, commentType, comment);
        }

        invokeCallbacks(cb -> cb.onVote(processInstanceId, taskId, userId, approved, comment));

        taskService.complete(taskId, variables);

        if (isMultiInstanceFinished(task)) {
            invokeCallbacks(cb -> cb.onFinish(processInstanceId, taskId, "finished"));
        }
    }

    @Override
    public void addCounterSigner(String taskId, List<String> assignees) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }
        if (assignees == null || assignees.isEmpty()) {
            throw new IllegalArgumentException("assignees 不可为 null 或空");
        }

        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "加签");
        TaskValidation.validateMultiInstance(multiInstanceDetector, task, taskId, "加签");

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
        taskService.addComment(taskId, processInstanceId, "ADD_SIGN", commentMsg.toString());

        invokeCallbacks(cb -> cb.onStart(processInstanceId, taskId, newAssignees));
    }

    @Override
    public void removeCounterSigner(String taskId, String assignee) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }
        if (StrUtil.isBlank(assignee)) {
            throw new IllegalArgumentException("assignee 不可为 null 或空");
        }

        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "减签");
        TaskValidation.validateMultiInstance(multiInstanceDetector, task, taskId, "减签");

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

        Task targetTaskObj = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .taskAssignee(assignee)
                .active()
                .singleResult();

        if (targetTaskObj == null) {
            throw new NotFoundException(
                    "未找到审批人 " + assignee + " 的活跃会签任务");
        }

        runtimeService.deleteMultiInstanceExecution(targetTaskObj.getExecutionId(), false);

        taskService.addComment(taskId, processInstanceId, "DELETE_SIGN",
                "移除审批人: " + assignee);
    }

    // ======================== 内部辅助 ========================

    private boolean hasVoted(PlusTask task, String userId) {
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .taskAssignee(userId)
                .finished()
                .count() > 0;
    }

    private List<String> resolveCurrentAssignees(PlusTask task) {
        return taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .active()
                .list()
                .stream()
                .map(Task::getAssignee)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean isMultiInstanceFinished(PlusTask task) {
        return taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .active()
                .count() == 0;
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

    private void validateCounterSignPermission(PlusTask task, String operation) {
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
            HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            if (hpi == null) {
                throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
            }
            authorizedUserId = hpi.getStartUserId();
        } else {
            String prevNodeId = prevNodes.get(0);
            List<HistoricTaskInstance> prevTasks = historyService.createHistoricTaskInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .taskDefinitionKey(prevNodeId)
                    .finished()
                    .orderByHistoricTaskInstanceEndTime().desc()
                    .listPage(0, 1);

            if (prevTasks.isEmpty()) {
                throw new NotFoundException("未找到上一节点 " + prevNodeId + " 的历史任务");
            }
            authorizedUserId = prevTasks.get(0).getAssignee();
        }

        if (!currentUserId.equals(authorizedUserId)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 无权" + operation + "，仅上一节点审批人可操作");
        }
    }
}
