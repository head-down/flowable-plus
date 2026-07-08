package io.github.flowable.plus.core;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * 流程信息补全工具——消除 {@link FlowablePlus} 中 convertToTodoVOs/convertToDoneVOs
 * 的重复 ProcessDefinition + HistoricProcessInstance 缓存查询模式。
 *
 * <p>单次批量操作内复用缓存，避免重复引擎 I/O。
 * 实例不应跨方法调用共享——每次批量操作创建新的实例。</p>
 *
 * @author flowable-plus
 */
class ProcessEnrichHelper {

    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final Map<String, ProcessDefinition> pdCache = new HashMap<>();
    private final Map<String, HistoricProcessInstance> hpiCache = new HashMap<>();

    ProcessEnrichHelper(RepositoryService repositoryService, HistoryService historyService) {
        this.repositoryService = repositoryService;
        this.historyService = historyService;
    }

    /**
     * 按 processDefinitionId 和 processInstanceId 补全流程信息。
     * 首次查询后缓存，后续同 id 直接命中缓存。
     */
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

    /**
     * 单次补全的结果。
     */
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
