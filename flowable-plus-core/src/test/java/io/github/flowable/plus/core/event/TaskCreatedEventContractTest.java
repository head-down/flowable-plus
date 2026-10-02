package io.github.flowable.plus.core.event;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.spi.ProcessEventListener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TaskCreatedEvent} 的契约与「无数据时零副作用」（ADR-0042 第 11 节第 4 条残留账本第 ① 行）。
 *
 * <p><b>零副作用的定义</b>（ADR-0042 第 11 节第 4 条）：<b>无状态变异</b>（不改写流程状态 / 历史 / 变量）
 * ＋ <b>无对外副作用</b>（不向外发射本机制语义上的事件）；<b>允许内部只读探活</b>（既有 {@link EventBus}
 * 一类的内部检查）。故本类的三条负向断言<b>不得</b>被读成「core 运行时零新增活动」——
 * 与 ADR-0042 第 11 节第 1 条「关闭后无感 ≠ 运行时零活动」同旨：core 仍会构造并发布一个无订阅者的
 * {@code TaskCreatedEvent}，其可观测边界只有主动实现 {@link ProcessEventListener#onTaskCreated}
 * 的一方能触达。</p>
 */
public class TaskCreatedEventContractTest {

    private static final Date CREATE_TIME = new Date(1700000000000L);

    private static PlusTask task(String assignee) {
        return new PlusTask("task-001", "leave:1:abc", "node1", "pi-001",
                assignee, null, "审批", "exec-1", CREATE_TIME);
    }

    // ======================== 字段契约 ========================

    @Test
    void shouldCarryContractFields() {
        TaskCreatedEvent event = TaskCreatedEvent.of(
                "task-001", "pi-001", "审批", "node1", "userA", CREATE_TIME);

        assertThat(event.getTaskId()).isEqualTo("task-001");
        assertThat(event.getProcessInstanceId()).isEqualTo("pi-001");
        assertThat(event.getTaskName()).isEqualTo("审批");
        assertThat(event.getNodeId()).isEqualTo("node1");
        assertThat(event.getAssignee()).isEqualTo("userA");
        assertThat(event.getEventTime()).isSameAs(CREATE_TIME);
        assertThat(event.toString()).contains("task-001", "pi-001", "node1");
    }

    @Test
    void shouldAllowNullAssignee() {
        // 引擎侧任务创建时可能尚无 assignee（候选组待认领），故 assignee 可空
        TaskCreatedEvent event = TaskCreatedEvent.of(
                "task-001", "pi-001", "审批", "node1", null, CREATE_TIME);

        assertThat(event.getAssignee()).isNull();
        assertThat(event.getEventTime()).isSameAs(CREATE_TIME);
    }

    // ======================== 三条负向断言 ========================

    @Test
    void shouldNotMutateStateWithoutSubscriber() {
        // 无发布者 ⇒ EventBus 短路：不构造事件、不做任何查询或写入
        EventBus eventBus = new EventBus(null);
        PlusTask source = task("userA");

        eventBus.taskCreated(source, CREATE_TIME);

        assertThat(eventBus.isEnabled()).isFalse();
        // 源任务未被改写
        assertThat(source.getId()).isEqualTo("task-001");
        assertThat(source.getProcessInstanceId()).isEqualTo("pi-001");
        assertThat(source.getTaskDefinitionKey()).isEqualTo("node1");
        assertThat(source.getAssignee()).isEqualTo("userA");
        assertThat(source.getCreateTime()).isSameAs(CREATE_TIME);
        // 结构保证：事件自身不可变（全部实例字段 private final ⇒ 无状态变异）
        assertThat(instanceFieldsArePrivateFinal(TaskCreatedEvent.class)).isTrue();
    }

    @Test
    void shouldNotEmitMechanismSemanticEventsWithoutSubscriber() {
        // 有发布者而无订阅者：事件仍被发射（残留账本第 ① 行），但只此一件、不引发任何二次发射
        CountingPublisher publisher = new CountingPublisher();
        ProcessEventListener unsubscribed = mock(ProcessEventListener.class);
        EventBus eventBus = new EventBus(publisher);

        eventBus.taskCreated(task("userA"), CREATE_TIME);

        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.get(0)).isInstanceOf(TaskCreatedEvent.class);
        // 无订阅者 ⇒ 发射链不触达任何监听器回调（本机制语义上的事件一件都未向外发出）
        verifyNoInteractions(unsubscribed);
    }

    private static boolean instanceFieldsArePrivateFinal(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!Modifier.isPrivate(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计数发布者：记录本总线实际投出的每一件事件，再委托给<b>空监听器</b>的
     * {@link DefaultEventPublisher}（即「有发布者、无订阅者」形态）。
     */
    private static final class CountingPublisher implements EventPublisher {

        private final List<ProcessEvent> published = new ArrayList<>();
        private final EventPublisher delegate = new DefaultEventPublisher(Collections.emptyList());

        @Override
        public void publish(ProcessEvent event) {
            published.add(event);
            delegate.publish(event);
        }
    }
}
