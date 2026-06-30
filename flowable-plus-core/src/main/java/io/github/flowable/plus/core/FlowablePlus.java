package io.github.flowable.plus.core;

import org.flowable.engine.ProcessEngine;

/**
 * Flowable-Plus 统一入口类，封装 Flowable 引擎操作，提供增强的中国式审批 API。
 *
 * <p>构造器注入 {@link ProcessEngine}，内部持有对 RuntimeService、TaskService、
 * RepositoryService、HistoryService 的引用。后续所有 Slice 的操作方法均以此类为入口。</p>
 */
public class FlowablePlus {

    private final ProcessEngine processEngine;

    /**
     * 构造器注入 ProcessEngine。
     *
     * @param processEngine Flowable 流程引擎实例，不可为 null
     */
    public FlowablePlus(ProcessEngine processEngine) {
        if (processEngine == null) {
            throw new IllegalArgumentException("ProcessEngine 不可为 null");
        }
        this.processEngine = processEngine;
    }

    /**
     * 获取底层 ProcessEngine 实例。
     */
    public ProcessEngine getProcessEngine() {
        return processEngine;
    }
}
