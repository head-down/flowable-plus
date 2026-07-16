package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.BpmnFormDataHelper;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 节点预览工作流：提供流程定义起始节点预览、运行时任务下游节点预测等能力。
 *
 * <p>封装 BPMN 模型遍历、审批人解析和表单数据提取逻辑，
 * 对外提供稳定的节点预览 API，将 Flowable 内部 API 细节隔离在模块内。</p>
 *
 * @author flowable-plus
 */
public class NodePreviewWorkflow {

    private final RepositoryService repositoryService;
    private final BpmnModelCache bpmnModelCache;
    private final NodeFinder nodeFinder;
    private final ApproverResolver approverResolver;
    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final BpmnFormDataHelper bpmnFormDataHelper;

    public NodePreviewWorkflow(RepositoryService repositoryService,
                                BpmnModelCache bpmnModelCache,
                                NodeFinder nodeFinder,
                                ApproverResolver approverResolver,
                                TaskService taskService,
                                RuntimeService runtimeService,
                                BpmnFormDataHelper bpmnFormDataHelper) {
        this.repositoryService = repositoryService;
        this.bpmnModelCache = bpmnModelCache;
        this.nodeFinder = nodeFinder;
        this.approverResolver = approverResolver;
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.bpmnFormDataHelper = bpmnFormDataHelper;
    }

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人（不评估网关条件，全部展开）。
     */
    public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey) {
        return getNextNodeApproversByProcessKey(processKey, null);
    }

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人（支持可选变量评估网关条件）。
     */
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

    /**
     * 获取当前任务所有下一节点的审批人（扁平列表）。
     */
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId) {
        return getNextTaskApprovers(taskId, null);
    }

    /**
     * 获取当前任务指定目标节点的审批人。
     */
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

    /**
     * 获取当前任务可流转至的下游节点列表。
     */
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

    /**
     * 共享遍历逻辑：从当前任务节点出发，解析下游节点列表。
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
     * 遍历中间结果：存储节点 ID、名称和原始 BPMN 元素引用。
     */
    private static class ResolvedNode {
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
