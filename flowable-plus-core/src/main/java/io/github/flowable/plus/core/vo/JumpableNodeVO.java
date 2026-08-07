package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.lang.Nullable;

import java.util.Date;

/**
 * 可跳转节点 VO，表示当前任务可跳转至的历史审批节点。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JumpableNodeVO {

    /** 节点 definitionKey（BPMN XML id） */
    private String nodeId;

    /** 节点名称（来自 BPMN 模型，保持纯净不追加系统提示） */
    private String nodeName;

    /**
     * 前端展示名称，含系统提示文案（如会签重定向说明）。
     * 非 null 时前端应优先使用此字段作为节点显示名。
     */
    @Nullable
    private String displayName;

    /** 最近一次处理该节点的审批人 */
    private String assignee;

    /** 最近一次完成时间 */
    private Date completeTime;

}
