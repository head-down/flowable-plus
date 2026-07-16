package io.github.flowable.plus.core;

import io.github.flowable.plus.core.vo.DoneTaskVO;
import io.github.flowable.plus.core.vo.TodoTaskVO;
import org.flowable.engine.*;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.idm.api.Group;
import org.flowable.idm.api.GroupQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.flowable.plus.core.domain.PageResult;
import io.github.flowable.plus.core.dto.TaskQueryDTO;
import io.github.flowable.plus.core.support.VOAssembler;
import io.github.flowable.plus.core.workflow.NodePreviewWorkflow;
import io.github.flowable.plus.core.workflow.ProcessQueryWorkflow;
import io.github.flowable.plus.core.workflow.TaskQueryModule;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2/S3: queryTodoTasks + queryDoneTasks 单元测试。
 */
public class TaskListOperationsTest {

    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private RepositoryService mockRepoService;
    private IdentityService mockIdentityService;
    private TaskQuery mockTaskQuery;
    private HistoricTaskInstanceQuery mockHistoricQuery;
    private FlowablePlus flowablePlus;

    @BeforeEach
    public void setUp() {
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockRepoService = mock(RepositoryService.class);
        mockIdentityService = mock(IdentityService.class);
        mockTaskQuery = mock(TaskQuery.class);
        mockHistoricQuery = mock(HistoricTaskInstanceQuery.class);

        when(mockTaskService.createTaskQuery()).thenReturn(mockTaskQuery);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(mockHistoricQuery);

        // Group query: 默认返回空组
        GroupQuery mockGroupQuery = mock(GroupQuery.class);
        when(mockIdentityService.createGroupQuery()).thenReturn(mockGroupQuery);
        when(mockGroupQuery.groupMember(anyString())).thenReturn(mockGroupQuery);
        when(mockGroupQuery.list()).thenReturn(Collections.emptyList());

        // 构建依赖链: VOAssembler -> TaskQueryModule -> FlowablePlus
        VOAssembler voAssembler = new VOAssembler(mockRepoService, mockHistoryService);
        TaskQueryModule taskQueryModule = new TaskQueryModule(mockTaskService, mockHistoryService,
                mockIdentityService, voAssembler);
        ProcessQueryWorkflow processQueryWorkflow = mock(ProcessQueryWorkflow.class);
        NodePreviewWorkflow nodePreviewWorkflow = mock(NodePreviewWorkflow.class);

        flowablePlus = new FlowablePlus(taskQueryModule, processQueryWorkflow, nodePreviewWorkflow);
    }

    // ======================== 参数校验 ========================

