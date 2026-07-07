package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审批人信息，含用户 ID 到任务 ID 的映射。
 *
 * <p>多实例场景下，列表页需要 userId → taskId 的映射以便点击"审批"时携带正确的任务 ID。
 * 普通节点为单元素列表，调用方逻辑统一。</p>
 *
 * @author flowable-plus
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssigneeInfo {

    /** 审批人 ID（必填） */
    private String userId;

    /** 对应的任务 ID（必填） */
    private String taskId;

    /** 审批人姓名（可选，引擎不填充，由调用方后置赋值） */
    private String name;
}
