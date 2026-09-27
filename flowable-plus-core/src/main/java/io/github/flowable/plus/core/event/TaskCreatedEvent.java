package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import lombok.Getter;

import java.util.Date;

/**
 * 任务新建事件：一个新待办就绪。
 *
 * <p><b>覆盖契约</b>（写在类型 javadoc 与 ADR，不写在名字里）：本事件只在 flowable-plus
 * <b>受控入口</b>发射 —— {@code startProcess} 之后（落在自动提交首任务之后），以及每次审批操作使
 * 新待办就绪时（驳回 / 撤回 / 跳转三链共用回退一处、驳回至发起人独立一处、会签投票、加签）。
 * <b>不覆盖</b>引擎驱动创建（定时器边界、异步续跑、消息事件、子流程派生），也<b>不覆盖</b>改派类操作
 * （转办 / 委派 / 收回委派 / 减签）与 {@code claimTask} —— 后者的任务本就已就绪，只是换人。</p>
 *
 * <p><b>「新就绪」的判据</b> = 引擎状态变更调用前后<b>活跃任务集之差</b>：差集为空则不发。
 * 故本事件是<b>每新就绪任务一个</b>，同一次受控入口可发多条。</p>
 *
 * <p><b>无数据时零副作用</b>（ADR-0042 第 11 节第 4 条残留账本第 ① 行）：无发布者时
 * {@link EventBus} 直接短路、连事件对象都不构造；有发布者而无订阅者时事件<b>仍被发射</b>，
 * 但无副作用。此处的「零副作用」= <b>无状态变异</b>（不改写流程状态 / 历史 / 变量）＋
 * <b>无对外副作用</b>（不向外发射本机制语义上的事件），<b>允许内部只读探活</b>（既有
 * {@link EventBus} 一类的内部检查）。<b>不得</b>把它读成「core 运行时零新增活动」—— 与
 * ADR-0042 第 11 节第 1 条「关闭后无感 ≠ 运行时零活动」同旨。</p>
 *
 * @author flowable-plus
 */
@Getter
public class TaskCreatedEvent implements DispatchableEvent {

    private final String taskId;
    private final String processInstanceId;
    private final String taskName;
    private final String nodeId;
    private final String assignee;
    private final Date createTime;

    private TaskCreatedEvent(String taskId, String processInstanceId, String taskName,
                             String nodeId, String assignee, Date createTime) {
        this.taskId = taskId;
        this.processInstanceId = processInstanceId;
        this.taskName = taskName;
        this.nodeId = nodeId;
        this.assignee = assignee;
        this.createTime = createTime;
    }

    public static TaskCreatedEvent of(String taskId, String processInstanceId, String taskName,
                                      String nodeId, String assignee, Date createTime) {
        return new TaskCreatedEvent(taskId, processInstanceId, taskName, nodeId, assignee, createTime);
    }

    @Override
    public Date getEventTime() {
        return createTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onTaskCreated(this);
    }

    @Override
    public String toString() {
        return "TaskCreatedEvent{taskId='" + taskId
                + "', processInstanceId='" + processInstanceId
                + "', nodeId='" + nodeId + "'}";
    }
}
