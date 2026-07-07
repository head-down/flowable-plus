package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        ProcessQueryOperations, TaskListOperations,
        NodePreviewOperations {

    @Getter
    private final ProcessEngine processEngine;
    @Getter
    private final UserContext userContext;
    private final NodeFinder nodeFinder;
    private final BpmnModelCache bpmnModelCache;
    private final GroupResolver groupResolver;

    /**
     * 构造器注入所有依赖。
     *
     * @param processEngine  Flowable 流程引擎实例，不可为 null
     * @param userContext     用户上下文，用于获取当前操作用户，不可为 null
     * @param nodeFinder      BPMN 节点遍历策略，不可为 null
     * @param bpmnModelCache  BPMN 模型缓存，不可为 null
     * @param groupResolver   候选组解析器，可为 null（解析 candidateGroups 时跳过）
     */
    public FlowablePlus(ProcessEngine processEngine, UserContext userContext, NodeFinder nodeFinder,
                        BpmnModelCache bpmnModelCache, GroupResolver groupResolver) {
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
        this.processEngine = processEngine;
        this.userContext = userContext;
        this.nodeFinder = nodeFinder;
        this.bpmnModelCache = bpmnModelCache;
        this.groupResolver = groupResolver;
    }

    // ======================== ProcessQueryOperations ========================

    private static final int BATCH_SIZE = 500;

    @Override
    public Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            throw new IllegalArgumentException("processInstanceIds 不可为 null 或空");
        }

        Map<String, ProcessSummaryVO> result = new LinkedHashMap<>();
        boolean foundAny = false;

        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();

        for (int i = 0; i < processInstanceIds.size(); i += BATCH_SIZE) {
            List<String> batch = processInstanceIds.subList(i, Math.min(i + BATCH_SIZE, processInstanceIds.size()));
            Set<String> batchSet = new LinkedHashSet<>(batch);

            // 1. 查询运行时实例
            List<ProcessInstance> runtimeInstances = runtimeService.createProcessInstanceQuery()
                    .processInstanceIds(batchSet)
                    .list();
            Set<String> runtimeIds = new HashSet<>();
            Map<String, ProcessInstance> runtimeMap = new HashMap<>();
            for (ProcessInstance pi : runtimeInstances) {
                runtimeIds.add(pi.getProcessInstanceId());
                runtimeMap.put(pi.getProcessInstanceId(), pi);
            }

            // 2. 查询运行时活跃任务
            Map<String, List<Task>> tasksByInstance = new HashMap<>();
            if (!runtimeIds.isEmpty()) {
                List<Task> activeTasks = taskService.createTaskQuery()
                        .processInstanceIdIn(new ArrayList<>(runtimeIds))
                        .list();
                for (Task task : activeTasks) {
                    tasksByInstance.computeIfAbsent(task.getProcessInstanceId(), k -> new ArrayList<>()).add(task);
                }
            }

            // 3. 查询历史实例（已结束的）
            List<String> deadIds = new ArrayList<>(batchSet);
            deadIds.removeAll(runtimeIds);
            Map<String, HistoricProcessInstance> histMap = new HashMap<>();
            if (!deadIds.isEmpty()) {
                List<HistoricProcessInstance> histInstances = historyService.createHistoricProcessInstanceQuery()
                        .processInstanceIds(new HashSet<>(deadIds))
                        .list();
                for (HistoricProcessInstance hpi : histInstances) {
                    histMap.put(hpi.getId(), hpi);
                }
            }

            // 4. 按输入顺序构建 VO
            for (String instanceId : batch) {
                ProcessSummaryVO vo;
                if (runtimeMap.containsKey(instanceId)) {
                    vo = buildRunningSummary(runtimeMap.get(instanceId),
                            tasksByInstance.getOrDefault(instanceId, Collections.emptyList()));
                } else if (histMap.containsKey(instanceId)) {
                    vo = buildEndedSummary(histMap.get(instanceId));
                } else {
                    continue;
                }
                result.put(instanceId, vo);
                foundAny = true;
            }
        }

        if (!foundAny) {
            log.warn("batchQueryProcessSummaries: 所有 processInstanceId 均不存在，共 {} 个", processInstanceIds.size());
        }

        return result;
    }

    private ProcessSummaryVO buildRunningSummary(ProcessInstance pi, List<Task> tasks) {
        ProcessSummaryVO.ProcessSummaryVOBuilder builder = ProcessSummaryVO.builder()
                .instanceId(pi.getProcessInstanceId())
                .businessKey(pi.getBusinessKey())
                .processDefinitionKey(pi.getProcessDefinitionKey())
                .processDefinitionName(pi.getProcessDefinitionName())
                .startUserId(pi.getStartUserId())
                .createTime(pi.getStartTime())
                .endTime(null)
                .suspendState(pi.isSuspended() ? 2 : 1)
                .isEnded(false)
                .endReason(null);

        if (tasks.isEmpty()) {
            // 运行时存在但无活跃任务（罕见情况，如节点创建过渡期）
            builder.currentTaskId(null)
                    .currentTaskName(null)
                    .currentNodeId(null)
                    .activeAssignees(Collections.emptyList());
        } else {
            Task firstTask = tasks.get(0);
            builder.currentTaskId(firstTask.getId())
                    .currentTaskName(firstTask.getName())
                    .currentNodeId(firstTask.getTaskDefinitionKey());

            List<AssigneeInfo> assignees = new ArrayList<>();
            for (Task t : tasks) {
                assignees.add(new AssigneeInfo(t.getAssignee(), t.getId(), null));
            }
            builder.activeAssignees(assignees);
        }

        return builder.build();
    }

    private ProcessSummaryVO buildEndedSummary(HistoricProcessInstance hpi) {
        return ProcessSummaryVO.builder()
                .instanceId(hpi.getId())
                .businessKey(hpi.getBusinessKey())
                .processDefinitionKey(hpi.getProcessDefinitionKey())
                .processDefinitionName(hpi.getProcessDefinitionName())
                .startUserId(hpi.getStartUserId())
                .createTime(hpi.getStartTime())
                .endTime(hpi.getEndTime())
                .currentTaskId(null)
                .currentTaskName(null)
                .currentNodeId(null)
                .suspendState(1)
                .isEnded(true)
                .endReason(hpi.getDeleteReason())
                .activeAssignees(Collections.emptyList())
                .build();
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

            List<ApproverInfoVO> approvers = new ArrayList<>();

            // assignee
            if (userTask.getAssignee() != null && !userTask.getAssignee().isEmpty()) {
                approvers.add(ApproverInfoVO.builder()
                        .id(userTask.getAssignee())
                        .type("assignee")
                        .build());
            }

            // candidateUsers
            if (userTask.getCandidateUsers() != null) {
                for (String candidateUser : userTask.getCandidateUsers()) {
                    approvers.add(ApproverInfoVO.builder()
                            .id(candidateUser)
                            .type("candidateUser")
                            .build());
                }
            }

            // candidateGroups
            if (userTask.getCandidateGroups() != null && groupResolver != null) {
                for (String groupId : userTask.getCandidateGroups()) {
                    List<String> members = groupResolver.getGroupMembers(groupId);
                    for (String memberId : members) {
                        approvers.add(ApproverInfoVO.builder()
                                .id(memberId)
                                .type("candidateGroup")
                                .groupId(groupId)
                                .build());
                    }
                }
            }

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
