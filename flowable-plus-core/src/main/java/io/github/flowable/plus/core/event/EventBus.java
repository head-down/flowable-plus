package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.domain.PlusTask;

import java.util.Date;

/**
 * 流程事件总线，作为发布事件的统一入口深层模块。
 *
 * <p>调用方（workflow 模块）通过领域语义方法发布事件，无需关心：
 * <ul>
 *   <li>发布者是否存在（{@code EventPublisher} 可为 null，内部自动短路）</li>
 *   <li>事件对象的构造（参数展开、时间戳生成内化于此）</li>
 *   <li>底层发布机制（同步/异步 {@code EventPublisher} 装饰对调用方透明）</li>
 * </ul>
 * </p>
 *
 * @author flowable-plus
 */
public class EventBus {

    private final EventPublisher eventPublisher;

    public EventBus(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 是否存在事件发布者。无发布者时用于短路后续发布相关逻辑
     * （如流程结束检测，避免无意义查询）。
     */
    public boolean isEnabled() {
        return eventPublisher != null;
    }

    /**
     * 通用发布入口，发布者不存在时静默跳过。
     *
     * @param event 流程事件对象，不可为 null
     */
    public void publish(ProcessEvent event) {
        if (eventPublisher != null) {
            eventPublisher.publish(event);
        }
    }

    // ======================== 任务事件 ========================

    /** 任务完成事件（assignee 为操作者） */
    public void taskCompleted(PlusTask task, String userId, String comment) {
        publish(TaskCompletedEvent.of(task.getId(), task.getProcessInstanceId(),
                task.getName(), task.getTaskDefinitionKey(), userId, comment, new Date()));
    }

    /** 任务驳回事件（assignee 为被驳回任务的当前审批人） */
    public void taskRejected(PlusTask task, String reason) {
        publish(TaskRejectedEvent.of(task.getId(), task.getProcessInstanceId(),
                task.getName(), task.getTaskDefinitionKey(), task.getAssignee(),
                reason, new Date()));
    }

    /** 任务撤回事件（operator 为执行撤回操作的人） */
    public void taskWithdrawn(PlusTask task, String userId, String reason) {
        publish(TaskWithdrawnEvent.of(task.getId(), task.getProcessInstanceId(),
                task.getName(), task.getTaskDefinitionKey(), task.getAssignee(),
                userId, reason, new Date()));
    }

    /** 任务转办事件 */
    public void taskTransferred(PlusTask task, String fromUserId, String toUserId, String reason) {
        publish(TaskTransferredEvent.of(task.getId(), task.getProcessInstanceId(),
                task.getName(), task.getTaskDefinitionKey(),
                fromUserId, toUserId, reason, new Date()));
    }

    /** 任务跳转事件 */
    public void taskJumped(PlusTask task, String targetNodeId, String reason, String commentType) {
        publish(TaskJumpedEvent.of(task.getId(), task.getProcessInstanceId(),
                task.getName(), task.getTaskDefinitionKey(), task.getAssignee(),
                targetNodeId, reason, commentType, new Date()));
    }

    /** 任务委派事件 */
    public void taskDelegated(PlusTask task, String fromUserId, String toUserId, String reason) {
        publish(TaskDelegatedEvent.of(task.getId(), task.getProcessInstanceId(),
                task.getName(), task.getTaskDefinitionKey(),
                fromUserId, toUserId, reason, new Date()));
    }

    // ======================== 流程事件 ========================

    /** 流程发起事件 */
    public void processStarted(String processDefinitionKey, String businessKey,
                               String processInstanceId, String userId) {
        publish(ProcessStartedEvent.of(processDefinitionKey, businessKey,
                processInstanceId, userId, new Date()));
    }

    /** 流程作废事件 */
    public void processInvalidated(String processInstanceId, String processDefinitionKey,
                                   String businessKey, String userId, String reason) {
        publish(ProcessInvalidatedEvent.of(processInstanceId, processDefinitionKey,
                businessKey, userId, reason, new Date()));
    }

    /**
     * 流程结束事件。endTime 为历史流程实例的实际结束时间，由调用方透传。
     */
    public void processEnded(String processInstanceId, String processDefinitionKey,
                             String businessKey, Date endTime) {
        publish(ProcessEndedEvent.of(processInstanceId, processDefinitionKey,
                businessKey, endTime));
    }
}
