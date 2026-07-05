package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;

import java.util.Map;

/**
 * 任务操作接口，定义审批任务的正向推进操作。
 *
 * <p>覆盖 {@link FlowablePlus} 中的任务完成、认领和流程发起，
 * 调用方可通过注入此接口限制可用的操作范围。</p>
 *
 * @author flowable-plus
 * @see FlowablePlus
 */
public interface TaskOperations {

    /**
     * 启动流程实例。
     *
     * @param processDefinitionKey 流程定义 KEY，不可为 null
     * @param businessKey          业务主键，可为 null
     * @param variables            流程变量，可为 null
     * @return 流程实例领域对象
     * @throws NotFoundException 流程定义不存在时抛出
     */
    PlusProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables);

    /**
     * 完成任务审批。
     *
     * <p>自动认领任务、添加审批意见后完成。</p>
     *
     * @param taskId    任务 ID，不可为 null
     * @param variables 流程变量，可为 null
     * @param comment   审批意见，可为 null
     * @throws NotFoundException 任务不存在时抛出
     * @throws IllegalArgumentException 任务为多实例子任务时抛出（请使用会签操作）
     */
    void completeTask(String taskId, Map<String, Object> variables, String comment);

    /**
     * 认领任务（低级 API）。
     *
     * <p>通常无需手动调用，{@link #completeTask} 已自动认领。</p>
     *
     * @param taskId 任务 ID，不可为 null
     * @throws NotFoundException 任务不存在时抛出
     */
    void claimTask(String taskId);
}
