package io.github.flowable.plus.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 流程图状态 VO，提供节点状态、已走连线及活跃任务信息供前端 bpmn.js 渲染。
 *
 * @author flowable-plus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagramStatesVO {

    /** 流程实例 ID */
    private String processInstanceId;

    /** 分类后的节点状态 */
    @Builder.Default
    private NodeStates states = new NodeStates();

    /** 已通过的连线 ID 列表 */
    @Builder.Default
    private List<String> completedFlows = Collections.emptyList();

    /** 当前活跃任务信息列表 */
    @Builder.Default
    private List<TaskBriefVO> activeTasks = Collections.emptyList();

    /**
     * 节点状态分类。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeStates {

        /** 当前活跃节点 ID 列表 */
        @Builder.Default
        private List<String> active = Collections.emptyList();

        /** 已完成审批节点 ID 列表（UserTask） */
        @Builder.Default
        private List<String> completed = Collections.emptyList();

        /** 已完成的自动节点 ID 列表（ServiceTask 等） */
        @Builder.Default
        private List<String> auto = Collections.emptyList();
    }

    /**
     * 活跃任务简要信息，仅从运行时表查询，保证高频接口性能。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskBriefVO {

        /** 任务 ID，供前端办理/驳回使用 */
        private String taskId;

        /** 关联的 BPMN 节点 ID (activityId) */
        private String activityId;

        /** 任务名称 */
        private String taskName;

        /** 当前处理人 */
        private String assignee;

        /** 候选组列表 */
        @Builder.Default
        private List<String> candidateGroups = Collections.emptyList();

        /** 节点到达时间 */
        private String createTime;

        /** 截止时间 */
        private String dueDate;

        /** 挂起状态：1=正常，2=已挂起 */
        private Integer suspensionState;
    }
}
