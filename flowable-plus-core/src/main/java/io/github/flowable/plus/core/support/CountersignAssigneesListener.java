package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.spi.AssigneeResolver;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 会签审批人自动填充 TaskListener。
 *
 * <p>在 MI 节点的 {@code TaskListener(create)} 事件中调用，
 * 检测 {@code assigneeList} 流程变量为空时，从
 * {@link AssigneeResolverRegistry} 获取审批人列表并写入。
 *
 * <p><b>BPMN 配置示例</b>（下游项目按需添加）：</p>
 * <pre>{@code
 * <userTask id="countersignTask" flowable:assignee="${assignee}">
 *   <extensionElements>
 *     <flowable:taskListener event="create"
 *         delegateExpression="${countersignAssigneesListener}" />
 *   </extensionElements>
 *   <multiInstanceLoopCharacteristics isSequential="false"
 *       flowable:collection="${assigneeList}"
 *       flowable:elementVariable="assignee">
 *     <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
 *   </multiInstanceLoopCharacteristics>
 * </userTask>
 * }</pre>
 *
 * <p>仅当 {@code assigneeList} 为空或 null 时才触发 {@link AssigneeResolver} 调用，
 * 已存在的审批人列表不会被覆盖。</p>
 *
 * @author flowable-plus
 */
public class CountersignAssigneesListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(CountersignAssigneesListener.class);

    private static final String VARIABLE_NAME = "assigneeList";

    private final AssigneeResolverRegistry registry;

    public CountersignAssigneesListener(AssigneeResolverRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void notify(DelegateTask delegateTask) {
        Object existing = delegateTask.getVariable(VARIABLE_NAME);
        if (existing instanceof List && !((List<?>) existing).isEmpty()) {
            return;
        }

        String processInstanceId = delegateTask.getProcessInstanceId();
        String taskDefinitionKey = delegateTask.getTaskDefinitionKey();

        if (log.isDebugEnabled()) {
            log.debug("assigneeList is empty, resolving for processInstanceId={}, taskDefinitionKey={}",
                    processInstanceId, taskDefinitionKey);
        }

        List<String> assignees = registry.resolve(processInstanceId, taskDefinitionKey);
        if (assignees != null && !assignees.isEmpty()) {
            delegateTask.setVariable(VARIABLE_NAME, assignees);
            log.debug("Resolved {} assignees for {}: {}", assignees.size(), taskDefinitionKey, assignees);
        } else {
            log.debug("No assignees resolved for {}, assigneeList remains empty", taskDefinitionKey);
        }
    }
}
