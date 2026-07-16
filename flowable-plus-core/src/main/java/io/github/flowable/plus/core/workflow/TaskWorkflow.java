package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.AutoApprovalRule;
import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.JumpableNodeVO;
import io.github.flowable.plus.core.api.ApprovalOperations;
import io.github.flowable.plus.core.domain.PlusProcessInstance;
import io.github.flowable.plus.core.domain.PlusHistoricProcessInstance;
import io.github.flowable.plus.core.domain.PlusHistoricTask;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.TaskValidation;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务工作流模块，封装常规审批任务的推进、驳回、撤回、撤销逻辑。
 *
 * @author flowable-plus
 */
public class TaskWorkflow implements ApprovalOperations {

    private static final Logger log = LoggerFactory.getLogger(TaskWorkflow.class);

    private final UserContext userContext;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;
    private final NodeFinder nodeFinder;
    private final MultiInstanceDetector multiInstanceDetector;
    private final List<AutoApprovalRule> autoApprovalRules;
    private final ExecutionTreeHelper executionTreeHelper;

public TaskWorkflow(UserContext userContext, TaskService taskService,
             HistoryService historyService, RuntimeService runtimeService,
             IdentityService identityService, NodeFinder nodeFinder,
             MultiInstanceDetector multiInstanceDetector,
             List<AutoApprovalRule> autoApprovalRules,
             ExecutionTreeHelper executionTreeHelper) {
        this.userContext = userContext;
        this.taskService = taskService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
        this.nodeFinder = nodeFinder;
        this.multiInstanceDetector = multiInstanceDetector;
        this.autoApprovalRules = autoApprovalRules != null ? autoApprovalRules : Collections.emptyList();
        this.executionTreeHelper = executionTreeHelper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlusProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        if (processDefinitionKey == null) {
            throw new IllegalArgumentException("processDefinitionKey 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        identityService.setAuthenticatedUserId(userId);
        try {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            PlusProcessInstance result = PlusProcessInstance.from(pi);

            // 自动提交：发起人身份下执行，仅一层。采用快速失败模式，异常正常传播
            if (!autoApprovalRules.isEmpty()) {
                autoCompleteFirstTasks(result.getProcessInstanceId(), userId, variables);
            }

            return result;
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> variables, String comment) {
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "审批");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String userId = userContext.getCurrentUserId();

        taskService.claim(taskId, userId);

        if (StrUtil.isNotBlank(comment)) {
            taskService.addComment(taskId, null, null, comment);
        }

        taskService.complete(taskId, variables);
    }

    @Override
    public void claimTask(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        taskService.claim(taskId, userId);
    }

    @Override
    public void rejectTask(String taskId, String reason) {
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "驳回");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "驳回");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        checkActiveParallelBranch(task);

        List<String> prevNodes = nodeFinder.findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);

        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法驳回至单一上级节点");
        }

        String targetNode = prevNodes.get(0);
        executeRollback(task, targetNode, reason, CommentType.REJECT.name());
    }

    @Override
    public void rejectTaskToInitiator(String taskId, String reason) {
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "驳回");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "驳回");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String initiatorNode = nodeFinder.findInitiatorNode(processDefinitionId);

        if (task.getTaskDefinitionKey().equals(initiatorNode)) {
            throw new NoPreviousNodeException("当前已是发起人节点，无法继续驳回");
        }

        // 驳回至发起人：先剥离并行网关分支，再回退节点
        executionTreeHelper.detachFromParallelGateway(task.getExecutionId(), reason);

        if (StrUtil.isNotBlank(reason)) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(),
                    CommentType.REJECT.name(), reason);
        }
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), initiatorNode)
                .changeState();
    }

    @Override
    public void withdrawTask(String taskId, String reason) {
        String currentUserId = userContext.getCurrentUserId();
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "撤回");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        if (currentUserId.equals(task.getAssignee())) {
            throw new PermissionDeniedException("无法撤回自己当前处理的任务 " + taskId);
        }

        String processInstanceId = task.getProcessInstanceId();

        checkActiveParallelBranch(task);

        List<String> prevNodes = nodeFinder.findPreviousNodes(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(), processInstanceId);

        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法确定撤回目标");
        }

        String prevNodeId = prevNodes.get(0);

        List<HistoricTaskInstance> prevTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(prevNodeId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1);
        PlusHistoricTask prevTask = !prevTasks.isEmpty() ? PlusHistoricTask.from(prevTasks.get(0)) : null;

        if (prevTask == null || !currentUserId.equals(prevTask.getAssignee())) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是上一节点审批人，无权撤回任务 " + taskId);
        }

        executeRollback(task, prevNodeId, reason, "WITHDRAW");
    }

    @Override
    public void revokeProcess(String processInstanceId, String reason) {
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId 不可为 null");
        }

        String currentUserId = userContext.getCurrentUserId();

        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi == null) {
            throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
        }
        PlusHistoricProcessInstance historicPi = PlusHistoricProcessInstance.from(hpi);

        if (!currentUserId.equals(historicPi.getStartUserId())) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是流程实例 " + processInstanceId + " 的发起人，无权撤销");
        }

        ProcessInstance runtimePi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (runtimePi == null) {
            throw new TaskAlreadyCompletedException(
                    "流程实例 " + processInstanceId + " 已结束，无法撤销");
        }

        String initiatorNode = nodeFinder.findInitiatorNode(historicPi.getProcessDefinitionId());
        Task activeTaskObj = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().singleResult();
        PlusTask activeTask = activeTaskObj != null ? PlusTask.from(activeTaskObj) : null;
        if (activeTask == null || !initiatorNode.equals(activeTask.getTaskDefinitionKey())) {
            throw new TaskAlreadyCompletedException(
                    "流程实例 " + processInstanceId + " 已推进后续节点，无法撤销");
        }

        runtimeService.deleteProcessInstance(processInstanceId, reason);
    }

    // ======================== 任意跳转 ========================

    @Override
    public void jumpToNode(String taskId, String targetNodeId, String reason, CommentType commentType) {
        if (targetNodeId == null) {
            throw new IllegalArgumentException("targetNodeId 不可为 null");
        }
        if (commentType == null) {
            throw new IllegalArgumentException("commentType 不可为 null");
        }

        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "跳转");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "跳转");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        // 防止自跳转
        if (currentActivityId.equals(targetNodeId)) {
            throw new InvalidTargetNodeException("目标节点 " + targetNodeId + " 与当前节点相同，不支持自跳转");
        }

        checkActiveParallelBranch(task);

        // 校验目标节点在可跳转列表中
        List<String> completedNodeIds = nodeFinder.findCompletedUserTasks(
                processDefinitionId, currentActivityId, processInstanceId);
        if (!completedNodeIds.contains(targetNodeId)) {
            throw new InvalidTargetNodeException("目标节点 " + targetNodeId + " 不在可跳转的历史节点列表中");
        }

        executeRollback(task, targetNodeId, reason, commentType.name());
    }

    @Override
    public List<JumpableNodeVO> getJumpableNodes(String taskId) {
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "查询可跳转节点");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "查询可跳转节点");

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        // 1. BPMN 回溯收集已完成的上游 UserTask
        List<String> nodeIds = nodeFinder.findCompletedUserTasks(
                processDefinitionId, currentActivityId, processInstanceId);
        if (nodeIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 组装 VO 列表，过滤掉多实例节点（会签/或签）
        List<JumpableNodeVO> result = new ArrayList<>();
        for (String nodeId : nodeIds) {
            if (multiInstanceDetector.isMultiInstanceNode(processDefinitionId, nodeId)) {
                continue;
            }
            String nodeName = nodeFinder.getNodeName(processDefinitionId, nodeId);

            List<HistoricTaskInstance> tasks = historyService.createHistoricTaskInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .taskDefinitionKey(nodeId)
                    .finished()
                    .orderByHistoricTaskInstanceEndTime().desc()
                    .listPage(0, 1);
            if (tasks.isEmpty()) {
                continue; // 历史无记录，跳过
            }
            PlusHistoricTask historicTask = PlusHistoricTask.from(tasks.get(0));

            result.add(JumpableNodeVO.builder()
                    .nodeId(nodeId)
                    .nodeName(nodeName)
                    .assignee(historicTask.getAssignee())
                    .completeTime(historicTask.getEndTime())
                    .build());
        }

        // 3. 按完成时间正序排序
        result.sort(Comparator.comparing(JumpableNodeVO::getCompleteTime));

        return result;
    }

    @Override
    public void transferTask(String taskId, String transferUserId, String reason) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }
        if (StrUtil.isBlank(transferUserId)) {
            throw new IllegalArgumentException("transferUserId 不可为 null 或空");
        }

        String currentUserId = userContext.getCurrentUserId();

        if (currentUserId.equals(transferUserId)) {
            throw new IllegalArgumentException("转办目标不可为当前审批人");
        }

        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "转办");
        TaskValidation.validateCurrentUserIsAssignee(task, currentUserId, taskId, "转办");

        String processInstanceId = task.getProcessInstanceId();

        taskService.setAssignee(taskId, transferUserId);

        String comment = "转办给 " + transferUserId;
        if (StrUtil.isNotBlank(reason)) {
            comment += "（" + reason + "）";
        }
        taskService.addComment(taskId, processInstanceId, CommentType.TRANSFER.name(), comment);
    }

    // ======================== 内部辅助 ========================

    /**
     * 检测当前任务是否处于并行网关 Fork 分支上。
     *
     * <p>通过 {@link RuntimeService#createExecutionQuery()} 查询当前执行对象的父级执行，
     * 统计同级活跃叶子执行数。当活跃叶子数 &gt; 1 时，表示存在其他并行分支，
     * 执行驳回/撤回/跳转会产生幽灵分支并导致流程死锁。</p>
     *
     * @param task 当前任务，不可为 null
     * @throws NoPreviousNodeException 当前节点位于并行分支上时抛出
     */
    private void checkActiveParallelBranch(PlusTask task) {
        Execution execution = runtimeService.createExecutionQuery()
                .executionId(task.getExecutionId()).singleResult();
        if (execution == null || execution.getParentId() == null) {
            return;
        }
        long activeSiblings = runtimeService.createExecutionQuery()
                .parentId(execution.getParentId()).count();
        if (activeSiblings > 1) {
            throw new NoPreviousNodeException(
                    "当前节点位于并行分支上，存在其他活跃分支，请使用驳回至发起人操作");
        }
    }

    private void executeRollback(PlusTask task, String targetActivityId, String reason, String commentType) {
        if (multiInstanceDetector.isMultiInstanceNode(task.getProcessDefinitionId(), targetActivityId)) {
            throw new InvalidTargetNodeException(
                    "目标节点 " + targetActivityId + " 是会签（多实例）节点，"
                    + "驳回/撤回/跳转至已完成的会签节点会破坏多实例计数器，不支持此操作");
        }

        if (StrUtil.isNotBlank(reason)) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), commentType, reason);
        }

        ChangeActivityStateBuilder builder = runtimeService.createChangeActivityStateBuilder();
        builder.processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), targetActivityId)
                .changeState();
    }

    /**
     * 自动完成发起人的首审批任务。
     *
     * <p>双重守卫：
     * <ul>
     *   <li>{@code isFirstStart} — 历史任务为空才触发，防止重新触发时误动提交</li>
     *   <li>快照隔离 — 仅处理当前活跃任务快照，自动完成后新产生的任务不级联</li>
     * </ul>
     * 多规则 OR 逻辑：任一规则返回非 null 即触发自动提交，意见取第一个非 null 结果。
     * </p>
     */
    private void autoCompleteFirstTasks(String processInstanceId, String userId,
                                         Map<String, Object> startVariables) {
        // 守卫 1：非首次启动不触发
        long historyCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).count();
        if (historyCount > 0) {
            return;
        }

        // 守卫 2：快照当前首任务
        List<Task> taskList = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        List<PlusTask> firstTasks = taskList.stream().map(PlusTask::from).collect(Collectors.toList());
        if (firstTasks.isEmpty()) {
            return;
        }

        Map<String, Object> readonlyVars = Collections.unmodifiableMap(startVariables);

        for (PlusTask task : firstTasks) {
            for (AutoApprovalRule rule : autoApprovalRules) {
                String evalResult = rule.evaluate(task, readonlyVars);
                if (evalResult != null) {
                    taskService.addComment(task.getId(), processInstanceId, "AUTO_COMPLETE", evalResult);
                    taskService.complete(task.getId(), null);
                    log.info("自动提交成功, taskId: {}, assignee: {}, comment: {}",
                            task.getId(), userId, evalResult);
                    break; // 当前任务已被处理，跳出规则循环
                }
            }
        }
    }
}
