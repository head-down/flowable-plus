package io.github.flowable.plus.core;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;

import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BpmnModelCache} 的默认实现，基于 {@link ConcurrentHashMap}。
 *
 * <p>以 processDefinitionId 为 key 缓存 BPMN 模型。由于模型部署后不可变，
 * 缓存永不过期，无淘汰策略。</p>
 *
 * @author flowable-plus
 */
public class DefaultBpmnModelCache implements BpmnModelCache {

    private final RepositoryService repositoryService;
    private final ConcurrentHashMap<String, BpmnModel> cache = new ConcurrentHashMap<>();

    /**
     * @param repositoryService Flowable 仓库服务
     */
    public DefaultBpmnModelCache(RepositoryService repositoryService) {
        if (repositoryService == null) {
            throw new IllegalArgumentException("RepositoryService 不可为 null");
        }
        this.repositoryService = repositoryService;
    }

    @Override
    public BpmnModel getBpmnModel(String processDefinitionId) {
        return cache.computeIfAbsent(processDefinitionId,
                repositoryService::getBpmnModel);
    }
}
