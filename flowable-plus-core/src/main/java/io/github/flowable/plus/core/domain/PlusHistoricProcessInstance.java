package io.github.flowable.plus.core.domain;

import org.flowable.engine.history.HistoricProcessInstance;

import java.util.Date;

/**
 * 历史流程实例领域对象，封装 Flowable 原生 {@link HistoricProcessInstance} 的核心属性。
 *
 * @author flowable-plus
 */
public class PlusHistoricProcessInstance {

    private final String id;
    private final String businessKey;
    private final String processDefinitionId;
    private final String processDefinitionKey;
    private final String processDefinitionName;
    private final String startUserId;
    private final Date startTime;
    private final Date endTime;
    private final String deleteReason;

    public PlusHistoricProcessInstance(String id, String businessKey, String processDefinitionId,
                                String processDefinitionKey, String processDefinitionName,
                                String startUserId, Date startTime, Date endTime, String deleteReason) {
        this.id = id;
        this.businessKey = businessKey;
        this.processDefinitionId = processDefinitionId;
        this.processDefinitionKey = processDefinitionKey;
        this.processDefinitionName = processDefinitionName;
        this.startUserId = startUserId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.deleteReason = deleteReason;
    }

    public static PlusHistoricProcessInstance from(HistoricProcessInstance hpi) {
        return new PlusHistoricProcessInstance(
                hpi.getId(),
                hpi.getBusinessKey(),
                hpi.getProcessDefinitionId(),
                hpi.getProcessDefinitionKey(),
                hpi.getProcessDefinitionName(),
                hpi.getStartUserId(),
                hpi.getStartTime(),
                hpi.getEndTime(),
                hpi.getDeleteReason());
    }

    public String getId() {
        return id;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public String getProcessDefinitionName() {
        return processDefinitionName;
    }

    public String getStartUserId() {
        return startUserId;
    }

    public Date getStartTime() {
        return startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public String getDeleteReason() {
        return deleteReason;
    }

    @Override
    public String toString() {
        return "PlusHistoricProcessInstance{"
                + "id='" + id + '\''
                + ", businessKey='" + businessKey + '\''
                + ", startUserId='" + startUserId + '\''
                + '}';
    }
}
