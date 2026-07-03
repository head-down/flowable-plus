package io.github.flowable.plus.core;

import org.flowable.bpmn.model.BpmnModel;

/**
 * BPMN 模型缓存，消除重复的引擎 I/O 开销。
 *
 * <p>BPMN 模型在部署后不可变，缓存无需失效策略。
 * 实现需保证线程安全。</p>
 *
 * @author flowable-plus
 */
public interface BpmnModelCache {

    /**
     * 获取指定流程定义的 BPMN 模型。
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @return BPMN 模型，若流程定义不存在则返回 null
     */
    BpmnModel getBpmnModel(String processDefinitionId);
}
