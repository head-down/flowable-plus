package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.enums.TraversalMode;
import io.github.flowable.plus.core.spi.ApproverContext;
import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.support.BpmnFormDataHelper;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
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
 * <p>接口面为三个语义入口，由两个正交维度组合：锚点（流程定义 / 运行时任务）
 * 与遍历深度（{@link TraversalMode}）。遍历深度是渲染策略而非独立领域概念，
 * 故降格为方法参数（见 ADR-0031）。</p>
 *
 * @author flowable-plus
 */
public class NodePreviewWorkflow {

    private final RepositoryService repositoryService;
    private final BpmnModelCache bpmnModelCache;
    private final NodeFinder nodeFinder;
    private final ApproverResolver approverResolver;
    private final UserContext userContext;
    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final BpmnFormDataHelper bpmnFormDataHelper;

    public NodePreviewWorkflow(RepositoryService repositoryService,
                                BpmnModelCache bpmnModelCache,
                                NodeFinder nodeFinder,
                                ApproverResolver approverResolver,
                                UserContext userContext,
                                TaskService taskService,
                                RuntimeService runtimeService,
                                BpmnFormDataHelper bpmnFormDataHelper) {
        this.repositoryService = repositoryService;
        this.bpmnModelCache = bpmnModelCache;
        this.nodeFinder = nodeFinder;
        this.approverResolver = approverResolver;
        this.userContext = userContext;
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.bpmnFormDataHelper = bpmnFormDataHelper;
    }

    // ======================== 定义锚点：发起前链路预览 ========================

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人（不评估网关条件，全部展开）。
     *
     * <p>等价于 {@code getNextNodeApprovers(processKey, mode, null)}。</p>
     *
     * @param processKey 流程定义 Key，不可为 null 或空
     * @param mode       遍历深度：{@link TraversalMode#FULL} 返回完整审批链路，
     *                   {@link TraversalMode#ADJACENT} 仅返回第一个审批层级
     * @return 初始审批节点列表，每个节点包含审批人列表
     */
    public List<NodeApproverVO> getNextNodeApprovers(String processKey, TraversalMode mode) {
        return getNextNodeApprovers(processKey, mode, null);
    }

    /**
     * 根据流程定义 Key 获取初始审批节点及审批人（支持可选变量评估网关条件）。
     *
     * <p>各节点的审批人列表（{@code approvers} 字段）由
     * {@link io.github.flowable.plus.core.support.UserTaskApproverResolver} 解析，
     * 同一节点内已做优先级去重。</p>
     *
     * @param processKey 流程定义 Key，不可为 null 或空
     * @param mode       遍历深度：{@link TraversalMode#FULL} 返回完整审批链路，
     *                   {@link TraversalMode#ADJACENT} 仅返回第一个审批层级
     * @param variables  变量上下文，为 null 时不评估条件，全部展开
     * @return 初始审批节点列表，每个节点包含审批人列表
     */
    public List<NodeApproverVO> getNextNodeApprovers(String processKey, TraversalMode mode,
                                                     Map<String, Object> variables) {
        requireNonBlank(processKey, "processKey");

        ProcessDefinition definition = resolveActiveDefinition(processKey);
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(definition.getId());

        List<String> nodeIds = traverseDefinitionNodes(definition.getId(), bpmnModel, mode, variables);

        ApproverContext context = buildDefinitionContext(variables);
        return toNodeApproverVOs(bpmnModel, nodeIds, context);
    }

    // ======================== 任务锚点：审批中下游预测 ========================

    /**
     * 获取当前任务可流转至的下游节点列表。
     *
     * <p>{@link TraversalMode#FULL} 返回所有可达下游节点（完整链路）；
     * {@link TraversalMode#ADJACENT} 仅返回紧邻的下一个审批层级，
     * 适合「下一步审批节点」展示场景。若下游存在 EndEvent 分支，
     * 结果中附带 {@link NextTaskNodeVO#END_TASK_CODE} 节点。</p>
     *
     * @param taskId 当前任务 ID，不可为 null 或空
     * @param mode   遍历深度
     * @return 下游节点列表
     */
    public List<NextTaskNodeVO> getNextTaskNodes(String taskId, TraversalMode mode) {
        requireNonBlank(taskId, "taskId");

        Task task = resolveTask(taskId);
        Map<String, Object> variables = runtimeService.getVariables(task.getProcessInstanceId());
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(task.getProcessDefinitionId());

        List<String> nodeIds = traverseTaskNodes(task, mode, variables);

        List<NextTaskNodeVO> result = toNextTaskNodeVOs(bpmnModel, nodeIds);
        appendEndEventIfReachable(task, variables, result);
        return result;
    }

    /**
     * 获取当前任务下游节点的审批人（扁平列表）。
     *
     * <p>{@link TraversalMode#FULL} 返回所有可达下游审批人；
     * {@link TraversalMode#ADJACENT} 仅返回紧邻节点的审批人。
     * 同一节点内的审批人已按优先级去重（assignee &gt; candidateUser &gt;
     * candidateGroup），跨节点不作去重——同一用户出现在多个节点时列表中出现多次
     * （各携带对应 nodeId），调用方应根据业务场景自行聚合或按 nodeId 过滤。</p>
     *
     * @param taskId 当前任务 ID，不可为 null 或空
     * @param mode   遍历深度
     * @return 下游节点审批人扁平列表
     */
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId, TraversalMode mode) {
        requireNonBlank(taskId, "taskId");

        Task task = resolveTask(taskId);
        Map<String, Object> variables = runtimeService.getVariables(task.getProcessInstanceId());
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(task.getProcessDefinitionId());

        List<String> nodeIds = traverseTaskNodes(task, mode, variables);

        ApproverContext context = buildTaskContext(task, variables);
        return toApproverInfoVOs(bpmnModel, nodeIds, context);
    }

    // ======================== 内部步骤 ========================

    private void requireNonBlank(String value, String paramName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(paramName + " 不可为 null 或空");
        }
    }

