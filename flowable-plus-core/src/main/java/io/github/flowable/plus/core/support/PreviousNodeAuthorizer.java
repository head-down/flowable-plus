package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.model.NodeFinder;
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
 * <p>内部采用流水线模式：先校验任务结构（查找上一节点），再校验身份（比对审批人）。
 * 对外暴露纯布尔接口，不抛异常，由调用方自行构造拒绝消息。</p>
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
     * <p>流水线校验：</p>
     * <ol>
     *   <li>查询任务，获取流程定义和节点信息</li>
     *   <li>查找上一审批节点</li>
     *   <li>查询上一节点历史审批人</li>
     *   <li>身份比对</li>
     * </ol>
     *
     * <p>任一环节失败均返回 {@code false}，由调用方抛
     * {@link io.github.flowable.plus.core.exception.PermissionDeniedException} 并附操作上下文。</p>
     *
     * @param userId 待校验的用户 ID
     * @param taskId 任务 ID
     * @return true 表示用户具备权限，false 表示不具备
     */
    public boolean isAuthorized(String userId, String taskId) {
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

        if (prevNodes.size() != 1) {
            return false;
        }

        // Step 3: 查询上一节点最后一次完成的历史任务
        String prevNodeId = prevNodes.get(0);
        List<HistoricTaskInstance> prevTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(prevNodeId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(0, 1);

        if (prevTasks.isEmpty()) {
            return false;
        }

        // Step 4: 身份比对
        String prevAssignee = prevTasks.get(0).getAssignee();
        return userId.equals(prevAssignee);
    }
}
