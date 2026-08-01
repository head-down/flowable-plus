package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.exception.AmbiguousPreviousNodeException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.strategy.PreviousNodeResolvers;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PreviousNodeAuthorizer 单元测试，覆盖单节点/多节点/零节点/策略降级等场景。
 *
 * @author flowable-plus
 */
public class PreviousNodeAuthorizerTest {

    private static final String USER_ID = "user1";
    private static final String TASK_ID = "task-001";
    private static final String PROCESS_INSTANCE_ID = "pi-001";
    private static final String PROCESS_DEFINITION_ID = "pd:1:001";
    private static final String TASK_DEFINITION_KEY = "taskA";
    private static final String PREV_NODE_ID = "prevNode";
    private static final String PREV_NODE_2 = "prevNode2";

    private TaskService mockTaskService;
    private HistoryService mockHistoryService;
    private NodeFinder mockNodeFinder;
    private PreviousNodeAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        mockTaskService = mock(TaskService.class);
        mockHistoryService = mock(HistoryService.class);
        mockNodeFinder = mock(NodeFinder.class);
        authorizer = new PreviousNodeAuthorizer(mockTaskService, mockHistoryService, mockNodeFinder);
    }

    // ========================== 单节点场景 ==========================

    @Test
    void shouldReturnTrueWhenSinglePrevNodeAndUserMatches() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Collections.singletonList(PREV_NODE_ID));

        HistoricTaskInstance prevTask = mock(HistoricTaskInstance.class);
        when(prevTask.getAssignee()).thenReturn(USER_ID);
        stubHistoricTaskQuery(PREV_NODE_ID, prevTask);

        assertThat(authorizer.isAuthorized(USER_ID, TASK_ID)).isTrue();
    }

    @Test
    void shouldReturnFalseWhenSinglePrevNodeAndUserMismatch() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Collections.singletonList(PREV_NODE_ID));

        HistoricTaskInstance prevTask = mock(HistoricTaskInstance.class);
        when(prevTask.getAssignee()).thenReturn("otherUser");
        stubHistoricTaskQuery(PREV_NODE_ID, prevTask);

        assertThat(authorizer.isAuthorized(USER_ID, TASK_ID)).isFalse();
    }

    @Test
    void shouldReturnFalseWhenSinglePrevNodeHasNoHistoricTask() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Collections.singletonList(PREV_NODE_ID));

        stubHistoricTaskQuery(PREV_NODE_ID, null);

        assertThat(authorizer.isAuthorized(USER_ID, TASK_ID)).isFalse();
    }

    // ========================== 零节点场景 ==========================

    @Test
    void shouldReturnFalseWhenNoPreviousNode() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenThrow(new NoPreviousNodeException("无上一审批节点"));

        assertThat(authorizer.isAuthorized(USER_ID, TASK_ID)).isFalse();
    }

    // ========================== 任务不存在场景 ==========================

    @Test
    void shouldReturnFalseWhenTaskNotFound() {
        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.taskId(TASK_ID)).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(null);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);

        assertThat(authorizer.isAuthorized(USER_ID, TASK_ID)).isFalse();
    }

    // ========================== 多节点场景 ==========================

    @Test
    void shouldThrowWhenMultiplePrevNodesAndNoStrategy() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Arrays.asList(PREV_NODE_ID, PREV_NODE_2));

        assertThatThrownBy(() -> authorizer.isAuthorized(USER_ID, TASK_ID))
                .isInstanceOf(AmbiguousPreviousNodeException.class)
                .hasMessageContaining("当前节点的前置节点存在多个")
                .hasMessageContaining(PREV_NODE_ID)
                .hasMessageContaining(PREV_NODE_2);
    }

    @Test
    void shouldUseFirstCandidateStrategyWhenMultiplePrevNodes() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Arrays.asList(PREV_NODE_ID, PREV_NODE_2));

        HistoricTaskInstance prevTask = mock(HistoricTaskInstance.class);
        when(prevTask.getAssignee()).thenReturn(USER_ID);
        stubHistoricTaskQuery(PREV_NODE_ID, prevTask);

        assertThat(authorizer.isAuthorized(USER_ID, TASK_ID, PreviousNodeResolvers.firstCandidate())).isTrue();
    }

    @Test
    void shouldUseLatestEndedStrategyWhenMultiplePrevNodes() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Arrays.asList(PREV_NODE_ID, PREV_NODE_2));

        // latestEnded 查询每个候选的结束时间
        HistoricActivityInstanceQuery aq1 = mock(HistoricActivityInstanceQuery.class);
        when(aq1.processInstanceId(PROCESS_INSTANCE_ID)).thenReturn(aq1);
        when(aq1.activityId(PREV_NODE_ID)).thenReturn(aq1);
        when(aq1.finished()).thenReturn(aq1);
        when(aq1.orderByHistoricActivityInstanceEndTime()).thenReturn(aq1);
        when(aq1.desc()).thenReturn(aq1);
        HistoricActivityInstance ai1 = mock(HistoricActivityInstance.class);
        when(ai1.getEndTime()).thenReturn(new Date(1000L));
        when(aq1.listPage(0, 1)).thenReturn(Collections.singletonList(ai1));

        HistoricActivityInstanceQuery aq2 = mock(HistoricActivityInstanceQuery.class);
        when(aq2.processInstanceId(PROCESS_INSTANCE_ID)).thenReturn(aq2);
        when(aq2.activityId(PREV_NODE_2)).thenReturn(aq2);
        when(aq2.finished()).thenReturn(aq2);
        when(aq2.orderByHistoricActivityInstanceEndTime()).thenReturn(aq2);
        when(aq2.desc()).thenReturn(aq2);
        HistoricActivityInstance ai2 = mock(HistoricActivityInstance.class);
        when(ai2.getEndTime()).thenReturn(new Date(2000L));
        when(aq2.listPage(0, 1)).thenReturn(Collections.singletonList(ai2));

        when(mockHistoryService.createHistoricActivityInstanceQuery())
                .thenReturn(aq1, aq2);

        // latestEnded 选 PREV_NODE_2（结束时间更晚），校验其审批人
        HistoricTaskInstance prevTask = mock(HistoricTaskInstance.class);
        when(prevTask.getAssignee()).thenReturn(USER_ID);
        stubHistoricTaskQuery(PREV_NODE_2, prevTask);

        assertThat(authorizer.isAuthorized(USER_ID, TASK_ID, PreviousNodeResolvers.latestEnded())).isTrue();
    }

    // ========================== 重载方法 ==========================

    @Test
    void shouldThrowWhenOverloadWithNullStrategyAndMultipleNodes() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Arrays.asList(PREV_NODE_ID, PREV_NODE_2));

        assertThatThrownBy(() -> authorizer.isAuthorized(USER_ID, TASK_ID, null))
                .isInstanceOf(AmbiguousPreviousNodeException.class);
    }

    @Test
    void shouldThrowWhenStrategyReturnsNull() {
        stubTask();
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, PROCESS_INSTANCE_ID))
                .thenReturn(Arrays.asList(PREV_NODE_ID, PREV_NODE_2));

        assertThatThrownBy(() -> authorizer.isAuthorized(USER_ID, TASK_ID,
                (candidates, piId, hs) -> null))
                .isInstanceOf(AmbiguousPreviousNodeException.class)
                .hasMessageContaining("未能从多个前置节点中确定目标节点");
    }

    // ========================== helper ==========================

    private void stubTask() {
        Task task = mock(Task.class);
        when(task.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);
        when(task.getProcessDefinitionId()).thenReturn(PROCESS_DEFINITION_ID);
        when(task.getTaskDefinitionKey()).thenReturn(TASK_DEFINITION_KEY);

        TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.taskId(TASK_ID)).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(task);
        when(mockTaskService.createTaskQuery()).thenReturn(taskQuery);
    }

    private void stubHistoricTaskQuery(String nodeId, HistoricTaskInstance result) {
        HistoricTaskInstanceQuery query = mock(HistoricTaskInstanceQuery.class);
        when(query.processInstanceId(PROCESS_INSTANCE_ID)).thenReturn(query);
        when(query.taskDefinitionKey(nodeId)).thenReturn(query);
        when(query.finished()).thenReturn(query);
        when(query.orderByHistoricTaskInstanceEndTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        if (result != null) {
            when(query.listPage(0, 1)).thenReturn(Collections.singletonList(result));
        } else {
            when(query.listPage(0, 1)).thenReturn(Collections.emptyList());
        }
        when(mockHistoryService.createHistoricTaskInstanceQuery()).thenReturn(query);
    }
}
