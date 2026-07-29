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

    /** 流程结束信号节点占位 taskCode */
    public static final String END_TASK_CODE = "__END__";

    /** 节点 definitionKey */
    private String taskCode;

    /** 节点名称 */
    private String taskName;

    /**
     * 节点扩展属性内容。
     * 来自 BPMN extensionElements 中的自定义元素，JSON 格式，可包含表单配置等。
     */
    private String formData;

    /**
     * 是否为流程结束信号。
     * 当 {@link #taskCode} 等于 {@link #END_TASK_CODE} 时，表示当前节点后无更多审批节点，
     * 流程将直接结束。
     */
    private boolean end;
}
