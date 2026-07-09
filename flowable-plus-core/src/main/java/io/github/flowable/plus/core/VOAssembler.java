package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VO 转换模块，将 Flowable 原生任务对象转换为框架 VO。
 *
 * <p>内建流程信息补全缓存（ProcessDefinition + HistoricProcessInstance），
 * 单次批量操作内复用缓存，消除重复引擎 I/O。
 * 调用方无需关心缓存生命周期——每次方法调用自动创建新的缓存上下文。</p>
 *
 * @author flowable-plus
 */
public class VOAssembler {

    private final RepositoryService repositoryService;
    private final HistoryService historyService;

    public VOAssembler(RepositoryService repositoryService, HistoryService historyService) {
        if (repositoryService == null) {
            throw new IllegalArgumentException("RepositoryService 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        this.repositoryService = repositoryService;
        this.historyService = historyService;
    }

    /**
     * 将 Flowable Task 列表转换为 TodoTaskVO 列表，补充流程定义和发起人信息。
     */
    public List<TodoTaskVO> toTodoVOs(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }

        EnrichContext ctx = new EnrichContext();

        List<TodoTaskVO> vos = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            EnrichedInfo info = ctx.enrich(task.getProcessDefinitionId(), task.getProcessInstanceId());

            vos.add(TodoTaskVO.builder()
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .processInstanceId(task.getProcessInstanceId())
                    .processDefinitionKey(info.processDefinitionKey)
                    .processDefinitionName(info.processDefinitionName)
                    .businessKey(info.businessKey)
                    .startUserId(info.startUserId)
                    .createTime(task.getCreateTime())
                    .assignee(task.getAssignee())
                    .build());
        }
        return vos;
    }

    /**
     * 将 HistoricTaskInstance 列表转换为 DoneTaskVO 列表，补充流程定义和发起人信息。
     */
    public List<DoneTaskVO> toDoneVOs(List<HistoricTaskInstance> historicTasks) {
        if (historicTasks.isEmpty()) {
            return Collections.emptyList();
        }

        EnrichContext ctx = new EnrichContext();

        List<DoneTaskVO> vos = new ArrayList<>(historicTasks.size());
        for (HistoricTaskInstance hti : historicTasks) {
            EnrichedInfo info = ctx.enrich(hti.getProcessDefinitionId(), hti.getProcessInstanceId());

            vos.add(DoneTaskVO.builder()
                    .taskId(hti.getId())
                    .taskName(hti.getName())
                    .processInstanceId(hti.getProcessInstanceId())
                    .processDefinitionKey(info.processDefinitionKey)
                    .processDefinitionName(info.processDefinitionName)
                    .businessKey(info.businessKey)
                    .startUserId(info.startUserId)
                    .createTime(hti.getCreateTime())
                    .endTime(hti.getEndTime())
                    .assignee(hti.getAssignee())
                    .deleteReason(hti.getDeleteReason())
                    .build());
        }
        return vos;
    }

    /**
     * 对已办列表按节点去重（多实例节点只保留一条）。
     *
     * <p>去重键为 (processInstanceId + taskDefinitionKey)，保留 endTime 最新的记录。</p>
     *
     * @param tasks 历史任务列表
     * @return 去重后的任务列表
     */
    public List<HistoricTaskInstance> dedupByNode(List<HistoricTaskInstance> tasks) {
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

    // ======================== 内部补全缓存 ========================

    /**
     * 单次批量操作内的补全上下文，缓存 ProcessDefinition 和
     * HistoricProcessInstance 查询结果。
     */
    private class EnrichContext {
        final Map<String, ProcessDefinition> pdCache = new HashMap<>();
        final Map<String, HistoricProcessInstance> hpiCache = new HashMap<>();

        EnrichedInfo enrich(String definitionId, String instanceId) {
            ProcessDefinition pd = null;
            HistoricProcessInstance hpi = null;

            if (definitionId != null) {
                pd = pdCache.computeIfAbsent(definitionId, id ->
                        repositoryService.createProcessDefinitionQuery()
                                .processDefinitionId(id).singleResult());
            }

            if (instanceId != null) {
                hpi = hpiCache.computeIfAbsent(instanceId, id ->
                        historyService.createHistoricProcessInstanceQuery()
                                .processInstanceId(id).singleResult());
            }

            return new EnrichedInfo(pd, hpi);
        }
    }

    static class EnrichedInfo {
        final String processDefinitionKey;
        final String processDefinitionName;
        final String businessKey;
        final String startUserId;

        EnrichedInfo(ProcessDefinition pd, HistoricProcessInstance hpi) {
            this.processDefinitionKey = pd != null ? pd.getKey() : null;
            this.processDefinitionName = pd != null ? pd.getName() : null;
            this.businessKey = hpi != null ? hpi.getBusinessKey() : null;
            this.startUserId = hpi != null ? hpi.getStartUserId() : null;
        }
    }
}
