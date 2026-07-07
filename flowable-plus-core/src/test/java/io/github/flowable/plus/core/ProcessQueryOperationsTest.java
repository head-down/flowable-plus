package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.AssigneeInfo;
import io.github.flowable.plus.core.vo.ProcessSummaryVO;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * batchQueryProcessSummaries 测试：基于 ProcessQueryWorkflow，
 * 通过 TaskRepository 和 HistoricRepository 接缝访问数据。
 */
public class ProcessQueryOperationsTest {

    private RuntimeService mockRuntimeService;
    private TaskRepository mockTaskRepository;
    private HistoricRepository mockHistoricRepository;
    private ProcessQueryWorkflow processQueryWorkflow;

    @BeforeEach
    public void setUp() {
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskRepository = mock(TaskRepository.class);
        mockHistoricRepository = mock(HistoricRepository.class);

        processQueryWorkflow = new ProcessQueryWorkflow(
                mockRuntimeService, mockTaskRepository, mockHistoricRepository);
    }

    // ======================== 参数校验 ========================

    @Test
    public void testRejectNullList() {
        assertThatThrownBy(() -> processQueryWorkflow.batchQueryProcessSummaries(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceIds");
    }

    @Test
    public void testRejectEmptyList() {
        assertThatThrownBy(() -> processQueryWorkflow.batchQueryProcessSummaries(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceIds");
    }

    // ======================== 单个运行中实例 ========================

    @Test
    public void testSingleRunningInstance() {
        String instanceId = "pi-001";
        ProcessInstance pi = createMockProcessInstance(instanceId, "biz-001", "leave", "请假审批",
                "userA", new Date());
        when(pi.isSuspended()).thenReturn(false);

        Task task = createMockTask("task-001", instanceId, "task1", "部门审批", "reviewer1");
        stubRunningQueries(Collections.singletonList(pi), Collections.singletonList(task));

        Map<String, ProcessSummaryVO> result = processQueryWorkflow.batchQueryProcessSummaries(
                Collections.singletonList(instanceId));

        assertThat(result).hasSize(1);
        ProcessSummaryVO vo = result.get(instanceId);
        assertThat(vo.getInstanceId()).isEqualTo("pi-001");
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

        Task task = createMockTask("task-002", instanceId, "task1", "部门审批", "reviewer1");
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

        Task task1 = createMockTask("task-a", instanceId, "task1", "会签审批", "reviewer1");
        Task task2 = createMockTask("task-b", instanceId, "task1", "会签审批", "reviewer2");
        Task task3 = createMockTask("task-c", instanceId, "task1", "会签审批", "reviewer3");
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

        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getId()).thenReturn(instanceId);
        when(hpi.getBusinessKey()).thenReturn("biz-ended");
        when(hpi.getProcessDefinitionKey()).thenReturn("leave");
        when(hpi.getProcessDefinitionName()).thenReturn("请假审批");
        when(hpi.getStartUserId()).thenReturn("userA");
        when(hpi.getStartTime()).thenReturn(startTime);
        when(hpi.getEndTime()).thenReturn(endTime);
        when(hpi.getDeleteReason()).thenReturn(null);

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
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getId()).thenReturn(instanceId);
        when(hpi.getBusinessKey()).thenReturn("biz-revoked");
        when(hpi.getProcessDefinitionKey()).thenReturn("leave");
        when(hpi.getProcessDefinitionName()).thenReturn("请假审批");
        when(hpi.getStartUserId()).thenReturn("userA");
        when(hpi.getStartTime()).thenReturn(new Date());
        when(hpi.getEndTime()).thenReturn(new Date());
        when(hpi.getDeleteReason()).thenReturn("发起人撤销流程");

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
        Task task = createMockTask("task-r", runningId, "task1", "部门审批", "reviewer1");
        stubRunningQueries(Collections.singletonList(pi), Collections.singletonList(task));

        // 已结束实例
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getId()).thenReturn(endedId);
        when(hpi.getBusinessKey()).thenReturn("biz-e");
        when(hpi.getProcessDefinitionKey()).thenReturn("leave");
        when(hpi.getProcessDefinitionName()).thenReturn("请假审批");
        when(hpi.getStartUserId()).thenReturn("userB");
        when(hpi.getStartTime()).thenReturn(new Date());
        when(hpi.getEndTime()).thenReturn(new Date());
        when(hpi.getDeleteReason()).thenReturn(null);
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

        Task t1 = createMockTask("t1", instanceId1, "n1", "节点1", "r1");
        Task t2 = createMockTask("t2", instanceId2, "n2", "节点2", "r2");
        Task t3 = createMockTask("t3", instanceId3, "n3", "节点3", "r3");
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

    private Task createMockTask(String taskId, String processInstanceId,
            String taskDefKey, String taskName, String assignee) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(task.getName()).thenReturn(taskName);
        when(task.getAssignee()).thenReturn(assignee);
        when(task.getProcessDefinitionId()).thenReturn("leave:1:abc123");
        return task;
    }

    private void stubRunningQueries(
            java.util.List<ProcessInstance> runtimeInstances,
            java.util.List<Task> activeTasks) {
        // ProcessInstanceQuery (RuntimeService — 无独立仓储)
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);
        when(piQuery.processInstanceIds(anySet())).thenReturn(piQuery);
        when(piQuery.list()).thenReturn(runtimeInstances);

        // TaskRepository 接缝
        when(mockTaskRepository.findActiveTasksByProcessInstanceIds(anyCollection()))
                .thenReturn(activeTasks);
    }

    private void stubEndedQueries(
            java.util.List<ProcessInstance> runtimeInstances,
            java.util.List<HistoricProcessInstance> histInstances) {
        // Runtime query
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);
        when(piQuery.processInstanceIds(anySet())).thenReturn(piQuery);
        when(piQuery.list()).thenReturn(runtimeInstances);

        // HistoricRepository 接缝
        when(mockHistoricRepository.findProcessInstancesByIds(anySet()))
                .thenReturn(histInstances);

        // TaskRepository 接缝 — 运行时无活跃任务
        when(mockTaskRepository.findActiveTasksByProcessInstanceIds(anyCollection()))
                .thenReturn(Collections.emptyList());
    }
}
