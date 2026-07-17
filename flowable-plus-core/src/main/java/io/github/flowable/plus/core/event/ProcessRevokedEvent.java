package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;

import java.util.Date;

/**
 * 流程撤销事件。
 *
 * @author flowable-plus
 */
public class ProcessRevokedEvent implements DispatchableEvent {

    private final String processInstanceId;
    private final String processDefinitionKey;
    private final String businessKey;
    private final String operator;
    private final String reason;
    private final Date revokeTime;

    private ProcessRevokedEvent(String processInstanceId, String processDefinitionKey,
                                String businessKey, String operator, String reason,
                                Date revokeTime) {
        this.processInstanceId = processInstanceId;
        this.processDefinitionKey = processDefinitionKey;
        this.businessKey = businessKey;
        this.operator = operator;
        this.reason = reason;
        this.revokeTime = revokeTime;
    }

    public static ProcessRevokedEvent of(String processInstanceId, String processDefinitionKey,
                                          String businessKey, String operator, String reason,
                                          Date revokeTime) {
        return new ProcessRevokedEvent(processInstanceId, processDefinitionKey,
                businessKey, operator, reason, revokeTime);
    }

    @Override
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    @Override
    public Date getEventTime() {
        return revokeTime;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getOperator() {
        return operator;
    }

    public String getReason() {
        return reason;
    }

    public Date getRevokeTime() {
        return revokeTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onProcessRevoked(this);
    }

    @Override
    public String toString() {
        return "ProcessRevokedEvent{processInstanceId='" + processInstanceId
                + "', processDefinitionKey='" + processDefinitionKey
                + "', operator='" + operator + "'}";
    }
}
