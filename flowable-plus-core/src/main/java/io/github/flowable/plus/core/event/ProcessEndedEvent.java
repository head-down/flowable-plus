package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import lombok.Getter;

import java.util.Date;

/**
 * 流程结束事件。
 *
 * @author flowable-plus
 */
@Getter
public class ProcessEndedEvent implements DispatchableEvent {

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
    public Date getEventTime() {
        return endTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onProcessEnded(this);
    }

    @Override
    public String toString() {
        return "ProcessEndedEvent{processInstanceId='" + processInstanceId
                + "', processDefinitionKey='" + processDefinitionKey + "'}";
    }
}
