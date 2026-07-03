package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.UserContext;
import lombok.Getter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.util.List;
import java.util.Map;

/**
 * Flowable-Plus 统一入口类，封装 Flowable 引擎操作，提供增强的中国式审批 API。
 *
 * <p>构造器注入 {@link ProcessEngine}、{@link UserContext} 和 {@link NodeFinder}，
 * 内部持有 RuntimeService、TaskService、HistoryService 的引用。</p>
 *
 * <p>所有业务方法的参数校验和异常转换在此层完成，NodeFinder 仅负责纯遍历逻辑。</p>
 */
public class FlowablePlus {

    @Getter
    private final ProcessEngine processEngine;
    @Getter
    private final UserContext userContext;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final NodeFinder nodeFinder;

    /**
     * 构造器注入 ProcessEngine、UserContext 和自定义 NodeFinder。
     *
     * @param processEngine Flowable 流程引擎实例，不可为 null
     * @param userContext   用户上下文，用于获取当前操作用户，不可为 null
     * @param nodeFinder    BPMN 节点遍历策略，不可为 null。可用于注入 Mock 或缓存适配器
     */
    public FlowablePlus(ProcessEngine processEngine, UserContext userContext, NodeFinder nodeFinder) {
        if (processEngine == null) {
            throw new IllegalArgumentException("ProcessEngine 不可为 null");
        }
        if (userContext == null) {
            throw new IllegalArgumentException("UserContext 不可为 null");
        }
        if (nodeFinder == null) {
            throw new IllegalArgumentException("NodeFinder 不可为 null");
        }
        this.processEngine = processEngine;
        this.userContext = userContext;
        this.repositoryService = processEngine.getRepositoryService();
        this.runtimeService = processEngine.getRuntimeService();
        this.taskService = processEngine.getTaskService();
        this.historyService = processEngine.getHistoryService();
        this.nodeFinder = nodeFinder;
    }

