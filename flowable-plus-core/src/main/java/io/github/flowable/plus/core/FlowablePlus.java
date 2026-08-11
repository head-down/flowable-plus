package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.ApprovalPersonnelVO;
import io.github.flowable.plus.core.enums.TraversalMode;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import io.github.flowable.plus.core.vo.DiagramStatesVO;
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
import io.github.flowable.plus.core.workflow.PersonnelWorkflow;
import io.github.flowable.plus.core.workflow.ProcessQueryWorkflow;
import io.github.flowable.plus.core.workflow.TaskQueryModule;
import io.github.flowable.plus.core.workflow.NodePreviewWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.task.api.TaskQuery;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Flowable-Plus 查询门面，负责收敛读操作的注入点。
 *
 * <p>只聚合读操作（待办/已办查询、节点预览、流程追踪、流程图、审批历史）。
 * 写操作（发起、同意、驳回、撤回、撤销、会签等）有意不进门面，
 * 请注入对应操作接口：{@link io.github.flowable.plus.core.api.ProcessLifecycleOperations}、
 * {@link io.github.flowable.plus.core.api.TaskExecutionOperations}、
 * {@link io.github.flowable.plus.core.api.CounterSignOperations}。
 * 参见 ADR-0010「门面范围」。</p>
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
    private final PersonnelWorkflow personnelWorkflow;

    /**
     * 构造器注入所有依赖。
     *
     * @param taskQueryModule      待办/已办查询模块，不可为 null
     * @param processQueryWorkflow 流程追踪模块，不可为 null
     * @param nodePreviewWorkflow  节点预览模块，不可为 null
     * @param diagramWorkflow      流程图生成模块，不可为 null
     * @param historyWorkflow      审批历史查询模块，不可为 null
     * @param personnelWorkflow    审批人员查询模块，不可为 null
     */
    public FlowablePlus(TaskQueryModule taskQueryModule,
                        ProcessQueryWorkflow processQueryWorkflow,
                        NodePreviewWorkflow nodePreviewWorkflow,
                        DiagramWorkflow diagramWorkflow,
                        HistoryWorkflow historyWorkflow,
                        PersonnelWorkflow personnelWorkflow) {
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
        if (personnelWorkflow == null) {
            throw new IllegalArgumentException("PersonnelWorkflow 不可为 null");
        }
        this.taskQueryModule = taskQueryModule;
        this.processQueryWorkflow = processQueryWorkflow;
        this.nodePreviewWorkflow = nodePreviewWorkflow;
        this.diagramWorkflow = diagramWorkflow;
        this.historyWorkflow = historyWorkflow;
        this.personnelWorkflow = personnelWorkflow;
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
                                                  Consumer<HistoricProcessInstanceQuery> enhancer) {
        return taskQueryModule.queryDoneTasks(userId, query, enhancer);
    }

    @Override
    public PageResult<DoneTaskVO> queryDoneTasksPrecise(String userId, TaskQueryDTO query) {
        return taskQueryModule.queryDoneTasksPrecise(userId, query);
    }

    // ======================== QueryOperations: 节点预览 (委托给 NodePreviewWorkflow) ========================

    @Override
    public List<NodeApproverVO> getNextNodeApprovers(String processKey, TraversalMode mode) {
        return nodePreviewWorkflow.getNextNodeApprovers(processKey, mode);
    }

    @Override
    public List<NodeApproverVO> getNextNodeApprovers(String processKey, TraversalMode mode,
                                                     Map<String, Object> variables) {
        return nodePreviewWorkflow.getNextNodeApprovers(processKey, mode, variables);
    }

    @Override
    public List<NextTaskNodeVO> getNextTaskNodes(String taskId, TraversalMode mode) {
        return nodePreviewWorkflow.getNextTaskNodes(taskId, mode);
    }

    @Override
    public List<ApproverInfoVO> getNextTaskApprovers(String taskId, TraversalMode mode) {
        return nodePreviewWorkflow.getNextTaskApprovers(taskId, mode);
    }

    // ======================== QueryOperations: 流程追踪 (委托给 ProcessQueryWorkflow) ========================

    @Override
    public ProcessSummaryVO getProcessSummary(String processInstanceId) {
        return processQueryWorkflow.getProcessSummary(processInstanceId);
    }

    @Override
    public Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds) {
        return processQueryWorkflow.batchQueryProcessSummaries(processInstanceIds);
    }

    @Override
    public String getBusinessKeyByProcessInstanceId(String processInstanceId) {
        return processQueryWorkflow.getBusinessKeyByProcessInstanceId(processInstanceId);
    }

    @Override
    public ApprovalPersonnelVO getApprovalPersonnel(String processInstanceId) {
        return personnelWorkflow.getApprovalPersonnel(processInstanceId);
    }

    // ======================== DiagramOperations: 流程图 (委托给 DiagramWorkflow) ========================

    @Override
    public ProcessDiagramVO getProcessDiagramXml(String processDefinitionId) {
        return diagramWorkflow.getProcessDiagramXml(processDefinitionId);
    }

    @Override
    public DiagramStatesVO getProcessDiagramStates(String processInstanceId) {
        return diagramWorkflow.getProcessDiagramStates(processInstanceId);
    }

    // ======================== HistoryOperations: 审批历史 (委托给 HistoryWorkflow) ========================

    @Override
    public List<ApprovalRecordVO> getApprovalHistory(String processInstanceId) {
        return historyWorkflow.getApprovalHistory(processInstanceId);
    }
}
