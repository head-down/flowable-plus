package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下一节点 VO，用于 S7 审批中下游节点预览。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NextTaskNodeVO {

    /** 节点 definitionKey */
    private String taskCode;

    /** 节点名称 */
    private String taskName;

    /**
     * 节点扩展属性内容。
     * 来自 BPMN extensionElements 中的自定义元素，JSON 格式，可包含表单配置等。
     */
    private String formData;
}
