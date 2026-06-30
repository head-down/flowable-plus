package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;

import java.util.List;

/**
 * Flowable-Plus 统一入口类，封装 Flowable 引擎操作，提供增强的中国式审批 API。
 *
 * <p>构造器注入 {@link ProcessEngine}，内部持有对 RuntimeService、TaskService、
 * RepositoryService、HistoryService 的引用，并组合 {@link NodeFinder} 提供
 * BPMN 模型遍历能力。</p>
 *
 * <p>所有业务方法的参数校验和异常转换在此层完成，NodeFinder 仅负责纯遍历逻辑。</p>
 */
public class FlowablePlus {

    private final ProcessEngine processEngine;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final NodeFinder nodeFinder;

    /**
     * 构造器注入 ProcessEngine，同时提取所有内部服务。
     *
     * @param processEngine Flowable 流程引擎实例，不可为 null
     */
    public FlowablePlus(ProcessEngine processEngine) {
        if (processEngine == null) {
            throw new IllegalArgumentException("ProcessEngine 不可为 null");
        }
        this.processEngine = processEngine;
        this.repositoryService = processEngine.getRepositoryService();
        this.runtimeService = processEngine.getRuntimeService();
        this.taskService = processEngine.getTaskService();
        this.historyService = processEngine.getHistoryService();
        this.nodeFinder = new NodeFinder(repositoryService, historyService);
    }

    /**
     * 获取底层 ProcessEngine 实例。
     */
    public ProcessEngine getProcessEngine() {
        return processEngine;
    }

    /**
     * 获取 RepositoryService。
     */
    public RepositoryService getRepositoryService() {
        return repositoryService;
    }

    /**
     * 获取 RuntimeService。
     */
    public RuntimeService getRuntimeService() {
        return runtimeService;
    }

    /**
     * 获取 TaskService。
     */
    public TaskService getTaskService() {
        return taskService;
    }

    /**
     * 获取 HistoryService。
     */
    public HistoryService getHistoryService() {
        return historyService;
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

        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

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

        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 不存在");
        }

        String result = nodeFinder.findInitiatorNode(processDefinitionId);
        if (result == null) {
            throw new NotFoundException("流程定义 " + processDefinitionId + " 中未找到发起人节点");
        }
        return result;
    }
}
