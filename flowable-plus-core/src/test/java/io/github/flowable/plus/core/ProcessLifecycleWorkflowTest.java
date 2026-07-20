package io.github.flowable.plus.core;

import io.github.flowable.plus.core.domain.PlusProcessInstance;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.spi.AutoApprovalRule;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProcessLifecycleWorkflow 单元测试：覆盖流程发起与撤销的
 * 正常路径和所有异常路径。
 */
public class ProcessLifecycleWorkflowTest {

    private static final String USER_ID = "user1";

    private UserContext userContext;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private RuntimeService mockRuntimeService;
    private IdentityService mockIdentityService;
    private NodeFinder mockNodeFinder;
    private ProcessLifecycleWorkflow workflow;

    @BeforeEach
    void setUp() {
        userContext = () -> USER_ID;
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockIdentityService = mock(IdentityService.class);
        mockNodeFinder = mock(NodeFinder.class);

        workflow = new ProcessLifecycleWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockIdentityService, mockNodeFinder, null, null);
    }

    // ======================== 发起 ========================

    @Test
    void testStartProcess() {
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", null))
                .thenReturn(mockPi);

        PlusProcessInstance result = workflow.startProcess("leave", "biz-001", null);

        assertThat(result.getProcessInstanceId()).isEqualTo("pi-001");
        assertThat(result.getBusinessKey()).isEqualTo("biz-001");
        assertThat(result.getProcessDefinitionId()).isEqualTo("leave:1:abc");
        verify(mockIdentityService).setAuthenticatedUserId(USER_ID);
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    @Test
    void testStartProcessRejectsNullKey() {
        assertThatThrownBy(() -> workflow.startProcess(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionKey");
    }

    @Test
    void testStartProcessClearsAuthOnException() {
        when(mockRuntimeService.startProcessInstanceByKey("leave", null, null))
                .thenThrow(new RuntimeException("引擎异常"));

        assertThatThrownBy(() -> workflow.startProcess("leave", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("引擎异常");
        verify(mockIdentityService).setAuthenticatedUserId(USER_ID);
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    @Test
    void testStartProcessWithVariables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 5000);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-002");
        when(mockPi.getBusinessKey()).thenReturn("biz-002");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-002", variables))
                .thenReturn(mockPi);

        PlusProcessInstance result = workflow.startProcess("leave", "biz-002", variables);

        assertThat(result.getProcessInstanceId()).isEqualTo("pi-002");
    }

    @Test
    void testStartProcessPropagatesAutoCompleteException() {
        AutoApprovalRule failingRule = (task, vars) -> {
            throw new RuntimeException("自动提交规则异常");
        };

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mock(Task.class)));

        workflow = new ProcessLifecycleWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockIdentityService, mockNodeFinder,
                Collections.singletonList(failingRule), null);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-003");
        when(mockPi.getBusinessKey()).thenReturn("biz-003");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");

        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 1000);
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-003", variables))
                .thenReturn(mockPi);

        assertThatThrownBy(() -> workflow.startProcess("leave", "biz-003", variables))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("自动提交规则异常");
        verify(mockIdentityService).setAuthenticatedUserId(USER_ID);
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    // ======================== 自动提交补充测试 ========================

    private ProcessLifecycleWorkflow createWorkflowWithRules(List<AutoApprovalRule> rules) {
        return new ProcessLifecycleWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockIdentityService, mockNodeFinder, rules, null);
    }

    @Test
    void shouldCompleteFirstTaskWhenRuleReturnsNonNull() {
        AutoApprovalRule rule = (task, vars) -> "自动通过";

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        Task mockTask = createMockTask("task-001", "leave:1:abc", "draft", "pi-001", USER_ID);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mockTask));

        workflow = createWorkflowWithRules(Collections.singletonList(rule));

        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 1000);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        PlusProcessInstance result = workflow.startProcess("leave", "biz-001", variables);

        assertThat(result.getProcessInstanceId()).isEqualTo("pi-001");
        verify(mockTaskService).addComment("task-001", "pi-001",
                CommentType.AUTO_COMPLETE.name(), "自动通过");
        verify(mockTaskService).complete("task-001", null);
        verify(mockIdentityService).setAuthenticatedUserId(USER_ID);
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    @Test
    void shouldNotCompleteWhenAllRulesReturnNull() {
        AutoApprovalRule rule = (task, vars) -> null;

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        Task mockTask = createMockTask("task-001", "leave:1:abc", "draft", "pi-001", USER_ID);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mockTask));

        workflow = createWorkflowWithRules(Collections.singletonList(rule));

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        workflow.startProcess("leave", "biz-001", variables);

        verify(mockTaskService, never()).complete(anyString(), any());
    }

    @Test
    void shouldNotCascadeToNewlyCreatedTasks() {
        AutoApprovalRule rule = (task, vars) -> "自动通过";

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        Task mockTask = createMockTask("task-001", "leave:1:abc", "draft", "pi-001", USER_ID);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mockTask));

        workflow = createWorkflowWithRules(Collections.singletonList(rule));

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        workflow.startProcess("leave", "biz-001", variables);

        verify(mockTaskService, times(1)).complete(anyString(), any());
    }

    @Test
    void shouldUseSecondRuleWhenFirstReturnsNull() {
        AutoApprovalRule rule1 = (task, vars) -> null;
        AutoApprovalRule rule2 = (task, vars) -> "由规则二自动通过";

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        Task mockTask = createMockTask("task-001", "leave:1:abc", "draft", "pi-001", USER_ID);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mockTask));

        workflow = createWorkflowWithRules(Arrays.asList(rule1, rule2));

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        workflow.startProcess("leave", "biz-001", variables);

        verify(mockTaskService).addComment("task-001", "pi-001",
                CommentType.AUTO_COMPLETE.name(), "由规则二自动通过");
        verify(mockTaskService).complete("task-001", null);
    }

    @Test
    void shouldShortCircuitAfterFirstMatch() {
        AutoApprovalRule rule1 = (task, vars) -> "规则一自动通过";
        AutoApprovalRule rule2 = mock(AutoApprovalRule.class);

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        Task mockTask = createMockTask("task-001", "leave:1:abc", "draft", "pi-001", USER_ID);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mockTask));

        workflow = createWorkflowWithRules(Arrays.asList(rule1, rule2));

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        workflow.startProcess("leave", "biz-001", variables);

        verify(mockTaskService).complete("task-001", null);
        verify(rule2, never()).evaluate(any(PlusTask.class), any());
    }

    @Test
    void shouldSkipAutoCompleteWhenHistoryExists() {
        AutoApprovalRule rule = (task, vars) -> "不该触发";

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(1L);

        workflow = createWorkflowWithRules(Collections.singletonList(rule));

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        workflow.startProcess("leave", "biz-001", variables);

        verify(mockTaskService, never()).complete(anyString(), any());
        verify(mockTaskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldCompleteAllFirstTasks() {
        AutoApprovalRule rule = (task, vars) -> "自动通过";

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        Task task1 = createMockTask("task-001", "leave:1:abc", "draft", "pi-001", USER_ID);
        Task task2 = createMockTask("task-002", "leave:1:abc", "draft", "pi-001", USER_ID);
        Task task3 = createMockTask("task-003", "leave:1:abc", "draft", "pi-001", USER_ID);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Arrays.asList(task1, task2, task3));

        workflow = createWorkflowWithRules(Collections.singletonList(rule));

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        workflow.startProcess("leave", "biz-001", variables);

        verify(mockTaskService, times(3)).complete(anyString(), any());
        verify(mockTaskService).addComment("task-001", "pi-001",
                CommentType.AUTO_COMPLETE.name(), "自动通过");
        verify(mockTaskService).addComment("task-002", "pi-001",
                CommentType.AUTO_COMPLETE.name(), "自动通过");
        verify(mockTaskService).addComment("task-003", "pi-001",
                CommentType.AUTO_COMPLETE.name(), "自动通过");
    }

    @Test
    void shouldPassReadonlyCopyOfVariablesToRule() {
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> capturedVars =
                new java.util.concurrent.atomic.AtomicReference<>();

        AutoApprovalRule rule = (task, vars) -> {
            capturedVars.set(vars);
            return "自动通过";
        };

        HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.count()).thenReturn(0L);

        Task mockTask = createMockTask("task-001", "leave:1:abc", "draft", "pi-001", USER_ID);
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(mockTask));

        workflow = createWorkflowWithRules(Collections.singletonList(rule));

        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 1000);
        variables.put("days", 3);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(mockRuntimeService.startProcessInstanceByKey("leave", "biz-001", variables))
                .thenReturn(mockPi);

        workflow.startProcess("leave", "biz-001", variables);

        Map<String, Object> received = capturedVars.get();
        assertThat(received).isNotNull();
        assertThat(received.get("amount")).isEqualTo(1000);
        assertThat(received.get("days")).isEqualTo(3);

        assertThatThrownBy(() -> received.put("newKey", "newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ======================== 撤销 ========================

    @Test
    void testRevokeProcess() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", USER_ID, new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(mockPi);

        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        Task activeTask = createMockTask("task-001", "leave:1:abc", "startTask", "pi-001", "user2");
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId("pi-001")).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(activeTask);

        workflow.revokeProcess("pi-001", "发起人撤销");

        verify(mockRuntimeService).deleteProcessInstance("pi-001", "发起人撤销");
        verify(mockTaskService).addComment("task-001", "pi-001", CommentType.REVOKE.name(), "发起人撤销");
    }

    @Test
    void testRevokeProcessRejectsNullProcessInstanceId() {
        assertThatThrownBy(() -> workflow.revokeProcess(null, "撤销"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    @Test
    void testRevokeProcessRejectsNonExistent() {
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-nope")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> workflow.revokeProcess("pi-nope", "撤销"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void testRevokeProcessRejectsNonInitiator() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", "anotherUser", new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        assertThatThrownBy(() -> workflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("无权撤销");
    }

    @Test
    void testRevokeProcessRejectsAlreadyEnded() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", USER_ID, new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> workflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    void testRevokeProcessRejectsAdvancedBeyondInitiator() {
        HistoricProcessInstance hpi = createMockHistoricPi(
                "pi-001", "biz-001", "leave:1:abc", "leave", "请假审批", USER_ID, new Date(), null, null);
        HistoricProcessInstanceQuery histPiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(histPiQuery);
        when(histPiQuery.processInstanceId("pi-001")).thenReturn(histPiQuery);
        when(histPiQuery.singleResult()).thenReturn(hpi);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        ProcessInstanceQuery mockPiQuery = mock(ProcessInstanceQuery.class);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(mockPiQuery);
        when(mockPiQuery.processInstanceId("pi-001")).thenReturn(mockPiQuery);
        when(mockPiQuery.singleResult()).thenReturn(mockPi);

        when(mockNodeFinder.findInitiatorNode("leave:1:abc")).thenReturn("startTask");

        Task activeTask = createMockTask("task-001", "leave:1:abc", "task2", "pi-001", "user2");
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId("pi-001")).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(activeTask);

        assertThatThrownBy(() -> workflow.revokeProcess("pi-001", "撤销"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining("已推进后续节点");
    }

    // ======================== 事件发布 ========================

    private ProcessLifecycleWorkflow createWorkflowWithEventPublisher(EventPublisher ep) {
        return new ProcessLifecycleWorkflow(userContext, mockTaskService, mockHistoryService,
                mockRuntimeService, mockIdentityService, mockNodeFinder, null, ep);
    }

    @Test
    void startProcessShouldPublishProcessStartedEvent() {
        EventPublisher mockEp = mock(EventPublisher.class);
        ProcessLifecycleWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        ProcessInstance mockPi = mock(ProcessInstance.class);
        when(mockPi.getProcessInstanceId()).thenReturn("pi-001");
        when(mockPi.getBusinessKey()).thenReturn("biz-001");
        when(mockPi.getProcessDefinitionId()).thenReturn("leave:1:1234");
        when(mockRuntimeService.startProcessInstanceByKey(eq("leave"), eq("biz-001"), any()))
                .thenReturn(mockPi);

        wf.startProcess("leave", "biz-001", null);

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.ProcessStartedEvent.class));
    }

    @Test
    void revokeProcessShouldPublishProcessRevokedAndEndedEvents() {
        EventPublisher mockEp = mock(EventPublisher.class);
        ProcessLifecycleWorkflow wf = createWorkflowWithEventPublisher(mockEp);

        HistoricProcessInstance mockHpi = createMockHistoricPi("pi-001", "biz-001", "leave:1:1234",
                "leave", "请假流程", USER_ID, new Date(), new Date(), null);
        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(hpiQuery.processInstanceId("pi-001")).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(mockHpi);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);

        ProcessInstance pi = mock(ProcessInstance.class);
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId("pi-001")).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(pi);
        when(mockRuntimeService.createProcessInstanceQuery()).thenReturn(piQuery);

        when(mockNodeFinder.findInitiatorNode("leave:1:1234")).thenReturn("initNode");

        Task mockTask = mock(Task.class);
        when(mockTask.getProcessInstanceId()).thenReturn("pi-001");
        when(mockTask.getId()).thenReturn("task-001");
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.processInstanceId("pi-001")).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(mockTask);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
        when(mockTask.getTaskDefinitionKey()).thenReturn("initNode");

        wf.revokeProcess("pi-001", "撤销原因");

        verify(mockEp).publish(any(io.github.flowable.plus.core.event.ProcessRevokedEvent.class));
        verify(mockEp).publish(any(io.github.flowable.plus.core.event.ProcessEndedEvent.class));
    }

    // ======================== Test Helpers ========================

    private Task createMockTask(String id, String definitionId, String taskDefKey,
            String instanceId, String assignee) {
        Task mockTask = mock(Task.class);
        when(mockTask.getId()).thenReturn(id);
        when(mockTask.getProcessDefinitionId()).thenReturn(definitionId);
        when(mockTask.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(mockTask.getProcessInstanceId()).thenReturn(instanceId);
        when(mockTask.getAssignee()).thenReturn(assignee);
        when(mockTask.getName()).thenReturn("测试任务");
        when(mockTask.getExecutionId()).thenReturn("exec-" + id);
        when(mockTask.getCreateTime()).thenReturn(new Date());
        return mockTask;
    }

    private HistoricProcessInstance createMockHistoricPi(String id, String businessKey, String definitionId,
            String definitionKey, String definitionName, String startUserId,
            Date startTime, Date endTime, String deleteReason) {
        HistoricProcessInstance mockHpi = mock(HistoricProcessInstance.class);
        when(mockHpi.getId()).thenReturn(id);
        when(mockHpi.getBusinessKey()).thenReturn(businessKey);
        when(mockHpi.getProcessDefinitionId()).thenReturn(definitionId);
        when(mockHpi.getProcessDefinitionKey()).thenReturn(definitionKey);
        when(mockHpi.getProcessDefinitionName()).thenReturn(definitionName);
        when(mockHpi.getStartUserId()).thenReturn(startUserId);
        when(mockHpi.getStartTime()).thenReturn(startTime);
        when(mockHpi.getEndTime()).thenReturn(endTime);
        when(mockHpi.getDeleteReason()).thenReturn(deleteReason);
        return mockHpi;
    }
}
