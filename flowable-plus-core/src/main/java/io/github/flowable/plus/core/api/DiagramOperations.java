package io.github.flowable.plus.core.api;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;

/**
 * 流程图操作接口，提供流程实例的高亮 SVG 流程图。
 *
 * @author flowable-plus
 */
public interface DiagramOperations {

    /**
     * 获取流程实例的流程图 SVG，包含节点状态标注。
     *
     * <p>节点状态分类：
     * <ul>
     *   <li>active — 当前活跃任务节点（红色 #FF4D4F）</li>
     *   <li>completed — 已完成审批节点（绿色 #52C41A）</li>
     *   <li>auto — 已完成的自动节点（ServiceTask 等，蓝色 #1890FF）</li>
     *   <li>flow-passed — 已通过的连线（绿色）</li>
     * </ul>
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @return 含节点状态标注的流程图 VO
     * @throws NotFoundException 如果流程实例不存在
     */
    ProcessDiagramVO getProcessDiagram(String processInstanceId);
}
