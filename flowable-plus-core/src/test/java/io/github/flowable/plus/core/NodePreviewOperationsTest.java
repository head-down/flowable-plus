package io.github.flowable.plus.core;

import io.github.flowable.plus.core.enums.TraversalMode;
import io.github.flowable.plus.core.spi.ApproverContext;
import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.NextTaskNodeVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.model.DefaultBpmnModelCache;
import io.github.flowable.plus.core.support.BpmnFormDataHelper;
import io.github.flowable.plus.core.support.UserTaskApproverResolver;
import io.github.flowable.plus.core.workflow.NodePreviewWorkflow;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 节点预览操作单元测试。
 * 审批人解析通过 {@link UserTaskApproverResolver} 委托给 {@link GroupResolver}。
 */
public class NodePreviewOperationsTest {

    private RepositoryService mockRepoService;
    private RuntimeService mockRuntimeService;
    private TaskService mockTaskService;
    private NodeFinder mockNodeFinder;
    private BpmnModelCache bpmnModelCache;
    private GroupResolver mockGroupResolver;
    private ApproverResolver approverResolver;
    private BpmnFormDataHelper bpmnFormDataHelper;
    private UserContext mockUserContext;
    private NodePreviewWorkflow nodePreviewWorkflow;

    @BeforeEach
    public void setUp() {
        mockRepoService = mock(RepositoryService.class);
        mockRuntimeService = mock(RuntimeService.class);
        mockTaskService = mock(TaskService.class);
        mockNodeFinder = mock(NodeFinder.class);
        mockGroupResolver = mock(GroupResolver.class);
        mockUserContext = mock(UserContext.class);
        when(mockUserContext.getCurrentUserId()).thenReturn("u1001");

        bpmnModelCache = new DefaultBpmnModelCache(mockRepoService);
        approverResolver = new UserTaskApproverResolver(mockGroupResolver);
        bpmnFormDataHelper = new BpmnFormDataHelper();

        nodePreviewWorkflow = new NodePreviewWorkflow(mockRepoService, bpmnModelCache,
                mockNodeFinder, approverResolver, mockUserContext, mockTaskService,
                mockRuntimeService, bpmnFormDataHelper);
    }

    // ======================== 参数校验 ========================

    @Test
    public void testRejectNullProcessKey() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextNodeApprovers(null, TraversalMode.FULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processKey");
    }