    /**
     * 向后查找上一审批节点。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @param currentActivityId   当前节点 ID，不可为 null
     * @param processInstanceId   流程实例 ID（用于查询历史数据判定网关分支），可为 null
     * @return 上一审批节点 ID 列表
     * @throws NotFoundException      流程定义或节点不存在时抛出
     * @throws NoPreviousNodeException 当前节点无上一审批节点时抛出
     */
    public List<String> findPreviousNodes(String processDefinitionId, String currentActivityId, String processInstanceId) {
        if (processDefinitionId == null) {
            throw new IllegalArgumentException("processDefinitionId 不可为 null");
        }
        if (currentActivityId == null) {
            throw new IllegalArgumentException("currentActivityId 不可为 null");
        }

        return nodeFinder.findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);
    }

    /**
     * 向前查找流程发起人节点（第一个 UserTask）。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @return 第一个 UserTask 的 ID
     * @throws NotFoundException 流程定义不存在或未找到发起人节点时抛出
     */
    public String findInitiatorNode(String processDefinitionId) {
        if (processDefinitionId == null) {
            throw new IllegalArgumentException("processDefinitionId 不可为 null");
        }

        return nodeFinder.findInitiatorNode(processDefinitionId);
    }

    // ======================== 基础操作 ========================

    /**
     * 启动流程实例。
     *
     * @param processDefinitionKey 流程定义 KEY，不可为 null
     * @param businessKey          业务主键，可为 null
     * @param variables            流程变量，可为 null
     * @return 流程实例
     * @throws NotFoundException 流程定义不存在时抛出
     */
    public ProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        if (processDefinitionKey == null) {
            throw new IllegalArgumentException("processDefinitionKey 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        IdentityService identityService = processEngine.getIdentityService();
        identityService.setAuthenticatedUserId(userId);
        try {
            return runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }

    /**
     * 完成任务审批。
     *
     * <p>自动认领任务、添加审批意见后完成。</p>
     *
     * @param taskId    任务 ID，不可为 null
     * @param variables 流程变量，可为 null
     * @param comment   审批意见，可为 null
     * @throws NotFoundException 任务不存在时抛出
     * @throws IllegalArgumentException 任务为多实例子任务时抛出（请使用会签操作）
     */
    public void completeTask(String taskId, Map<String, Object> variables, String comment) {
        Task task = validateTaskExists(taskId, "审批");
        assertNotMultiInstance(task, taskId);

        String userId = userContext.getCurrentUserId();

        // 自动认领
        taskService.claim(taskId, userId);

        // 添加审批意见
        if (comment != null && !comment.isEmpty()) {
            taskService.addComment(taskId, null, comment);
        }

        // 完成任务
        taskService.complete(taskId, variables);
    }

    // ======================== 会签 ========================

    /**
     * 会签操作：完成当前用户的会签子任务。
     *
     * <p>自动认领任务后，根据 {@code approved} 参数写入 AGREE 或 COUNTER_SIGN_REJECT
     * 类型的审批意见，最后调用引擎 complete。多实例的完成条件由 BPMN 模型中定义的
     * {@code completionCondition} 表达式控制。</p>
     *
     * @param taskId    任务 ID，不可为 null
     * @param approved  true 表示同意，false 表示驳回
     * @param variables 流程变量，可为 null
     * @param comment   审批意见，可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws IllegalArgumentException     非多实例子任务时抛出（请使用审批操作）
     */
    public void counterSign(String taskId, boolean approved, Map<String, Object> variables, String comment) {
        Task task = validateTaskAndPermission(taskId, "会签");

        MultiInstanceInfo mi = resolveMultiInstance(task);
        if (mi == null) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 不是多实例子任务，请使用审批操作(completeTask)");
        }

        String userId = userContext.getCurrentUserId();

        // 自动认领
        taskService.claim(taskId, userId);

        // 写入审批意见（AGREE / COUNTER_SIGN_REJECT）
        if (comment != null && !comment.isEmpty()) {
            String commentType = approved ? "AGREE" : "COUNTER_SIGN_REJECT";
            taskService.addComment(taskId, null, commentType, comment);
        }

        // 完成当前子任务，引擎自动评估 completionCondition
        taskService.complete(taskId, variables);
    }

    /**
     * 认领任务（低级 API）。
     *
     * <p>通常无需手动调用，{@link #completeTask} 已自动认领。
     * 此方法用于需要单独认领任务的场景。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @throws NotFoundException 任务不存在时抛出
     */
    public void claimTask(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        taskService.claim(taskId, userId);
    }

    // ======================== 驳回 ========================

    /**
     * 驳回至上一审批节点。
     *
     * <p>审批人不同意当前任务，退回至上一审批节点。仅支持串行流程中的驳回，
     * 并行网关汇合后的节点无法驳回至单一上级节点。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @param reason 驳回原因，可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws NoPreviousNodeException       无上一审批节点或处于并行网关汇合之后时抛出
     */
    public void rejectTask(String taskId, String reason) {
        Task task = validateTaskAndPermission(taskId, "驳回");
        assertNotMultiInstance(task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        List<String> prevNodes = findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);

        // 并行网关汇合场景：多个上一节点时拒绝驳回
        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法驳回至单一上级节点");
        }

        String targetNode = prevNodes.get(0);
        executeRollback(task, targetNode, reason, "REJECT");
    }

    /**
     * 驳回至流程发起人节点。
     *
     * <p>审批人不同意当前任务，退回至流程的第一个审批节点（发起人所在节点）。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @param reason 驳回原因，可为 null
     * @throws NotFoundException            任务或流程定义不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是当前任务审批人时抛出
     * @throws NoPreviousNodeException       当前已是发起人节点时抛出
     */
    public void rejectTaskToInitiator(String taskId, String reason) {
        Task task = validateTaskAndPermission(taskId, "驳回");
        assertNotMultiInstance(task, taskId);

        String processDefinitionId = task.getProcessDefinitionId();
        String initiatorNode = findInitiatorNode(processDefinitionId);

        // 当前节点已经是发起人节点，无法继续驳回
        if (task.getTaskDefinitionKey().equals(initiatorNode)) {
            throw new NoPreviousNodeException("当前已是发起人节点，无法继续驳回");
        }

        executeRollback(task, initiatorNode, reason, "REJECT");
    }

    // ======================== 撤回 ========================

    /**
     * 撤回已提交的任务。
     *
     * <p>上一节点审批人主动收回已提交的待办，阻止当前审批人继续处理。
     * 执行后任务回到撤回人的待办列表，保留表单数据。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @param reason 撤回原因，可为 null
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     调用者不是上一节点审批人或尝试撤回自己的任务时抛出
     * @throws NoPreviousNodeException       无上一审批节点或处于并行网关汇合之后时抛出
     */
    public void withdrawTask(String taskId, String reason) {
        String currentUserId = userContext.getCurrentUserId();
        Task task = validateTaskExists(taskId, "撤回");
        assertNotMultiInstance(task, taskId);

        // 不能撤回自己当前正在处理的任务
        if (currentUserId.equals(task.getAssignee())) {
            throw new PermissionDeniedException("无法撤回自己当前处理的任务 " + taskId);
        }

        String processInstanceId = task.getProcessInstanceId();
        List<String> prevNodes = findPreviousNodes(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(), processInstanceId);

        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法确定撤回目标");
        }

        String prevNodeId = prevNodes.get(0);

        // 校验当前用户是否为上一节点审批人
        HistoricTaskInstance prevTask = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(prevNodeId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1)
                .stream().findFirst().orElse(null);

        if (prevTask == null || !currentUserId.equals(prevTask.getAssignee())) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是上一节点审批人，无权撤回任务 " + taskId);
        }

        executeRollback(task, prevNodeId, reason, "WITHDRAW");
    }

    // ======================== 撤销 ========================

    /**
     * 撤销整个流程实例。
     *
     * <p>流程发起人撤销运行中的流程实例，采用软删除策略——
     * 删除运行时实例但保留历史记录供审计。
     * 仅当流程停留在发起人节点时允许撤销，一旦有人审批同意则不可撤销。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @param reason            撤销原因，可为 null
     * @throws NotFoundException            流程实例不存在时抛出
     * @throws TaskAlreadyCompletedException 流程已结束或已推进后续节点时抛出
     * @throws PermissionDeniedException     调用者不是流程发起人时抛出
     */
    public void revokeProcess(String processInstanceId, String reason) {
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId 不可为 null");
        }

        String currentUserId = userContext.getCurrentUserId();

        // 1. 查历史流程实例，校验存在性和发起人身份
        HistoricProcessInstance historicPi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (historicPi == null) {
            throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
        }

        // 2. 权限校验：调用者必须是发起人
        if (!currentUserId.equals(historicPi.getStartUserId())) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是流程实例 " + processInstanceId + " 的发起人，无权撤销");
        }

        // 3. 校验流程仍在运行
        ProcessInstance runtimePi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (runtimePi == null) {
            throw new TaskAlreadyCompletedException(
                    "流程实例 " + processInstanceId + " 已结束，无法撤销");
        }

        // 4. 校验流程仍在发起人节点
        String initiatorNode = findInitiatorNode(historicPi.getProcessDefinitionId());
        Task activeTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().singleResult();
        if (activeTask == null || !initiatorNode.equals(activeTask.getTaskDefinitionKey())) {
            throw new TaskAlreadyCompletedException(
                    "流程实例 " + processInstanceId + " 已推进后续节点，无法撤销");
        }

        // 5. 软删除：删除运行时实例，历史自动保留
        runtimeService.deleteProcessInstance(processInstanceId, reason);
    }

    // ======================== 内部辅助 ========================

    /**
     * 校验任务存在性和完成状态（不做权限校验）。
     *
     * @param taskId    任务 ID
     * @param operation 操作名称（用于错误消息）
     * @return 运行时任务对象
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     */
    private Task validateTaskExists(String taskId, String operation) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            HistoricTaskInstance historic = historyService.createHistoricTaskInstanceQuery()
                    .taskId(taskId).singleResult();
            if (historic != null) {
                throw new TaskAlreadyCompletedException("任务 " + taskId + " 已完成，无法" + operation);
            }
            throw new NotFoundException("任务 " + taskId + " 不存在");
        }

        return task;
    }

    /**
     * 校验任务存在性、完成状态和当前审批人权限。
     */
    private Task validateTaskAndPermission(String taskId, String operation) {
        Task task = validateTaskExists(taskId, operation);

        String currentUserId = userContext.getCurrentUserId();
        if (task.getAssignee() == null || !task.getAssignee().equals(currentUserId)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是任务 " + taskId + " 的审批人，无权" + operation);
        }

        return task;
    }

    /**
     * 断言当前任务不是多实例子任务，否则抛出异常引导使用正确的 API。
     */
    private void assertNotMultiInstance(Task task, String taskId) {
        if (resolveMultiInstance(task) != null) {
            throw new IllegalArgumentException(
                    "任务 " + taskId + " 是多实例子任务，请使用会签操作(counterSign)");
        }
    }

    /**
     * 执行审批节点回退：写入原因，再通过 ChangeActivityStateBuilder 跳转节点。
     *
     * @param task             当前任务
     * @param targetActivityId 目标节点 ID
     * @param reason           操作原因，可为 null
     * @param commentType      Flowable Comment 类型，用于区分驳回/撤回
     */
    private void executeRollback(Task task, String targetActivityId, String reason, String commentType) {
        if (reason != null && !reason.isEmpty()) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), commentType, reason);
        }

        ChangeActivityStateBuilder builder = runtimeService.createChangeActivityStateBuilder();
        builder.processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), targetActivityId)
                .changeState();
    }

    /**
     * 解析 Task 的多实例信息。
     *
     * <p>通过 BPMN 模型层检测 Task 对应的 FlowElement 是否配置了
     * {@link MultiInstanceLoopCharacteristics}。不限制节点类型，
     * 任何 Activity 上的多实例都可以检测。</p>
     *
     * @param task 已校验的任务对象，不可为 null
     * @return 多实例信息，若非多实例则返回 null
     */
    MultiInstanceInfo resolveMultiInstance(Task task) {
        BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
        if (bpmnModel == null) {
            return null;
        }
        FlowElement flowElement = bpmnModel.getFlowElement(task.getTaskDefinitionKey());
        if (flowElement == null) {
            return null;
        }

        // 检查节点是否配置了多实例循环特征
        if (flowElement instanceof org.flowable.bpmn.model.Activity) {
            org.flowable.bpmn.model.Activity activity = (org.flowable.bpmn.model.Activity) flowElement;
            if (activity.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics) {
                MultiInstanceLoopCharacteristics mic =
                        (MultiInstanceLoopCharacteristics) activity.getLoopCharacteristics();
                return new MultiInstanceInfo(mic.isSequential(), mic.getCompletionCondition());
            }
        }
        return null;
    }

    /**
     * 多实例信息，包含运行时判断多实例子任务所需的属性。
     */
    public static class MultiInstanceInfo {
        /** 是否为串行多实例 */
        private final boolean sequential;
        /** 完成条件表达式，如 {@code ${nrOfCompletedInstances/nrOfInstances >= 0.5}} */
        private final String completionCondition;

        MultiInstanceInfo(boolean sequential, String completionCondition) {
            this.sequential = sequential;
            this.completionCondition = completionCondition;
        }

        public boolean isSequential() {
            return sequential;
        }

        public String getCompletionCondition() {
            return completionCondition;
        }
    }
}
