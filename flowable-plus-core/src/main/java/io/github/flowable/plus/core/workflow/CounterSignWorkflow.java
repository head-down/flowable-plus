package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.event.EventBus;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.support.ProcessEndDetector;
import io.github.flowable.plus.core.support.TaskValidation;
import io.github.flowable.plus.core.api.CounterSignOperations;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.model.CountersignRoundResolver;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
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

    private static final Logger log = LoggerFactory.getLogger(CounterSignWorkflow.class);

    private final UserContext userContext;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final MultiInstanceDetector multiInstanceDetector;
    private final NodeFinder nodeFinder;
    private final List<CounterSignCallback> counterSignCallbacks;
    private final EventBus eventBus;
    private final ProcessEndDetector processEndDetector;
    private final CountersignRoundResolver countersignRoundResolver;

    public CounterSignWorkflow(UserContext userContext, TaskService taskService,
                        HistoryService historyService, RuntimeService runtimeService,
                        MultiInstanceDetector multiInstanceDetector, NodeFinder nodeFinder,
                        List<CounterSignCallback> counterSignCallbacks,
                        EventBus eventBus,
                        ProcessEndDetector processEndDetector,
                        CountersignRoundResolver countersignRoundResolver) {
        this.userContext = userContext;
        this.taskService = taskService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.multiInstanceDetector = multiInstanceDetector;
        this.nodeFinder = nodeFinder;
        this.counterSignCallbacks = counterSignCallbacks;
        this.eventBus = eventBus;
        this.processEndDetector = processEndDetector;
        this.countersignRoundResolver = countersignRoundResolver;
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

        if (approved) {
            eventBus.taskCompleted(task, userId, comment);
        } else {
            eventBus.taskRejected(task, comment);
        }
        processEndDetector.checkAndPublish(task.getProcessInstanceId());

        if (countersignRoundResolver.isRoundFinished(
                task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getId())) {
            invokeCallbacks(cb -> cb.onFinish(processInstanceId, taskId, "finished"));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>实现细节</b>：新轮次通过 {@code CountersignRoundResolver#isRoundFinished} 检测，
     * 轮次索引通过 {@code CountersignRoundResolver#nextRoundIndex} 从 {@code ACT_HI_VARINST}
     * 查询历史 {@code csRoundIndex} 最大值 + 1 计算。<em>调用方不得在调用本方法前
     * 将发起任务的 {@code csRoundIndex} 写入历史表</em>，否则当前任务会"自引用污染"
     * 历史查询结果，导致新建子任务轮次偏移。</p>
     *
     * <p><b>发起任务打标</b>（ADR-0019 时序内化，2026-08-08）：本方法会在打标阶段将
     * 操作者任务与新增审批人一起写入 {@code csRoundIndex}，调用方无需再按 ADR-0019
     * 原时序手动为发起任务打标。这保证原始审批人（owner）首次加签即获得显式轮次，
     * 后续加签并入当前轮，不会因运行时缺显式轮次而被
     * {@code CountersignRoundResolver#isRoundFinished}
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
        validateAddCounterSignerArgs(taskId, assignees);

        PlusTask task = resolveAddCounterSignerTask(taskId);
        String processInstanceId = task.getProcessInstanceId();
        String activityId = task.getTaskDefinitionKey();

        // 全部查重通过前不产生任何副作用（ADR-0024）
        List<String> newAssignees = partitionNewAssignees(assignees, resolveCurrentAssignees(task));

        // 轮次检测（ADR-0022）：模式 A（伪单例，countersignInitiator 已写入）才有轮次概念，
        // 由 CountersignRoundResolver.isRoundFinished 判定是否本轮已结束；模式 B（固定会签）无轮次概念，
        // 单执行周期内加签必然发生在本轮未投完时，永远并入当前轮。
        boolean isNewRound = detectNewRound(task, processInstanceId, activityId);
        int roundIndex = resolveRoundIndex(processInstanceId, activityId, isNewRound);

        validateNotVotedInRound(newAssignees, processInstanceId, activityId, roundIndex);

        // 通过全部查重后才执行副作用：写入 initiator / 批量加签 / 打标
        performAddCounterSigner(task, newAssignees, roundIndex);

        StringBuilder commentMsg = new StringBuilder("加签审批人: ")
                .append(String.join(", ", newAssignees));
        if (isNewRound) {
            commentMsg.append("，开启第 ").append(roundIndex + 1).append(" 轮会签");
        }
        taskService.addComment(taskId, processInstanceId, CommentType.ADD_SIGN.name(), commentMsg.toString());

        invokeCallbacks(cb -> cb.onStart(processInstanceId, taskId, newAssignees));
    }

    /**
     * 校验加签入参：taskId 与 assignees 非空。
     */
    private void validateAddCounterSignerArgs(String taskId, List<String> assignees) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }
        if (assignees == null || assignees.isEmpty()) {
            throw new IllegalArgumentException("assignees 不可为 null 或空");
        }
    }

    /**
     * 校验加签任务：任务存在 + MI 节点 + 权限（ADR-0023）。
     */
    private PlusTask resolveAddCounterSignerTask(String taskId) {
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "加签");
        TaskValidation.validateMultiInstance(multiInstanceDetector, task, taskId, "加签");
        validateCounterSignPermission(task);
        return task;
    }

    /**
     * 拆分配签名单，过滤已在当前会签的审批人（维度一）。
     *
     * <p>名单内自重复检测（如 {@code [A, A]}）与维度一（与当前活跃会签人重复）
     * 均整体失败，不创建任何任务（ADR-0024，替换原"全部重复时静默 return"）。</p>
     *
     * @return 真正需要加签的新审批人列表
     */
    private List<String> partitionNewAssignees(List<String> assignees, List<String> currentAssignees) {
        // 名单内自重复检测（如 [A, A]）：整体失败，避免创建两个同 assignee 的重复任务
        if (new HashSet<>(assignees).size() != assignees.size()) {
            throw new IllegalArgumentException(
                    "加签名单存在重复审批人，无法加签: " + String.join(", ", assignees));
        }

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

        // 维度一：与当前活跃会签人重复 → 整体失败
        if (!skippedAssignees.isEmpty()) {
            throw new IllegalArgumentException(
                    "审批人 " + String.join(", ", skippedAssignees) + " 已在本轮会签中，无法重复加签");
        }
        if (newAssignees.isEmpty()) {
            throw new IllegalArgumentException("加签名单无有效审批人，无法加签");
        }
        return newAssignees;
    }

    /**
     * 检测本次加签是否开启新轮次（全部审批完成后加签 = 新一轮）。
     *
     * <p>trySetCounterSignInitiator 后移（ADR-0024）：查重前置要求不产生任何副作用，
     * 首次伪单例加签两种时序下 isNewRound 均 false、roundIndex=0，等价性已验证。</p>
     *
     * <p>modeA 检测（读 {@code countersignInitiator} 流程变量）留在写侧，
     * resolver 依赖面只有 HistoryService + TaskService。</p>
     */
    private boolean detectNewRound(PlusTask task, String processInstanceId, String activityId) {
        boolean modeA = runtimeService.getVariable(processInstanceId,
                MultiInstanceDetector.buildCountersignInitiatorVarName(activityId)) != null;
        return modeA && countersignRoundResolver.isRoundFinished(
                processInstanceId, activityId, task.getId());
    }

    /**
     * 解析加签轮次索引：新轮次取历史 max + 1，否则并入当前轮。
     */
    private int resolveRoundIndex(String processInstanceId, String activityId, boolean isNewRound) {
        if (isNewRound) {
            return countersignRoundResolver.nextRoundIndex(processInstanceId, activityId);
        }
        return countersignRoundResolver.currentRoundIndex(processInstanceId, activityId);
    }

    /**
     * 校验加签人与本轮（当前执行周期内、csRoundIndex 匹配）已投过票的审批人不重复（维度二）。
     *
     * <p>整体失败（ADR-0024：roundIndex==0 时无标历史任务视为隐式轮次 0，
     * 覆盖模式 B 无打标场景）。</p>
     */
    private void validateNotVotedInRound(List<String> newAssignees, String processInstanceId,
                                          String activityId, int roundIndex) {
        Set<String> votedAssigneesInRound = countersignRoundResolver.votedAssigneesInRound(
                processInstanceId, activityId, roundIndex);
        List<String> alreadyVoted = newAssignees.stream()
                .filter(votedAssigneesInRound::contains)
                .collect(Collectors.toList());
        if (!alreadyVoted.isEmpty()) {
            throw new IllegalArgumentException(
                    "审批人 " + String.join(", ", alreadyVoted) + " 已在本轮投过票，无法重复加签");
        }
    }

    /**
     * 执行加签副作用：写入 countersignInitiator（仅伪单例首次加签）+ 批量加签 + 打标。
     *
     * <p><b>打标</b>（ADR-0019 时序内化，2026-08-08）：始终为新任务打上 csRoundIndex，
     * 并将操作者任务（发起任务）归入同一轮次（批量查询 + 内存过滤 + 统一打标，N→1 降维）。
     * 此前仅给新加签人打标，原始审批人（owner）运行时无 csRoundIndex，
     * 其再次加签时 CountersignRoundResolver.isRoundFinished 误判"本轮将尽"而开启新一轮（round 1+）。
     * 内化打标后 owner 首次加签即获显式轮次，后续加签走 roundVar 分支并入当前轮，
     * ADR-0019 的"调用方时序"要求相应放宽（调用方无需再手动为发起任务打标）。</p>
     */
    private void performAddCounterSigner(PlusTask task, List<String> newAssignees, int roundIndex) {
        String processInstanceId = task.getProcessInstanceId();
        String activityId = task.getTaskDefinitionKey();

        trySetCounterSignInitiator(task);

        // 批量加签
        for (String assignee : newAssignees) {
            HashMap<String, Object> executionVariables = new HashMap<>();
            executionVariables.put("assignee", assignee);
            runtimeService.addMultiInstanceExecution(activityId, processInstanceId, executionVariables);
        }

        // 始终为新任务打上 csRoundIndex，并将操作者任务（发起任务）归入同一轮次
        Set<String> newAssigneeSet = new HashSet<>(newAssignees);
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .active()
                .list();
        for (Task t : activeTasks) {
            if (newAssigneeSet.contains(t.getAssignee()) || t.getId().equals(task.getId())) {
                taskService.setVariableLocal(t.getId(), CountersignRoundResolver.CS_ROUND_INDEX_VAR, roundIndex);
            }
        }
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

        eventBus.taskDelegated(task, currentUserId, delegateUserId, reason);

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
     * 尝试设置会签发起人变量。仅在伪单例状态下首次调用加签且变量尚未设置时写入。
     *
     * <p>伪单例判据统一收敛至 {@link MultiInstanceDetector#isPseudoSingleton}（ADR-0034），
     * 消除会签侧与常规审批拦截侧的口径漂移。判据说明详见该方法的 Javadoc。</p>
     */
    private void trySetCounterSignInitiator(PlusTask task) {
        String varName = MultiInstanceDetector.buildCountersignInitiatorVarName(task.getTaskDefinitionKey());

        Object existing = runtimeService.getVariable(task.getProcessInstanceId(), varName);
        if (existing != null) {
            return;
        }

        if (!multiInstanceDetector.isPseudoSingleton(task)) {
            return;
        }

        runtimeService.setVariable(task.getProcessInstanceId(), varName, userContext.getCurrentUserId());
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
                task.getProcessInstanceId(), MultiInstanceDetector.buildCountersignInitiatorVarName(activityId));
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
