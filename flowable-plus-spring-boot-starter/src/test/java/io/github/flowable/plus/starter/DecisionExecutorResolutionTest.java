package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.event.TaskCreatedEvent;
import io.github.flowable.plus.extension.decision.DecisionNodeDeclaration;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策专属池的取用面回归（issue #102）：专属池是机制的<b>内部件</b>，「这是本机制的池」不得依赖
 * 「应用里恰好只有一个 {@link ThreadPoolExecutor} 类型的 Bean」。
 *
 * <p><b>缺陷形态</b>：适配器以 {@code ObjectProvider<ThreadPoolExecutor>} <b>无限定符</b>取池，而
 * {@code ObjectProvider#getIfAvailable()} 的语义是「唯一候选」—— 应用自带任意一个
 * {@code ThreadPoolExecutor} Bean（带定时线程池的 Spring Boot 工程极常见）即抛
 * {@code NoUniqueBeanDefinitionException}；该异常被适配器的宽捕获接住、只落一条 WARN，拉面
 * <b>永久 fail-closed</b>（零证据、零观测），唯一症状是一行易漏的 WARN。</p>
 *
 * <p><b>判据面 = 线程建在哪个池上</b>：到点事件是<b>同步入队</b>（{@code Executor#execute} 内建 worker），
 * 故「专属池的线程数」是确定读而非竞态读 —— 被投递的必须落在机制自己的池上、应用池保持零线程。
 * 直接钉住「按名 / 按限定符取回专属池」这一格，避开「只在控制台可见的 WARN」这种易漏观测面。</p>
 */
class DecisionExecutorResolutionTest {

    /** 声明面索引里的启用声明节点 id */
    private static final String DECLARED_NODE_ID = "declaredApprovalTask";

    /** 机制专属池的 Bean 名（与装配面 {@code @Bean} 名同字面；名字本身由装配闭集断言独立钉住） */
    private static final String DECISION_POOL_BEAN_NAME = "decisionExecutor";

    /** 到点订阅适配器的 Bean 名 */
    private static final String LISTENER_BEAN_NAME = "decisionTaskCreatedListener";

    /** 应用侧第二个池的 Bean 名（与 issue 实测的消费方 Bean 名同形，便于对照异常文案） */
    private static final String APPLICATION_POOL_BEAN_NAME = "scheduledExecutorService";

    /** 上下文里本类型 Bean 的期望基数（机制专属池 + 应用自带池） */
    private static final int EXPECTED_POOL_BEAN_COUNT = 2;

    /** 一次入队建出的 worker 数（任务提交即建线程，无预热、无预启） */
    private static final int WORKERS_PER_SUBMITTED_TASK = 1;

    @Test
    void pullFaceUsesItsOwnPoolWhenApplicationRegistersAnotherOfTheSameType() {
        final ThreadPoolExecutor applicationPool = new ScheduledThreadPoolExecutor(1);
        try {
            DecisionAssemblyTestSupport.baseRunner()
                    .withPropertyValues("flowable.plus.decision.enabled=true")
                    .withBean(APPLICATION_POOL_BEAN_NAME, ThreadPoolExecutor.class, () -> applicationPool)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeanNamesForType(ThreadPoolExecutor.class))
                                .as("前提：上下文里确有第二个 ThreadPoolExecutor 候选（否则本测试不成立）")
                                .hasSize(EXPECTED_POOL_BEAN_COUNT);

                        // 预热走捷径：直接合入索引，把被测面收窄到「池怎么取」这一格
                        context.getBean(DecisionDeclaredNodeIndex.class)
                                .merge(Collections.singletonMap(DECLARED_NODE_ID, declaredUserTask()));

                        final ThreadPoolExecutor decisionPool =
                                context.getBean(DECISION_POOL_BEAN_NAME, ThreadPoolExecutor.class);
                        ((DecisionTaskCreatedListenerAdapter) context.getBean(LISTENER_BEAN_NAME))
                                .onTaskCreated(taskCreatedEvent());

                        assertThat(decisionPool.getPoolSize())
                                .as("到点事件须落在机制专属池上（取池歧义 ⇒ 适配器 fail-closed ⇒ 任务永不入队）")
                                .isEqualTo(WORKERS_PER_SUBMITTED_TASK);
                        assertThat(applicationPool.getPoolSize())
                                .as("机制不得把决策任务投到应用自己的池上")
                                .isZero();
                    });
        } finally {
            applicationPool.shutdownNow();
        }
    }

    /** 声明面索引的元素形态：本机制 URI 下带启用声明的 UserTask（取值合法 = 小写字面量） */
    private static UserTask declaredUserTask() {
        final UserTask declared = new UserTask();
        declared.setId(DECLARED_NODE_ID);
        final ExtensionAttribute enabled = new ExtensionAttribute();
        enabled.setNamespace(DecisionNodeDeclaration.NAMESPACE_URI);
        enabled.setName(DecisionNodeDeclaration.DECISION_ENABLED);
        enabled.setValue(Boolean.TRUE.toString());
        declared.addAttribute(enabled);
        return declared;
    }

    private static TaskCreatedEvent taskCreatedEvent() {
        return TaskCreatedEvent.of("task-" + DECLARED_NODE_ID, "instance-1", "审批任务",
                DECLARED_NODE_ID, "approver", new Date());
    }
}
