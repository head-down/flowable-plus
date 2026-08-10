package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.event.TaskCompletedEvent;
import io.github.flowable.plus.core.event.TaskDelegatedEvent;
import io.github.flowable.plus.core.event.TaskRejectedEvent;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.support.ProcessEndDetector;
import io.github.flowable.plus.core.support.TaskValidation;
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
import java.util.Date;
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

    /** 流程实例级变量前缀：会签发起人，后接 taskDefinitionKey 实现多节点隔离 */
    static final String COUNTERSIGN_INITIATOR_VAR_PREFIX = "countersignInitiator_";

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

    public CounterSignWorkflow(UserContext userContext, TaskService taskService,
                        HistoryService historyService, RuntimeService runtimeService,
                        MultiInstanceDetector multiInstanceDetector, NodeFinder nodeFinder,
                        List<CounterSignCallback> counterSignCallbacks,
                        EventPublisher eventPublisher,
                        ProcessEndDetector processEndDetector) {
        this.userContext = userContext;
        this.taskService = taskService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.multiInstanceDetector = multiInstanceDetector;
        this.nodeFinder = nodeFinder;
        this.counterSignCallbacks = counterSignCallbacks;
        this.eventPublisher = eventPublisher;
        this.processEndDetector = processEndDetector;
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
     *
     * <p><b>发起任务打标</b>（ADR-0019 时序内化，2026-08-08）：本方法会在打标阶段将
     * 操作者任务与新增审批人一起写入 {@code csRoundIndex}，调用方无需再按 ADR-0019
     * 原时序手动为发起任务打标。这保证原始审批人（owner）首次加签即获得显式轮次，
     * 后续加签并入当前轮，不会因运行时缺显式轮次而被 {@link #isMultiInstanceFinished}
     * 误判"本轮将尽"而开启新一轮（隐患 C）。</p>
     *
     * <p><b>查重 fast fail</b>（ADR-0024，2026-08-10）：两层查重命中任一即抛
     * {@link IllegalArgumentException}，不创建任何任务、不写 comment、不产生副作用：
     * <ol>
     *   <li>维度一：与当前活跃会签人重复（含名单内自重复 {@code [A, A]}）</li>
     *   <li>维度二：与本轮（当前执行周期内、{@code csRoundIndex} 匹配）已投过票的审批人重复</li>
     * </ol>
     * 全部查重通过后才执行副作用（写 initiator / 批量加签 / 打标 / comment）。</p>
     *
     * <p><b>建模约束</b>（ADR-0026，2026-08-10）：本方法通过
     * {@code RuntimeService#addMultiInstanceExecution} 新建子执行时，写入的变量名
     * 固定为 {@code assignee}（见 {@code executionVariables.put("assignee", assignee)}）。
     * 因此 BPMN 会签节点必须满足<b>三处命名一致</b>：
     * {@code flowable:elementVariable="assignee"}、
     * {@code flowable:assignee="${assignee}"}、本方法写入的 {@code assignee}。
     * 若 assignee 表达式引用其它变量（如 {@code ${nextApprover}}），任务创建时
     * {@code UserTaskActivityBehavior} 在子执行上求值该表达式，会沿作用域链命中
     * 流程实例级变量 {@code nextApprover}（上一步流转设置的旧值，所有子实例共享），
     * 导致<b>所有加签任务错分给同一个人</b>，加签人收不到任务。详见 ADR-0026。</p>
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

        // 名单内自重复检测（如 [A, A]）：整体失败，避免创建两个同 assignee 的重复任务
        if (new HashSet<>(assignees).size() != assignees.size()) {
            throw new IllegalArgumentException(
                    "加签名单存在重复审批人，无法加签: " + String.join(", ", assignees));
        }

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

        // 维度一：与当前活跃会签人重复 → 整体失败（ADR-0024，替换原"全部重复时静默 return"）
        if (!skippedAssignees.isEmpty()) {
            throw new IllegalArgumentException(
                    "审批人 " + String.join(", ", skippedAssignees) + " 已在本轮会签中，无法重复加签");
        }
        if (newAssignees.isEmpty()) {
            throw new IllegalArgumentException("加签名单无有效审批人，无法加签");
        }

        // 检测是否开启新轮次（全部审批完成后加签 = 新一轮）
        // 模式分派（ADR-0022）：模式 A（伪单例，countersignInitiator 已写入）才有轮次概念，
        // 由 isMultiInstanceFinished 判定是否本轮已结束；模式 B（固定会签）无轮次概念，
        // 单执行周期内加签必然发生在本轮未投完时，永远并入当前轮。
        // trySetCounterSignInitiator 后移（ADR-0024）：查重前置要求不产生任何副作用，
        // 首次伪单例加签两种时序下 isNewRound 均 false、roundIndex=0，等价性已验证。
        boolean modeA = runtimeService.getVariable(processInstanceId,
                buildCountersignInitiatorVarName(activityId)) != null;
        boolean isNewRound = false;
        if (modeA) {
            isNewRound = isMultiInstanceFinished(task);
        }
        int roundIndex;
        if (isNewRound) {
            roundIndex = determineNextRoundIndex(processInstanceId, activityId);
        } else {
            roundIndex = determineCurrentRoundIndex(processInstanceId, activityId);
        }

        // 维度二：与本轮（当前执行周期内、csRoundIndex 匹配）已投过票的审批人重复 → 整体失败
        //（ADR-0024：roundIndex==0 时无标历史任务视为隐式轮次 0，覆盖模式 B 无打标场景）
        Set<String> votedAssigneesInRound = resolveVotedAssigneesInRound(
                processInstanceId, activityId, roundIndex);
        List<String> alreadyVoted = newAssignees.stream()
                .filter(votedAssigneesInRound::contains)
                .collect(Collectors.toList());
        if (!alreadyVoted.isEmpty()) {
            throw new IllegalArgumentException(
                    "审批人 " + String.join(", ", alreadyVoted) + " 已在本轮投过票，无法重复加签");
        }

        // 通过全部查重后才执行副作用：写入 countersignInitiator（仅伪单例首次加签）
        trySetCounterSignInitiator(task);

        // 批量加签
        for (String assignee : newAssignees) {
            HashMap<String, Object> executionVariables = new HashMap<>();
            executionVariables.put("assignee", assignee);
            runtimeService.addMultiInstanceExecution(activityId, processInstanceId, executionVariables);
        }

        // 始终为新任务打上 csRoundIndex，并将操作者任务（发起任务）归入同一轮次（批量查询 + 内存过滤 + 统一打标，N→1 降维）
        // 隐患 C 修复（2026-08-08）：此前仅给新加签人打标，原始审批人（owner）运行时无 csRoundIndex，
        // 其再次加签时 isMultiInstanceFinished 误判"本轮将尽"而开启新一轮（round 1+）。
        // 内化打标后 owner 首次加签即获显式轮次，后续加签走 roundVar 分支并入当前轮，
        // ADR-0019 的"调用方时序"要求相应放宽（调用方无需再手动为发起任务打标）。
        Set<String> newAssigneeSet = new HashSet<>(newAssignees);
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .active()
                .list();
        for (Task t : activeTasks) {
            if (newAssigneeSet.contains(t.getAssignee()) || t.getId().equals(task.getId())) {
                taskService.setVariableLocal(t.getId(), "csRoundIndex", roundIndex);
            }
        }

        StringBuilder commentMsg = new StringBuilder("加签审批人: ")
                .append(String.join(", ", newAssignees));
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

    /**
     * 判断是否为伪单例状态：活跃审批人仅 1 人，且该节点自进入以来从未出现过第二个任务。
     *
     * <p>判据：全局历史任务数（含活跃/已完成/被减签删除）== 1，即只有当前这一个活跃任务。
     * 与 finished 计数口径无关，因此：
     * <ul>
     *   <li>模式 A 伪单例首次加签：历史任务数 == 1 → 伪单例 ✓</li>
     *   <li>模式 B 固定会签减签至 1 人：历史任务数 &gt; 1 → 非伪单例，不会被误翻转 ✓</li>
     *   <li>模式 B 折返后新周期 1 人：全局历史任务数仍 &gt; 1（含上一周期）→ 非伪单例 ✓</li>
     * </ul></p>
     */
    private boolean isPseudoSingleton(PlusTask task) {
        long activeCount = taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .active()
                .count();
        if (activeCount != 1) {
            return false;
        }
        long historyTaskCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .count();
        return historyTaskCount == 1;
    }

    /**
     * 尝试设置会签发起人变量。仅在伪单例状态下首次调用加签且变量尚未设置时写入。
     */
    private void trySetCounterSignInitiator(PlusTask task) {
        String varName = buildCountersignInitiatorVarName(task.getTaskDefinitionKey());

        Object existing = runtimeService.getVariable(task.getProcessInstanceId(), varName);
        if (existing != null) {
            return;
        }

        if (!isPseudoSingleton(task)) {
            return;
        }

        runtimeService.setVariable(task.getProcessInstanceId(), varName, userContext.getCurrentUserId());
    }

    private static String buildCountersignInitiatorVarName(String taskDefinitionKey) {
        return COUNTERSIGN_INITIATOR_VAR_PREFIX + taskDefinitionKey;
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
            // 已完成数按当前执行周期限定——折返（重新进入会签节点）后，
            // 上一周期的已完成任务不计入本轮，否则会误判"本轮即将结束"。
            Date cycleBoundary = findCurrentCycleBoundary(task.getProcessInstanceId(),
                    task.getTaskDefinitionKey());
            long finishedCount = countFinishedInCurrentCycle(task.getProcessInstanceId(),
                    task.getTaskDefinitionKey(), cycleBoundary);
            if (finishedCount == 0) {
                return false;
            }
            Task sole = taskService.createTaskQuery()
                    .processInstanceId(task.getProcessInstanceId())
                    .taskDefinitionKey(task.getTaskDefinitionKey())
                    .active()
                    .singleResult();
            if (sole != null && sole.getId().equals(task.getId())) {
                // 唯一活跃任务 == 操作者自己（加签场景）：操作者任务仍活跃，本轮尚未结束。
                // 隐患 C 修复（2026-08-08）：无论操作者是否带 csRoundIndex，一律返回 false 并入当前轮。
                // 此前"无 csRoundIndex → 判定新一轮"的残留路径已消除——该路径在折返重建后
                // （多实例重建注入多人、owner 本轮未加签过）仍可达，会与单实例路径行为不一致。
                return false;
            }
            // 唯一活跃任务不是操作者自己（counterSign 场景，task 已完成）：本轮同样未结束。
            return false;
        }
        return false;
    }

    /**
     * 统计当前执行周期内（开始时间不早于 {@code cycleBoundary}）的已完成会签任务数。
     * 无周期边界（首个周期/老数据）时不做过滤，等价于历史全局计数。
     */
    private long countFinishedInCurrentCycle(String processInstanceId, String taskDefinitionKey,
                                             Date cycleBoundary) {
        List<HistoricTaskInstance> finished = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .finished()
                .list();
        if (cycleBoundary == null) {
            return finished.size();
        }
        return finished.stream()
                .filter(t -> isWithinCycle(t, cycleBoundary))
                .count();
    }

    /**
     * 判断历史任务是否属于当前执行周期（startTime 不早于 {@code cycleBoundary}）。
     * 周期边界为 null（无历史周期分隔/老数据）时不过滤；startTime 为 null（历史数据异常）
     * 视为早于周期边界，不计入当前周期。
     */
    private boolean isWithinCycle(HistoricTaskInstance task, Date cycleBoundary) {
        return cycleBoundary == null
                || (task.getStartTime() != null && !task.getStartTime().before(cycleBoundary));
    }

    /**
     * 解析当前执行周期内、指定轮次已投过票的审批人集合（ADR-0024）。
     *
     * <p>判定口径：仅统计 {@code findCurrentCycleBoundary} 限定周期内的同节点已完成任务；
     * 轮次匹配规则（{@link #matchesRound}）：{@code roundIndex > 0} 时要求任务局部变量
     * {@code csRoundIndex == roundIndex}；{@code roundIndex == 0} 时无 csRoundIndex
     * 或 {@code csRoundIndex == 0} 均视为隐式轮次 0（原始审批人/模式 B 固定会签未打标）。</p>
     *
     * <p><b>周期限定</b>修复折返后跨周期撞号误拦：上一周期已投票人的 csRoundIndex 可能与本周期
     * 撞号，按 startTime 限定周期后不参与本周期匹配（漏洞 B）。</p>
     *
     * <p><b>剔除被删除任务</b>：减签（{@code deleteMultiInstanceExecution}）也会留下 finished
     * 历史记录（{@code deleteReason} 非 null），被减签者从未投票，不应误判为"已投票"
     * （否则"减签后再加签回"被误拦）。</p>
     */
    private Set<String> resolveVotedAssigneesInRound(String processInstanceId, String activityId,
                                                     int roundIndex) {
        Date cycleBoundary = findCurrentCycleBoundary(processInstanceId, activityId);

        List<HistoricTaskInstance> finishedTasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .finished()
                .includeTaskLocalVariables()
                .list();

        return finishedTasks.stream()
                // 剔除被删除（减签/终止）的任务：deleteReason 非 null 表示从未投票，
                // 仅统计正常投票完成（deleteReason 为 null）的任务
                .filter(t -> t.getDeleteReason() == null)
                // 周期限定：仅统计当前执行周期内的任务
                .filter(t -> isWithinCycle(t, cycleBoundary))
                .filter(t -> matchesRound(t, roundIndex))
                .map(HistoricTaskInstance::getAssignee)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 轮次匹配：{@code roundIndex > 0} 时要求 {@code csRoundIndex == roundIndex}；
     * {@code roundIndex == 0} 时无标（缺失）或 == 0 均视为隐式轮次 0。
     */
    private boolean matchesRound(HistoricTaskInstance task, int roundIndex) {
        Map<String, Object> taskLocalVariables = task.getTaskLocalVariables();
        Object roundVar = taskLocalVariables != null
                ? taskLocalVariables.get(CS_ROUND_INDEX_VAR) : null;
        if (roundIndex > 0) {
            return roundVar instanceof Integer && ((Integer) roundVar).intValue() == roundIndex;
        }
        return roundVar == null
                || (roundVar instanceof Integer && ((Integer) roundVar).intValue() == 0);
    }

    /**
     * 确定下一个会签轮次索引。
     * 查询历史 csRoundIndex Task 局部变量，按 taskDefinitionKey 过滤以避免跨节点污染，
     * 然后计算 max + 1。若无历史数据（老数据或首轮），返回 1（原始审批人轮次为隐式 0）。
     *
     * <p><b>执行周期限定</b>：折返（驳回/退回/跳转重新进入会签节点）会创建新的执行周期，
     * 轮次编号应在周期内重新计数。通过 {@link #findCurrentCycleBoundary} 确定当前周期
     * 的历史边界，仅统计边界之后的 csRoundIndex，避免沿用上一周期的全局 max。</p>
     */
    private int determineNextRoundIndex(String processInstanceId, String taskDefinitionKey) {
        Date cycleBoundary = findCurrentCycleBoundary(processInstanceId, taskDefinitionKey);

        // 按 taskDefinitionKey 获取所有历史任务 ID，用于 csRoundIndex 范围限定
        List<HistoricTaskInstance> tasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .list();

        java.util.Set<String> taskIds = tasks.stream()
                .filter(t -> isWithinCycle(t, cycleBoundary))
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
     * 确定当前执行周期的历史边界：按开始时间升序遍历历史任务，
     * 取最后一组连续同 {@code taskDefinitionKey} 任务中最早任务的开始时间。
     *
     * <p>折返重新进入会签节点后，新周期任务与上一周期之间必然隔着其它节点任务
     * （如 confirmTask），据此切分周期。当前周期内多次加签/轮次仍属同一周期，
     * 不会被拆分。无历史任务或无法切分时返回 {@code null}（不做过滤，兼容老数据）。</p>
     *
     * <p><b>建模约束（隐患 D，2026-08-08）</b>：周期切分依赖折返路径上存在
     * <b>非本节点 key 的中间任务</b>。若建模让会签节点<b>直接环回自己</b>（无中间节点），
     * 时间线上同 key 任务连续，边界会退化为全历史最早任务，导致周期重置失效、
     * 轮次沿用上一周期全局 max。折返路径应至少经过一个中间节点（如确认/回迁节点）；
     * 约束行为由单元测试 {@code testAddCounterSignerDirectLoopKeepsGlobalMaxRound} 固定。</p>
     */
    private Date findCurrentCycleBoundary(String processInstanceId, String taskDefinitionKey) {
        List<HistoricTaskInstance> tasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime().asc()
                .list();

        Date boundary = null;
        boolean inCurrentRun = false;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            HistoricTaskInstance t = tasks.get(i);
            // startTime 为 null（历史数据异常）无法参与边界切分，跳过避免污染边界
            if (t.getStartTime() == null) {
                continue;
            }
            if (taskDefinitionKey.equals(t.getTaskDefinitionKey())) {
                // 从后向前持续更新：最终停留在本周期 run 中最早任务（周期起始点）
                inCurrentRun = true;
                boundary = t.getStartTime();
            } else if (inCurrentRun) {
                break;
            }
        }
        return boundary;
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

    /**
     * 校验加签/减签权限。
     *
     * <p>两种模式：
     * <ul>
     *   <li><b>伪单例模式</b>（countersignInitiator 已设置）：
     *       会签发起人 <b>或</b> 当前节点活跃审批人可加签/减签。</li>
     *   <li><b>固定会签模式</b>（countersignInitiator 未设置）：
     *       当前节点活跃审批人可加签/减签。</li>
     * </ul>
     * 流程发起人 <b>不再</b> 作为权限旁路。</p>
     */
    private void validateCounterSignPermission(PlusTask task) {
        String currentUserId = userContext.getCurrentUserId();
        String activityId = task.getTaskDefinitionKey();

        // 1. 查询 countersignInitiator 流程变量
        Object initiatorObj = runtimeService.getVariable(
                task.getProcessInstanceId(), buildCountersignInitiatorVarName(activityId));
        String countersignInitiator = initiatorObj != null ? initiatorObj.toString() : null;

        // 2. 模式A：会签发起人直接放行
        if (countersignInitiator != null && currentUserId.equals(countersignInitiator)) {
            return;
        }

        // 3. 两种模式统一收口：当前节点活跃审批人可操作
        // 模式A放宽理由（2026-08-08）：发起人加签后其待办消失（发起人不投票），仅发起人有权限会导致
        // 前端有入口（活跃审批人可见加签按钮）但后端无权限的死锁，与钉钉/飞书
        // "当前审批人可加签"的主流行为不一致（见 docs/research/countersign-permission-model-research.md）。
        List<String> currentAssignees = resolveCurrentAssignees(task);
        if (currentAssignees.contains(currentUserId)) {
            return;
        }

        if (countersignInitiator != null) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是会签发起人 " + countersignInitiator
                            + " 或当前节点活跃审批人，无权操作");
        }
        throw new PermissionDeniedException(
                "用户 " + currentUserId + " 不是当前会签节点活跃审批人，无权操作");
    }
}
