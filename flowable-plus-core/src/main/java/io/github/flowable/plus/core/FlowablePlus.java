package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.ProcessDiagramVO;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import io.github.flowable.plus.core.api.DiagramOperations;
import io.github.flowable.plus.core.api.HistoryOperations;
import io.github.flowable.plus.core.api.QueryOperations;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import io.github.flowable.plus.core.workflow.DiagramWorkflow;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import io.github.flowable.plus.core.workflow.ProcessQueryWorkflow;
import io.github.flowable.plus.core.workflow.TaskQueryModule;
import io.github.flowable.plus.core.workflow.NodePreviewWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Flowable-Plus 统一入口 Façade，负责编排与组合各模块能力。
 *
 * <p>待办/已办查询委托给 {@link TaskQueryModule}，
 * 流程追踪委托给 {@link ProcessQueryWorkflow}，
 * 节点预览委托给 {@link NodePreviewWorkflow}，
 * 流程图委托给 {@link DiagramWorkflow}，
 * 审批历史委托给 {@link HistoryWorkflow}。</p>
 *
 * @author flowable-plus
 */
@Slf4j
public class FlowablePlus implements QueryOperations, DiagramOperations, HistoryOperations {

    private final TaskQueryModule taskQueryModule;
    private final ProcessQueryWorkflow processQueryWorkflow;
    private final NodePreviewWorkflow nodePreviewWorkflow;
    private final DiagramWorkflow diagramWorkflow;
    private final HistoryWorkflow historyWorkflow;

    /**
     * 构造器注入所有依赖。
     *
     * @param taskQueryModule      待办/已办查询模块，不可为 null
     * @param processQueryWorkflow 流程追踪模块，不可为 null
     * @param nodePreviewWorkflow  节点预览模块，不可为 null
     * @param diagramWorkflow      流程图生成模块，不可为 null
     * @param historyWorkflow      审批历史查询模块，不可为 null
     */
    public FlowablePlus(TaskQueryModule taskQueryModule,
                        ProcessQueryWorkflow processQueryWorkflow,
                        NodePreviewWorkflow nodePreviewWorkflow,
                        DiagramWorkflow diagramWorkflow,
                        HistoryWorkflow historyWorkflow) {
        if (taskQueryModule == null) {
            throw new IllegalArgumentException("TaskQueryModule 不可为 null");
        }
        if (processQueryWorkflow == null) {
            throw new IllegalArgumentException("ProcessQueryWorkflow 不可为 null");
        }
        if (nodePreviewWorkflow == null) {
            throw new IllegalArgumentException("NodePreviewWorkflow 不可为 null");
        }
        if (diagramWorkflow == null) {
            throw new IllegalArgumentException("DiagramWorkflow 不可为 null");
        }
        if (historyWorkflow == null) {
            throw new IllegalArgumentException("HistoryWorkflow 不可为 null");
        }
        this.taskQueryModule = taskQueryModule;
        this.processQueryWorkflow = processQueryWorkflow;
        this.nodePreviewWorkflow = nodePreviewWorkflow;
        this.diagramWorkflow = diagramWorkflow;
        this.historyWorkflow = historyWorkflow;
    }

    // ======================== QueryOperations: 待办/已办 (委托给 TaskQueryModule) ========================

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query) {
        return taskQueryModule.queryTodoTasks(userId, query);
    }

    @Override
    public PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query, Consumer<TaskQuery> enhancer) {
        return taskQueryModule.queryTodoTasks(userId, query, enhancer);
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query) {
        return taskQueryModule.queryDoneTasks(userId, query);
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query,
                                                  Consumer<HistoricTaskInstanceQuery> enhancer) {
        return taskQueryModule.queryDoneTasks(userId, query, enhancer);
    }

    // ======================== QueryOperations: 节点预览 (委托给 NodePreviewWorkflow) ========================

    @Override
    public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey) {
        return nodePreviewWorkflow.getNextNodeApproversByProcessKey(processKey);
    }

    @Override
    public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey, Map<String, Object> variables) {
        return nodePreviewWorkflow.getNextNodeApproversByProcessKey(processKey, variables);
    }

    @Override
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId) {
        return nodePreviewWorkflow.getNextTaskApprovers(taskId);
    }

    @Override
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId, String targetNodeId) {
        return nodePreviewWorkflow.getNextTaskApprovers(taskId, targetNodeId);
    }

    @Override
    public List<NextTaskNodeVO> getNextTaskNodes(String processInstanceId, String taskId) {
        return nodePreviewWorkflow.getNextTaskNodes(processInstanceId, taskId);
    }

    // ======================== QueryOperations: 流程追踪 (委托给 ProcessQueryWorkflow) ========================

    @Override
    public Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds) {
        return processQueryWorkflow.batchQueryProcessSummaries(processInstanceIds);
    }

    @Override
    public List<ApprovalTraceVO> getApprovalTrace(String processInstanceId) {
        return processQueryWorkflow.getApprovalTrace(processInstanceId);
    }

    // ======================== DiagramOperations: 流程图 (委托给 DiagramWorkflow) ========================

    @Override
    public ProcessDiagramVO getProcessDiagram(String processInstanceId) {
        return diagramWorkflow.getProcessDiagram(processInstanceId);
    }

    // ======================== HistoryOperations: 审批历史 (委托给 HistoryWorkflow) ========================

    @Override
    public List<ApprovalRecordVO> getApprovalHistory(String processInstanceId) {
        return historyWorkflow.getApprovalHistory(processInstanceId);
    }
}
