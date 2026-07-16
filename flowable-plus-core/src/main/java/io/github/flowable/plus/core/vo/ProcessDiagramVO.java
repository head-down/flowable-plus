package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程图 VO，包含含高亮状态的 SVG 流程图。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDiagramVO {

    /** 流程实例 ID */
    private String processInstanceId;

    /** 流程定义 ID */
    private String processDefinitionId;

    /** 含 data-state 标注和 CSS 的完整 SVG 字符串 */
    private String svg;
}
