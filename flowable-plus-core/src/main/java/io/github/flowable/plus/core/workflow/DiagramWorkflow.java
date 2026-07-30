package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.DiagramStatesVO;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流程图模块，提供 BPMN XML 和节点状态信息供前端 bpmn.js 渲染。
 *
 * <p>节点状态分类：
 * <ul>
 *   <li>active — 当前活跃任务节点</li>
 *   <li>completed — 已完成审批节点（UserTask）</li>
 *   <li>auto — 已完成的自动节点（ServiceTask 等）</li>
 * </ul>
 *
 * @author flowable-plus
 */
public class DiagramWorkflow {

    private static final Logger log = LoggerFactory.getLogger(DiagramWorkflow.class);

    /** Gateways 和事件的 BPMN 类型后缀，不需要标注状态 */
    private static final Set<String> SKIP_TYPES = new HashSet<>();

    static {
        SKIP_TYPES.add("startEvent");
        SKIP_TYPES.add("endEvent");
        SKIP_TYPES.add("exclusiveGateway");
        SKIP_TYPES.add("parallelGateway");
        SKIP_TYPES.add("inclusiveGateway");
        SKIP_TYPES.add("eventBasedGateway");
        SKIP_TYPES.add("boundaryEvent");
        SKIP_TYPES.add("intermediateCatchEvent");
        SKIP_TYPES.add("intermediateThrowEvent");
    }

    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final TaskService taskService;

    public DiagramWorkflow(HistoryService historyService,
                           RepositoryService repositoryService,
                           TaskService taskService) {
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (repositoryService == null) {
            throw new IllegalArgumentException("RepositoryService 不可为 null");
        }
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
    }

    /**
     * 获取流程定义的 BPMN 2.0 XML。
     *
     * <p>参数为 processDefinitionId 而非 processInstanceId，
     * 确保同一流程定义下所有实例共享 HTTP 缓存。</p>
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @return 包含 BPMN XML 的 VO
     * @throws NotFoundException 如果流程定义不存在
     */
    public ProcessDiagramVO getProcessDiagramXml(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isEmpty()) {
            throw new IllegalArgumentException("processDefinitionId 不可为 null 或空");
        }

        // 查询流程定义获取部署 ID 和资源名
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (pd == null) {
            throw new NotFoundException("未找到流程定义 " + processDefinitionId);
        }

        // 读取原始 BPMN XML 字节流
        try (InputStream is = repositoryService.getResourceAsStream(
                pd.getDeploymentId(), pd.getResourceName())) {
            if (is == null) {
                throw new NotFoundException(
                        "未找到流程定义 " + processDefinitionId + " 的 BPMN XML 资源");
            }
            String xml;
            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
                scanner.useDelimiter("\\A");
                xml = scanner.hasNext() ? scanner.next() : "";
            }
            return ProcessDiagramVO.builder()
                    .processDefinitionId(processDefinitionId)
                    .xml(xml)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("读取 BPMN XML 资源失败: " + processDefinitionId, e);
        }
    }

    /**
     * 获取流程实例的节点状态、已完成连线及活跃任务信息。
     *
     * <p>仅查询运行时表（TaskQuery、HistoricActivityInstanceQuery），
     * 不涉及历史任务表，保证高频接口性能。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @return 包含节点状态、已完成连线和活跃任务的 VO
     * @throws NotFoundException 如果流程实例不存在
     */
    public DiagramStatesVO getProcessDiagramStates(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId 不可为 null 或空");
        }

        // 1. 验证流程实例存在
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (hpi == null) {
            throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
        }

        // 2. 查询所有历史活动实例（按时间升序，用于分类节点状态）
        List<HistoricActivityInstance> allActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list();

        // 3. 查询未完成的活动实例（当前活跃节点）
        List<HistoricActivityInstance> activeActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .unfinished()
                .list();
        Set<String> activeNodeIds = activeActivities.stream()
                .map(HistoricActivityInstance::getActivityId)
                .collect(Collectors.toSet());

        // 4. 分类节点状态
        List<String> activeIds = new ArrayList<>();
        List<String> completedIds = new ArrayList<>();
        List<String> autoIds = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        for (HistoricActivityInstance act : allActivities) {
            String nodeId = act.getActivityId();
            if (processed.contains(nodeId)) {
                continue;
            }
            processed.add(nodeId);

            if (activeNodeIds.contains(nodeId)) {
                activeIds.add(nodeId);
                continue;
            }

            String type = act.getActivityType();
            if (SKIP_TYPES.contains(type)) {
                continue;
            }

            if ("userTask".equals(type)) {
                completedIds.add(nodeId);
            } else {
                autoIds.add(nodeId);
            }
        }

        DiagramStatesVO.NodeStates states = DiagramStatesVO.NodeStates.builder()
                .active(activeIds)
                .completed(completedIds)
                .auto(autoIds)
                .build();

        // 5. 查询已完成的连线（SequenceFlow 执行历史）
        List<HistoricActivityInstance> flowInstances = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType("sequenceFlow")
                .finished()
                .list();
        List<String> completedFlows = flowInstances.stream()
                .map(HistoricActivityInstance::getActivityId)
                .collect(Collectors.toList());

        // 6. 查询当前活跃任务
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list();

        List<DiagramStatesVO.TaskBriefVO> activeTaskVos = new ArrayList<>();
        for (Task t : activeTasks) {
            List<String> candidateGroups;
            try {
                candidateGroups = new ArrayList<>();
                // Flowable 的 getIdentityLinks() 返回类型为 List<? extends IdentityLinkInfo>，
                // 在特定运行时实现下遍历可能引发运行时异常，此处做防御性处理。
                if (t.getIdentityLinks() != null) {
                    for (org.flowable.identitylink.api.IdentityLinkInfo link : t.getIdentityLinks()) {
                        if ("candidate".equals(link.getType()) && link.getGroupId() != null) {
                            candidateGroups.add(link.getGroupId());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("获取任务 {} 的身份链接失败: {}", t.getId(), e.getMessage());
                candidateGroups = new ArrayList<>();
            }

            activeTaskVos.add(DiagramStatesVO.TaskBriefVO.builder()
                    .taskId(t.getId())
                    .activityId(t.getTaskDefinitionKey())
                    .taskName(t.getName())
                    .assignee(t.getAssignee())
                    .candidateGroups(candidateGroups)
                    .createTime(t.getCreateTime() != null ? t.getCreateTime().toString() : null)
                    .dueDate(t.getDueDate() != null ? t.getDueDate().toString() : null)
                    .suspensionState(t.isSuspended() ? 2 : 1)
                    .build());
        }
        log.debug("getProcessDiagramStates: processInstanceId={}, active={}, completed={}, auto={}, flows={}, tasks={}",
                processInstanceId, activeIds.size(), completedIds.size(), autoIds.size(),
                completedFlows.size(), activeTaskVos.size());

        return DiagramStatesVO.builder()
                .processInstanceId(processInstanceId)
                .states(states)
                .completedFlows(completedFlows)
                .activeTasks(activeTaskVos)
                .build();
    }
}
