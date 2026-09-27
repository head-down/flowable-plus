package io.github.flowable.plus.extension.decision;

import lombok.Getter;

import java.util.Date;

/**
 * 流程实例元数据（{@link DecisionPayload#getProcessInstanceMetadata()} 的定型小对象，ADR-0042 第 6 节）。
 *
 * <p>字段取引擎 {@code org.flowable.engine.runtime.ProcessInstance} 的<b>同名字段最小子集</b>
 * （一律 {@code NULLABLE}）。{@code businessKey} 与发起人（{@code startUserId}）同属<b>流程实例元数据</b>
 * —— 成员为承载单元级、<b>不在元数据上开字段级枚举</b>，字段取舍归<b>出域策略的内容选择</b>。</p>
 *
 * <p>字段全空 <b>不等于</b>「段未声明」：段未声明取 {@code null}（见 {@link DecisionPayload}）。</p>
 */
@Getter
public final class ProcessInstanceMetadata {

    /** 流程实例标识 */
    private final String processInstanceId;

    /** 流程定义 key */
    private final String processDefinitionKey;

    /** 业务键 */
    private final String businessKey;

    /** 发起人 */
    private final String startUserId;

    /** 实例开始时间 */
    private final Date startTime;

    /**
     * 构造流程实例元数据小对象。
     *
     * @param processInstanceId   流程实例标识，可空
     * @param processDefinitionKey 流程定义 key，可空
     * @param businessKey         业务键，可空
     * @param startUserId         发起人，可空
     * @param startTime           开始时间，可空
     */
    public ProcessInstanceMetadata(final String processInstanceId,
                                   final String processDefinitionKey,
                                   final String businessKey,
                                   final String startUserId,
                                   final Date startTime) {
        this.processInstanceId = processInstanceId;
        this.processDefinitionKey = processDefinitionKey;
        this.businessKey = businessKey;
        this.startUserId = startUserId;
        this.startTime = startTime == null ? null : new Date(startTime.getTime());
    }
}
