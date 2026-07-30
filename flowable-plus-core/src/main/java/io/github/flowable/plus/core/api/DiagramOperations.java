package io.github.flowable.plus.core.api;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.DiagramStatesVO;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;

/**
 * 流程图操作接口，提供 BPMN XML 和节点状态供前端 bpmn.js 渲染。
 *
 * @author flowable-plus
 */
public interface DiagramOperations {

    /**
     * 获取流程定义的 BPMN 2.0 XML。
     *
     * <p>注意：参数为 processDefinitionId 而非 processInstanceId，
     * 确保同一流程定义下的所有实例共享 HTTP 缓存。</p>
     *
     * @param processDefinitionId 流程定义 ID，不可为 null
     * @return 包含 BPMN XML 字符串的 VO
     * @throws NotFoundException 如果流程定义不存在
     */
    ProcessDiagramVO getProcessDiagramXml(String processDefinitionId);

    /**
     * 获取流程实例的节点状态、已完成连线及活跃任务信息。
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @return 包含分类节点状态、已完成连线和活跃任务的 VO
     * @throws NotFoundException 如果流程实例不存在
     */
    DiagramStatesVO getProcessDiagramStates(String processInstanceId);
}
