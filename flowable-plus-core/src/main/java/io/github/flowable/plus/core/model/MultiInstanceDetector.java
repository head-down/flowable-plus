package io.github.flowable.plus.core.model;

import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import io.github.flowable.plus.core.domain.PlusTask;

/**
 * 多实例检测模块，判断 BPMN 节点是否配置了多实例（会签/或签）。
 *
 * <p>复用 {@link BpmnModelCache} 加载 BPMN 模型，不直接访问引擎。</p>
 *
 * @author flowable-plus
 */
public class MultiInstanceDetector {

    private final BpmnModelCache bpmnModelCache;

    public MultiInstanceDetector(BpmnModelCache bpmnModelCache) {
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        this.bpmnModelCache = bpmnModelCache;
    }

    /**
     * 判断任务是否为多实例子任务（会签/或签）。
     *
     * @param task 任务领域对象，不可为 null
     * @return true 如果对应 BPMN 节点配置了 multiInstanceLoopCharacteristics
     */
    public boolean isMultiInstance(PlusTask task) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(task.getProcessDefinitionId());
        return isMultiInstanceInternal(bpmnModel, task.getTaskDefinitionKey());
    }

    /**
     * 判断指定流程定义的节点是否为多实例（会签/或签）。
     *
     * @param processDefinitionId 流程定义 ID
     * @param taskDefinitionKey   任务定义 KEY
     * @return true 如果对应 BPMN 节点配置了 multiInstanceLoopCharacteristics
     */
    public boolean isMultiInstanceNode(String processDefinitionId, String taskDefinitionKey) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        return isMultiInstanceInternal(bpmnModel, taskDefinitionKey);
    }

    private boolean isMultiInstanceInternal(BpmnModel bpmnModel, String taskDefinitionKey) {
        if (bpmnModel == null) {
            return false;
        }
        FlowElement flowElement = bpmnModel.getFlowElement(taskDefinitionKey);
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
