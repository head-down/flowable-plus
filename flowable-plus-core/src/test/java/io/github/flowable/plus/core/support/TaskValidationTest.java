package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TaskValidation 校验工具类单元测试。
 *
 * <p>直接钉住 6 个 static 方法的异常分类与放行/拦截分支矩阵，
 * 作为 8+ 调用点（TaskExecutionWorkflow / CounterSignWorkflow）共用校验口径的快速反馈通道。
 * 重点覆盖 ADR-0034 伪单例放行（运行时非 MI 放行）与 ADR-0035 折返后发起人决策任务豁免两条边界。</p>
 */
class TaskValidationTest {

    private static final String TASK_ID = "task-001";
    private static final String PI_ID = "pi-001";
    private static final String NODE_KEY = "approveNode";
    private static final String USER_A = "user-a";

    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private MultiInstanceDetector mockMultiInstanceDetector;

    @BeforeEach
    void setUp() {
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
    }

    // ======================== validateTaskExists ========================

    @Test
    void testValidateTaskExistsNullTaskIdThrows() {
        assertThatThrownBy(() -> TaskValidation.validateTaskExists(
                mockTaskService, mockHistoryService, null, "审批"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void testValidateTaskExistsActiveTaskReturnsPlusTask() {
        whenActiveTaskQuery(TASK_ID, mockActiveTask(TASK_ID, USER_A));

        PlusTask result = TaskValidation.validateTaskExists(
                mockTaskService, mockHistoryService, TASK_ID, "审批");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(TASK_ID);
        assertThat(result.getProcessInstanceId()).isEqualTo(PI_ID);
        assertThat(result.getTaskDefinitionKey()).isEqualTo(NODE_KEY);
        assertThat(result.getAssignee()).isEqualTo(USER_A);
    }

    @Test
    void testValidateTaskExistsCompletedTaskThrows() {
        whenActiveTaskQuery(TASK_ID, null);
        whenHistoryTaskById(TASK_ID, mock(HistoricTaskInstance.class));

        assertThatThrownBy(() -> TaskValidation.validateTaskExists(
                mockTaskService, mockHistoryService, TASK_ID, "审批"))
                .isInstanceOf(TaskAlreadyCompletedException.class)
                .hasMessageContaining(TASK_ID);
    }

    @Test
    void testValidateTaskExistsNotFoundThrows() {
        whenActiveTaskQuery(TASK_ID, null);
        whenHistoryTaskById(TASK_ID, null);

        assertThatThrownBy(() -> TaskValidation.validateTaskExists(
                mockTaskService, mockHistoryService, TASK_ID, "审批"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(TASK_ID);
    }

    // ======================== validateCurrentUserIsAssignee ========================

    @Test
    void testValidateCurrentUserIsAssigneeMatchPasses() {
        assertThatCode(() -> TaskValidation.validateCurrentUserIsAssignee(
                createTask(USER_A), USER_A, TASK_ID, "审批"))
                .doesNotThrowAnyException();
    }

    @Test
    void testValidateCurrentUserIsAssigneeNullAssigneeThrows() {
        assertThatThrownBy(() -> TaskValidation.validateCurrentUserIsAssignee(
                createTask(null), USER_A, TASK_ID, "审批"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是任务");
    }

    @Test
    void testValidateCurrentUserIsAssigneeMismatchThrows() {
        assertThatThrownBy(() -> TaskValidation.validateCurrentUserIsAssignee(
                createTask("user-b"), USER_A, TASK_ID, "审批"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("不是任务");
    }

    // ======================== validatePreviousNodeAssignee ========================

    @Test
    void testValidatePreviousNodeAssigneeMatchPasses() {
        whenPreviousFinishedTask(Collections.singletonList(createHistoricTask(USER_A)));

        assertThatCode(() -> TaskValidation.validatePreviousNodeAssignee(
                mockHistoryService, PI_ID, NODE_KEY, USER_A, TASK_ID, "撤回"))
                .doesNotThrowAnyException();
    }

    @Test
    void testValidatePreviousNodeAssigneeNoHistoryThrows() {
        whenPreviousFinishedTask(Collections.emptyList());

        assertThatThrownBy(() -> TaskValidation.validatePreviousNodeAssignee(
                mockHistoryService, PI_ID, NODE_KEY, USER_A, TASK_ID, "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("上一节点审批人");
    }

    @Test
    void testValidatePreviousNodeAssigneeMismatchThrows() {
        whenPreviousFinishedTask(Collections.singletonList(createHistoricTask("user-b")));

        assertThatThrownBy(() -> TaskValidation.validatePreviousNodeAssignee(
                mockHistoryService, PI_ID, NODE_KEY, USER_A, TASK_ID, "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("上一节点审批人");
    }

    @Test
    void testValidatePreviousNodeAssigneeNullHistoryAssigneeThrows() {
        whenPreviousFinishedTask(Collections.singletonList(createHistoricTask(null)));

        assertThatThrownBy(() -> TaskValidation.validatePreviousNodeAssignee(
                mockHistoryService, PI_ID, NODE_KEY, USER_A, TASK_ID, "撤回"))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("上一节点审批人");
    }

    // ======================== validateMultiInstance ========================

    @Test
    void testValidateMultiInstanceOnMultiInstancePasses() {
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(true);

        assertThatCode(() -> TaskValidation.validateMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID, "会签"))
                .doesNotThrowAnyException();
    }

    @Test
    void testValidateMultiInstanceOnNormalNodeThrows() {
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isMultiInstance(task)).thenReturn(false);

        assertThatThrownBy(() -> TaskValidation.validateMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID, "会签"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是多实例子任务");
    }

    // ======================== validateNotMultiInstance（4 参版） ========================

    @Test
    void testValidateNotMultiInstanceAllowFalseNonRuntimeMultiInstancePasses() {
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(task)).thenReturn(false);

        assertThatCode(() -> TaskValidation.validateNotMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID, false))
                .doesNotThrowAnyException();
    }

    @Test
    void testValidateNotMultiInstanceAllowFalseRuntimeMultiInstanceThrows() {
        // allow=false：即使命中发起人决策任务也不豁免（豁免仅在 allow=true 时生效）
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(task)).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(task)).thenReturn(true);

        assertThatThrownBy(() -> TaskValidation.validateNotMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testValidateNotMultiInstanceAllowInitiatorButNotInitiatorTaskThrows() {
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(task)).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(task)).thenReturn(false);

        assertThatThrownBy(() -> TaskValidation.validateNotMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testValidateNotMultiInstanceInitiatorDecisionTaskExempted() {
        // ADR-0035：折返后发起人决策任务豁免常规操作拦截
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(task)).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(task)).thenReturn(true);

        assertThatCode(() -> TaskValidation.validateNotMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID, true))
                .doesNotThrowAnyException();
    }

    // ======================== validateNotMultiInstance（3 参版委托） ========================

    @Test
    void testValidateNotMultiInstanceThreeArgNonRuntimeMultiInstancePasses() {
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(task)).thenReturn(false);

        assertThatCode(() -> TaskValidation.validateNotMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void testValidateNotMultiInstanceThreeArgRuntimeMultiInstanceThrows() {
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(task)).thenReturn(true);

        assertThatThrownBy(() -> TaskValidation.validateNotMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    @Test
    void testValidateNotMultiInstanceThreeArgIgnoresInitiatorExemption() {
        // 3 参版委托 allow=false：即使命中发起人决策任务也保持拦截（不豁免）
        PlusTask task = createTask(USER_A);
        when(mockMultiInstanceDetector.isRuntimeMultiInstance(task)).thenReturn(true);
        when(mockMultiInstanceDetector.isInitiatorDecisionTask(task)).thenReturn(true);

        assertThatThrownBy(() -> TaskValidation.validateNotMultiInstance(
                mockMultiInstanceDetector, task, TASK_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多实例子任务");
    }

    // ======================== helpers ========================

    private PlusTask createTask(String assignee) {
        return new PlusTask(TASK_ID, "leave:1:abc", NODE_KEY, PI_ID, assignee,
                null, "审批", "exec-001", new Date());
    }

    private Task mockActiveTask(String taskId, String assignee) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(PI_ID);
        when(task.getTaskDefinitionKey()).thenReturn(NODE_KEY);
        when(task.getAssignee()).thenReturn(assignee);
        return task;
    }

    private HistoricTaskInstance createHistoricTask(String assignee) {
        HistoricTaskInstance hti = mock(HistoricTaskInstance.class);
        when(hti.getAssignee()).thenReturn(assignee);
        return hti;
    }

    private void whenActiveTaskQuery(String taskId, Task result) {
        TaskQuery query = mock(TaskQuery.class);
        when(mockTaskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(taskId)).thenReturn(query);
        when(query.singleResult()).thenReturn(result);
    }

    private void whenHistoryTaskById(String taskId, HistoricTaskInstance result) {
        HistoricTaskInstanceQuery query = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(query);
        when(query.taskId(taskId)).thenReturn(query);
        when(query.singleResult()).thenReturn(result);
    }

    private void whenPreviousFinishedTask(List<HistoricTaskInstance> result) {
        HistoricTaskInstanceQuery query = mock(HistoricTaskInstanceQuery.class);
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(PI_ID)).thenReturn(query);
        when(query.taskDefinitionKey(NODE_KEY)).thenReturn(query);
        when(query.finished()).thenReturn(query);
        when(query.orderByHistoricTaskInstanceEndTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        when(query.listPage(0, 1)).thenReturn(result);
    }
}
