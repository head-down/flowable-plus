package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.spi.ProcessEventListener;
import lombok.Getter;

import java.util.Date;

/**
 * 流程作废事件。
 *
 * @author flowable-plus
 */
@Getter
public class ProcessInvalidatedEvent implements DispatchableEvent {

    private final String processInstanceId;
    private final String processDefinitionKey;
    private final String businessKey;
    private final String operator;
    private final String reason;
    private final Date invalidateTime;

    protected ProcessInvalidatedEvent(String processInstanceId, String processDefinitionKey,
                                       String businessKey, String operator, String reason,
                                       Date invalidateTime) {
        this.processInstanceId = processInstanceId;
        this.processDefinitionKey = processDefinitionKey;
        this.businessKey = businessKey;
        this.operator = operator;
        this.reason = reason;
        this.invalidateTime = invalidateTime;
    }

    public static ProcessInvalidatedEvent of(String processInstanceId, String processDefinitionKey,
                                              String businessKey, String operator, String reason,
                                              Date invalidateTime) {
        return new ProcessInvalidatedEvent(processInstanceId, processDefinitionKey,
                businessKey, operator, reason, invalidateTime);
    }

    @Override
    public Date getEventTime() {
        return invalidateTime;
    }

    @Override
    public void accept(ProcessEventListener listener) {
        listener.onProcessInvalidated(this);
    }

    @Override
    public String toString() {
        return "ProcessInvalidatedEvent{processInstanceId='" + processInstanceId
                + "', processDefinitionKey='" + processDefinitionKey
                + "', operator='" + operator + "'}";
    }
}
