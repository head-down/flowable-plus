package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.api.TaskExecutionOperations;
import io.github.flowable.plus.core.domain.PlusHistoricTask;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.event.TaskCompletedEvent;
import io.github.flowable.plus.core.event.TaskRejectedEvent;
import io.github.flowable.plus.core.event.TaskTransferredEvent;
import io.github.flowable.plus.core.event.TaskWithdrawnEvent;
import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.support.PreviousNodeAuthorizer;
import io.github.flowable.plus.core.support.ProcessEndDetector;
import io.github.flowable.plus.core.support.TaskValidation;
import io.github.flowable.plus.core.vo.JumpableNodeVO;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.task.api.history.HistoricTaskInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 任务执行工作流模块，封装常规审批任务的推进、驳回、撤回、跳转、转办和认领逻辑。
 *
 * @author flowable-plus
 */
public class TaskExecutionWorkflow implements TaskExecutionOperations {

    private final UserContext userContext;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final NodeFinder nodeFinder;
    private final MultiInstanceDetector multiInstanceDetector;
    private final ExecutionTreeHelper executionTreeHelper;
    private final EventPublisher eventPublisher;
    private final ProcessEndDetector processEndDetector;
    private final PreviousNodeAuthorizer previousNodeAuthorizer;

    public TaskExecutionWorkflow(UserContext userContext, TaskService taskService,
                                  HistoryService historyService, RuntimeService runtimeService,
                                  NodeFinder nodeFinder, MultiInstanceDetector multiInstanceDetector,
                                  ExecutionTreeHelper executionTreeHelper,
                                  EventPublisher eventPublisher,
                                  ProcessEndDetector processEndDetector,
                                  PreviousNodeAuthorizer previousNodeAuthorizer) {
        this.userContext = userContext;
        this.taskService = taskService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.nodeFinder = nodeFinder;
        this.multiInstanceDetector = multiInstanceDetector;
        this.executionTreeHelper = executionTreeHelper;
        this.eventPublisher = eventPublisher;
        this.processEndDetector = processEndDetector;
        this.previousNodeAuthorizer = previousNodeAuthorizer;
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> variables, String comment) {
        PlusTask task = TaskValidation.validateTaskExists(taskService, historyService, taskId, "审批");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String userId = userContext.getCurrentUserId();

        taskService.claim(taskId, userId);

        if (StrUtil.isNotBlank(comment)) {
            taskService.addComment(taskId, task.getProcessInstanceId(), CommentType.AGREE.name(), comment);
        }

        taskService.complete(taskId, variables);

        if (eventPublisher != null) {
            eventPublisher.publish(TaskCompletedEvent.of(task.getId(), task.getProcessInstanceId(),
                    task.getName(), task.getTaskDefinitionKey(), userId, comment, new java.util.Date()));
            processEndDetector.checkAndPublish(task.getProcessInstanceId());
        }
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

        if (eventPublisher != null) {
            eventPublisher.publish(TaskRejectedEvent.of(task.getId(), task.getProcessInstanceId(),
                    task.getName(), task.getTaskDefinitionKey(), task.getAssignee(),
                    reason, new java.util.Date()));
        }
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

        if (eventPublisher != null) {
            eventPublisher.publish(TaskRejectedEvent.of(task.getId(), task.getProcessInstanceId(),
                    task.getName(), task.getTaskDefinitionKey(), task.getAssignee(),
                    reason, new java.util.Date()));
        }
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

        if (!previousNodeAuthorizer.isAuthorized(currentUserId, taskId)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是上一节点审批人，无权撤回任务 " + taskId);
        }

        executeRollback(task, prevNodeId, reason, CommentType.WITHDRAW.name());

        if (eventPublisher != null) {
            eventPublisher.publish(TaskWithdrawnEvent.of(task.getId(), task.getProcessInstanceId(),
                    task.getName(), task.getTaskDefinitionKey(), task.getAssignee(),
                    currentUserId, reason, new java.util.Date()));
        }
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

        if (eventPublisher != null) {
            eventPublisher.publish(TaskTransferredEvent.of(task.getId(), task.getProcessInstanceId(),
                    task.getName(), task.getTaskDefinitionKey(),
                    currentUserId, transferUserId, reason, new java.util.Date()));
        }

        String comment = "转办给 " + transferUserId;
        if (StrUtil.isNotBlank(reason)) {
            comment += "（" + reason + "）";
        }
        taskService.addComment(taskId, processInstanceId, CommentType.TRANSFER.name(), comment);
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
}
