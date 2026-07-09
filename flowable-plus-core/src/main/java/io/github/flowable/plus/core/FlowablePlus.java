package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Flowable-Plus 统一入口 Façade，负责编排与组合各模块能力。
 *
 * <p>待办/已办查询委托给 {@link TaskQueryModule}，
 * BPMN 扩展解析委托给 {@link BpmnFormDataHelper}，
 * VO 转换委托给 {@link VOAssembler}（通过 TaskQueryModule 间接使用）。
 * 节点预览逻辑内聚于本模块。
 * 常规任务推进与驳回操作已下沉至 {@link TaskWorkflow}，
 * 会签操作已下沉至 {@link CounterSignWorkflow}。</p>
 *
 * @author flowable-plus
 */
@Slf4j
public class FlowablePlus implements TaskListOperations, NodePreviewOperations {

    private final TaskQueryModule taskQueryModule;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final NodeFinder nodeFinder;
    private final BpmnModelCache bpmnModelCache;
    private final ApproverResolver approverResolver;
    private final BpmnFormDataHelper bpmnFormDataHelper;

    /**
     * 构造器注入所有依赖。
     *
     * @param taskQueryModule     待办/已办查询模块，不可为 null
     * @param runtimeService      Flowable 运行时服务，不可为 null
     * @param repositoryService   Flowable 仓储服务，不可为 null
     * @param taskService         Flowable 任务服务，不可为 null
     * @param nodeFinder          BPMN 节点遍历策略，不可为 null
     * @param bpmnModelCache      BPMN 模型缓存，不可为 null
     * @param approverResolver    审批人解析策略，不可为 null
     * @param bpmnFormDataHelper  BPMN 扩展属性解析工具，不可为 null
     */
    public FlowablePlus(TaskQueryModule taskQueryModule,
                        RuntimeService runtimeService,
                        RepositoryService repositoryService,
                        TaskService taskService,
                        NodeFinder nodeFinder,
                        BpmnModelCache bpmnModelCache,
                        ApproverResolver approverResolver,
                        BpmnFormDataHelper bpmnFormDataHelper) {
        if (taskQueryModule == null) {
            throw new IllegalArgumentException("TaskQueryModule 不可为 null");
        }
        if (runtimeService == null) {
            throw new IllegalArgumentException("RuntimeService 不可为 null");
        }
        if (repositoryService == null) {
            throw new IllegalArgumentException("RepositoryService 不可为 null");
        }
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        if (nodeFinder == null) {
            throw new IllegalArgumentException("NodeFinder 不可为 null");
        }
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        if (approverResolver == null) {
            throw new IllegalArgumentException("ApproverResolver 不可为 null");
        }
        if (bpmnFormDataHelper == null) {
            throw new IllegalArgumentException("BpmnFormDataHelper 不可为 null");
        }
        this.taskQueryModule = taskQueryModule;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.nodeFinder = nodeFinder;
        this.bpmnModelCache = bpmnModelCache;
        this.approverResolver = approverResolver;
        this.bpmnFormDataHelper = bpmnFormDataHelper;
    }

