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
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.idm.api.Group;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    // ======================== TaskListOperations (S2/S3) ========================

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query) {
        return queryTodoTasks(userId, query, null);
    }

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query, Consumer<TaskQuery> enhancer) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不可为 null 或空");
        }
        if (query == null) {
            query = new TaskQueryDTO();
        }

        List<String> groupIds = getGroupIds(userId);

        TaskQuery taskQuery = processEngine.getTaskService().createTaskQuery();
        if (groupIds.isEmpty()) {
            taskQuery.or()
                    .taskAssignee(userId)
                    .taskCandidateUser(userId)
                    .endOr();
        } else {
            taskQuery.or()
                    .taskAssignee(userId)
                    .taskCandidateUser(userId)
                    .taskCandidateGroupIn(groupIds)
                    .endOr();
        }

        applyTodoFilters(taskQuery, query);

        if (enhancer != null) {
            enhancer.accept(taskQuery);
        }

        long total = taskQuery.count();

        int firstResult = (query.getPageNum() - 1) * query.getPageSize();
        List<Task> tasks = taskQuery
                .orderByTaskCreateTime().desc()
                .listPage(firstResult, query.getPageSize());

        List<TodoTaskVO> vos = convertToTodoVOs(tasks);

        return new PageResult<>(total, query.getPageNum(), query.getPageSize(), vos);
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query) {
        return queryDoneTasks(userId, query, null);
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                                  Consumer<HistoricTaskInstanceQuery> enhancer) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不可为 null 或空");
        }
        if (query == null) {
            query = new TaskQueryDTO();
        }

        HistoryService historyService = processEngine.getHistoryService();

        HistoricTaskInstanceQuery historicQuery = historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(userId)
                .finished();

        if (query.getProcessDefinitionKey() != null && !query.getProcessDefinitionKey().isEmpty()) {
            historicQuery.processDefinitionKey(query.getProcessDefinitionKey());
        }
        if (query.getTaskName() != null && !query.getTaskName().isEmpty()) {
            historicQuery.taskName(query.getTaskName());
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            historicQuery.processInstanceBusinessKeyLike("%" + query.getKeyword() + "%");
        }
        if (query.getBeginDate() != null) {
            historicQuery.taskCompletedAfter(query.getBeginDate());
        }
        if (query.getEndDate() != null) {
            historicQuery.taskCompletedBefore(query.getEndDate());
        }

        if (enhancer != null) {
            enhancer.accept(historicQuery);
        }

        // 查询全部（多实例去重必须在内存中完成，不能依赖数据库分页）
        List<HistoricTaskInstance> allHistoricTasks = historicQuery
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();

        List<HistoricTaskInstance> deduped = dedupByNode(allHistoricTasks);

        long total = deduped.size();

        int fromIndex = (query.getPageNum() - 1) * query.getPageSize();
        int toIndex = Math.min(fromIndex + query.getPageSize(), deduped.size());
        List<HistoricTaskInstance> pageTasks;
        if (fromIndex >= deduped.size()) {
            pageTasks = Collections.emptyList();
        } else {
            pageTasks = new ArrayList<>(deduped.subList(fromIndex, toIndex));
        }

        List<DoneTaskVO> vos = convertToDoneVOs(pageTasks);

        return new PageResult<>(total, query.getPageNum(), query.getPageSize(), vos);
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

    // ======================== 内部辅助方法 (S2/S3) ========================

    /**
     * 获取用户所属候选组 ID 列表。
     */
    private List<String> getGroupIds(String userId) {
        IdentityService identityService = processEngine.getIdentityService();
        return identityService.createGroupQuery().groupMember(userId).list()
                .stream().map(Group::getId).collect(Collectors.toList());
    }

    /**
     * 将 TaskQueryDTO 过滤条件应用到 TaskQuery。
     */
    private void applyTodoFilters(TaskQuery taskQuery, TaskQueryDTO query) {
        if (query.getProcessDefinitionKey() != null && !query.getProcessDefinitionKey().isEmpty()) {
            taskQuery.processDefinitionKey(query.getProcessDefinitionKey());
        }
        if (query.getTaskName() != null && !query.getTaskName().isEmpty()) {
            taskQuery.taskName(query.getTaskName());
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            taskQuery.processInstanceBusinessKeyLike("%" + query.getKeyword() + "%");
        }
        if (query.getBeginDate() != null) {
            taskQuery.taskCreatedAfter(query.getBeginDate());
        }
        if (query.getEndDate() != null) {
            taskQuery.taskCreatedBefore(query.getEndDate());
        }
    }

    /**
     * 将 Flowable Task 列表转换为 TodoTaskVO 列表，补充流程定义和发起人信息。
     */
    private List<TodoTaskVO> convertToTodoVOs(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }

        RepositoryService repositoryService = processEngine.getRepositoryService();
        HistoryService historyService = processEngine.getHistoryService();
        Map<String, ProcessDefinition> pdCache = new HashMap<>();
        Map<String, HistoricProcessInstance> hpiCache = new HashMap<>();

        List<TodoTaskVO> vos = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            String defId = task.getProcessDefinitionId();
            ProcessDefinition pd = pdCache.computeIfAbsent(defId, id ->
                    repositoryService.createProcessDefinitionQuery().processDefinitionId(id).singleResult());

            String instanceId = task.getProcessInstanceId();
            HistoricProcessInstance hpi = hpiCache.computeIfAbsent(instanceId, id ->
                    historyService.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());

            vos.add(TodoTaskVO.builder()
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .processInstanceId(instanceId)
                    .processDefinitionKey(pd != null ? pd.getKey() : null)
                    .processDefinitionName(pd != null ? pd.getName() : null)
                    .businessKey(hpi != null ? hpi.getBusinessKey() : null)
                    .startUserId(hpi != null ? hpi.getStartUserId() : null)
                    .createTime(task.getCreateTime())
                    .assignee(task.getAssignee())
                    .build());
        }
        return vos;
    }

    /**
     * 对已办列表按节点去重（多实例节点只保留一条）。
     * 去重键为 (processInstanceId + taskDefinitionKey)，保留 endTime 最新的记录。
     */
    private List<HistoricTaskInstance> dedupByNode(List<HistoricTaskInstance> tasks) {
        Map<String, HistoricTaskInstance> map = new LinkedHashMap<>();
        for (HistoricTaskInstance hti : tasks) {
            String key = hti.getProcessInstanceId() + "|" + hti.getTaskDefinitionKey();
            HistoricTaskInstance existing = map.get(key);
            if (existing == null
                    || (hti.getEndTime() != null
                    && (existing.getEndTime() == null || hti.getEndTime().after(existing.getEndTime())))) {
                map.put(key, hti);
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 将 HistoricTaskInstance 列表转换为 DoneTaskVO 列表，补充流程定义和发起人信息。
     */
    private List<DoneTaskVO> convertToDoneVOs(List<HistoricTaskInstance> historicTasks) {
        if (historicTasks.isEmpty()) {
            return Collections.emptyList();
        }

        RepositoryService repositoryService = processEngine.getRepositoryService();
        HistoryService historyService = processEngine.getHistoryService();
        Map<String, ProcessDefinition> pdCache = new HashMap<>();
        Map<String, HistoricProcessInstance> hpiCache = new HashMap<>();

        List<DoneTaskVO> vos = new ArrayList<>(historicTasks.size());
        for (HistoricTaskInstance hti : historicTasks) {
            String defId = hti.getProcessDefinitionId();
            ProcessDefinition pd = defId != null
                    ? pdCache.computeIfAbsent(defId, id ->
                            repositoryService.createProcessDefinitionQuery().processDefinitionId(id).singleResult())
                    : null;

            String instanceId = hti.getProcessInstanceId();
            HistoricProcessInstance hpi = instanceId != null
                    ? hpiCache.computeIfAbsent(instanceId, id ->
                            historyService.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult())
                    : null;

            vos.add(DoneTaskVO.builder()
                    .taskId(hti.getId())
                    .taskName(hti.getName())
                    .processInstanceId(instanceId)
                    .processDefinitionKey(pd != null ? pd.getKey() : null)
                    .processDefinitionName(pd != null ? pd.getName() : null)
                    .businessKey(hpi != null ? hpi.getBusinessKey() : null)
                    .startUserId(hpi != null ? hpi.getStartUserId() : null)
                    .createTime(hti.getCreateTime())
                    .endTime(hti.getEndTime())
                    .assignee(hti.getAssignee())
                    .deleteReason(hti.getDeleteReason())
                    .build());
        }
        return vos;
    }
}
