package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5: getNextNodeApproversByProcessKey 单元测试。
 * 审批人解析通过 {@link UserTaskApproverResolver} 委托给 {@link GroupResolver}。
 */
public class NodePreviewOperationsTest {

    private RepositoryService mockRepoService;
    private RuntimeService mockRuntimeService;
    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private IdentityService mockIdentityService;
    private NodeFinder mockNodeFinder;
    private BpmnModelCache bpmnModelCache;
    private GroupResolver mockGroupResolver;
    private ApproverResolver approverResolver;
    private FlowablePlus flowablePlus;

    @BeforeEach
    public void setUp() {
        mockRepoService = mock(RepositoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockIdentityService = mock(IdentityService.class);
        mockNodeFinder = mock(NodeFinder.class);
        mockGroupResolver = mock(GroupResolver.class);

        bpmnModelCache = new DefaultBpmnModelCache(mockRepoService);
        approverResolver = new UserTaskApproverResolver(mockGroupResolver);
        BpmnFormDataHelper bpmnFormDataHelper = new BpmnFormDataHelper();

        // TaskQueryModule 仅用于 FlowablePlus 构造，NodePreviewOperations 测试不调用其方法
        TaskQueryModule taskQueryModule = mock(TaskQueryModule.class);

        flowablePlus = new FlowablePlus(taskQueryModule, mockRuntimeService, mockRepoService,
                mockTaskService, mockNodeFinder, bpmnModelCache, approverResolver, bpmnFormDataHelper);
    }

    // ======================== 参数校验 ========================

    @Test
    public void testRejectNullProcessKey() {
        assertThatThrownBy(() -> flowablePlus.getNextNodeApproversByProcessKey(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processKey");
    }

    @Test
    public void testRejectEmptyProcessKey() {
        assertThatThrownBy(() -> flowablePlus.getNextNodeApproversByProcessKey(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processKey");
    }

    @Test
    public void testProcessKeyNotFound() {
        ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
        when(mockRepoService.createProcessDefinitionQuery()).thenReturn(pdQuery);
        when(pdQuery.processDefinitionKey("unknown-key")).thenReturn(pdQuery);
        when(pdQuery.latestVersion()).thenReturn(pdQuery);
        when(pdQuery.active()).thenReturn(pdQuery);
        when(pdQuery.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> flowablePlus.getNextNodeApproversByProcessKey("unknown-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到流程定义");
    }

    // ======================== assignee 类型审批人 ========================

    @Test
    public void testAssigneeTypeApprover() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        UserTask userTask = buildUserTask("taskA", "部门经理审批", "manager1", null, null);
        BpmnModel model = buildBpmnModel(userTask);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = flowablePlus.getNextNodeApproversByProcessKey(processKey);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
        assertThat(result.get(0).getNodeName()).isEqualTo("部门经理审批");
        assertThat(result.get(0).getApprovers()).hasSize(1);
        assertThat(result.get(0).getApprovers().get(0).getId()).isEqualTo("manager1");
        assertThat(result.get(0).getApprovers().get(0).getType()).isEqualTo("assignee");
    }

    // ======================== candidateUser 类型审批人 ========================

    @Test
    public void testCandidateUserTypeApprover() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        UserTask userTask = buildUserTask("taskA", "部门经理审批", null,
                Arrays.asList("user1", "user2"), null);
        BpmnModel model = buildBpmnModel(userTask);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = flowablePlus.getNextNodeApproversByProcessKey(processKey);

        assertThat(result).hasSize(1);
        List<ApproverInfoVO> approvers = result.get(0).getApprovers();
        assertThat(approvers).hasSize(2);
        assertThat(approvers.get(0).getType()).isEqualTo("candidateUser");
        assertThat(approvers.get(1).getType()).isEqualTo("candidateUser");
        assertThat(approvers.get(0).getId()).isEqualTo("user1");
        assertThat(approvers.get(1).getId()).isEqualTo("user2");
    }

    // ======================== candidateGroup 类型审批人（GroupResolver 展开） ========================

    @Test
    public void testCandidateGroupExpandViaGroupResolver() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        UserTask userTask = buildUserTask("taskA", "多级审批", null, null,
                Arrays.asList("dept_manager", "dept_director"));
        BpmnModel model = buildBpmnModel(userTask);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Collections.singletonList("taskA"));
        when(mockGroupResolver.getGroupMembers("dept_manager"))
                .thenReturn(Arrays.asList("userA", "userB"));
        when(mockGroupResolver.getGroupMembers("dept_director"))
                .thenReturn(Collections.singletonList("userC"));

        List<NodeApproverVO> result = flowablePlus.getNextNodeApproversByProcessKey(processKey);

        assertThat(result).hasSize(1);
        List<ApproverInfoVO> approvers = result.get(0).getApprovers();
        assertThat(approvers).hasSize(3);
        assertThat(approvers.get(0).getId()).isEqualTo("userA");
        assertThat(approvers.get(0).getType()).isEqualTo("candidateGroup");
        assertThat(approvers.get(0).getGroupId()).isEqualTo("dept_manager");
        assertThat(approvers.get(1).getId()).isEqualTo("userB");
        assertThat(approvers.get(1).getType()).isEqualTo("candidateGroup");
        assertThat(approvers.get(2).getId()).isEqualTo("userC");
        assertThat(approvers.get(2).getType()).isEqualTo("candidateGroup");
        assertThat(approvers.get(2).getGroupId()).isEqualTo("dept_director");
    }

    @Test
    public void testCandidateGroupNullGroupResolver() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        TaskQueryModule taskQueryModule = mock(TaskQueryModule.class);
        BpmnFormDataHelper bpmnFormDataHelper = new BpmnFormDataHelper();
        FlowablePlus fpWithoutResolver = new FlowablePlus(taskQueryModule, mockRuntimeService,
                mockRepoService, mockTaskService, mockNodeFinder, bpmnModelCache,
                new UserTaskApproverResolver(null), bpmnFormDataHelper);

        UserTask userTask = buildUserTask("taskA", "多级审批", null, null,
                Collections.singletonList("dept_manager"));
        BpmnModel model = buildBpmnModel(userTask);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = fpWithoutResolver.getNextNodeApproversByProcessKey(processKey);

        // candidateGroups 因 GroupResolver 为 null 而被跳过
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getApprovers()).isEmpty();
    }

