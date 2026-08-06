package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.event.TaskCompletedEvent;
import io.github.flowable.plus.core.event.TaskDelegatedEvent;
import io.github.flowable.plus.core.event.TaskRejectedEvent;
import io.github.flowable.plus.core.exception.AmbiguousPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.support.ProcessEndDetector;
import io.github.flowable.plus.core.support.TaskValidation;
import io.github.flowable.plus.core.support.PreviousNodeAuthorizer;
import io.github.flowable.plus.core.api.CounterSignOperations;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会签工作流模块，封装多实例审批任务的投票与人员管理逻辑。
 *
 * @author flowable-plus
 */
public class CounterSignWorkflow implements CounterSignOperations {

    /** Task 局部变量名：会签轮次索引 */
    static final String CS_ROUND_INDEX_VAR = "csRoundIndex";

    private static final Logger log = LoggerFactory.getLogger(CounterSignWorkflow.class);

    private final UserContext userContext;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final MultiInstanceDetector multiInstanceDetector;
    private final NodeFinder nodeFinder;
    private final List<CounterSignCallback> counterSignCallbacks;
    private final EventPublisher eventPublisher;
    private final ProcessEndDetector processEndDetector;
    private final PreviousNodeAuthorizer previousNodeAuthorizer;

    public CounterSignWorkflow(UserContext userContext, TaskService taskService,
                        HistoryService historyService, RuntimeService runtimeService,
                        MultiInstanceDetector multiInstanceDetector, NodeFinder nodeFinder,
                        List<CounterSignCallback> counterSignCallbacks,
                        EventPublisher eventPublisher,
                        ProcessEndDetector processEndDetector,
                        PreviousNodeAuthorizer previousNodeAuthorizer) {
        this.userContext = userContext;
        this.taskService = taskService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.multiInstanceDetector = multiInstanceDetector;
        this.nodeFinder = nodeFinder;
        this.counterSignCallbacks = counterSignCallbacks;
        this.eventPublisher = eventPublisher;
        this.processEndDetector = processEndDetector;
        this.previousNodeAuthorizer = previousNodeAuthorizer;
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
            String commentType = approved ? CommentType.COUNTER_SIGN_AGREE.name() : CommentType.COUNTER_SIGN_REJECT.name();
            taskService.addComment(taskId, processInstanceId, commentType, comment);
        }

        invokeCallbacks(cb -> cb.onVote(processInstanceId, taskId, userId, approved, comment));

        taskService.complete(taskId, variables);

        if (eventPublisher != null) {
            if (approved) {
                eventPublisher.publish(TaskCompletedEvent.of(task.getId(), task.getProcessInstanceId(),
                        task.getName(), task.getTaskDefinitionKey(), userId, comment, new java.util.Date()));
            } else {
                eventPublisher.publish(TaskRejectedEvent.of(task.getId(), task.getProcessInstanceId(),
                        task.getName(), task.getTaskDefinitionKey(), task.getAssignee(),
                        comment, new java.util.Date()));
            }
            processEndDetector.checkAndPublish(task.getProcessInstanceId());
        }

        if (isMultiInstanceFinished(task)) {
            invokeCallbacks(cb -> cb.onFinish(processInstanceId, taskId, "finished"));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>实现细节</b>：新轮次通过 {@link #isMultiInstanceFinished} 检测，
     * 轮次索引通过 {@link #determineNextRoundIndex} 从 {@code ACT_HI_VARINST}
     * 查询历史 {@code csRoundIndex} 最大值 + 1 计算。<em>调用方不得在调用本方法前
     * 将发起任务的 {@code csRoundIndex} 写入历史表</em>，否则当前任务会"自引用污染"
     * 历史查询结果，导致新建子任务轮次偏移。</p>
     */
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

        validateCounterSignPermission(task);

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

        // 检测是否开启新轮次（全部审批完成后加签 = 新一轮）
        boolean isNewRound = isMultiInstanceFinished(task);
        int roundIndex;
        if (isNewRound) {
            roundIndex = determineNextRoundIndex(processInstanceId, activityId);
        } else {
            roundIndex = determineCurrentRoundIndex(processInstanceId, activityId);
        }

        // 批量加签
        for (String assignee : newAssignees) {
            HashMap<String, Object> executionVariables = new HashMap<>();
            executionVariables.put("assignee", assignee);
            runtimeService.addMultiInstanceExecution(activityId, processInstanceId, executionVariables);
        }

        // 始终为新任务打上 csRoundIndex（批量查询 + 内存过滤 + 统一打标，N→1 降维）
        Set<String> newAssigneeSet = new HashSet<>(newAssignees);
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .active()
                .list();
        for (Task t : activeTasks) {
            if (newAssigneeSet.contains(t.getAssignee())) {
                taskService.setVariableLocal(t.getId(), "csRoundIndex", roundIndex);
            }
        }

        StringBuilder commentMsg = new StringBuilder("加签审批人: ")
                .append(String.join(", ", newAssignees));
        if (!skippedAssignees.isEmpty()) {
            commentMsg.append("，跳过重复: ").append(String.join(", ", skippedAssignees));
        }
        if (isNewRound) {
            commentMsg.append("，开启第 ").append(roundIndex + 1).append(" 轮会签");
        }
        taskService.addComment(taskId, processInstanceId, CommentType.ADD_SIGN.name(), commentMsg.toString());

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

        validateCounterSignPermission(task);

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

        taskService.addComment(taskId, processInstanceId, CommentType.DELETE_SIGN.name(),
                "移除审批人: " + assignee);
    }

    @Override
    public void delegateTask(String taskId, String delegateUserId, String reason) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }
        if (StrUtil.isBlank(delegateUserId)) {
            throw new IllegalArgumentException("delegateUserId 不可为 null 或空");
        }

        String currentUserId = userContext.getCurrentUserId();

        if (currentUserId.equals(delegateUserId)) {
            throw new IllegalArgumentException("委派目标不可为当前审批人");
        }

        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "委派");
        TaskValidation.validateCurrentUserIsAssignee(task, currentUserId, taskId, "委派");
        TaskValidation.validateMultiInstance(multiInstanceDetector, task, taskId, "委派");