    // ======================== TaskListOperations (委托给 TaskQueryModule) ========================

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query) {
        return taskQueryModule.queryTodoTasks(userId, query);
    }

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query, Consumer<TaskQuery> enhancer) {
        return taskQueryModule.queryTodoTasks(userId, query, enhancer);
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query) {
        return taskQueryModule.queryDoneTasks(userId, query);
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                                  Consumer<HistoricTaskInstanceQuery> enhancer) {
        return taskQueryModule.queryDoneTasks(userId, query, enhancer);
    }

    // ======================== NodePreviewOperations ========================

    @Override
    public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey) {
        return getNextNodeApproversByProcessKey(processKey, null);
    }

    @Override
    public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey, Map<String, Object> variables) {
        if (processKey == null || processKey.isEmpty()) {
            throw new IllegalArgumentException("processKey 不可为 null 或空");
        }

        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .latestVersion()
                .active()
                .singleResult();
        if (definition == null) {
            throw new IllegalArgumentException("未找到流程定义，processKey=" + processKey);
        }

        String definitionId = definition.getId();
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(definitionId);

        List<String> nodeIds = nodeFinder.findAllReachableUserTasks(definitionId, variables);

        List<NodeApproverVO> result = new ArrayList<>();
        for (String nodeId : nodeIds) {
            FlowElement flowElement = bpmnModel.getFlowElement(nodeId);
            if (!(flowElement instanceof UserTask)) {
                continue;
            }
            UserTask userTask = (UserTask) flowElement;

            List<ApproverInfoVO> approvers = approverResolver.resolveApprovers(userTask);

            result.add(NodeApproverVO.builder()
                    .nodeId(nodeId)
                    .nodeName(userTask.getName())
                    .approvers(approvers)
                    .build());
        }

        return result;
    }

    @Override
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId) {
        return getNextTaskApprovers(taskId, null);
    }

    @Override
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId, String targetNodeId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId 不可为 null 或空");
        }

        Task task = taskService.createTaskQuery()
                .taskId(taskId).singleResult();
        if (task == null) {
            throw new NotFoundException("任务 " + taskId + " 不存在");
        }

        List<ResolvedNode> nodes = resolveDownstreamNodes(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(), task.getProcessInstanceId());

        List<ApproverInfoVO> result = new ArrayList<>();
        for (ResolvedNode node : nodes) {
            if (targetNodeId != null && !targetNodeId.equals(node.nodeId)) {
                continue;
            }
            if (!(node.flowElement instanceof UserTask)) {
                continue;
            }
            List<ApproverInfoVO> approvers = approverResolver.resolveApprovers((UserTask) node.flowElement);
            for (ApproverInfoVO vo : approvers) {
                vo.setNodeId(node.nodeId);
                vo.setNodeName(node.nodeName);
            }
            result.addAll(approvers);
        }
        return result;
    }

    @Override
    public List<NextTaskNodeVO> getNextTaskNodes(String processInstanceId, String taskId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId 不可为 null 或空");
        }

        Task task = taskService.createTaskQuery()
                .taskId(taskId).singleResult();
        if (task == null) {
            throw new NotFoundException("任务 " + taskId + " 不存在");
        }

        List<ResolvedNode> nodes = resolveDownstreamNodes(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(), processInstanceId);

        List<NextTaskNodeVO> result = new ArrayList<>();
        for (ResolvedNode node : nodes) {
            String formData = bpmnFormDataHelper.extractFormData(node.flowElement);
            result.add(NextTaskNodeVO.builder()
                    .taskCode(node.nodeId)
                    .taskName(node.nodeName)
                    .formData(formData)
                    .build());
        }
        return result;
    }

    // ======================== 内部辅助 ========================

    /**
     * 共享遍历逻辑：从当前任务节点出发，解析下游节点列表。
     * 通过 RuntimeService 获取运行时变量用于条件评估。
     */
    private List<ResolvedNode> resolveDownstreamNodes(String processDefinitionId,
                                                       String currentActivityId, String processInstanceId) {
        Map<String, Object> variables = runtimeService.getVariables(processInstanceId);

        List<String> nodeIds = nodeFinder.findNextUserTasks(
                processDefinitionId, currentActivityId, processInstanceId, variables);

        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);

        List<ResolvedNode> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            FlowElement element = bpmnModel.getFlowElement(nodeId);
            if (element != null) {
                nodes.add(new ResolvedNode(nodeId, element.getName(), element));
            }
        }
        return nodes;
    }

    /**
     * 遍历中间结果 VO：存储节点 ID、名称和原始 BPMN 元素引用。
     */
    static class ResolvedNode {
        final String nodeId;
        final String nodeName;
        final FlowElement flowElement;

        ResolvedNode(String nodeId, String nodeName, FlowElement flowElement) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.flowElement = flowElement;
        }
    }
}
