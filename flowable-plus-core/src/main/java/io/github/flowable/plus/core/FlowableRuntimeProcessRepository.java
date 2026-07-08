package io.github.flowable.plus.core;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@link RuntimeProcessRepository} 的 Flowable 适配器。
 *
 * @author flowable-plus
 */
public class FlowableRuntimeProcessRepository implements RuntimeProcessRepository {

    private final RuntimeService runtimeService;

    public FlowableRuntimeProcessRepository(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public List<ProcessInstance> findProcessInstancesByIds(Set<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Collections.emptyList();
        }
        return runtimeService.createProcessInstanceQuery()
                .processInstanceIds(processInstanceIds)
                .list();
    }
}
