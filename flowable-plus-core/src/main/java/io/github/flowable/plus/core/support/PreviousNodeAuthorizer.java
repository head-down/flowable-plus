package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.exception.AmbiguousPreviousNodeException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.strategy.PreviousNodeResolutionStrategy;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;


import java.util.List;

/**
 * 上一节点审批人授权器，封装"上一节点审批人身份校验"的流水线查询逻辑。
 *
 * <p>在 {@link io.github.flowable.plus.core.workflow.TaskExecutionWorkflow#withdrawTask}
 * 和 {@link io.github.flowable.plus.core.workflow.CounterSignWorkflow#addCounterSigner}
 * / {@link io.github.flowable.plus.core.workflow.CounterSignWorkflow#removeCounterSigner}
 * 中替代原有的内联权限校验，消除重复。</p>
 *
 * <p>内部采用流水线模式：先校验任务结构（查找上一节点），再校验身份（比对审批人）。</p>
 *
 * <p>当 {@code findPreviousNodes} 返回多个候选节点时（如并行网关汇合），
 * 基础方法 {@link #isAuthorized(String, String)} 会抛出
 * {@link AmbiguousPreviousNodeException}，调用方应使用
 * {@link #isAuthorized(String, String, PreviousNodeResolutionStrategy)}
 * 提供节点选择策略。身份不匹配时返回 {@code false}，由调用方自行构造拒绝消息。</p>
 *
 * @author flowable-plus
 */
public class PreviousNodeAuthorizer {

    private final TaskService taskService;
    private final HistoryService historyService;
    private final NodeFinder nodeFinder;

    public PreviousNodeAuthorizer(TaskService taskService, HistoryService historyService,
                                   NodeFinder nodeFinder) {
        this.taskService = taskService;
        this.historyService = historyService;
        this.nodeFinder = nodeFinder;
    }

    /**
     * 校验当前用户是否具备基于上一节点审批人的操作权限。
     *
     * <p>等价于 {@code isAuthorized(userId, taskId, null)}。
     * 当 {@code findPreviousNodes} 返回多个候选节点时抛出
     * {@link AmbiguousPreviousNodeException}。</p>
     *
     * @param userId 待校验的用户 ID
     * @param taskId 任务 ID
     * @return true 表示用户具备权限，false 表示不具备
     * @throws AmbiguousPreviousNodeException 前置节点存在多个，无法唯一确定
     */
    public boolean isAuthorized(String userId, String taskId) {
        return isAuthorized(userId, taskId, null);
    }

    /**
     * 带节点选择策略的权限校验。
     *
     * <p>流水线校验：</p>
     * <ol>
     *   <li>查询任务，获取流程定义和节点信息</li>
     *   <li>查找上一审批节点</li>
     *   <li>若存在多个前置节点，使用 strategy 选择目标节点</li>
     *   <li>查询上一节点历史审批人</li>
     *   <li>身份比对</li>
     * </ol>
     *
     * <p>strategy 为 {@code null} 且多节点时抛出 {@link AmbiguousPreviousNodeException}。</p>
     *
     * @param userId   待校验的用户 ID
     * @param taskId   任务 ID
     * @param strategy 多候选节点时的选择策略，为 null 时等价于
     *                 {@link #isAuthorized(String, String)}
     * @return true 表示用户具备权限，false 表示不具备
     * @throws AmbiguousPreviousNodeException strategy 为 null 且前置节点存在多个
     */
    public boolean isAuthorized(String userId, String taskId,
                                 PreviousNodeResolutionStrategy strategy) {
        // Step 1: 查询任务
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            return false;
        }

        String processInstanceId = task.getProcessInstanceId();
        String processDefinitionId = task.getProcessDefinitionId();
        String taskDefinitionKey = task.getTaskDefinitionKey();

        // Step 2: 查找上一节点
        List<String> prevNodes;
        try {
            prevNodes = nodeFinder.findPreviousNodes(processDefinitionId, taskDefinitionKey, processInstanceId);
        } catch (NoPreviousNodeException e) {
            return false;
        }

        // Step 3: 多节点处理
        String prevNodeId;
        if (prevNodes.size() > 1) {
            if (strategy == null) {
                throw new AmbiguousPreviousNodeException(
                        "当前节点的前置节点存在多个（" + prevNodes + "），无法确定唯一上一节点");
            }
            prevNodeId = strategy.resolve(prevNodes, processInstanceId, historyService);
            if (prevNodeId == null) {
                throw new AmbiguousPreviousNodeException(
                        "节点选择策略未能从多个前置节点中确定目标节点: " + prevNodes);
            }
        } else {
            prevNodeId = prevNodes.get(0);
        }

        // Step 4: 查询上一节点最后一次完成的历史任务
        List<HistoricTaskInstance> prevTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(prevNodeId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1);

        if (prevTasks.isEmpty()) {
            return false;
        }

        // Step 5: 身份比对
        String prevAssignee = prevTasks.get(0).getAssignee();
        return userId.equals(prevAssignee);
    }
}
