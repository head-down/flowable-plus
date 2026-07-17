package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;

import java.util.Date;

/**
 * 流程发起事件。
 *
 * @author flowable-plus
 */
public class ProcessStartedEvent implements DispatchableEvent {

    private final String processInstanceId;
    private final String processDefinitionKey;
    private final String businessKey;
    private final String startUserId;
    private final Date startTime;

    private ProcessStartedEvent(String processInstanceId, String processDefinitionKey,
                                String businessKey, String startUserId, Date startTime) {
        this.processInstanceId = processInstanceId;
        this.processDefinitionKey = processDefinitionKey;
        this.businessKey = businessKey;
        this.startUserId = startUserId;
        this.startTime = startTime;
    }

    public static ProcessStartedEvent of(String processDefinitionKey, String businessKey,
                                          String processInstanceId, String startUserId,
                                          Date startTime) {
        return new ProcessStartedEvent(processInstanceId, processDefinitionKey,
                businessKey, startUserId, startTime);
    }

    @Override
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    @Override
    public Date getEventTime() {
        return startTime;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getStartUserId() {
        return startUserId;
    }

    public Date getStartTime() {
        return startTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onProcessStarted(this);
    }

    @Override
    public String toString() {
        return "ProcessStartedEvent{processInstanceId='" + processInstanceId
                + "', processDefinitionKey='" + processDefinitionKey
                + "', startUserId='" + startUserId + "'}";
    }
}
