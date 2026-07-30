package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程图 XML VO，包含 BPMN 2.0 XML 字符串供前端 bpmn.js 渲染。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDiagramVO {

    /** 流程定义 ID，供前端 HTTP 缓存使用 */
    private String processDefinitionId;

    /** BPMN 2.0 XML 字符串 */
    private String xml;
}