    private ProcessDefinition resolveActiveDefinition(String processKey) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .latestVersion()
                .active()
                .singleResult();
        if (definition == null) {
            throw new IllegalArgumentException("未找到流程定义，processKey=" + processKey);
        }
        return definition;
    }

    private Task resolveTask(String taskId) {
        Task task = taskService.createTaskQuery()
                .taskId(taskId).singleResult();
        if (task == null) {
            throw new NotFoundException("任务 " + taskId + " 不存在");
        }
        return task;
    }

    /**
     * 定义锚点遍历：锚点恒为 StartEvent，深度由模式表达。
     */
    private List<String> traverseDefinitionNodes(String definitionId, BpmnModel bpmnModel,
                                                 TraversalMode mode, Map<String, Object> variables) {
        String startNodeId = findStartEventId(bpmnModel);
        return nodeFinder.findDownstreamUserTasks(definitionId, startNodeId, mode, variables);
    }

    /**
     * 任务锚点遍历：锚点为当前任务节点，深度由模式表达。
     */
    private List<String> traverseTaskNodes(Task task, TraversalMode mode, Map<String, Object> variables) {
        return nodeFinder.findDownstreamUserTasks(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(), mode, variables);
    }

    /**
     * 从 BPMN 模型中查找 StartEvent 节点 ID。
     *
     * @throws IllegalStateException 模型无流程或未找到 StartEvent（模型配置错误）
     */
    private String findStartEventId(BpmnModel bpmnModel) {
        if (bpmnModel.getProcesses() == null || bpmnModel.getProcesses().isEmpty()) {
            throw new IllegalStateException("BPMN 模型中未找到 StartEvent");
        }
        return bpmnModel.getProcesses().get(0).getFlowElements().stream()
                .filter(element -> element instanceof StartEvent)
                .map(FlowElement::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("BPMN 模型中未找到 StartEvent"));
    }

    /**
     * 定义锚点上下文：无 processInstanceId / taskId，variables 来自调用方（可为 null）。
     */
    private ApproverContext buildDefinitionContext(Map<String, Object> variables) {
        return new ApproverContext(variables, resolveCurrentUserId(), null, null);
    }

    /**
     * 任务锚点上下文：运行时全量变量 + 当前用户 + 实例 / 任务 ID。
     */
    private ApproverContext buildTaskContext(Task task, Map<String, Object> variables) {
        return new ApproverContext(variables, resolveCurrentUserId(),
                task.getProcessInstanceId(), task.getId());
    }

    private String resolveCurrentUserId() {
        return userContext == null ? null : userContext.getCurrentUserId();
    }

    /**
     * 节点分组 VO 映射：仅保留 UserTask 节点。
     */
    private List<NodeApproverVO> toNodeApproverVOs(BpmnModel bpmnModel, List<String> nodeIds,
                                                   ApproverContext context) {
        List<NodeApproverVO> result = new ArrayList<>();
        for (String nodeId : nodeIds) {
            FlowElement flowElement = bpmnModel.getFlowElement(nodeId);
            if (!(flowElement instanceof UserTask)) {
                continue;
            }
            UserTask userTask = (UserTask) flowElement;
            List<ApproverInfoVO> approvers = approverResolver.resolveApprovers(userTask, context);
            result.add(NodeApproverVO.builder()
                    .nodeId(nodeId)
                    .nodeName(userTask.getName())
                    .approvers(approvers)
                    .build());
        }
        return result;
    }

    /**
     * 节点列表 VO 映射：保留全部可达元素（不限于 UserTask）。
     */
    private List<NextTaskNodeVO> toNextTaskNodeVOs(BpmnModel bpmnModel, List<String> nodeIds) {
        List<NextTaskNodeVO> result = new ArrayList<>();
        for (String nodeId : nodeIds) {
            FlowElement element = bpmnModel.getFlowElement(nodeId);
            if (element == null) {
                continue;
            }
            String formData = bpmnFormDataHelper.extractFormData(element);
            result.add(NextTaskNodeVO.builder()
                    .taskCode(nodeId)
                    .taskName(element.getName())
                    .formData(formData)
                    .build());
        }
        return result;
    }

    /**
     * 扁平审批人 VO 映射：仅保留 UserTask 节点，跨节点不作去重。
     */
    private List<ApproverInfoVO> toApproverInfoVOs(BpmnModel bpmnModel, List<String> nodeIds,
                                                   ApproverContext context) {
        List<ApproverInfoVO> result = new ArrayList<>();
        for (String nodeId : nodeIds) {
            FlowElement element = bpmnModel.getFlowElement(nodeId);
            if (!(element instanceof UserTask)) {
                continue;
            }
            UserTask userTask = (UserTask) element;
            List<ApproverInfoVO> approvers = approverResolver.resolveApprovers(userTask, context);
            for (ApproverInfoVO vo : approvers) {
                vo.setNodeId(nodeId);
                vo.setNodeName(userTask.getName());
            }
            result.addAll(approvers);
        }
        return result;
    }

    /**
     * EndEvent 分支检查：下游存在可达 EndEvent 时追加结束节点 VO（与 UserTask 并列）。
     */
    private void appendEndEventIfReachable(Task task, Map<String, Object> variables,
                                           List<NextTaskNodeVO> result) {
        List<String> endIds = nodeFinder.findReachableEndEvents(
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(), variables);
        if (!endIds.isEmpty()) {
            result.add(NextTaskNodeVO.builder()
                    .taskCode(NextTaskNodeVO.END_TASK_CODE)
                    .taskName("流程结束")
                    .end(true)
                    .build());
        }
    }
}
