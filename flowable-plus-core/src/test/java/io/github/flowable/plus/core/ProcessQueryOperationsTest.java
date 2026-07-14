package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.vo.ApprovalTraceVO;
import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * batchQueryProcessSummaries 测试：基于 ProcessQueryWorkflow，
 * 通过 TaskService 和 HistoryService 接缝访问数据。
 */
public class ProcessQueryOperationsTest {

    private RuntimeService mockRuntimeService;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private MultiInstanceDetector mockMultiInstanceDetector;
    private ProcessInstanceQuery mockProcessInstanceQuery;
    private ProcessQueryWorkflow processQueryWorkflow;

    @BeforeEach
    public void setUp() {
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
        mockProcessInstanceQuery = mock(ProcessInstanceQuery.class);

        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockProcessInstanceQuery);
        when(mockProcessInstanceQuery.processInstanceIds(anySet())).thenReturn(mockProcessInstanceQuery);

        processQueryWorkflow = new ProcessQueryWorkflow(
                mockRuntimeService, mockTaskService, mockHistoryService, mockMultiInstanceDetector);
    }

    // ======================== 参数校验 ========================

    @Test
    public void testRejectNullIds() {
        assertThatThrownBy(() -> processQueryWorkflow.batchQueryProcessSummaries(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceIds");
    }

    @Test
    public void testRejectEmptyIds() {
        assertThatThrownBy(() -> processQueryWorkflow.batchQueryProcessSummaries(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceIds");
    }

    @Test
    public void testEmptyResult() {
        stubRunningQueries(Collections.emptyList(), Collections.emptyList());

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList("pi-nonexistent"));

        assertThat(result).isEmpty();
    }

    // ======================== 运行中实例 ========================

    @Test
    public void testRunningInstance() {
        String instanceId = "pi-running";
        ProcessInstance pi = createMockProcessInstance(instanceId, "biz-001", "leave", "请假审批",
                "userA", new Date());
        when(pi.isSuspended()).thenReturn(false);

        PlusTask task = createPlusTask("task-001", instanceId, "task1", "部门审批", "reviewer1");
        stubRunningQueries(Collections.singletonList(pi), Collections.singletonList(task));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList(instanceId));

        ProcessSummaryVO vo = result.get(instanceId);
        assertThat(vo.getInstanceId()).isEqualTo(instanceId);
        assertThat(vo.getBusinessKey()).isEqualTo("biz-001");
        assertThat(vo.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(vo.getProcessDefinitionName()).isEqualTo("请假审批");
        assertThat(vo.getStartUserId()).isEqualTo("userA");
        assertThat(vo.getCurrentTaskId()).isEqualTo("task-001");
        assertThat(vo.getCurrentTaskName()).isEqualTo("部门审批");
        assertThat(vo.getCurrentNodeId()).isEqualTo("task1");
        assertThat(vo.getIsEnded()).isFalse();
        assertThat(vo.getSuspendState()).isEqualTo(1);
        assertThat(vo.getEndReason()).isNull();
        assertThat(vo.getEndTime()).isNull();
        assertThat(vo.getActiveAssignees()).hasSize(1);
        assertThat(vo.getActiveAssignees().get(0).getUserId()).isEqualTo("reviewer1");
        assertThat(vo.getActiveAssignees().get(0).getTaskId()).isEqualTo("task-001");
    }

    // ======================== 挂起实例 ========================

    @Test
    public void testSuspendedInstance() {
        String instanceId = "pi-suspended";
        ProcessInstance pi = createMockProcessInstance(instanceId, "biz-002", "leave", "请假审批",
                "userA", new Date());
        when(pi.isSuspended()).thenReturn(true);

        PlusTask task = createPlusTask("task-002", instanceId, "task1", "部门审批", "reviewer1");
        stubRunningQueries(Collections.singletonList(pi), Collections.singletonList(task));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList(instanceId));

        ProcessSummaryVO vo = result.get(instanceId);
        assertThat(vo.getIsEnded()).isFalse();
        assertThat(vo.getSuspendState()).isEqualTo(2);
    }

    // ======================== 多实例任务 ========================

    @Test
    public void testMultiInstanceTask() {
        String instanceId = "pi-mi";
        ProcessInstance pi = createMockProcessInstance(instanceId, "biz-mi", "leave", "请假审批",
                "userA", new Date());
        when(pi.isSuspended()).thenReturn(false);

        PlusTask task1 = createPlusTask("task-a", instanceId, "task1", "会签审批", "reviewer1");
        PlusTask task2 = createPlusTask("task-b", instanceId, "task1", "会签审批", "reviewer2");
        PlusTask task3 = createPlusTask("task-c", instanceId, "task1", "会签审批", "reviewer3");
        stubRunningQueries(Collections.singletonList(pi),
                Arrays.asList(task1, task2, task3));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList(instanceId));

        ProcessSummaryVO vo = result.get(instanceId);
        assertThat(vo.getCurrentTaskId()).isEqualTo("task-a");
        assertThat(vo.getCurrentTaskName()).isEqualTo("会签审批");
        assertThat(vo.getActiveAssignees()).hasSize(3);
        assertThat(vo.getActiveAssignees().get(0).getUserId()).isEqualTo("reviewer1");
        assertThat(vo.getActiveAssignees().get(0).getTaskId()).isEqualTo("task-a");
        assertThat(vo.getActiveAssignees().get(1).getUserId()).isEqualTo("reviewer2");
        assertThat(vo.getActiveAssignees().get(1).getTaskId()).isEqualTo("task-b");
        assertThat(vo.getActiveAssignees().get(2).getUserId()).isEqualTo("reviewer3");
        assertThat(vo.getActiveAssignees().get(2).getTaskId()).isEqualTo("task-c");
    }

    // ======================== 已结束实例 ========================

    @Test
    public void testEndedInstance() {
        String instanceId = "pi-ended";
        Date startTime = new Date(100000);
        Date endTime = new Date(200000);

        PlusHistoricProcessInstance hpi = createPlusHistoricPi(instanceId, "biz-ended", "leave",
                "请假审批", "userA", startTime, endTime, null);

        stubEndedQueries(Collections.emptyList(), Collections.singletonList(hpi));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList(instanceId));

        ProcessSummaryVO vo = result.get(instanceId);
        assertThat(vo.getInstanceId()).isEqualTo(instanceId);
        assertThat(vo.getBusinessKey()).isEqualTo("biz-ended");
        assertThat(vo.getIsEnded()).isTrue();
        assertThat(vo.getEndTime()).isNotNull();
        assertThat(vo.getEndReason()).isNull();
        assertThat(vo.getCurrentTaskId()).isNull();
        assertThat(vo.getCurrentTaskName()).isNull();
        assertThat(vo.getActiveAssignees()).isEmpty();
    }

    // ======================== 撤销的实例 ========================

    @Test
    public void testRevokedInstance() {
        String instanceId = "pi-revoked";
        Date now = new Date();
        PlusHistoricProcessInstance hpi = createPlusHistoricPi(instanceId, "biz-revoked", "leave",
                "请假审批", "userA", now, now, "发起人撤销流程");

        stubEndedQueries(Collections.emptyList(), Collections.singletonList(hpi));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList(instanceId));

        ProcessSummaryVO vo = result.get(instanceId);
        assertThat(vo.getIsEnded()).isTrue();
        assertThat(vo.getEndReason()).isEqualTo("发起人撤销流程");
    }

    // ======================== 批量混合查询 ========================

    @Test
    public void testMixedInstances() {
        String runningId = "pi-running";
        String endedId = "pi-ended";
        String notFoundId = "pi-nonexistent";

        // 运行中实例
        ProcessInstance pi = createMockProcessInstance(runningId, "biz-r", "leave", "请假审批",
                "userA", new Date());
        when(pi.isSuspended()).thenReturn(false);
        PlusTask task = createPlusTask("task-r", runningId, "task1", "部门审批", "reviewer1");
        stubRunningQueries(Collections.singletonList(pi), Collections.singletonList(task));

        // 已结束实例
        Date now = new Date();
        PlusHistoricProcessInstance hpi = createPlusHistoricPi(endedId, "biz-e", "leave",
                "请假审批", "userB", now, now, null);
        stubEndedQueries(Collections.singletonList(pi), Collections.singletonList(hpi));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Arrays.asList(runningId, endedId, notFoundId));

        assertThat(result).containsKey(runningId);
        assertThat(result.get(runningId).getIsEnded()).isFalse();

        assertThat(result).containsKey(endedId);
        assertThat(result.get(endedId).getIsEnded()).isTrue();

        assertThat(result).doesNotContainKey(notFoundId);
        assertThat(result).hasSize(2);
    }

    // ======================== 返回顺序 ========================

    @Test
    public void testReturnOrderMatchesInput() {
        String instanceId1 = "pi-001";
        String instanceId2 = "pi-002";
        String instanceId3 = "pi-003";

        ProcessInstance pi1 = createMockProcessInstance(instanceId1, "biz-1", "l", "请假", "u1", new Date());
        ProcessInstance pi2 = createMockProcessInstance(instanceId2, "biz-2", "r", "报销", "u2", new Date());
        ProcessInstance pi3 = createMockProcessInstance(instanceId3, "biz-3", "l", "请假", "u3", new Date());
        when(pi1.isSuspended()).thenReturn(false);
        when(pi2.isSuspended()).thenReturn(false);
        when(pi3.isSuspended()).thenReturn(false);

        PlusTask t1 = createPlusTask("t1", instanceId1, "n1", "节点1", "r1");
        PlusTask t2 = createPlusTask("t2", instanceId2, "n2", "节点2", "r2");
        PlusTask t3 = createPlusTask("t3", instanceId3, "n3", "节点3", "r3");
        stubRunningQueries(Arrays.asList(pi1, pi2, pi3), Arrays.asList(t1, t2, t3));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Arrays.asList(instanceId1, instanceId2, instanceId3));

        assertThat(result).isInstanceOf(LinkedHashMap.class);
        assertThat(new ArrayList<>(result.keySet()))
                .containsExactly(instanceId1, instanceId2, instanceId3);
    }

    // ======================== 运行时无活跃任务 ========================

    @Test
    public void testRunningInstanceNoActiveTask() {
        String instanceId = "pi-no-task";
        ProcessInstance pi = createMockProcessInstance(instanceId, "biz-n", "leave", "请假审批",
                "userA", new Date());
        when(pi.isSuspended()).thenReturn(false);

        stubRunningQueries(Collections.singletonList(pi), Collections.emptyList());

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList(instanceId));

        ProcessSummaryVO vo = result.get(instanceId);
        assertThat(vo.getIsEnded()).isFalse();
        assertThat(vo.getCurrentTaskId()).isNull();
        assertThat(vo.getCurrentTaskName()).isNull();
        assertThat(vo.getCurrentNodeId()).isNull();
        assertThat(vo.getActiveAssignees()).isEmpty();
    }

    // ======================== 审批轨迹 ========================

    @Test
    public void testApprovalTraceNotFound() {
        List<HistoricTaskInstance> emptyHistTasks = Collections.emptyList();
        stubApprovalTraceQueries(Collections.emptyList(), "pi-nonexistent", null, emptyHistTasks);

        assertThatThrownBy(() -> processQueryWorkflow.getApprovalTrace("pi-nonexistent"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("pi-nonexistent");
    }

    @Test
    public void testApprovalTraceBasic() {
        String instanceId = "pi-basic";
        Date t1Start = new Date(1000);
        Date t1End = new Date(2000);
        Date t2Start = new Date(3000);
        Date t2End = new Date(4000);

        PlusHistoricTask ht1 = createPlusHistoricTask("ht-1", instanceId, "node1", "部门审批",
                "user1", t1Start, t1End, null);
        PlusHistoricTask ht2 = createPlusHistoricTask("ht-2", instanceId, "node2", "经理审批",
                "user2", t2Start, t2End, null);

        HistoricTaskInstance mockHt1 = toMockHistoricTask(ht1);
        HistoricTaskInstance mockHt2 = toMockHistoricTask(ht2);
        stubApprovalTraceQueries(Collections.emptyList(), instanceId, null,
                Arrays.asList(mockHt1, mockHt2));
        when(mockTaskService.getProcessInstanceComments(instanceId)).thenReturn(Collections.emptyList());

        List<ApprovalTraceVO> result = processQueryWorkflow.getApprovalTrace(instanceId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTaskId()).isEqualTo("ht-1");
        assertThat(result.get(0).getTaskName()).isEqualTo("部门审批");
        assertThat(result.get(0).getNodeId()).isEqualTo("node1");
        assertThat(result.get(0).getAssignee()).isEqualTo("user1");
        assertThat(result.get(0).getStartTime()).isEqualTo(t1Start);
        assertThat(result.get(0).getEndTime()).isEqualTo(t1End);
        assertThat(result.get(0).getDurationMillis()).isEqualTo(1000L);
        assertThat(result.get(0).getApproved()).isTrue();
        assertThat(result.get(0).getIsRejected()).isFalse();
        assertThat(result.get(1).getTaskId()).isEqualTo("ht-2");
        assertThat(result.get(1).getTaskName()).isEqualTo("经理审批");
        assertThat(result.get(1).getNodeId()).isEqualTo("node2");
        assertThat(result.get(1).getDurationMillis()).isEqualTo(1000L);
    }

    @Test
    public void testApprovalTraceWithActiveTask() {
        String instanceId = "pi-active";
        Date t1Start = new Date(1000);
        Date t1End = new Date(2000);
        Date t2Start = new Date(3000);

        PlusHistoricTask ht1 = createPlusHistoricTask("ht-1", instanceId, "node1", "部门审批",
                "user1", t1Start, t1End, null);
        PlusTask activeTask = new PlusTask("task-active", "leave:1:abc", "node2", instanceId,
                "user2", "经理审批", "exec-2", t2Start);

        HistoricTaskInstance mockHt1 = toMockHistoricTask(ht1);
        stubApprovalTraceQueries(Collections.singletonList(toMockTask(activeTask)), instanceId, null,
                Collections.singletonList(mockHt1));
        when(mockTaskService.getProcessInstanceComments(instanceId)).thenReturn(Collections.emptyList());

        List<ApprovalTraceVO> result = processQueryWorkflow.getApprovalTrace(instanceId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEndTime()).isNotNull();
        assertThat(result.get(0).getApproved()).isTrue();
        assertThat(result.get(1).getTaskId()).isEqualTo("task-active");
        assertThat(result.get(1).getEndTime()).isNull();
        assertThat(result.get(1).getDurationMillis()).isNull();
        assertThat(result.get(1).getApproved()).isNull();
        assertThat(result.get(1).getIsRejected()).isNull();
    }

    @Test
    public void testApprovalTraceRejectedStatus() {
        String instanceId = "pi-reject";
        Date t1Start = new Date(1000);
        Date t1End = new Date(2000);

        PlusHistoricTask ht1 = createPlusHistoricTask("ht-1", instanceId, "node1", "部门审批",
                "user1", t1Start, t1End, "驳回至发起人");
        PlusHistoricTask ht2 = createPlusHistoricTask("ht-2", instanceId, "node1", "部门审批",
                "user2", t1Start, t1End, null);

        HistoricTaskInstance mockHt1 = toMockHistoricTask(ht1);
        HistoricTaskInstance mockHt2 = toMockHistoricTask(ht2);
        stubApprovalTraceQueries(Collections.emptyList(), instanceId, null,
                Arrays.asList(mockHt1, mockHt2));
        when(mockTaskService.getProcessInstanceComments(instanceId)).thenReturn(Collections.emptyList());

        List<ApprovalTraceVO> result = processQueryWorkflow.getApprovalTrace(instanceId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getApproved()).isFalse();
        assertThat(result.get(0).getIsRejected()).isTrue();
        assertThat(result.get(1).getApproved()).isTrue();
        assertThat(result.get(1).getIsRejected()).isFalse();
    }

    @Test
    public void testApprovalTraceWithComment() {
        String instanceId = "pi-comment";
        Date t1Start = new Date(1000);
        Date t1End = new Date(2000);

        PlusHistoricTask ht1 = createPlusHistoricTask("ht-1", instanceId, "node1", "部门审批",
                "user1", t1Start, t1End, null);

        Comment comment = mock(Comment.class);
        when(comment.getTaskId()).thenReturn("ht-1");
        when(comment.getFullMessage()).thenReturn("同意，请继续");

        HistoricTaskInstance mockHt1 = toMockHistoricTask(ht1);
        stubApprovalTraceQueries(Collections.emptyList(), instanceId, null,
                Collections.singletonList(mockHt1));
        when(mockTaskService.getProcessInstanceComments(instanceId)).thenReturn(Collections.singletonList(comment));

        List<ApprovalTraceVO> result = processQueryWorkflow.getApprovalTrace(instanceId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getComment()).isEqualTo("同意，请继续");
    }

    @Test
    public void testApprovalTraceEmptyTasks() {
        String instanceId = "pi-no-tasks";

        PlusHistoricProcessInstance hpi = new PlusHistoricProcessInstance(
                instanceId, "biz-1", "leave:1:abc", "leave", "请假审批",
                "userA", new Date(), new Date(), null);

        stubApprovalTraceQueries(Collections.emptyList(), instanceId, toMockHistoricPi(hpi), null);

        List<ApprovalTraceVO> result = processQueryWorkflow.getApprovalTrace(instanceId);

        assertThat(result).isEmpty();
    }

    @Test
    public void testApprovalTraceWithdrawStatus() {
        String instanceId = "pi-withdraw";
        Date t1Start = new Date(1000);
        Date t1End = new Date(2000);

        PlusHistoricTask ht1 = createPlusHistoricTask("ht-1", instanceId, "node1", "部门审批",
                "user1", t1Start, t1End, "WITHDRAW");

        HistoricTaskInstance mockHt1 = toMockHistoricTask(ht1);
        stubApprovalTraceQueries(Collections.emptyList(), instanceId, null,
                Collections.singletonList(mockHt1));
        when(mockTaskService.getProcessInstanceComments(instanceId)).thenReturn(Collections.emptyList());

        List<ApprovalTraceVO> result = processQueryWorkflow.getApprovalTrace(instanceId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getApproved()).isFalse();
        assertThat(result.get(0).getIsRejected()).isTrue();
    }

    @Test
    public void testApprovalTraceCounterSign() {
        String instanceId = "pi-countersign";
        Date tStart = new Date(1000);

        PlusHistoricTask ht1 = createPlusHistoricTask("ht-cs-1", instanceId, "counterSignNode", "会签审批",
                "userA", tStart, new Date(2000), null);
        PlusHistoricTask ht2 = createPlusHistoricTask("ht-cs-2", instanceId, "counterSignNode", "会签审批",
                "userB", new Date(1100), new Date(2100), null);
        PlusHistoricTask ht3 = createPlusHistoricTask("ht-cs-3", instanceId, "counterSignNode", "会签审批",
                "userC", new Date(1200), new Date(2200), null);

        HistoricTaskInstance mockHt1 = toMockHistoricTask(ht1);
        HistoricTaskInstance mockHt2 = toMockHistoricTask(ht2);
        HistoricTaskInstance mockHt3 = toMockHistoricTask(ht3);

        stubApprovalTraceQueries(Collections.emptyList(), instanceId, null,
                Arrays.asList(mockHt1, mockHt2, mockHt3));
        when(mockTaskService.getProcessInstanceComments(instanceId)).thenReturn(Collections.emptyList());
        when(mockMultiInstanceDetector.isMultiInstanceNode(anyString(), anyString())).thenReturn(true);

        List<ApprovalTraceVO> result = processQueryWorkflow.getApprovalTrace(instanceId);

        assertThat(result).hasSize(1);
        ApprovalTraceVO parent = result.get(0);
        assertThat(parent.getNodeId()).isEqualTo("counterSignNode");
        assertThat(parent.getTaskName()).isEqualTo("会签审批");
        assertThat(parent.getAssignee()).isNull();
        assertThat(parent.getApproved()).isTrue();
        assertThat(parent.getIsRejected()).isFalse();
        assertThat(parent.getCountersignDetails()).hasSize(3);
        assertThat(parent.getCountersignDetails().get(0).getAssignee()).isEqualTo("userA");
        assertThat(parent.getCountersignDetails().get(1).getAssignee()).isEqualTo("userB");
        assertThat(parent.getCountersignDetails().get(2).getAssignee()).isEqualTo("userC");
    }

    // ======================== Test Helpers ========================

    private ProcessInstance createMockProcessInstance(String instanceId, String businessKey,
            String procDefKey, String procDefName, String startUserId, Date startTime) {
        ProcessInstance pi = mock(ProcessInstance.class);
        when(pi.getProcessInstanceId()).thenReturn(instanceId);
        when(pi.getBusinessKey()).thenReturn(businessKey);
        when(pi.getProcessDefinitionKey()).thenReturn(procDefKey);
        when(pi.getProcessDefinitionName()).thenReturn(procDefName);
        when(pi.getStartUserId()).thenReturn(startUserId);
        when(pi.getStartTime()).thenReturn(startTime);
        return pi;
    }

    private PlusTask createPlusTask(String taskId, String processInstanceId,
            String taskDefKey, String taskName, String assignee) {
        return new PlusTask(taskId, "leave:1:abc123", taskDefKey, processInstanceId,
                assignee, taskName, "exec-" + taskId, new Date());
    }

    private PlusHistoricProcessInstance createPlusHistoricPi(String instanceId, String businessKey,
            String procDefKey, String procDefName, String startUserId,
            Date startTime, Date endTime, String deleteReason) {
        return new PlusHistoricProcessInstance(instanceId, businessKey, "leave:1:abc123",
                procDefKey, procDefName, startUserId, startTime, endTime, deleteReason);
    }

    private PlusHistoricTask createPlusHistoricTask(String taskId, String processInstanceId,
            String taskDefKey, String taskName, String assignee,
            Date createTime, Date endTime, String deleteReason) {
        return new PlusHistoricTask(taskId, "leave:1:abc123", taskDefKey, processInstanceId,
                assignee, taskName, createTime, endTime, deleteReason);
    }

    private Task toMockTask(PlusTask pt) {
        Task mockTask = mock(Task.class);
        when(mockTask.getId()).thenReturn(pt.getId());
        when(mockTask.getProcessDefinitionId()).thenReturn(pt.getProcessDefinitionId());
        when(mockTask.getTaskDefinitionKey()).thenReturn(pt.getTaskDefinitionKey());
        when(mockTask.getProcessInstanceId()).thenReturn(pt.getProcessInstanceId());
        when(mockTask.getAssignee()).thenReturn(pt.getAssignee());
        when(mockTask.getName()).thenReturn(pt.getName());
        when(mockTask.getExecutionId()).thenReturn(pt.getExecutionId());
        when(mockTask.getCreateTime()).thenReturn(pt.getCreateTime());
        return mockTask;
    }

    private HistoricTaskInstance toMockHistoricTask(PlusHistoricTask hpt) {
        HistoricTaskInstance mockTask = mock(HistoricTaskInstance.class);
        when(mockTask.getId()).thenReturn(hpt.getId());
        when(mockTask.getProcessDefinitionId()).thenReturn(hpt.getProcessDefinitionId());
        when(mockTask.getTaskDefinitionKey()).thenReturn(hpt.getTaskDefinitionKey());
        when(mockTask.getProcessInstanceId()).thenReturn(hpt.getProcessInstanceId());
        when(mockTask.getAssignee()).thenReturn(hpt.getAssignee());
        when(mockTask.getName()).thenReturn(hpt.getName());
        when(mockTask.getCreateTime()).thenReturn(hpt.getCreateTime());
        when(mockTask.getEndTime()).thenReturn(hpt.getEndTime());
        when(mockTask.getDeleteReason()).thenReturn(hpt.getDeleteReason());
        return mockTask;
    }

    private HistoricProcessInstance toMockHistoricPi(PlusHistoricProcessInstance hpi) {
        HistoricProcessInstance mockHpi = mock(HistoricProcessInstance.class);
        when(mockHpi.getId()).thenReturn(hpi.getId());
        when(mockHpi.getBusinessKey()).thenReturn(hpi.getBusinessKey());
        when(mockHpi.getProcessDefinitionId()).thenReturn(hpi.getProcessDefinitionId());
        when(mockHpi.getProcessDefinitionKey()).thenReturn(hpi.getProcessDefinitionKey());
        when(mockHpi.getProcessDefinitionName()).thenReturn(hpi.getProcessDefinitionName());
        when(mockHpi.getStartUserId()).thenReturn(hpi.getStartUserId());
        when(mockHpi.getStartTime()).thenReturn(hpi.getStartTime());
        when(mockHpi.getEndTime()).thenReturn(hpi.getEndTime());
        when(mockHpi.getDeleteReason()).thenReturn(hpi.getDeleteReason());
        return mockHpi;
    }

    private void stubRunningQueries(
            List<ProcessInstance> runtimeInstances,
            List<PlusTask> activePlusTasks) {
        when(mockProcessInstanceQuery.list()).thenReturn(runtimeInstances);

        List<Task> mockTasks = new ArrayList<>();
        for (PlusTask pt : activePlusTasks) {
            mockTasks.add(toMockTask(pt));
        }
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceIdIn(anyCollection())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(mockTasks);

        // Also stub HistoricProcessInstanceQuery for ended instances
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceIds(anySet())).thenReturn(histPiQuery);
        when(histPiQuery.list()).thenReturn(Collections.emptyList());
    }

    private void stubEndedQueries(
            List<ProcessInstance> runtimeInstances,
            List<PlusHistoricProcessInstance> histPlusInstances) {
        when(mockProcessInstanceQuery.list()).thenReturn(runtimeInstances);

        List<HistoricProcessInstance> mockHpis = new ArrayList<>();
        for (PlusHistoricProcessInstance hpi : histPlusInstances) {
            mockHpis.add(toMockHistoricPi(hpi));
        }
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceIds(anySet())).thenReturn(histPiQuery);
        when(histPiQuery.list()).thenReturn(mockHpis);

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceIdIn(anyCollection())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.emptyList());
    }

    private void stubApprovalTraceQueries(List<Task> activeTasks, String instanceId,
                                           HistoricProcessInstance hpi,
                                           List<HistoricTaskInstance> historicTasks) {
        // Active task queries
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(instanceId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(activeTasks != null ? activeTasks : Collections.emptyList());

        // Historic task instances
        HistoricTaskInstanceQuery histTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(histTaskQuery.processInstanceId(instanceId)).thenReturn(histTaskQuery);
        when(histTaskQuery.finished()).thenReturn(histTaskQuery);
        when(histTaskQuery.orderByHistoricTaskInstanceStartTime()).thenReturn(histTaskQuery);
        when(histTaskQuery.asc()).thenReturn(histTaskQuery);
        when(histTaskQuery.list()).thenReturn(historicTasks != null ? historicTasks : Collections.emptyList());

        // Historic process instance (always stubbed — even when hpi is null)
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(histPiQuery.processInstanceId(instanceId)).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(histTaskQuery);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);

        // Comments
        when(mockTaskService.getProcessInstanceComments(instanceId)).thenReturn(Collections.emptyList());
    }

    private void stubHistoricTasksForTrace(String instanceId, List<HistoricTaskInstance> tasks) {
        // Merged into stubApprovalTraceQueries
    }
}
