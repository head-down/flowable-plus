package io.github.flowable.plus.core;

import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.vo.ApproverInfoVO;
import io.github.flowable.plus.core.vo.NodeApproverVO;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.*;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5: getNextNodeApproversByProcessKey 单元测试。
 */
public class NodePreviewOperationsTest {

    private ProcessEngine mockEngine;
    private RepositoryService mockRepoService;
    private NodeFinder mockNodeFinder;
    private BpmnModelCache bpmnModelCache;
    private GroupResolver mockGroupResolver;
    private FlowablePlus flowablePlus;

    @BeforeEach
    public void setUp() {
        mockEngine = mock(ProcessEngine.class);
        mockRepoService = mock(RepositoryService.class);
        mockNodeFinder = mock(NodeFinder.class);
        mockGroupResolver = mock(GroupResolver.class);

        when(mockEngine.getRepositoryService()).thenReturn(mockRepoService);
        when(mockEngine.getRuntimeService()).thenReturn(mock(RuntimeService.class));
        when(mockEngine.getTaskService()).thenReturn(mock(TaskService.class));
        when(mockEngine.getHistoryService()).thenReturn(mock(HistoryService.class));
        when(mockEngine.getIdentityService()).thenReturn(mock(IdentityService.class));

        ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
        when(config.getExpressionManager()).thenReturn(mock(ExpressionManager.class));
        when(mockEngine.getProcessEngineConfiguration()).thenReturn(config);

        UserContext userContext = () -> "testUser";
        bpmnModelCache = new DefaultBpmnModelCache(mockRepoService);

        flowablePlus = new FlowablePlus(mockEngine, userContext, mockNodeFinder, bpmnModelCache,
                mockGroupResolver, null);
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

        UserContext userContext = () -> "testUser";
        FlowablePlus fpWithoutResolver = new FlowablePlus(mockEngine, userContext, mockNodeFinder,
                bpmnModelCache, null, null);

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

    // ======================== 辅助方法 ========================

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
