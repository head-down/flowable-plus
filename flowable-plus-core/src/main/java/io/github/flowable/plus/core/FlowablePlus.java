package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.UserContext;
import lombok.Getter;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Flowable-Plus 统一入口 Façade，封装 Flowable 引擎操作，提供增强的中国式审批 API。
 *
 * <p>内部将业务逻辑委托给 {@link TaskWorkflow} 和 {@link CounterSignWorkflow} 两个 module，
 * 通过实现 {@link NodeNavigation}、{@link TaskOperations}、{@link CounterSignOperations}、
 * {@link RejectionOperations}、{@link ProcessLifecycle} 接口提供细粒度的访问控制。</p>
 *
 * @author flowable-plus
 */
public class FlowablePlus implements
        NodeNavigation, TaskOperations, CounterSignOperations,
        RejectionOperations, ProcessLifecycle {

    @Getter
    private final ProcessEngine processEngine;
    @Getter
    private final UserContext userContext;

    private final NodeFinder nodeFinder;
    private final TaskWorkflow taskWorkflow;
    private final CounterSignWorkflow counterSignWorkflow;

    /**
     * 构造器注入所有依赖。
     *
     * @param processEngine       Flowable 流程引擎实例，不可为 null
     * @param userContext          用户上下文，用于获取当前操作用户，不可为 null
     * @param nodeFinder           BPMN 节点遍历策略，不可为 null
     * @param bpmnModelCache       BPMN 模型缓存，不可为 null
     * @param counterSignCallbacks 会签回调列表，可为 null（自动转为空列表）
     */
    public FlowablePlus(ProcessEngine processEngine, UserContext userContext, NodeFinder nodeFinder,
                        BpmnModelCache bpmnModelCache, List<CounterSignCallback> counterSignCallbacks) {
        this(processEngine, userContext, nodeFinder, bpmnModelCache,
                processEngine != null ? new FlowableTaskRepository(processEngine.getTaskService()) : null,
                processEngine != null ? new FlowableHistoricRepository(processEngine.getHistoryService()) : null,
                counterSignCallbacks);
    }

    FlowablePlus(ProcessEngine processEngine, UserContext userContext, NodeFinder nodeFinder,
                 BpmnModelCache bpmnModelCache, TaskRepository taskRepository,
                 HistoricRepository historicRepository, List<CounterSignCallback> counterSignCallbacks) {
        if (processEngine == null) {
            throw new IllegalArgumentException("ProcessEngine 不可为 null");
        }
        if (userContext == null) {
            throw new IllegalArgumentException("UserContext 不可为 null");
        }
        if (nodeFinder == null) {
            throw new IllegalArgumentException("NodeFinder 不可为 null");
        }
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        if (taskRepository == null) {
            throw new IllegalArgumentException("TaskRepository 不可为 null");
        }
        if (historicRepository == null) {
            throw new IllegalArgumentException("HistoricRepository 不可为 null");
        }
        this.processEngine = processEngine;
        this.userContext = userContext;
        this.nodeFinder = nodeFinder;

        RuntimeService runtimeService = processEngine.getRuntimeService();

        this.taskWorkflow = new TaskWorkflow(userContext, taskRepository, historicRepository,
                runtimeService, processEngine.getIdentityService(), nodeFinder, bpmnModelCache);

        List<CounterSignCallback> callbacks = counterSignCallbacks != null
                ? new ArrayList<>(counterSignCallbacks) : Collections.emptyList();
        this.counterSignWorkflow = new CounterSignWorkflow(userContext, taskRepository,
                historicRepository, runtimeService, bpmnModelCache, nodeFinder, callbacks);
    }

    // ======================== NodeNavigation ========================

    @Override
    public List<String> findPreviousNodes(String processDefinitionId, String currentActivityId, String processInstanceId) {
        if (processDefinitionId == null) {
            throw new IllegalArgumentException("processDefinitionId 不可为 null");
        }
        if (currentActivityId == null) {
            throw new IllegalArgumentException("currentActivityId 不可为 null");
        }
        return nodeFinder.findPreviousNodes(processDefinitionId, currentActivityId, processInstanceId);
    }

    @Override
    public String findInitiatorNode(String processDefinitionId) {
        if (processDefinitionId == null) {
            throw new IllegalArgumentException("processDefinitionId 不可为 null");
        }
        return nodeFinder.findInitiatorNode(processDefinitionId);
    }

    // ======================== TaskOperations ========================

    @Override
    public ProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        return taskWorkflow.startProcess(processDefinitionKey, businessKey, variables);
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> variables, String comment) {
        taskWorkflow.completeTask(taskId, variables, comment);
    }

    @Override
    public void claimTask(String taskId) {
        taskWorkflow.claimTask(taskId);
    }

    // ======================== CounterSignOperations ========================

    @Override
    public void counterSign(String taskId, boolean approved, Map<String, Object> variables, String comment) {
        counterSignWorkflow.counterSign(taskId, approved, variables, comment);
    }

    @Override
    public void addCounterSigner(String taskId, List<String> assignees) {
        counterSignWorkflow.addCounterSigner(taskId, assignees);
    }

    @Override
    public void removeCounterSigner(String taskId, String assignee) {
        counterSignWorkflow.removeCounterSigner(taskId, assignee);
    }

    // ======================== RejectionOperations ========================

    @Override
    public void rejectTask(String taskId, String reason) {
        taskWorkflow.rejectTask(taskId, reason);
    }

    @Override
    public void rejectTaskToInitiator(String taskId, String reason) {
        taskWorkflow.rejectTaskToInitiator(taskId, reason);
    }

    // ======================== ProcessLifecycle ========================

    @Override
    public void withdrawTask(String taskId, String reason) {
        taskWorkflow.withdrawTask(taskId, reason);
    }

    @Override
    public void revokeProcess(String processInstanceId, String reason) {
        taskWorkflow.revokeProcess(processInstanceId, reason);
    }

    // ======================== 测试辅助 ========================

    /**
     * 判断 Task 是否为多实例子任务，供同包测试使用。
     */
    boolean isMultiInstance(Task task) {
        return taskWorkflow.isMultiInstance(task);
    }
}
