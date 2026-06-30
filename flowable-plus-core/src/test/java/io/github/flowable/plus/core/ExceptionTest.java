package io.github.flowable.plus.core;

import io.github.flowable.plus.core.exception.FlowablePlusException;
import io.github.flowable.plus.core.exception.NoPreviousNodeException;
import io.github.flowable.plus.core.exception.NotFoundException;
import io.github.flowable.plus.core.exception.PermissionDeniedException;
import io.github.flowable.plus.core.exception.TaskAlreadyCompletedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 异常体系测试：验证继承关系和中文异常信息。
 */
public class ExceptionTest {

    @Test
    public void testInheritanceChain() {
        assertThat(new NotFoundException("任务不存在"))
                .isInstanceOf(FlowablePlusException.class)
                .isInstanceOf(RuntimeException.class);

        assertThat(new PermissionDeniedException("权限不足"))
                .isInstanceOf(FlowablePlusException.class)
                .isInstanceOf(RuntimeException.class);

        assertThat(new NoPreviousNodeException("无上一审批节点"))
                .isInstanceOf(FlowablePlusException.class)
                .isInstanceOf(RuntimeException.class);

        assertThat(new TaskAlreadyCompletedException("任务已完成"))
                .isInstanceOf(FlowablePlusException.class)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    public void testExceptionMessage() {
        NotFoundException ex = new NotFoundException("流程实例 not-found-123 不存在");
        assertThat(ex.getMessage()).isEqualTo("流程实例 not-found-123 不存在");

        PermissionDeniedException pex = new PermissionDeniedException("用户 zhangsan 无权限操作此任务");
        assertThat(pex.getMessage()).isEqualTo("用户 zhangsan 无权限操作此任务");

        NoPreviousNodeException npex = new NoPreviousNodeException("节点 task-001 无上一审批节点");
        assertThat(npex.getMessage()).isEqualTo("节点 task-001 无上一审批节点");

        TaskAlreadyCompletedException tcex = new TaskAlreadyCompletedException("任务 task-002 已完成，不可重复操作");
        assertThat(tcex.getMessage()).isEqualTo("任务 task-002 已完成，不可重复操作");
    }

    @Test
    public void testExceptionWithCause() {
        RuntimeException cause = new RuntimeException("原始错误");
        FlowablePlusException ex = new FlowablePlusException("操作失败", cause);

        assertThat(ex.getMessage()).isEqualTo("操作失败");
        assertThat(ex.getCause()).isEqualTo(cause);
    }
}
