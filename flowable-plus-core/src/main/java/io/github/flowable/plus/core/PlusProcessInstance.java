package io.github.flowable.plus.core;

import org.flowable.engine.runtime.ProcessInstance;

/**
 * 流程实例领域对象，封装 Flowable 原生 {@link ProcessInstance} 的常用属性。
 *
 * <p>作为 {@link TaskOperations#startProcess} 的返回值，
 * 消除公开 API 中的 Flowable 类型泄漏。</p>
 *
 * @author flowable-plus
 */
public class PlusProcessInstance {

    private final String processInstanceId;
    private final String businessKey;
    private final String processDefinitionId;

    /**
     * 从 Flowable 原生 ProcessInstance 创建领域对象。
     *
     * @param pi Flowable 流程实例
     * @return 领域对象
     */
    static PlusProcessInstance from(ProcessInstance pi) {
        return new PlusProcessInstance(
                pi.getProcessInstanceId(),
                pi.getBusinessKey(),
                pi.getProcessDefinitionId());
    }

    private PlusProcessInstance(String processInstanceId, String businessKey, String processDefinitionId) {
        this.processInstanceId = processInstanceId;
        this.businessKey = businessKey;
        this.processDefinitionId = processDefinitionId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    @Override
    public String toString() {
        return "PlusProcessInstance{"
                + "processInstanceId='" + processInstanceId + '\''
                + ", businessKey='" + businessKey + '\''
                + ", processDefinitionId='" + processDefinitionId + '\''
                + '}';
    }
}
