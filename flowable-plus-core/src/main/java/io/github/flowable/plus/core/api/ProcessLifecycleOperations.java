package io.github.flowable.plus.core.api;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.domain.PlusProcessInstance;

import java.util.Map;

/**
 * 流程生命周期操作接口，定义流程发起和撤销操作。
 *
 * @author flowable-plus
 */
public interface ProcessLifecycleOperations {

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
     * 撤销整个流程实例。
     *
     * <p>流程发起人撤销运行中的流程实例，采用软删除策略——
     * 删除运行时实例但保留历史记录供审计。</p>
     *
     * @param processInstanceId 流程实例 ID，不可为 null
     * @param reason            撤销原因，可为 null
     * @throws NotFoundException            流程实例不存在时抛出
     * @throws TaskAlreadyCompletedException 流程已结束或已推进后续节点时抛出
     * @throws PermissionDeniedException     调用者不是流程发起人时抛出
     */
    void revokeProcess(String processInstanceId, String reason);
}
