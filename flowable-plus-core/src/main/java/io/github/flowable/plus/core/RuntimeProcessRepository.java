package io.github.flowable.plus.core;

import org.flowable.engine.runtime.ProcessInstance;

import java.util.List;
import java.util.Set;

/**
 * 运行时流程实例仓储——为 {@link ProcessQueryWorkflow} 提供对
 * {@link org.flowable.engine.RuntimeService} 的接缝隔离。
 *
 * <p>返回 Flowable 原生 {@link ProcessInstance} 类型，
 * 因该对象仅在 {@link ProcessQueryWorkflow} 内部使用，
 * 不穿透至公开 API。</p>
 *
 * @author flowable-plus
 */
public interface RuntimeProcessRepository {

    /**
     * 按流程实例 ID 集合查询运行时实例。
     *
     * @param processInstanceIds 流程实例 ID 集合，不可为 null 或空
     * @return 运行时实例列表，无结果返回空列表
     */
    List<ProcessInstance> findProcessInstancesByIds(Set<String> processInstanceIds);
}
