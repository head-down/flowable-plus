package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.UserContext;
import cn.hutool.core.util.StrUtil;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;

import java.util.List;
import java.util.Map;

/**
 * 任务工作流模块，封装常规审批任务的推进、驳回、撤回、撤销逻辑。
 *
 * @author flowable-plus
 */
class TaskWorkflow {

    private final UserContext userContext;
    private final TaskRepository taskRepository;
    private final HistoricRepository historicRepository;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;
    private final NodeFinder nodeFinder;
    private final BpmnModelCache bpmnModelCache;

    TaskWorkflow(UserContext userContext, TaskRepository taskRepository,
                 HistoricRepository historicRepository, RuntimeService runtimeService,
                 IdentityService identityService, NodeFinder nodeFinder,
                 BpmnModelCache bpmnModelCache) {
        this.userContext = userContext;
        this.taskRepository = taskRepository;
        this.historicRepository = historicRepository;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
        this.nodeFinder = nodeFinder;
        this.bpmnModelCache = bpmnModelCache;
    }

    PlusProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        if (processDefinitionKey == null) {
            throw new IllegalArgumentException("processDefinitionKey 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        identityService.setAuthenticatedUserId(userId);
        try {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            return PlusProcessInstance.from(pi);
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }

    void completeTask(String taskId, Map<String, Object> variables, String comment) {
        Task task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "审批");
        assertNotMultiInstance(task, taskId);

        String userId = userContext.getCurrentUserId();

        taskRepository.claim(taskId, userId);

        if (StrUtil.isNotBlank(comment)) {
            taskRepository.addComment(taskId, null, null, comment);
        }

        taskRepository.complete(taskId, variables);
    }

    void claimTask(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        taskRepository.claim(taskId, userId);
    }

    void rejectTask(String taskId, String reason) {
        Task task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "驳回");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "驳回");
        assertNotMultiInstance(task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        List<String> prevNodes = nodeFinder.findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);

        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法驳回至单一上级节点");
        }

        String targetNode = prevNodes.get(0);
        executeRollback(task, targetNode, reason, "REJECT");
    }

    void rejectTaskToInitiator(String taskId, String reason) {
        Task task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "驳回");
        TaskValidation.validateCurrentUserIsAssignee(task, userContext.getCurrentUserId(), taskId, "驳回");
        assertNotMultiInstance(task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String initiatorNode = nodeFinder.findInitiatorNode(processDefinitionId);

        if (task.getTaskDefinitionKey().equals(initiatorNode)) {
            throw new NoPreviousNodeException("当前已是发起人节点，无法继续驳回");
        }

        executeRollback(task, initiatorNode, reason, "REJECT");
    }

    void withdrawTask(String taskId, String reason) {
        String currentUserId = userContext.getCurrentUserId();
        Task task = TaskValidation.validateTaskExists(taskRepository, historicRepository, taskId, "撤回");
        assertNotMultiInstance(task, taskId);

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

        HistoricTaskInstance prevTask = historicRepository.findLatestFinishedTask(processInstanceId, prevNodeId);

        if (prevTask == null || !currentUserId.equals(prevTask.getAssignee())) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是上一节点审批人，无权撤回任务 " + taskId);
        }

        executeRollback(task, prevNodeId, reason, "WITHDRAW");
    }

    void revokeProcess(String processInstanceId, String reason) {
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId 不可为 null");
        }

        String currentUserId = userContext.getCurrentUserId();

        HistoricProcessInstance historicPi = historicRepository.findProcessInstance(processInstanceId);
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
        Task activeTask = taskRepository.findActiveByProcessInstance(processInstanceId);
        if (activeTask == null || !initiatorNode.equals(activeTask.getTaskDefinitionKey())) {
            throw new TaskAlreadyCompletedException(
                    "流程实例 " + processInstanceId + " 已推进后续节点，无法撤销");
        }

        runtimeService.deleteProcessInstance(processInstanceId, reason);
    }

    // ======================== 内部辅助 ========================

    private void assertNotMultiInstance(Task task, String taskId) {
        if (isMultiInstance(task)) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 是多实例子任务，请使用会签操作(counterSign)");
        }
    }

    boolean isMultiInstance(Task task) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(task.getProcessDefinitionId());
        if (bpmnModel == null) {
            return false;
        }
        FlowElement flowElement = bpmnModel.getFlowElement(task.getTaskDefinitionKey());
        if (flowElement == null) {
            return false;
        }
        if (flowElement instanceof Activity) {
            Activity activity = (Activity) flowElement;
            return activity.getLoopCharacteristics() != null;
        }
        return false;
    }

    private void executeRollback(Task task, String targetActivityId, String reason, String commentType) {
        if (StrUtil.isNotBlank(reason)) {
            taskRepository.addComment(task.getId(), task.getProcessInstanceId(), commentType, reason);
        }

        ChangeActivityStateBuilder builder = runtimeService.createChangeActivityStateBuilder();
        builder.processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), targetActivityId)
                .changeState();
    }
}
