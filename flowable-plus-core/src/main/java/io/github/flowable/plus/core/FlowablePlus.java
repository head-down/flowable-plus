package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Flowable-Plus 统一入口 Façade，封装需要跨模块协调的查询与预览操作。
 *
 * <p>常规任务推进与驳回等操作已下沉至 {@link TaskWorkflow}，
 * 会签操作已下沉至 {@link CounterSignWorkflow}，可直接注入使用。</p>
 *
 * @author flowable-plus
 */
@Slf4j
public class FlowablePlus implements
        TaskListOperations,
        NodePreviewOperations {

    @Getter
    private final ProcessEngine processEngine;
    @Getter
    private final UserContext userContext;
    private final NodeFinder nodeFinder;
    private final BpmnModelCache bpmnModelCache;
    private final ApproverResolver approverResolver;

    /**
     * 构造器注入所有依赖。
     *
     * @param processEngine    Flowable 流程引擎实例，不可为 null
     * @param userContext       用户上下文，用于获取当前操作用户，不可为 null
     * @param nodeFinder        BPMN 节点遍历策略，不可为 null
     * @param bpmnModelCache    BPMN 模型缓存，不可为 null
     * @param approverResolver  审批人解析策略，不可为 null
     */
    public FlowablePlus(ProcessEngine processEngine, UserContext userContext, NodeFinder nodeFinder,
                        BpmnModelCache bpmnModelCache, ApproverResolver approverResolver) {
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
        if (approverResolver == null) {
            throw new IllegalArgumentException("ApproverResolver 不可为 null");
        }
        this.processEngine = processEngine;
        this.userContext = userContext;
        this.nodeFinder = nodeFinder;
        this.bpmnModelCache = bpmnModelCache;
        this.approverResolver = approverResolver;
    }

    // ======================== TaskListOperations (S2/S3 — 待实现) ========================

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query) {
        throw new UnsupportedOperationException("queryTodoTasks 尚未实现，将在 S2 中完成");
    }

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query, Consumer<TaskQuery> enhancer) {
        throw new UnsupportedOperationException("queryTodoTasks 尚未实现，将在 S2 中完成");
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query) {
        throw new UnsupportedOperationException("queryDoneTasks 尚未实现，将在 S3 中完成");
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                                  Consumer<HistoricTaskInstanceQuery> enhancer) {
        throw new UnsupportedOperationException("queryDoneTasks 尚未实现，将在 S3 中完成");
    }

    // ======================== NodePreviewOperations (S5 已实现, S6/S7 — 待实现) ========================

    @Override
    public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey) {
        return getNextNodeApproversByProcessKey(processKey, null);
    }

    @Override
    public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey, Map<String, Object> variables) {
        if (processKey == null || processKey.isEmpty()) {
            throw new IllegalArgumentException("processKey 不可为 null 或空");
        }

        RepositoryService repositoryService = processEngine.getRepositoryService();
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
        throw new UnsupportedOperationException("getNextTaskApprovers 尚未实现，将在 S6 中完成");
    }

    @Override
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId, String targetNodeId) {
        throw new UnsupportedOperationException("getNextTaskApprovers 尚未实现，将在 S6 中完成");
    }

    @Override
    public List<NextTaskNodeVO> getNextTaskNodes(String processInstanceId, String taskId) {
        throw new UnsupportedOperationException("getNextTaskNodes 尚未实现，将在 S7 中完成");
    }
}
