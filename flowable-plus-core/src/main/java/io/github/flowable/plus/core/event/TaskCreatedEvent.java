package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import lombok.Getter;

import java.util.Date;

/**
 * 任务新建事件：一个新待办就绪。
 *
 * <p><b>覆盖契约（写进类型 javadoc，不写进名字）</b>：本事件只在 flowable-plus <b>受控入口</b>发射 ——
 * {@code startProcess} 之后，以及每次审批操作使新待办就绪时（驳回 / 撤回 / 跳转三链共用一处、发起人决策任务独立一处）。
 * <b>不覆盖</b>引擎驱动创建（定时器边界、异步续跑、消息事件、子流程派生）与改派类 / {@code claimTask}。</p>
 *
 * <p>「新就绪」的判据 = 引擎调用前后活跃任务集之差；差集为空则<b>不发</b>。故本事件是<b>每新就绪任务一个</b>。</p>
 *
 * <p>本事件是 core 的<b>唯一增量</b>（拉管线的到点信号，ADR-0042 第 11 节残留账本第 ① 行）：无订阅者时仍发射、
 * 但无副作用（「无副作用」= 无状态变异 + 不对外发射本机制语义上的事件；允许内部只读探活）。</p>
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