    @Test
    public void testRejectEmptyProcessKey() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextNodeApprovers("", TraversalMode.FULL))
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

        assertThatThrownBy(() -> nodePreviewWorkflow.getNextNodeApprovers("unknown-key", TraversalMode.FULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到流程定义");
    }

    // ======================== 定义锚点 · 全遍历（FULL） ========================

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

        List<NodeApproverVO> result = nodePreviewWorkflow.getNextNodeApprovers(processKey, TraversalMode.FULL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
        assertThat(result.get(0).getNodeName()).isEqualTo("部门经理审批");
        assertThat(result.get(0).getApprovers()).hasSize(1);
        assertThat(result.get(0).getApprovers().get(0).getId()).isEqualTo("manager1");
        assertThat(result.get(0).getApprovers().get(0).getType()).isEqualTo("assignee");
    }

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

        List<NodeApproverVO> result = nodePreviewWorkflow.getNextNodeApprovers(processKey, TraversalMode.FULL);

        assertThat(result).hasSize(1);
        List<ApproverInfoVO> approvers = result.get(0).getApprovers();
        assertThat(approvers).hasSize(2);
        assertThat(approvers.get(0).getType()).isEqualTo("candidateUser");
        assertThat(approvers.get(1).getType()).isEqualTo("candidateUser");
        assertThat(approvers.get(0).getId()).isEqualTo("user1");
        assertThat(approvers.get(1).getId()).isEqualTo("user2");
    }

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

        List<NodeApproverVO> result = nodePreviewWorkflow.getNextNodeApprovers(processKey, TraversalMode.FULL);

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

        NodePreviewWorkflow npwWithoutResolver = new NodePreviewWorkflow(mockRepoService, bpmnModelCache,
                mockNodeFinder, new UserTaskApproverResolver(null), mockUserContext, mockTaskService,
                mockRuntimeService, bpmnFormDataHelper);

        UserTask userTask = buildUserTask("taskA", "多级审批", null, null,
                Collections.singletonList("dept_manager"));
        BpmnModel model = buildBpmnModel(userTask);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = npwWithoutResolver.getNextNodeApprovers(processKey, TraversalMode.FULL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getApprovers()).isEmpty();
    }

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

        List<NodeApproverVO> result = nodePreviewWorkflow.getNextNodeApprovers(processKey, TraversalMode.FULL);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
        assertThat(result.get(0).getNodeName()).isEqualTo("节点A");
        assertThat(result.get(1).getNodeId()).isEqualTo("taskB");
        assertThat(result.get(1).getNodeName()).isEqualTo("节点B");
    }

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

        List<NodeApproverVO> result = nodePreviewWorkflow
                .getNextNodeApprovers(processKey, TraversalMode.FULL, variables);

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

        List<NodeApproverVO> result = nodePreviewWorkflow
                .getNextNodeApprovers(processKey, TraversalMode.FULL, (Map<String, Object>) null);

        assertThat(result).hasSize(1);
    }

    // ======================== 定义锚点 · 紧邻遍历（ADJACENT） ========================

    @Test
    public void testAdjacentRejectNullProcessKey() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextNodeApprovers(null, TraversalMode.ADJACENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processKey");
    }

    @Test
    public void testAdjacentRejectEmptyProcessKey() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextNodeApprovers("", TraversalMode.ADJACENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processKey");
    }

    @Test
    public void testAdjacentProcessKeyNotFound() {
        ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
        when(mockRepoService.createProcessDefinitionQuery()).thenReturn(pdQuery);
        when(pdQuery.processDefinitionKey("unknown-key")).thenReturn(pdQuery);
        when(pdQuery.latestVersion()).thenReturn(pdQuery);
        when(pdQuery.active()).thenReturn(pdQuery);
        when(pdQuery.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> nodePreviewWorkflow.getNextNodeApprovers("unknown-key", TraversalMode.ADJACENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到流程定义");
    }

    @Test
    public void testAdjacentSingleNode() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        UserTask userTask = buildUserTask("taskA", "部门经理审批", "manager1", null, null);
        BpmnModel model = buildBpmnModel(userTask);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "start", null))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = nodePreviewWorkflow.getNextNodeApprovers(processKey, TraversalMode.ADJACENT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
        assertThat(result.get(0).getNodeName()).isEqualTo("部门经理审批");
        assertThat(result.get(0).getApprovers()).hasSize(1);
        assertThat(result.get(0).getApprovers().get(0).getId()).isEqualTo("manager1");
    }

    @Test
    public void testAdjacentMultipleNodes() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        UserTask taskA = buildUserTask("taskA", "节点A", "userA", null, null);
        UserTask taskB = buildUserTask("taskB", "节点B", "userB", null, null);
        BpmnModel model = buildBpmnModel(taskA, taskB);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "start", null))
                .thenReturn(Arrays.asList("taskA", "taskB"));

        List<NodeApproverVO> result = nodePreviewWorkflow.getNextNodeApprovers(processKey, TraversalMode.ADJACENT);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
        assertThat(result.get(1).getNodeId()).isEqualTo("taskB");
    }

    @Test
    public void testAdjacentWithVariablesPassesToNodeFinder() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 5000);

        stubProcessDefinition(processKey, definitionId);

        UserTask taskA = buildUserTask("taskA", "主管审批", "supervisor", null, null);
        BpmnModel model = buildBpmnModel(taskA);

        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "start", variables))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = nodePreviewWorkflow
                .getNextNodeApprovers(processKey, TraversalMode.ADJACENT, variables);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeId()).isEqualTo("taskA");
    }

    // ======================== 任务锚点 · 全遍历（FULL）审批人 ========================

    @Test
    public void testGetNextTaskApproversFlatList() {
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

        List<ApproverInfoVO> result = nodePreviewWorkflow.getNextTaskApprovers(taskId, TraversalMode.FULL);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("manager1");
        assertThat(result.get(0).getNodeId()).isEqualTo("nodeB");
        assertThat(result.get(0).getNodeName()).isEqualTo("部门经理");
        assertThat(result.get(1).getId()).isEqualTo("ceo1");
        assertThat(result.get(1).getNodeId()).isEqualTo("nodeC");
        assertThat(result.get(1).getNodeName()).isEqualTo("总经理");
    }

    /**
     * targetNodeId 过滤能力已删除（ADR-0031）：调用方按 nodeId 自行过滤，结果等价。
     */
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

        List<ApproverInfoVO> result = nodePreviewWorkflow.getNextTaskApprovers(taskId, TraversalMode.FULL)
                .stream()
                .filter(vo -> "nodeC".equals(vo.getNodeId()))
                .collect(Collectors.toList());

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

        List<ApproverInfoVO> result = nodePreviewWorkflow.getNextTaskApprovers(taskId, TraversalMode.FULL)
                .stream()
                .filter(vo -> "nonexistent".equals(vo.getNodeId()))
                .collect(Collectors.toList());

        assertThat(result).isEmpty();
    }

    @Test
    public void testGetNextTaskApproversRejectNullTaskId() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextTaskApprovers(null, TraversalMode.FULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    public void testGetNextTaskApproversRejectEmptyTaskId() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextTaskApprovers("", TraversalMode.FULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== 任务锚点 · 全遍历（FULL）节点列表 ========================

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
        when(mockNodeFinder.findReachableEndEvents(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.emptyList());

        List<NextTaskNodeVO> result = nodePreviewWorkflow.getNextTaskNodes(taskId, TraversalMode.FULL);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTaskCode()).isEqualTo("nodeB");
        assertThat(result.get(0).getTaskName()).isEqualTo("部门经理");
        assertThat(result.get(0).getFormData()).isNull();
        assertThat(result.get(1).getTaskCode()).isEqualTo("nodeC");
        assertThat(result.get(1).getTaskName()).isEqualTo("总经理");
        assertThat(result.get(1).getFormData()).isNull();
    }

    @Test
    public void testGetNextTaskNodesRejectNullTaskId() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextTaskNodes(null, TraversalMode.FULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== 任务锚点 · 紧邻遍历（ADJACENT）节点列表 ========================

    @Test
    public void testGetAdjacentTaskNodes() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        UserTask adjacentA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        BpmnModel model = buildBpmnModel(adjacentA);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.singletonList("nodeB"));
        when(mockNodeFinder.findReachableEndEvents(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.emptyList());

        List<NextTaskNodeVO> result = nodePreviewWorkflow.getNextTaskNodes(taskId, TraversalMode.ADJACENT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTaskCode()).isEqualTo("nodeB");
        assertThat(result.get(0).getTaskName()).isEqualTo("部门经理");
        assertThat(result.get(0).getFormData()).isNull();
    }

    @Test
    public void testGetAdjacentTaskNodesRejectNullTaskId() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextTaskNodes(null, TraversalMode.ADJACENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    /**
     * 紧邻遍历无 UserTask 但下游为 EndEvent → 应返回 end=true 的 VO。
     */
    @Test
    public void testGetAdjacentTaskNodesEndSignal() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.findReachableEndEvents(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.singletonList("endEvent1"));

        List<NextTaskNodeVO> result = nodePreviewWorkflow.getNextTaskNodes(taskId, TraversalMode.ADJACENT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTaskCode()).isEqualTo(NextTaskNodeVO.END_TASK_CODE);
        assertThat(result.get(0).getTaskName()).isEqualTo("流程结束");
        assertThat(result.get(0).isEnd()).isTrue();
    }

    /**
     * 紧邻遍历无 UserTask 且 findReachableEndEvents 也返回空 → 返回空列表。
     */
    @Test
    public void testGetAdjacentTaskNodesEmptyNoEndSignal() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.findReachableEndEvents(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.emptyList());

        List<NextTaskNodeVO> result = nodePreviewWorkflow.getNextTaskNodes(taskId, TraversalMode.ADJACENT);

        assertThat(result).isEmpty();
    }

    /**
     * 紧邻遍历：UserTask 与 EndEvent 并存（如网关分支场景）→ 两者都应返回。
     */
    @Test
    public void testGetAdjacentTaskNodesWithEndEventBranch() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        UserTask adjacentA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        BpmnModel model = buildBpmnModel(adjacentA);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.singletonList("nodeB"));
        when(mockNodeFinder.findReachableEndEvents(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.singletonList("endEvent1"));

        List<NextTaskNodeVO> result = nodePreviewWorkflow.getNextTaskNodes(taskId, TraversalMode.ADJACENT);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTaskCode()).isEqualTo("nodeB");
        assertThat(result.get(0).getTaskName()).isEqualTo("部门经理");
        assertThat(result.get(1).getTaskCode()).isEqualTo(NextTaskNodeVO.END_TASK_CODE);
        assertThat(result.get(1).getTaskName()).isEqualTo("流程结束");
        assertThat(result.get(1).isEnd()).isTrue();
    }

    // ======================== 任务锚点 · 全遍历（FULL）节点列表 EndSignal ========================

    /**
     * 全遍历无 UserTask 但下游为 EndEvent → 应返回 end=true 的 VO。
     */
    @Test
    public void testGetNextTaskNodesEndSignal() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        when(mockNodeFinder.findNextUserTasks(definitionId, "nodeA", processInstanceId, new HashMap<>()))
                .thenReturn(Collections.emptyList());
        when(mockNodeFinder.findReachableEndEvents(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.singletonList("endEvent1"));

        List<NextTaskNodeVO> result = nodePreviewWorkflow.getNextTaskNodes(taskId, TraversalMode.FULL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTaskCode()).isEqualTo(NextTaskNodeVO.END_TASK_CODE);
        assertThat(result.get(0).getTaskName()).isEqualTo("流程结束");
        assertThat(result.get(0).isEnd()).isTrue();
    }

    /**
     * 全遍历：下游 UserTask 与 EndEvent 并存（如网关分支场景）→ 两者都应返回。
     */
    @Test
    public void testGetNextTaskNodesWithEndEventBranch() {
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
        when(mockNodeFinder.findReachableEndEvents(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.singletonList("endEvent1"));

        List<NextTaskNodeVO> result = nodePreviewWorkflow.getNextTaskNodes(taskId, TraversalMode.FULL);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTaskCode()).isEqualTo("nodeB");
        assertThat(result.get(0).getTaskName()).isEqualTo("部门经理");
        assertThat(result.get(1).getTaskCode()).isEqualTo(NextTaskNodeVO.END_TASK_CODE);
        assertThat(result.get(1).getTaskName()).isEqualTo("流程结束");
        assertThat(result.get(1).isEnd()).isTrue();
    }

    // ======================== 任务锚点 · 紧邻遍历（ADJACENT）审批人 ========================

    @Test
    public void testGetAdjacentTaskApproversFlatList() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        UserTask adjacentA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        UserTask adjacentB = buildUserTask("nodeC", "总经理", "ceo1", null, null);
        BpmnModel model = buildBpmnModel(adjacentA, adjacentB);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Arrays.asList("nodeB", "nodeC"));

        List<ApproverInfoVO> result = nodePreviewWorkflow.getNextTaskApprovers(taskId, TraversalMode.ADJACENT);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("manager1");
        assertThat(result.get(0).getNodeId()).isEqualTo("nodeB");
        assertThat(result.get(0).getNodeName()).isEqualTo("部门经理");
        assertThat(result.get(1).getId()).isEqualTo("ceo1");
        assertThat(result.get(1).getNodeId()).isEqualTo("nodeC");
        assertThat(result.get(1).getNodeName()).isEqualTo("总经理");
    }

    @Test
    public void testGetAdjacentTaskApproversCandidateUser() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(new HashMap<>());

        UserTask adjacentA = buildUserTask("nodeB", "审批节点", null,
                Arrays.asList("user1", "user2"), null);
        BpmnModel model = buildBpmnModel(adjacentA);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAdjacentUserTasks(definitionId, "nodeA", new HashMap<>()))
                .thenReturn(Collections.singletonList("nodeB"));

        List<ApproverInfoVO> result = nodePreviewWorkflow.getNextTaskApprovers(taskId, TraversalMode.ADJACENT);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo("candidateUser");
        assertThat(result.get(0).getId()).isEqualTo("user1");
        assertThat(result.get(1).getType()).isEqualTo("candidateUser");
        assertThat(result.get(1).getId()).isEqualTo("user2");
    }

    @Test
    public void testGetAdjacentTaskApproversRejectNullTaskId() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextTaskApprovers(null, TraversalMode.ADJACENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    public void testGetAdjacentTaskApproversRejectEmptyTaskId() {
        assertThatThrownBy(() -> nodePreviewWorkflow.getNextTaskApprovers("", TraversalMode.ADJACENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    // ======================== 运行上下文传递（ApproverContext） ========================

    @Test
    public void testDefinitionAnchorPassesContextToResolver() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 5000);

        stubProcessDefinition(processKey, definitionId);

        CapturingApproverResolver capturingResolver = new CapturingApproverResolver();
        NodePreviewWorkflow npw = new NodePreviewWorkflow(mockRepoService, bpmnModelCache,
                mockNodeFinder, capturingResolver, mockUserContext, mockTaskService,
                mockRuntimeService, bpmnFormDataHelper);

        UserTask taskA = buildUserTask("taskA", "主管审批", "supervisor", null, null);
        BpmnModel model = buildBpmnModel(taskA);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, variables))
                .thenReturn(Collections.singletonList("taskA"));

        List<NodeApproverVO> result = npw.getNextNodeApprovers(processKey, TraversalMode.FULL, variables);

        assertThat(result).hasSize(1);
        assertThat(capturingResolver.capturedContext).isNotNull();
        assertThat(capturingResolver.capturedContext.getVariables()).containsEntry("amount", 5000);
        assertThat(capturingResolver.capturedContext.getCurrentUserId()).isEqualTo("u1001");
        assertThat(capturingResolver.capturedContext.getProcessInstanceId()).isNull();
        assertThat(capturingResolver.capturedContext.getTaskId()).isNull();
    }

    @Test
    public void testDefinitionAnchorWithoutVariablesStillPassesCurrentUser() {
        String processKey = "leave";
        String definitionId = "leave:1:abc";

        stubProcessDefinition(processKey, definitionId);

        CapturingApproverResolver capturingResolver = new CapturingApproverResolver();
        NodePreviewWorkflow npw = new NodePreviewWorkflow(mockRepoService, bpmnModelCache,
                mockNodeFinder, capturingResolver, mockUserContext, mockTaskService,
                mockRuntimeService, bpmnFormDataHelper);

        UserTask taskA = buildUserTask("taskA", "审批", "user", null, null);
        BpmnModel model = buildBpmnModel(taskA);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findAllReachableUserTasks(definitionId, null))
                .thenReturn(Collections.singletonList("taskA"));

        npw.getNextNodeApprovers(processKey, TraversalMode.FULL);

        assertThat(capturingResolver.capturedContext).isNotNull();
        assertThat(capturingResolver.capturedContext.getVariables()).isNull();
        assertThat(capturingResolver.capturedContext.getCurrentUserId()).isEqualTo("u1001");
        assertThat(capturingResolver.capturedContext.getProcessInstanceId()).isNull();
        assertThat(capturingResolver.capturedContext.getTaskId()).isNull();
    }

    @Test
    public void testTaskAnchorPassesContextToResolver() {
        String taskId = "task-001";
        String processInstanceId = "pi-001";
        String definitionId = "leave:1:abc";
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 5000);

        Task task = mockTask(taskId, definitionId, processInstanceId, "nodeA");
        when(mockRuntimeService.getVariables(processInstanceId)).thenReturn(variables);

        CapturingApproverResolver capturingResolver = new CapturingApproverResolver();
        NodePreviewWorkflow npw = new NodePreviewWorkflow(mockRepoService, bpmnModelCache,
                mockNodeFinder, capturingResolver, mockUserContext, mockTaskService,
                mockRuntimeService, bpmnFormDataHelper);

        UserTask downstreamA = buildUserTask("nodeB", "部门经理", "manager1", null, null);
        BpmnModel model = buildBpmnModel(downstreamA);
        when(bpmnModelCache.getBpmnModel(definitionId)).thenReturn(model);
        when(mockNodeFinder.findNextUserTasks(definitionId, "nodeA", processInstanceId, variables))
                .thenReturn(Collections.singletonList("nodeB"));

        List<ApproverInfoVO> result = npw.getNextTaskApprovers(taskId, TraversalMode.FULL);

        assertThat(result).hasSize(1);
        assertThat(capturingResolver.capturedContext).isNotNull();
        assertThat(capturingResolver.capturedContext.getVariables()).containsEntry("amount", 5000);
        assertThat(capturingResolver.capturedContext.getCurrentUserId()).isEqualTo("u1001");
        assertThat(capturingResolver.capturedContext.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(capturingResolver.capturedContext.getTaskId()).isEqualTo(taskId);
    }

    // ======================== 辅助方法 ========================

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

    /**
     * 捕获解析器：记录最后一次收到的 {@link ApproverContext}，用于验证上下文传递。
     */
    private static class CapturingApproverResolver implements ApproverResolver {
        ApproverContext capturedContext;

        @Override
        public List<ApproverInfoVO> resolveApprovers(UserTask userTask, ApproverContext context) {
            this.capturedContext = context;
            return Collections.singletonList(ApproverInfoVO.builder()
                    .id("resolved")
                    .type("assignee")
                    .build());
        }
    }
}
