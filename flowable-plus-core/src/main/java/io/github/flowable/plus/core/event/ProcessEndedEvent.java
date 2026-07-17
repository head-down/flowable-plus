package io.github.flowable.plus.core.event;

import java.util.Date;

/**
 * 流程结束事件。
 *
 * @author flowable-plus
 */
public class ProcessEndedEvent implements ProcessEvent {

    private final String processInstanceId;
    private final String processDefinitionKey;
    private final String businessKey;
    private final Date endTime;

    private ProcessEndedEvent(String processInstanceId, String processDefinitionKey,
                              String businessKey, Date endTime) {
        this.processInstanceId = processInstanceId;
        this.processDefinitionKey = processDefinitionKey;
        this.businessKey = businessKey;
        this.endTime = endTime;
    }

    public static ProcessEndedEvent of(String processInstanceId, String processDefinitionKey,
                                        String businessKey, Date endTime) {
        return new ProcessEndedEvent(processInstanceId, processDefinitionKey,
                businessKey, endTime);
    }

    @Override
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    @Override
    public Date getEventTime() {
        return endTime;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public Date getEndTime() {
        return endTime;
    }

    @Override
    public String toString() {
        return "ProcessEndedEvent{processInstanceId='" + processInstanceId
                + "', processDefinitionKey='" + processDefinitionKey + "'}";
    }
}
