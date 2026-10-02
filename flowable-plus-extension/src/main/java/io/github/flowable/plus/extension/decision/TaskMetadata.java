package io.github.flowable.plus.extension.decision;

import lombok.Getter;

import java.util.Date;

/**
 * 任务元数据（{@link DecisionPayload#getTaskMetadata()} 的定型小对象，ADR-0042 第 6 节）。
 *
 * <p>字段取引擎 {@code org.flowable.task.api.Task} 字段的<b>最小子集</b>（一律 {@code NULLABLE}：
 * 引擎在任务未认领 / 未设置时本就不给值）；除 {@link #nodeId} 对应 {@code Task#getTaskDefinitionKey()}
 * 外，其余与引擎<b>同名字段</b>逐一对应。<b>不在元数据上开字段级枚举</b> —— 字段取舍归
 * <b>出域策略的内容选择</b>（与变量不能逐字段声明同理）；「声明了什么段 ⇒ 出去什么段」由此端到端可对。</p>
 *
 * <p>字段全空 <b>不等于</b>「段未声明」：段未声明取 {@code null}（见 {@link DecisionPayload}）。</p>
 */
@Getter
public final class TaskMetadata {

    /** 任务标识 */
    private final String taskId;

    /** 任务名称 */
    private final String taskName;

    /** 节点标识（BPMN 流元素 id；对应引擎的 {@code Task#getTaskDefinitionKey()}） */
    private final String nodeId;

    /** 办理人（未认领时为空） */
    private final String assignee;

    /** 任务创建时间 */
    private final Date createTime;

    /**
     * 构造任务元数据小对象。
     *
     * @param taskId     任务标识，可空
     * @param taskName   任务名称，可空
     * @param nodeId     节点标识，可空
     * @param assignee   办理人，可空
     * @param createTime 创建时间，可空
     */
    public TaskMetadata(final String taskId,
                        final String taskName,
                        final String nodeId,
                        final String assignee,
                        final Date createTime) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.assignee = assignee;
        this.createTime = createTime == null ? null : new Date(createTime.getTime());
    }
}
