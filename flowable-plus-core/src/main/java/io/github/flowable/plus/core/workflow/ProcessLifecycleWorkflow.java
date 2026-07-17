package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.api.ProcessLifecycleOperations;
import io.github.flowable.plus.core.domain.PlusHistoricProcessInstance;
import io.github.flowable.plus.core.domain.PlusProcessInstance;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.event.ProcessEndedEvent;
import io.github.flowable.plus.core.event.ProcessRevokedEvent;
import io.github.flowable.plus.core.event.ProcessStartedEvent;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.spi.AutoApprovalRule;
import io.github.flowable.plus.core.spi.UserContext;
import cn.hutool.core.util.StrUtil;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流程生命周期工作流模块，封装流程发起与撤销逻辑。
 *
 * @author flowable-plus
 */
public class ProcessLifecycleWorkflow implements ProcessLifecycleOperations {

    private static final Logger log = LoggerFactory.getLogger(ProcessLifecycleWorkflow.class);

    private final UserContext userContext;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;
    private final NodeFinder nodeFinder;
    private final List<AutoApprovalRule> autoApprovalRules;
    private final EventPublisher eventPublisher;

    public ProcessLifecycleWorkflow(UserContext userContext, TaskService taskService,
                                     HistoryService historyService, RuntimeService runtimeService,
                                     IdentityService identityService, NodeFinder nodeFinder,
                                     List<AutoApprovalRule> autoApprovalRules,
                                     EventPublisher eventPublisher) {
        this.userContext = userContext;
        this.taskService = taskService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
        this.nodeFinder = nodeFinder;
        this.autoApprovalRules = autoApprovalRules != null ? autoApprovalRules : Collections.emptyList();
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlusProcessInstance startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        if (processDefinitionKey == null) {
            throw new IllegalArgumentException("processDefinitionKey 不可为 null");
        }

        String userId = userContext.getCurrentUserId();
        identityService.setAuthenticatedUserId(userId);
        try {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            PlusProcessInstance result = PlusProcessInstance.from(pi);

            if (eventPublisher != null) {
                eventPublisher.publish(ProcessStartedEvent.of(processDefinitionKey, businessKey,
                        result.getProcessInstanceId(), userId, new java.util.Date()));
            }

            // 自动提交：发起人身份下执行，仅一层。采用快速失败模式，异常正常传播
            if (!autoApprovalRules.isEmpty()) {
                autoCompleteFirstTasks(result.getProcessInstanceId(), userId, variables);
            }

            return result;
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }

    @Override
    public void revokeProcess(String processInstanceId, String reason) {
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId 不可为 null");
        }

        String currentUserId = userContext.getCurrentUserId();

        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi == null) {
            throw new NotFoundException("流程实例 " + processInstanceId + " 不存在");
        }
        PlusHistoricProcessInstance historicPi = PlusHistoricProcessInstance.from(hpi);

        if (!currentUserId.equals(historicPi.getStartUserId())) {
            throw new PermissionDeniedException(
                    "用户 " + currentUserId + " 不是流程实例 " + processInstanceId + " 的发起人，无权撤销");
        }

        ProcessInstance runtimePi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (runtimePi == null) {
            throw new TaskAlreadyCompletedException(
                    "流程实例 " + processInstanceId + " 已结束，无法撤销");
        }

        String initiatorNode = nodeFinder.findInitiatorNode(historicPi.getProcessDefinitionId());
        Task activeTaskObj = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().singleResult();
        PlusTask activeTask = activeTaskObj != null ? PlusTask.from(activeTaskObj) : null;
        if (activeTask == null || !initiatorNode.equals(activeTask.getTaskDefinitionKey())) {
            throw new TaskAlreadyCompletedException(
                    "流程实例 " + processInstanceId + " 已推进后续节点，无法撤销");
        }

        String commentText = StrUtil.isNotBlank(reason) ? reason : "撤销流程实例";
        taskService.addComment(activeTask.getId(), processInstanceId, CommentType.REVOKE.name(), commentText);

        runtimeService.deleteProcessInstance(processInstanceId, reason);

        if (eventPublisher != null) {
            eventPublisher.publish(ProcessRevokedEvent.of(processInstanceId,
                    historicPi.getProcessDefinitionKey(), historicPi.getBusinessKey(),
                    currentUserId, reason, new java.util.Date()));
            // 撤销后流程已确定结束
            eventPublisher.publish(ProcessEndedEvent.of(processInstanceId,
                    historicPi.getProcessDefinitionKey(), historicPi.getBusinessKey(),
                    new java.util.Date()));
        }
    }

    // ======================== 内部辅助 ========================

    /**
     * 自动完成发起人的首审批任务。
     *
     * <p>双重守卫：
     * <ul>
     *   <li>{@code isFirstStart} — 历史任务为空才触发，防止重新触发时误动提交</li>
     *   <li>快照隔离 — 仅处理当前活跃任务快照，自动完成后新产生的任务不级联</li>
     * </ul>
     * 多规则 OR 逻辑：任一规则返回非 null 即触发自动提交，意见取第一个非 null 结果。
     * </p>
     */
    private void autoCompleteFirstTasks(String processInstanceId, String userId,
                                         Map<String, Object> startVariables) {
        // 守卫 1：非首次启动不触发
        long historyCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).count();
        if (historyCount > 0) {
            return;
        }

        // 守卫 2：快照当前首任务
        List<Task> taskList = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        List<PlusTask> firstTasks = taskList.stream().map(PlusTask::from).collect(Collectors.toList());
        if (firstTasks.isEmpty()) {
            return;
        }

        Map<String, Object> readonlyVars = Collections.unmodifiableMap(startVariables);

        for (PlusTask task : firstTasks) {
            for (AutoApprovalRule rule : autoApprovalRules) {
                String evalResult = rule.evaluate(task, readonlyVars);
                if (evalResult != null) {
                    taskService.addComment(task.getId(), processInstanceId, CommentType.AUTO_COMPLETE.name(), evalResult);
                    taskService.complete(task.getId(), null);
                    log.info("自动提交成功, taskId: {}, assignee: {}, comment: {}",
                            task.getId(), userId, evalResult);
                    break; // 当前任务已被处理，跳出规则循环
                }
            }
        }
    }
}
