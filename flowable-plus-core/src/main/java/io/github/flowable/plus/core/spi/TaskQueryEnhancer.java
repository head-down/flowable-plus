package io.github.flowable.plus.core.spi;

import org.flowable.task.api.TaskQuery;

/**
 * 待办/已办查询自定义过滤条件回调。
 *
 * <p>调用方在此回调中直接调用 Flowable {@link TaskQuery} 的 processVariableValueXXX 方法
 * 追加过滤条件。前提是业务数据必须作为 process variables 存储在流程实例中。</p>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface TaskQueryEnhancer {

    /**
     * 在引擎查询上追加自定义过滤条件。
     *
     * @param query Flowable TaskQuery 查询对象
     */
    void enhance(TaskQuery query);
}