        String processInstanceId = task.getProcessInstanceId();

        taskService.delegateTask(taskId, delegateUserId);

        if (eventPublisher != null) {
            eventPublisher.publish(TaskDelegatedEvent.of(task.getId(), task.getProcessInstanceId(),
                    task.getName(), task.getTaskDefinitionKey(),
                    currentUserId, delegateUserId, reason, new java.util.Date()));
        }

        String comment = "委派给 " + delegateUserId;
        if (StrUtil.isNotBlank(reason)) {
            comment += "（" + reason + "）";
        }
        taskService.addComment(taskId, processInstanceId, CommentType.DELEGATE.name(), comment);
    }

    @Override
    public void resolveDelegate(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        String currentUserId = userContext.getCurrentUserId();

        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "收回委派");

        String owner = task.getOwner();
        if (owner == null || !currentUserId.equals(owner)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是任务 " + taskId + " 的委派人，无权收回");
        }

        taskService.resolveTask(taskId);

        String comment = "从 " + task.getAssignee() + " 收回委派";
        taskService.addComment(taskId, task.getProcessInstanceId(),
                CommentType.RESOLVE_DELEGATE.name(), comment);
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
        long activeCount = taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .active()
                .count();
        if (activeCount == 0) {
            return true;
        }
        // 排除当前任务自身（addCounterSigner 场景中当前任务仍活跃）
        if (activeCount == 1) {
            // 区分"伪单例"和"真正的最后一人"：
            // 伪单例（只有 1 人且无人已完成）：未完成
            // 真正最后一人（他人已完成，只剩当前任务）：即将完成
            long finishedCount = historyService.createHistoricTaskInstanceQuery()
                    .processInstanceId(task.getProcessInstanceId())
                    .taskDefinitionKey(task.getTaskDefinitionKey())
                    .finished()
                    .count();
            if (finishedCount == 0) {
                return false;
            }
            Task sole = taskService.createTaskQuery()
                    .processInstanceId(task.getProcessInstanceId())
                    .taskDefinitionKey(task.getTaskDefinitionKey())
                    .active()
                    .singleResult();
            return sole != null && sole.getId().equals(task.getId());
        }
        return false;
    }

    /**
     * 确定下一个会签轮次索引。
     * 查询历史 csRoundIndex Task 局部变量，按 taskDefinitionKey 过滤以避免跨节点污染，
     * 然后计算 max + 1。若无历史数据（老数据或首轮），返回 1（原始审批人轮次为隐式 0）。
     */
    private int determineNextRoundIndex(String processInstanceId, String taskDefinitionKey) {
        // 按 taskDefinitionKey 获取所有历史任务 ID，用于 csRoundIndex 范围限定
        List<HistoricTaskInstance> tasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .list();

        java.util.Set<String> taskIds = tasks.stream()
                .map(HistoricTaskInstance::getId)
                .collect(Collectors.toSet());

        if (taskIds.isEmpty()) {
            return 1;
        }

        // 查询所有 csRoundIndex，内存过滤到当前节点的 taskId
        List<HistoricVariableInstance> vars = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(CS_ROUND_INDEX_VAR)
                .list();

        int maxRound = 0;
        for (HistoricVariableInstance var : vars) {
            if (taskIds.contains(var.getTaskId()) && var.getValue() instanceof Integer) {
                maxRound = Math.max(maxRound, (Integer) var.getValue());
            }
        }
        return maxRound > 0 ? maxRound + 1 : 1;
    }

    /**
     * 确定当前会签轮次索引（非新轮次加签场景）。
     *
     * <p>策略：
     * <ol>
     *   <li>优先从当前节点活跃任务读取 csRoundIndex 运行时变量</li>
     *   <li>降级：从历史数据推断，nextRound - 1（nextRound 最小为 1，
     *       因此 currentRound 最小为 0，即原始审批人隐式轮次）</li>
     * </ol>
     */
    private int determineCurrentRoundIndex(String processInstanceId,
                                            String taskDefinitionKey) {
        // 1. 优先从活跃任务的 csRoundIndex 读取当前轮次
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .active()
                .list();
        for (Task t : activeTasks) {
            Object var = taskService.getVariableLocal(t.getId(), CS_ROUND_INDEX_VAR);
            if (var instanceof Integer) {
                return (Integer) var;
            }
        }
        // 2. 降级：活跃任务无 csRoundIndex（如原始审批人轮次隐式 0）
        // determineNextRoundIndex 最小返回 1 → currentRound = 0 ✓
        return determineNextRoundIndex(processInstanceId, taskDefinitionKey) - 1;
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

    private void validateCounterSignPermission(PlusTask task) {
        String currentUserId = userContext.getCurrentUserId();
        try {
            if (!previousNodeAuthorizer.isAuthorized(currentUserId, task.getId())) {
                throw new PermissionDeniedException(
                        "用户 " + currentUserId + " 无权操作会签任务，仅上一节点审批人可操作");
            }
        } catch (AmbiguousPreviousNodeException e) {
            throw new PermissionDeniedException(
                    "当前会签任务的前置节点存在多个，无法唯一确定上一节点审批人", e);
        }
    }
}
