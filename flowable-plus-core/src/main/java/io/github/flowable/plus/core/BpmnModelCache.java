package io.github.flowable.plus.core;

import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.task.api.Task;

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

    /**
     * 判断任务是否为多实例子任务（会签/或签）。
     *
     * @param task 任务对象，不可为 null
     * @return true 如果对应 BPMN 节点配置了 multiInstanceLoopCharacteristics
     */
    default boolean isMultiInstance(Task task) {
        BpmnModel bpmnModel = getBpmnModel(task.getProcessDefinitionId());
        if (bpmnModel == null) {
            return false;
        }
        FlowElement flowElement = bpmnModel.getFlowElement(task.getTaskDefinitionKey());
        if (flowElement == null) {
            return false;
        }
        if (flowElement instanceof Activity) {
            Activity activity = (Activity) flowElement;
            return activity.getLoopCharacteristics() != null;
        }
        return false;
    }
}
