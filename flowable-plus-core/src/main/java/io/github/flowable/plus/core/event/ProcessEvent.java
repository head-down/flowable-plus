package io.github.flowable.plus.core.event;

import java.util.Date;

/**
 * 流程事件基接口，所有事件对象实现此接口。
 * 提供所有事件共有的基础属性。
 *
 * @author flowable-plus
 */
public interface ProcessEvent {

    /** 流程实例 ID */
    String getProcessInstanceId();

    /** 事件发生时间 */
    Date getEventTime();
}
