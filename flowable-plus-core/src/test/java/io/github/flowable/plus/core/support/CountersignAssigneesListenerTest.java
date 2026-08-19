package io.github.flowable.plus.core.support;

import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CountersignAssigneesListener 单元测试。
 *
 * <p>覆盖 assigneeList 已存在 / 为空触发解析 / 解析结果为空 三分支：
 * <ul>
 *   <li>已有非空 assigneeList → 不触发 {@link AssigneeResolverRegistry#resolve}，不覆盖变量</li>
 *   <li>assigneeList 为空或缺失 → 从 registry 解析，非空结果写回变量</li>
 *   <li>解析结果为空（无 Resolver 或 Resolver 返回空）→ 保持不设置</li>
 * </ul></p>
 */
class CountersignAssigneesListenerTest {

    private static final String PROCESS_INSTANCE_ID = "pi-001";
    private static final String TASK_DEF_KEY = "countersignTask";

    private DelegateTask mockDelegateTask;
    private AssigneeResolverRegistry mockRegistry;

    @BeforeEach
    void setUp() {
        mockDelegateTask = mock(DelegateTask.class);
        mockRegistry = mock(AssigneeResolverRegistry.class);
        when(mockDelegateTask.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);
        when(mockDelegateTask.getTaskDefinitionKey()).thenReturn(TASK_DEF_KEY);
    }

    // ======================== 分支 1：assigneeList 已存在（非空） ========================

    @Test
    void existingAssigneeListShouldNotResolveOrOverride() {
        when(mockDelegateTask.getVariable("assigneeList"))
                .thenReturn(Arrays.asList("userA", "userB"));

        new CountersignAssigneesListener(mockRegistry).notify(mockDelegateTask);

        verify(mockRegistry, never()).resolve(PROCESS_INSTANCE_ID, TASK_DEF_KEY);
        verify(mockDelegateTask, never()).setVariable(eq("assigneeList"), any());
    }

    // ======================== 分支 2：assigneeList 为空 → 解析成功写回 ========================

    @Test
    void emptyAssigneeListShouldResolveAndSetVariable() {
        when(mockDelegateTask.getVariable("assigneeList")).thenReturn(null);
        when(mockRegistry.resolve(PROCESS_INSTANCE_ID, TASK_DEF_KEY))
                .thenReturn(Arrays.asList("userC", "userD"));

        new CountersignAssigneesListener(mockRegistry).notify(mockDelegateTask);

        verify(mockRegistry).resolve(PROCESS_INSTANCE_ID, TASK_DEF_KEY);
        verify(mockDelegateTask).setVariable("assigneeList", Arrays.asList("userC", "userD"));
    }

    // ======================== 分支 3：解析结果为空 → 不设置 ========================

    @Test
    void emptyResolutionShouldNotSetVariable() {
        when(mockDelegateTask.getVariable("assigneeList")).thenReturn(Collections.emptyList());
        when(mockRegistry.resolve(PROCESS_INSTANCE_ID, TASK_DEF_KEY))
                .thenReturn(Collections.emptyList());

        new CountersignAssigneesListener(mockRegistry).notify(mockDelegateTask);

        verify(mockRegistry).resolve(PROCESS_INSTANCE_ID, TASK_DEF_KEY);
        verify(mockDelegateTask, never()).setVariable(eq("assigneeList"), any());
    }

    // ======================== 分支 3b：无 Resolver 的真实 registry → 不设置 ========================

    @Test
    void noResolverRegistryShouldNotSetVariable() {
        when(mockDelegateTask.getVariable("assigneeList")).thenReturn(null);

        new CountersignAssigneesListener(new AssigneeResolverRegistry()).notify(mockDelegateTask);

        verify(mockDelegateTask, never()).setVariable(eq("assigneeList"), any());
    }
}
