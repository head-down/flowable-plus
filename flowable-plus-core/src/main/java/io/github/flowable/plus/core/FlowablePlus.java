package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.spi.UserContext;
import lombok.AccessLevel;
import lombok.Getter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;

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
@Getter
public class FlowablePlus {

    private final ProcessEngine processEngine;
    private final UserContext userContext;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;
    @Getter(AccessLevel.NONE)
    private final NodeFinder nodeFinder;

    /**
     * 构造器注入 ProcessEngine 和 UserContext，同时提取所有内部服务。
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
        this.nodeFinder = new NodeFinder(repositoryService, historyService);
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

        BpmnModel bpmnModel = getRequiredBpmnModel(processDefinitionId);
        FlowElement currentElement = bpmnModel.getFlowElement(currentActivityId);
        if (currentElement == null) {
            throw new NotFoundException("节点 " + currentActivityId + " 不存在");
        }

        List<String> result = nodeFinder.findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);
        if (result.isEmpty()) {
            throw new NoPreviousNodeException("节点 " + currentActivityId + " 无上一审批节点");
        }
        return result;
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

        BpmnModel bpmnModel = getRequiredBpmnModel(processDefinitionId);

        String result = nodeFinder.findInitiatorNode(processDefinitionId);
        if (result == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 中未找到发起人节点");
        }
        return result;
    }

    /**
     * 加载 BPMN 模型，不存在时抛出 {@link NotFoundException}。
     */
    private BpmnModel getRequiredBpmnModel(String processDefinitionId) {
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }
        return bpmnModel;
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
}