    @Test
    public void testTodoRejectNullUserId() {
        assertThatThrownBy(() -> flowablePlus.queryTodoTasks(null, new TaskQueryDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    public void testTodoRejectEmptyUserId() {
        assertThatThrownBy(() -> flowablePlus.queryTodoTasks("", new TaskQueryDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    public void testDoneRejectNullUserId() {
        assertThatThrownBy(() -> flowablePlus.queryDoneTasks(null, new TaskQueryDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    public void testDoneRejectEmptyUserId() {
        assertThatThrownBy(() -> flowablePlus.queryDoneTasks("", new TaskQueryDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    // ======================== S2: 待办列表 ========================

    @Test
    public void testTodoBasicQuery() {
        stubTaskQueryOr();
        when(mockTaskQuery.count()).thenReturn(0L);
        when(mockTaskQuery.orderByTaskCreateTime()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.desc()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.listPage(0, 20)).thenReturn(Collections.emptyList());

        PageResult<TodoTaskVO> result = flowablePlus.queryTodoTasks("user1", new TaskQueryDTO());

        assertThat(result.getTotal()).isEqualTo(0);
        assertThat(result.getPageNum()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getRecords()).isEmpty();
    }

    @Test
    public void testTodoWithSingleTask() {
        stubTaskQueryOr();
        when(mockTaskQuery.count()).thenReturn(1L);

        Task task = createMockTask("task-1", "pi-1", "deptApprove", "部门审批", "user1", "leave:1:abc");
        when(mockTaskQuery.orderByTaskCreateTime()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.desc()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.listPage(0, 20)).thenReturn(Collections.singletonList(task));

        stubProcessDefinition("leave:1:abc", "leave", "请假审批");
        stubHistoricProcessInstance("pi-1", "biz-001", "initiator");

        PageResult<TodoTaskVO> result = flowablePlus.queryTodoTasks("user1", new TaskQueryDTO());

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
        TodoTaskVO vo = result.getRecords().get(0);
        assertThat(vo.getTaskId()).isEqualTo("task-1");
        assertThat(vo.getTaskName()).isEqualTo("部门审批");
        assertThat(vo.getProcessInstanceId()).isEqualTo("pi-1");
        assertThat(vo.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(vo.getProcessDefinitionName()).isEqualTo("请假审批");
        assertThat(vo.getBusinessKey()).isEqualTo("biz-001");
        assertThat(vo.getStartUserId()).isEqualTo("initiator");
        assertThat(vo.getAssignee()).isEqualTo("user1");
    }

    @Test
    public void testTodoWithFilters() {
        stubTaskQueryOr();
        when(mockTaskQuery.processDefinitionKey("leave")).thenReturn(mockTaskQuery);
        when(mockTaskQuery.taskName("部门审批")).thenReturn(mockTaskQuery);
        when(mockTaskQuery.processInstanceBusinessKeyLike("%借款%")).thenReturn(mockTaskQuery);
        when(mockTaskQuery.count()).thenReturn(0L);
        when(mockTaskQuery.orderByTaskCreateTime()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.desc()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.listPage(0, 10)).thenReturn(Collections.emptyList());

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageSize(10);
        query.setProcessDefinitionKey("leave");
        query.setTaskName("部门审批");
        query.setKeyword("借款");

        PageResult<TodoTaskVO> result = flowablePlus.queryTodoTasks("user1", query);

        assertThat(result.getTotal()).isEqualTo(0);
        assertThat(result.getPageSize()).isEqualTo(10);
    }

    @Test
    public void testTodoWithEnhancer() {
        stubTaskQueryOr();
        when(mockTaskQuery.processDefinitionKey("leave")).thenReturn(mockTaskQuery);
        when(mockTaskQuery.count()).thenReturn(0L);
        when(mockTaskQuery.orderByTaskCreateTime()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.desc()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.listPage(0, 20)).thenReturn(Collections.emptyList());

        Consumer<TaskQuery> enhancer = q -> q.processVariableValueEquals("amount", 1000);
        when(mockTaskQuery.processVariableValueEquals("amount", 1000)).thenReturn(mockTaskQuery);

        TaskQueryDTO query = new TaskQueryDTO();
        query.setProcessDefinitionKey("leave");

        PageResult<TodoTaskVO> result = flowablePlus.queryTodoTasks("user1", query, enhancer);

        assertThat(result.getTotal()).isEqualTo(0);
        verify(mockTaskQuery).processVariableValueEquals("amount", 1000);
    }

    @Test
    public void testTodoWithCandidateGroups() {
        // 模拟用户属于组 "deptMgr"
        Group group = mock(Group.class);
        when(group.getId()).thenReturn("deptMgr");
        GroupQuery mockGroupQuery = mock(GroupQuery.class);
        when(mockIdentityService.createGroupQuery()).thenReturn(mockGroupQuery);
        when(mockGroupQuery.groupMember("user1")).thenReturn(mockGroupQuery);
        when(mockGroupQuery.list()).thenReturn(Collections.singletonList(group));

        stubTaskQueryOrWithGroup();
        when(mockTaskQuery.count()).thenReturn(0L);
        when(mockTaskQuery.orderByTaskCreateTime()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.desc()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.listPage(0, 20)).thenReturn(Collections.emptyList());

        PageResult<TodoTaskVO> result = flowablePlus.queryTodoTasks("user1", new TaskQueryDTO());

        assertThat(result.getTotal()).isEqualTo(0);
    }

    // ======================== S3: 已办列表 ========================

    @Test
    public void testDoneBasicQuery() {
        stubDoneBaseQuery();
        when(mockHistoricQuery.count()).thenReturn(0L);
        when(mockHistoricQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.desc()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.list()).thenReturn(Collections.emptyList());

        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks("user1", new TaskQueryDTO());

        assertThat(result.getTotal()).isEqualTo(0);
        assertThat(result.getRecords()).isEmpty();
    }

    @Test
    public void testDoneWithSingleTask() {
        stubDoneBaseQuery();

        Date endTime = new Date();
        HistoricTaskInstance hti = createMockHistoricTask("ht-1", "pi-1", "deptApprove", "部门审批",
                "user1", endTime);
        when(mockHistoricQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.desc()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.list()).thenReturn(Collections.singletonList(hti));

        stubProcessDefinition("leave:1:abc", "leave", "请假审批");
        stubHistoricProcessInstance("pi-1", "biz-001", "initiator");

        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks("user1", new TaskQueryDTO());

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
        DoneTaskVO vo = result.getRecords().get(0);
        assertThat(vo.getTaskId()).isEqualTo("ht-1");
        assertThat(vo.getTaskName()).isEqualTo("部门审批");
        assertThat(vo.getProcessDefinitionKey()).isEqualTo("leave");
        assertThat(vo.getProcessDefinitionName()).isEqualTo("请假审批");
        assertThat(vo.getBusinessKey()).isEqualTo("biz-001");
        assertThat(vo.getStartUserId()).isEqualTo("initiator");
        assertThat(vo.getAssignee()).isEqualTo("user1");
        assertThat(vo.getEndTime()).isEqualTo(endTime);
    }

    @Test
    public void testDoneMultiInstanceDedup() {
        stubDoneBaseQuery();

        Date earlyEnd = new Date(100000);
        Date lateEnd = new Date(200000);

        // 两个会签子任务，同节点
        HistoricTaskInstance hti1 = createMockHistoricTask("ht-1", "pi-1", "counterSign",
                "会签审批", "user1", earlyEnd);
        HistoricTaskInstance hti2 = createMockHistoricTask("ht-2", "pi-1", "counterSign",
                "会签审批", "user2", lateEnd);

        when(mockHistoricQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.desc()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.list()).thenReturn(Arrays.asList(hti2, hti1));

        stubProcessDefinition("leave:1:abc", "leave", "请假审批");
        stubHistoricProcessInstance("pi-1", "biz-001", "initiator");

        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks("user1", new TaskQueryDTO());

        // 去重后只有 1 条
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
        // 保留 endTime 最新的
        assertThat(result.getRecords().get(0).getEndTime()).isEqualTo(lateEnd);
    }

    @Test
    public void testDoneMultiInstanceDifferentNodes() {
        stubDoneBaseQuery();

        Date endTime = new Date();
        HistoricTaskInstance hti1 = createMockHistoricTask("ht-1", "pi-1", "deptApprove",
                "部门审批", "user1", endTime);
        HistoricTaskInstance hti2 = createMockHistoricTask("ht-2", "pi-1", "hrApprove",
                "HR审批", "user1", endTime);

        when(mockHistoricQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.desc()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.list()).thenReturn(Arrays.asList(hti2, hti1));

        stubProcessDefinition("leave:1:abc", "leave", "请假审批");
        stubHistoricProcessInstance("pi-1", "biz-001", "initiator");

        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks("user1", new TaskQueryDTO());

        // 不同节点不应去重
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).hasSize(2);
    }

    @Test
    public void testDoneWithFilters() {
        stubDoneBaseQuery();
        when(mockHistoricQuery.processDefinitionKey("leave")).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.taskName("部门审批")).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.processInstanceBusinessKeyLike("%借款%")).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.desc()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.list()).thenReturn(Collections.emptyList());

        TaskQueryDTO query = new TaskQueryDTO();
        query.setProcessDefinitionKey("leave");
        query.setTaskName("部门审批");
        query.setKeyword("借款");

        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks("user1", query);

        assertThat(result.getTotal()).isEqualTo(0);
    }

    @Test
    public void testDoneWithEnhancer() {
        stubDoneBaseQuery();
        when(mockHistoricQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.desc()).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.list()).thenReturn(Collections.emptyList());

        Consumer<HistoricTaskInstanceQuery> enhancer = q ->
                q.processVariableValueEquals("amount", 1000);
        when(mockHistoricQuery.processVariableValueEquals("amount", 1000)).thenReturn(mockHistoricQuery);

        PageResult<DoneTaskVO> result = flowablePlus.queryDoneTasks("user1", new TaskQueryDTO(), enhancer);

        assertThat(result.getTotal()).isEqualTo(0);
        verify(mockHistoricQuery).processVariableValueEquals("amount", 1000);
    }

    // ======================== 分页验证 ========================

    @Test
    public void testPaginationParams() {
        stubTaskQueryOr();
        when(mockTaskQuery.count()).thenReturn(50L);
        when(mockTaskQuery.orderByTaskCreateTime()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.desc()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.listPage(30, 15)).thenReturn(Collections.emptyList());

        TaskQueryDTO query = new TaskQueryDTO();
        query.setPageNum(3);
        query.setPageSize(15);

        PageResult<TodoTaskVO> result = flowablePlus.queryTodoTasks("user1", query);

        assertThat(result.getTotal()).isEqualTo(50);
        assertThat(result.getPageNum()).isEqualTo(3);
        assertThat(result.getPageSize()).isEqualTo(15);
    }

    // ======================== Test Helpers ========================

    private void stubTaskQueryOr() {
        TaskQuery mockOrQuery = mock(TaskQuery.class);
        when(mockTaskQuery.or()).thenReturn(mockOrQuery);
        when(mockOrQuery.taskAssignee(anyString())).thenReturn(mockOrQuery);
        when(mockOrQuery.taskCandidateUser(anyString())).thenReturn(mockOrQuery);
        when(mockOrQuery.endOr()).thenReturn(mockTaskQuery);
    }

    private void stubTaskQueryOrWithGroup() {
        TaskQuery mockOrQuery = mock(TaskQuery.class);
        when(mockTaskQuery.or()).thenReturn(mockOrQuery);
        when(mockOrQuery.taskAssignee(anyString())).thenReturn(mockOrQuery);
        when(mockOrQuery.taskCandidateUser(anyString())).thenReturn(mockOrQuery);
        when(mockOrQuery.taskCandidateGroupIn(anyCollection())).thenReturn(mockOrQuery);
        when(mockOrQuery.endOr()).thenReturn(mockTaskQuery);
    }

    private void stubDoneBaseQuery() {
        when(mockHistoricQuery.taskAssignee(anyString())).thenReturn(mockHistoricQuery);
        when(mockHistoricQuery.finished()).thenReturn(mockHistoricQuery);
    }

    private void stubProcessDefinition(String defId, String key, String name) {
        ProcessDefinition pd = mock(ProcessDefinition.class);
        when(pd.getKey()).thenReturn(key);
        when(pd.getName()).thenReturn(name);

        ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
        when(mockRepoService.createProcessDefinitionQuery()).thenReturn(pdQuery);
        when(pdQuery.processDefinitionId(defId)).thenReturn(pdQuery);
        when(pdQuery.singleResult()).thenReturn(pd);
    }

    private void stubHistoricProcessInstance(String instanceId, String businessKey, String startUserId) {
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getBusinessKey()).thenReturn(businessKey);
        when(hpi.getStartUserId()).thenReturn(startUserId);

        HistoricProcessInstanceQuery hpiQuery = mock(HistoricProcessInstanceQuery.class);
        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(hpiQuery);
        when(hpiQuery.processInstanceId(instanceId)).thenReturn(hpiQuery);
        when(hpiQuery.singleResult()).thenReturn(hpi);
    }

    private Task createMockTask(String taskId, String processInstanceId, String taskDefKey,
            String taskName, String assignee, String processDefinitionId) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(processInstanceId);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(task.getName()).thenReturn(taskName);
        when(task.getAssignee()).thenReturn(assignee);
        when(task.getProcessDefinitionId()).thenReturn(processDefinitionId);
        when(task.getCreateTime()).thenReturn(new Date());
        return task;
    }

    private HistoricTaskInstance createMockHistoricTask(String taskId, String processInstanceId,
            String taskDefKey, String taskName, String assignee, Date endTime) {
        HistoricTaskInstance hti = mock(HistoricTaskInstance.class);
        when(hti.getId()).thenReturn(taskId);
        when(hti.getProcessInstanceId()).thenReturn(processInstanceId);
        when(hti.getTaskDefinitionKey()).thenReturn(taskDefKey);
        when(hti.getName()).thenReturn(taskName);
        when(hti.getAssignee()).thenReturn(assignee);
        when(hti.getProcessDefinitionId()).thenReturn("leave:1:abc");
        when(hti.getCreateTime()).thenReturn(new Date(endTime.getTime() - 3600000));
        when(hti.getEndTime()).thenReturn(endTime);
        when(hti.getDeleteReason()).thenReturn(null);
        return hti;
    }
}
