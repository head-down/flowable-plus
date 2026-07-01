package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.spi.UserContext;
import lombok.Getter;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.util.List;
import java.util.Map;

/**
 * Flowable-Plus 统一入口类，封装 Flowable 引擎操作，提供增强的中国式审批 API。
 *
 * <p>构造器注入 {@link ProcessEngine} 和 {@link UserContext}，内部持有对 RuntimeService、TaskService、
 * RepositoryService、HistoryService 的引用，并组合 {@link NodeFinder} 提供
 * BPMN 模型遍历能力。</p>
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
    private final IdentityService identityService;
    private final NodeFinder nodeFinder;

    /**
     * 构造器注入 ProcessEngine 和 UserContext，使用默认 BPMN 遍历策略。
     *
     * @param processEngine Flowable 流程引擎实例，不可为 null
     * @param userContext   用户上下文，用于获取当前操作用户，不可为 null
     */
    public FlowablePlus(ProcessEngine processEngine, UserContext userContext) {
        if (processEngine == null) {
            throw new IllegalArgumentException("ProcessEngine 不可为 null");
        }
        if (userContext == null) {
            throw new IllegalArgumentException("UserContext 不可为 null");
        }
        this.processEngine = processEngine;
        this.userContext = userContext;
        this.repositoryService = processEngine.getRepositoryService();
        this.runtimeService = processEngine.getRuntimeService();
        this.taskService = processEngine.getTaskService();
        this.historyService = processEngine.getHistoryService();
        this.identityService = processEngine.getIdentityService();
        this.nodeFinder = new DefaultNodeFinder(repositoryService, historyService);
    }

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
        this.identityService = processEngine.getIdentityService();
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
     */
    public void completeTask(String taskId, Map<String, Object> variables, String comment) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

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

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();

        List<String> prevNodes = findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);

        // 并行网关汇合场景：多个上一节点时拒绝驳回
        if (prevNodes.size() > 1) {
            throw new NoPreviousNodeException("当前节点位于并行网关汇合之后，无法驳回至单一上级节点");
        }

        String targetNode = prevNodes.get(0);
        executeReject(task, targetNode, reason);
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

        String processDefinitionId = task.getProcessDefinitionId();
        String initiatorNode = findInitiatorNode(processDefinitionId);

        // 当前节点已经是发起人节点，无法继续驳回
        if (task.getTaskDefinitionKey().equals(initiatorNode)) {
            throw new NoPreviousNodeException("当前已是发起人节点，无法继续驳回");
        }

        executeReject(task, initiatorNode, reason);
    }

    // ======================== 驳回内部辅助 ========================

    /**
     * 校验任务存在性、完成状态和当前用户权限。
     *
     * @param taskId    任务 ID
     * @param operation 操作名称（用于错误消息）
     * @return 运行时任务对象
     * @throws NotFoundException            任务不存在时抛出
     * @throws TaskAlreadyCompletedException 任务已完成时抛出
     * @throws PermissionDeniedException     权限不足时抛出
     */
    private Task validateTaskAndPermission(String taskId, String operation) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不可为 null");
        }

        // 查询运行时任务
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            // 查历史区分"已完成"和"不存在"
            HistoricTaskInstance historic = historyService.createHistoricTaskInstanceQuery()
                    .taskId(taskId).singleResult();
            if (historic != null) {
                throw new TaskAlreadyCompletedException("任务 " + taskId + " 已完成，无法" + operation);
            }
            throw new NotFoundException("任务 " + taskId + " 不存在");
        }

        // 权限校验：调用者必须是当前任务的审批人
        String currentUserId = userContext.getCurrentUserId();
        if (task.getAssignee() == null || !task.getAssignee().equals(currentUserId)) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是任务 " + taskId + " 的审批人，无权" + operation);
        }

        return task;
    }

    /**
     * 执行驳回节点跳转：先写入驳回意见，再通过 ChangeActivityStateBuilder 跳转节点。
     *
     * @param task             当前任务
     * @param targetActivityId 目标节点 ID
     * @param reason           驳回原因，可为 null
     */
    private void executeReject(Task task, String targetActivityId, String reason) {
        // 跳转前写入驳回意见
        if (reason != null && !reason.isEmpty()) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), reason);
        }

        // 执行节点跳转
        ChangeActivityStateBuilder builder = runtimeService.createChangeActivityStateBuilder();
        builder.processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), targetActivityId)
                .changeState();
    }
}
