package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.event.EventBus;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;

/**
 * 流程结束检测器，封装"流程实例是否已结束"的查询逻辑。
 *
 * <p>原先在 TaskWorkflow 和 CounterSignWorkflow 中各有一份完全相同的
 * tryPublishProcessEnded 实现。抽取为独立模块，消除重复。</p>
 *
 * @author flowable-plus
 */
public class ProcessEndDetector {

    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final EventBus eventBus;

    public ProcessEndDetector(RuntimeService runtimeService,
                               HistoryService historyService,
                               EventBus eventBus) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.eventBus = eventBus;
    }

    /**
     * 检测流程实例是否已结束，若已结束则发布 ProcessEndedEvent。
     *
     * @param processInstanceId 流程实例 ID
     */
    public void checkAndPublish(String processInstanceId) {
        if (!eventBus.isEnabled()) {
            return;
        }
        ProcessInstance runtimePi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (runtimePi == null) {
            HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            if (hpi != null) {
                eventBus.processEnded(processInstanceId,
                        hpi.getProcessDefinitionKey(), hpi.getBusinessKey(),
                        hpi.getEndTime());
            }
        }
    }
}
