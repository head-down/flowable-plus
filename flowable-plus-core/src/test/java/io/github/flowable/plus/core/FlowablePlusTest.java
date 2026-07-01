package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FlowablePlus 集成测试：验证构造注入、服务访问、业务委托和异常包装。
 */
public class FlowablePlusTest {

    private ProcessEngine mockEngine;
    private RepositoryService mockRepoService;
    private RuntimeService mockRuntimeService;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private IdentityService mockIdentityService;
    private FlowablePlus flowablePlus;

    private UserContext userContext;

    @BeforeEach
    public void setUp() {
        mockEngine = mock(ProcessEngine.class);
        mockRepoService = mock(RepositoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockIdentityService = mock(IdentityService.class);
        userContext = () -> "testUser";

        when(mockEngine.getRepositoryService()).thenReturn(mockRepoService);
        when(mockEngine.getRuntimeService()).thenReturn(mockRuntimeService);
        when(mockEngine.getTaskService()).thenReturn(mockTaskService);
        when(mockEngine.getHistoryService()).thenReturn(mockHistoryService);
        when(mockEngine.getIdentityService()).thenReturn(mockIdentityService);

        flowablePlus = new FlowablePlus(mockEngine, userContext);
    }

    // ======================== 构造注入 ========================

    @Test
    public void testConstructorInjectsProcessEngine() {
        assertThat(flowablePlus.getProcessEngine()).isSameAs(mockEngine);
    }

    @Test
    public void testConstructorRejectsNullProcessEngine() {
        assertThatThrownBy(() -> new FlowablePlus(null, userContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProcessEngine 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullUserContext() {
        assertThatThrownBy(() -> new FlowablePlus(mockEngine, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserContext 不可为 null");
    }

    // ======================== Service getter ========================

    @Test
    public void testGetRepositoryService() {
        assertThat(flowablePlus.getRepositoryService()).isSameAs(mockRepoService);
    }

    @Test
    public void testGetRuntimeService() {
        assertThat(flowablePlus.getRuntimeService()).isSameAs(mockRuntimeService);
    }

    @Test
    public void testGetTaskService() {
        assertThat(flowablePlus.getTaskService()).isSameAs(mockTaskService);
    }

    @Test
    public void testGetHistoryService() {
        assertThat(flowablePlus.getHistoryService()).isSameAs(mockHistoryService);
    }

    // ======================== findPreviousNodes ========================

    /**
     * 正常委托：start → task1 → task2，从 task2 回溯应找到 [task1]
     */
    @Test
    public void testFindPreviousNodesDelegation() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        UserTask task2 = builder.addUserTask("task2");
        builder.addSequenceFlow("f1", start, task1);
        builder.addSequenceFlow("f2", task1, task2);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        List<String> result = flowablePlus.findPreviousNodes("proc-1", "task2", null);

        assertThat(result).containsExactly("task1");
    }

    @Test
    public void testFindPreviousNodesModelNotFound() {
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(null);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "task1", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 proc-1 不存在");
    }

    @Test
    public void testFindPreviousNodesNodeNotFound() {
        TestModelBuilder builder = new TestModelBuilder();
        builder.addStartEvent("start");
        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "nonexistent", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("节点 nonexistent 不存在");
    }

    @Test
    public void testFindPreviousNodesNoPreviousNode() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", "task1", null))
                .isInstanceOf(NoPreviousNodeException.class)
                .hasMessageContaining("节点 task1 无上一审批节点");
    }

    @Test
    public void testFindPreviousNodesNullProcDefId() {
        assertThatThrownBy(() -> flowablePlus.findPreviousNodes(null, "task1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    @Test
    public void testFindPreviousNodesNullActivityId() {
        assertThatThrownBy(() -> flowablePlus.findPreviousNodes("proc-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentActivityId");
    }

    // ======================== findInitiatorNode ========================

    /**
     * 正常委托：start → task1，应返回 task1
     */
    @Test
    public void testFindInitiatorNodeDelegation() {
        TestModelBuilder builder = new TestModelBuilder();
        StartEvent start = builder.addStartEvent("start");
        UserTask task1 = builder.addUserTask("task1");
        builder.addSequenceFlow("f1", start, task1);

        BpmnModel model = builder.build();
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(model);

        String result = flowablePlus.findInitiatorNode("proc-1");

        assertThat(result).isEqualTo("task1");
    }

    @Test
    public void testFindInitiatorNodeModelNotFound() {
        when(mockRepoService.getBpmnModel("proc-1")).thenReturn(null);

        assertThatThrownBy(() -> flowablePlus.findInitiatorNode("proc-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("流程定义 proc-1 不存在");
    }

    @Test
    public void testFindInitiatorNodeNullProcDefId() {
        assertThatThrownBy(() -> flowablePlus.findInitiatorNode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionId");
    }

    // ======================== startProcess ========================

    @Test
    public void testStartProcess() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("key", "value");
        ProcessInstance mockInstance = mock(ProcessInstance.class);
        when(mockRuntimeService.startProcessInstanceByKey("myProcess", "biz-001", variables))
                .thenReturn(mockInstance);

        ProcessInstance result = flowablePlus.startProcess("myProcess", "biz-001", variables);

        assertThat(result).isSameAs(mockInstance);
        // 验证身份服务设置
        verify(mockIdentityService).setAuthenticatedUserId("testUser");
        verify(mockIdentityService).setAuthenticatedUserId(null);
        // 验证启动流程
        verify(mockRuntimeService).startProcessInstanceByKey("myProcess", "biz-001", variables);
    }

    @Test
    public void testStartProcessWithNullBusinessKey() {
        Map<String, Object> variables = new HashMap<>();
        ProcessInstance mockInstance = mock(ProcessInstance.class);
        when(mockRuntimeService.startProcessInstanceByKey("myProcess", null, variables))
                .thenReturn(mockInstance);

        ProcessInstance result = flowablePlus.startProcess("myProcess", null, variables);

        assertThat(result).isSameAs(mockInstance);
        verify(mockIdentityService).setAuthenticatedUserId("testUser");
        verify(mockIdentityService).setAuthenticatedUserId(null);
    }

    @Test
    public void testStartProcessNullKey() {
        assertThatThrownBy(() -> flowablePlus.startProcess(null, "biz", new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processDefinitionKey");
    }

    // ======================== completeTask ========================

    @Test
    public void testCompleteTaskWithComment() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("approved", true);

        flowablePlus.completeTask("task-001", variables, "同意");

        // 验证操作顺序：先认领、再添加意见、最后完成
        verify(mockTaskService).claim("task-001", "testUser");
        verify(mockTaskService).addComment("task-001", null, "同意");
        verify(mockTaskService).complete("task-001", variables);
    }

    @Test
    public void testCompleteTaskWithoutComment() {
        flowablePlus.completeTask("task-002", null, null);

        verify(mockTaskService).claim("task-002", "testUser");
        verify(mockTaskService, never()).addComment(any(), any(), any());
        verify(mockTaskService).complete("task-002", null);
    }

    @Test
    public void testCompleteTaskWithEmptyComment() {
        flowablePlus.completeTask("task-003", null, "");

        verify(mockTaskService).claim("task-003", "testUser");
        verify(mockTaskService, never()).addComment(any(), any(), any());
        verify(mockTaskService).complete("task-003", null);
    }

    @Test
    public void testCompleteTaskNullId() {
        assertThatThrownBy(() -> flowablePlus.completeTask(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== claimTask ========================

    @Test
    public void testClaimTask() {
        flowablePlus.claimTask("task-001");

        verify(mockTaskService).claim("task-001", "testUser");
    }

    @Test
    public void testClaimTaskNullId() {
        assertThatThrownBy(() -> flowablePlus.claimTask(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }
}
