package io.github.flowable.plus.core;

import org.flowable.bpmn.model.BpmnModel;

/**
 * BPMN 模型缓存，消除重复的引擎 I/O 开销。
 *
 * <p>BPMN 模型在部署后不可变，缓存无需失效策略。
 * 实现需保证线程安全。 多实例检测逻辑已提取到 {@link MultiInstanceDetector}。</p>
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

    /**
     * 根据流程定义 Key 获取最新活跃版本的 BPMN 模型。
     * 用于 CallActivity 引用外部流程定义时加载模型。
     *
     * @param processKey 流程定义 Key，不可为 null
     * @return BPMN 模型，若流程定义不存在则返回 null
     */
    default BpmnModel getBpmnModelByProcessKey(String processKey) {
        return null;
    }
}