    // ======================== 多节点 ========================

    @Test
    public void testMultipleNodes() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        UserTask taskA = buildUserTask("taskA", "节点A", "userA", null, null);
        UserTask taskB = buildUserTask("taskB", "节点B", "userB", null, null);
        BpmnModel model = buildBpmnModel(taskA, taskB);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Arrays.asList("taskA", "taskB"));

        List<NodeApproverVO> result = flowablePlus.getNextNodeApproversByProcessKey(processKey);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
        assertThat(result.get(0).getNodeName()).isEqualTo("节点A");
        assertThat(result.get(1).getNodeId()).isEqualTo("taskB");
        assertThat(result.get(1).getNodeName()).isEqualTo("节点B");
    }

    // ======================== 带变量的条件路由 ========================

    @Test
    public void testWithVariablesPassesToNodeFinder() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 5000);

        stubProcessDefinition(processKey, definitionId);

        UserTask taskA = buildUserTask("taskA", "主管审批", "supervisor", null, null);
        BpmnModel model = buildBpmnModel(taskA);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, variables))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = flowablePlus.getNextNodeApproversByProcessKey(processKey, variables);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
    }

    @Test
    public void testNullVariablesDelegatesToNoArg() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        UserTask taskA = buildUserTask("taskA", "审批", "user", null, null);
        BpmnModel model = buildBpmnModel(taskA);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = flowablePlus.getNextNodeApproversByProcessKey(processKey, (Map<String, Object>) null);

        assertThat(result).hasSize(1);
    }

    // ======================== S6: getNextTaskApprovers ========================

    @Test
    public void testGetNextTaskApproversFlatList() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        // 准备 Task 查询
        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");

        // 准备运行时变量
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        // 准备 BPMN 模型：两个下游 UserTask
        UserTask downstreamA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        UserTask downstreamB = buildUserTask("nodeC", "总经理", "ceo1", null, null);
        BpmnModel model = buildBpmnModel(downstreamA, downstreamB);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);

        // NodeFinder 返回下游节点
        when(mockNodeFinder.findNextUserTasks(definitionId, "nodeA", processInstanceId, new HashMap<>()))
                .thenReturn(Arrays.asList("nodeB", "nodeC"));

        List<ApproverInfoVO> result = flowablePlus.getNextTaskApprovers(taskId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("manager1");
        assertThat(result.get(0).getNodeId()).isEqualTo("nodeB");
        assertThat(result.get(0).getNodeName()).isEqualTo("部门经理");
        assertThat(result.get(1).getId()).isEqualTo("ceo1");
        assertThat(result.get(1).getNodeId()).isEqualTo("nodeC");
        assertThat(result.get(1).getNodeName()).isEqualTo("总经理");
    }

    @Test
    public void testGetNextTaskApproversFilterByTargetNodeId() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        UserTask downstreamA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        UserTask downstreamB = buildUserTask("nodeC", "总经理", "ceo1", null, null);
        BpmnModel model = buildBpmnModel(downstreamA, downstreamB);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findNextUserTasks(definitionId, "nodeA", processInstanceId, new HashMap<>()))
                .thenReturn(Arrays.asList("nodeB", "nodeC"));

        // 只查询 nodeC 的审批人
        List<ApproverInfoVO> result = flowablePlus.getNextTaskApprovers(taskId, "nodeC");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("ceo1");
        assertThat(result.get(0).getNodeId()).isEqualTo("nodeC");
    }

    @Test
    public void testGetNextTaskApproversTargetNodeNotFoundReturnsEmpty() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        UserTask downstreamA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        BpmnModel model = buildBpmnModel(downstreamA);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findNextUserTasks(definitionId, "nodeA", processInstanceId, new HashMap<>()))
                .thenReturn(Collections.singletonList("nodeB"));

        List<ApproverInfoVO> result = flowablePlus.getNextTaskApprovers(taskId, "nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    public void testGetNextTaskApproversRejectNullTaskId() {
        assertThatThrownBy(() -> flowablePlus.getNextTaskApprovers(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    public void testGetNextTaskApproversRejectEmptyTaskId() {
        assertThatThrownBy(() -> flowablePlus.getNextTaskApprovers(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== S7: getNextTaskNodes ========================

    @Test
    public void testGetNextTaskNodes() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        UserTask downstreamA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        UserTask downstreamB = buildUserTask("nodeC", "总经理", "ceo1", null, null);
        BpmnModel model = buildBpmnModel(downstreamA, downstreamB);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findNextUserTasks(definitionId, "nodeA", processInstanceId, new HashMap<>()))
                .thenReturn(Arrays.asList("nodeB", "nodeC"));

        List<NextTaskNodeVO> result = flowablePlus.getNextTaskNodes(processInstanceId, taskId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTaskCode()).isEqualTo("nodeB");
        assertThat(result.get(0).getTaskName()).isEqualTo("部门经理");
        assertThat(result.get(0).getFormData()).isNull();
        assertThat(result.get(1).getTaskCode()).isEqualTo("nodeC");
        assertThat(result.get(1).getTaskName()).isEqualTo("总经理");
        assertThat(result.get(1).getFormData()).isNull();
    }

    @Test
    public void testGetNextTaskNodesRejectNullProcessInstanceId() {
        assertThatThrownBy(() -> flowablePlus.getNextTaskNodes(null, "task-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processInstanceId");
    }

    @Test
    public void testGetNextTaskNodesRejectNullTaskId() {
        assertThatThrownBy(() -> flowablePlus.getNextTaskNodes("pi-001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== 辅助方法 ========================

    /**
     * 构建一个 Mock Task 并设置 TaskQuery chain。
     */
    private Task mockTask(String taskId, String definitionId, String processInstanceId, String taskDefinitionKey) {
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.taskId(taskId)).thenReturn(taskQuery);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessDefinitionId()).thenReturn(definitionId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        when(taskQuery.singleResult()).thenReturn(task);
        return task;
    }

    private void stubProcessDefinition(String processKey, String definitionId) {
        ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
        when(mockRepoService.createProcessDefinitionQuery()).thenReturn(pdQuery);
        when(pdQuery.processDefinitionKey(processKey)).thenReturn(pdQuery);
        when(pdQuery.latestVersion()).thenReturn(pdQuery);
        when(pdQuery.active()).thenReturn(pdQuery);

        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(definitionId);
        when(definition.getName()).thenReturn(processKey + "流程");
        when(pdQuery.singleResult()).thenReturn(definition);
    }

    private UserTask buildUserTask(String id, String name, String assignee,
                                    List<String> candidateUsers, List<String> candidateGroups) {
        UserTask task = new UserTask();
        task.setId(id);
        task.setName(name);
        if (assignee != null) {
            task.setAssignee(assignee);
        }
        if (candidateUsers != null) {
            task.setCandidateUsers(candidateUsers);
        }
        if (candidateGroups != null) {
            task.setCandidateGroups(candidateGroups);
        }
        return task;
    }

    private BpmnModel buildBpmnModel(UserTask... userTasks) {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("testProcess");
        StartEvent startEvent = new StartEvent();
        startEvent.setId("start");
        process.addFlowElement(startEvent);
        for (UserTask task : userTasks) {
            process.addFlowElement(task);
            SequenceFlow flow = new SequenceFlow();
            flow.setId("flow_" + task.getId());
            flow.setSourceRef("start");
            flow.setTargetRef(task.getId());
            process.addFlowElement(flow);
            startEvent.getOutgoingFlows().add(flow);
            task.getIncomingFlows().add(flow);
        }
        model.addProcess(process);
        return model;
    }
}
