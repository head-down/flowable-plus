package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.vo.ApprovalPersonnelVO;
import io.github.flowable.plus.core.vo.UserInfo;
import io.github.flowable.plus.core.spi.UserInfoResolver;
import org.assertj.core.api.SoftAssertions;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PersonnelWorkflow 单元测试。
 */
public class PersonnelWorkflowTest {

    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private TaskQuery mockTaskQuery;
    private HistoricTaskInstanceQuery mockHistoricTaskQuery;
    private HistoricProcessInstanceQuery mockHistoricProcessQuery;

    @BeforeEach
    public void setUp() {
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockTaskQuery = mock(TaskQuery.class);
        mockHistoricTaskQuery = mock(HistoricTaskInstanceQuery.class);
        mockHistoricProcessQuery = mock(HistoricProcessInstanceQuery.class);
    }

    @Test
    public void testConstructorRejectsNullTaskService() {
        assertThatThrownBy(() -> new PersonnelWorkflow(null, mockHistoryService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskService 不可为 null");
    }

    @Test
    public void testConstructorRejectsNullHistoryService() {
        assertThatThrownBy(() -> new PersonnelWorkflow(mockTaskService, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HistoryService 不可为 null");
    }

    @Test
    public void testGetApprovalPersonnelRejectsNullId() {
        PersonnelWorkflow workflow = new PersonnelWorkflow(mockTaskService, mockHistoryService, null);
        assertThatThrownBy(() -> workflow.getApprovalPersonnel(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testGetApprovalPersonnelRejectsEmptyId() {
        PersonnelWorkflow workflow = new PersonnelWorkflow(mockTaskService, mockHistoryService, null);
        assertThatThrownBy(() -> workflow.getApprovalPersonnel(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testGetApprovalPersonnelProcessNotExists() {
        // 模拟流程实例不存在的情况
        when(mockTaskService.createTaskQuery()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.processInstanceId(anyString())).thenReturn(mockTaskQuery);
        when(mockTaskQuery.active()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.processInstanceId(anyString())).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.finished()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.asc()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.list()).thenReturn(Collections.emptyList());

        when(mockHistoryService.createHistoricProcessInstanceQuery()).thenReturn(mockHistoricProcessQuery);
        when(mockHistoricProcessQuery.processInstanceId(anyString())).thenReturn(mockHistoricProcessQuery);
        when(mockHistoricProcessQuery.singleResult()).thenReturn(null);

        PersonnelWorkflow workflow = new PersonnelWorkflow(mockTaskService, mockHistoryService, null);
        ApprovalPersonnelVO result = workflow.getApprovalPersonnel("non-existent-id");

        assertThat(result).isNotNull();
        assertThat(result.getApproved()).isEmpty();
        assertThat(result.getPending()).isEmpty();
    }

    @Test
    public void testGetApprovalPersonnelBasicGrouping() {
        String processInstanceId = "proc-1";

        // 模拟活跃任务（2 个不同的 assignee，其中 1 个重复出现 2 次）
        List<Task> activeTasks = new ArrayList<>();
        activeTasks.add(createMockTask("task-1", "user-a", new Date(1000)));
        activeTasks.add(createMockTask("task-2", "user-b", new Date(2000)));
        activeTasks.add(createMockTask("task-3", "user-a", new Date(1500))); // 重复 user-a

        when(mockTaskService.createTaskQuery()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.processInstanceId(processInstanceId)).thenReturn(mockTaskQuery);
        when(mockTaskQuery.active()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.list()).thenReturn(activeTasks);

        // 模拟历史任务（2 个不同的 assignee）
        Date approvalTime1 = new Date(500);
        Date approvalTime2 = new Date(800);
        List<HistoricTaskInstance> historicTasks = new ArrayList<>();
        historicTasks.add(createMockHistoricTask("hit-1", "user-c", approvalTime1));
        historicTasks.add(createMockHistoricTask("hit-2", "user-d", approvalTime2));
        historicTasks.add(createMockHistoricTask("hit-3", "user-c", new Date(900))); // 重复 user-c

        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.processInstanceId(processInstanceId)).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.finished()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.asc()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.list()).thenReturn(historicTasks);

        PersonnelWorkflow workflow = new PersonnelWorkflow(mockTaskService, mockHistoryService, null);
        ApprovalPersonnelVO result = workflow.getApprovalPersonnel(processInstanceId);

        SoftAssertions softly = new SoftAssertions();
        // 已审批：user-c（取首次出现）, user-d，按时间升序
        softly.assertThat(result.getApproved()).hasSize(2);
        softly.assertThat(result.getApproved().get(0).getUserId()).isEqualTo("user-c");
        softly.assertThat(result.getApproved().get(0).getApprovalTime()).isEqualTo(approvalTime1);
        softly.assertThat(result.getApproved().get(1).getUserId()).isEqualTo("user-d");
        softly.assertThat(result.getApproved().get(1).getApprovalTime()).isEqualTo(approvalTime2);

        // 未审批：user-a, user-b（去重，按任务创建时间升序）
        softly.assertThat(result.getPending()).hasSize(2);
        softly.assertThat(result.getPending().get(0).getUserId()).isEqualTo("user-a");
        softly.assertThat(result.getPending().get(0).getTaskId()).isNotNull();
        softly.assertThat(result.getPending().get(1).getUserId()).isEqualTo("user-b");
        softly.assertThat(result.getPending().get(1).getTaskId()).isNotNull();

        softly.assertAll();
    }

    @Test
    public void testGetApprovalPersonnelWithUserInfoResolver() {
        String processInstanceId = "proc-1";

        // 模拟已审批人员
        List<Task> activeTasks = Collections.emptyList();
        when(mockTaskService.createTaskQuery()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.processInstanceId(processInstanceId)).thenReturn(mockTaskQuery);
        when(mockTaskQuery.active()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.list()).thenReturn(activeTasks);

        List<HistoricTaskInstance> historicTasks = Collections.singletonList(
                createMockHistoricTask("hit-1", "user-x", new Date(100)));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.processInstanceId(processInstanceId)).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.finished()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.asc()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.list()).thenReturn(historicTasks);

        // 自定义 UserInfoResolver
        UserInfoResolver resolver = userIds -> Collections.singletonMap("user-x",
                UserInfo.builder().nickName("张三").deptId("D001").deptName("研发部").build());

        PersonnelWorkflow workflow = new PersonnelWorkflow(mockTaskService, mockHistoryService, resolver);
        ApprovalPersonnelVO result = workflow.getApprovalPersonnel(processInstanceId);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result.getApproved()).hasSize(1);
        softly.assertThat(result.getApproved().get(0).getUserId()).isEqualTo("user-x");
        softly.assertThat(result.getApproved().get(0).getNickName()).isEqualTo("张三");
        softly.assertThat(result.getApproved().get(0).getDeptId()).isEqualTo("D001");
        softly.assertThat(result.getApproved().get(0).getDeptName()).isEqualTo("研发部");
        softly.assertThat(result.getPending()).isEmpty();
        softly.assertAll();
    }

    @Test
    public void testGetApprovalPersonnelWithoutResolverReturnsNullFields() {
        String processInstanceId = "proc-1";

        List<Task> activeTasks = Collections.emptyList();
        when(mockTaskService.createTaskQuery()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.processInstanceId(processInstanceId)).thenReturn(mockTaskQuery);
        when(mockTaskQuery.active()).thenReturn(mockTaskQuery);
        when(mockTaskQuery.list()).thenReturn(activeTasks);

        List<HistoricTaskInstance> historicTasks = Collections.singletonList(
                createMockHistoricTask("hit-1", "user-x", new Date(100)));
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.processInstanceId(processInstanceId)).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.finished()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.asc()).thenReturn(mockHistoricTaskQuery);
        when(mockHistoricTaskQuery.list()).thenReturn(historicTasks);

        PersonnelWorkflow workflow = new PersonnelWorkflow(mockTaskService, mockHistoryService, null);
        ApprovalPersonnelVO result = workflow.getApprovalPersonnel(processInstanceId);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result.getApproved()).hasSize(1);
        softly.assertThat(result.getApproved().get(0).getUserId()).isEqualTo("user-x");
        softly.assertThat(result.getApproved().get(0).getNickName()).isNull();
        softly.assertThat(result.getApproved().get(0).getDeptId()).isNull();
        softly.assertThat(result.getApproved().get(0).getDeptName()).isNull();
        softly.assertAll();
    }

    private Task createMockTask(String id, String assignee, Date createTime) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getAssignee()).thenReturn(assignee);
        when(task.getCreateTime()).thenReturn(createTime);
        return task;
    }

    private HistoricTaskInstance createMockHistoricTask(String id, String assignee, Date endTime) {
        HistoricTaskInstance hti = mock(HistoricTaskInstance.class);
        when(hti.getId()).thenReturn(id);
        when(hti.getAssignee()).thenReturn(assignee);
        when(hti.getEndTime()).thenReturn(endTime);
        when(hti.getCreateTime()).thenReturn(new Date(endTime.getTime() - 1000));
        return hti;
    }
}
