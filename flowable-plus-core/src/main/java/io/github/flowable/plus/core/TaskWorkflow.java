package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.InvalidTargetNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.AutoApprovalRule;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.JumpableNodeVO;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 任务工作流模块，封装常规审批任务的推进、驳回、撤回、撤销逻辑。
 *
 * @author flowable-plus
 */
public class TaskWorkflow implements ApprovalOperations {

    private static final Logger log = LoggerFactory.getLogger(TaskWorkflow.class);

    private final UserContext userContext;
    private final TaskRepository taskRepository;
    private final HistoricRepository historicRepository;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;
    private final NodeFinder nodeFinder;
    private final MultiInstanceDetector multiInstanceDetector;
    private final List<AutoApprovalRule> autoApprovalRules;

    public TaskWorkflow(UserContext userContext, TaskRepository taskRepository,
                 HistoricRepository historicRepository, RuntimeService runtimeService,
                 IdentityService identityService, NodeFinder nodeFinder,
                 MultiInstanceDetector multiInstanceDetector,
                 List<AutoApprovalRule> autoApprovalRules) {
        this.userContext = userContext;
        this.taskRepository = taskRepository;
        this.historicRepository = historicRepository;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
        this.nodeFinder = nodeFinder;
        this.multiInstanceDetector = multiInstanceDetector;
        this.autoApprovalRules = autoApprovalRules != null ? autoApprovalRules : Collections.emptyList();
    }

    @Override
    public PlusProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        if (processDefinitionKey == null) {
            throw new IllegalArgumentException("processDefinitionKey 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        identityService.setAuthenticatedUserId(userId);
        try {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            PlusProcessInstance result = PlusProcessInstance.from(pi);

            // 自动提交：发起人身份下执行，仅一层
            if (!autoApprovalRules.isEmpty()) {
                try {
                    autoCompleteFirstTasks(result.getProcessInstanceId(), userId, variables);
                } catch (Exception e) {
                    log.warn("自动提交失败，降级为不触发, processInstanceId: {}", result.getProcessInstanceId(), e);
                }
            }

            return result;
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> variables, String comment) {
        PlusTask task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "审批");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String userId = userContext.getCurrentUserId();

        taskRepository.claim(taskId, userId);

        if (StrUtil.isNotBlank(comment)) {
            taskRepository.addComment(taskId, null, null, comment);
        }

        taskRepository.complete(taskId, variables);
    }

    @Override
    public void claimTask(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        taskRepository.claim(taskId, userId);
    }

    @Override
    public void rejectTask(String taskId, String reason) {
        PlusTask task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "驳回");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "驳回");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        List<String> prevNodes = nodeFinder.findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);

        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法驳回至单一上级节点");
        }

        String targetNode = prevNodes.get(0);
        executeRollback(task, targetNode, reason, CommentType.REJECT.name());
    }

    @Override
    public void rejectTaskToInitiator(String taskId, String reason) {
        PlusTask task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "驳回");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "驳回");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String initiatorNode = nodeFinder.findInitiatorNode(processDefinitionId);

        if (task.getTaskDefinitionKey().equals(initiatorNode)) {
            throw new NoPreviousNodeException("当前已是发起人节点，无法继续驳回");
        }

        executeRollback(task, initiatorNode, reason, CommentType.REJECT.name());
    }

    @Override
    public void withdrawTask(String taskId, String reason) {
        String currentUserId = userContext.getCurrentUserId();
        PlusTask task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "撤回");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        if (currentUserId.equals(task.getAssignee())) {
            throw new PermissionDeniedException("无法撤回自己当前处理的任务 " + taskId);
        }

        String processInstanceId = task.getProcessInstanceId();
        List<String> prevNodes = nodeFinder.findPreviousNodes(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(), processInstanceId);

        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法确定撤回目标");
        }

        String prevNodeId = prevNodes.get(0);

        PlusHistoricTask prevTask = historicRepository.findLatestFinishedTask(processInstanceId, prevNodeId);

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

        PlusHistoricProcessInstance historicPi = historicRepository.findProcessInstance(processInstanceId);
        if (historicPi == null) {
            throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
        }

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
        PlusTask activeTask = taskRepository.findActiveByProcessInstance(processInstanceId);
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

        PlusTask task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "跳转");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "跳转");
        TaskValidation.validateNotMultiInstance(multiInstanceDetector, task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        // 防止自跳转
        if (currentActivityId.equals(targetNodeId)) {
            throw new InvalidTargetNodeException("目标节点 " + targetNodeId + " 与当前节点相同，不支持自跳转");
        }

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
        PlusTask task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "查询可跳转节点");
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

        // 2. 组装 VO 列表
        List<JumpableNodeVO> result = new ArrayList<>();
        for (String nodeId : nodeIds) {
            String nodeName = nodeFinder.getNodeName(processDefinitionId, nodeId);

            PlusHistoricTask historicTask = historicRepository.findLatestFinishedTask(processInstanceId, nodeId);
            if (historicTask == null) {
                continue; // 历史无记录，跳过
            }

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

    private void executeRollback(PlusTask task, String targetActivityId, String reason, String commentType) {
        if (StrUtil.isNotBlank(reason)) {
            taskRepository.addComment(task.getId(), task.getProcessInstanceId(), commentType, reason);
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
     *   <li>{@code isFirstStart} — 历史任务为空才触发，防止重新触发时误��动提交</li>
     *   <li>快照隔离 — 仅处理当前活跃任务快照，自动完成后新产生的任务不级联</li>
     * </ul>
     * 多规则 OR 逻辑：任一规则返回非 null 即触发自动提交，意见取第一个非 null 结果。
     * </p>
     */
    private void autoCompleteFirstTasks(String processInstanceId, String userId,
                                         Map<String, Object> startVariables) {
        // 守卫 1：非首次启动不触发
        if (historicRepository.hasHistoricTasks(processInstanceId)) {
            return;
        }

        // 守卫 2：快照当前首任务
        List<PlusTask> firstTasks = taskRepository.findActiveTasksByProcessInstance(processInstanceId);
        if (firstTasks.isEmpty()) {
            return;
        }

        Map<String, Object> readonlyVars = Collections.unmodifiableMap(startVariables);

        for (PlusTask task : firstTasks) {
            for (AutoApprovalRule rule : autoApprovalRules) {
                String evalResult = rule.evaluate(task, readonlyVars);
                if (evalResult != null) {
                    taskRepository.addComment(task.getId(), processInstanceId, "AUTO_COMPLETE", evalResult);
                    taskRepository.complete(task.getId(), null);
                    log.info("自动提交成功, taskId: {}, assignee: {}, comment: {}",
                            task.getId(), userId, evalResult);
                    break; // 当前任务已被处理，跳出规则循环
                }
            }
        }
    }
}
