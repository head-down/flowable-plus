package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.event.EventBus;
import io.github.flowable.plus.extension.decision.SuggestionSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * S1 —— classpath 无 extension 时的装配缺席（ADR-0042 第 11 节第 6 条 (d) + 第 4 条残留账本）。
 *
 * <p>断言名形态与 {@code docs/impl/0042-verification-landings.md} §4 的 S1 行逐字一致。</p>
 */
class DecisionAssemblyAbsenceTest {

    @Test
    void contextStartsWithoutExtensionOnClasspath() {
        // extension 的入口契约类型被过滤 ⇒ 两个决策配置类整体缺席，上下文正常启动
        DecisionAssemblyTestSupport.baseRunner()
                .withClassLoader(new FilteredClassLoader(SuggestionSubmissionService.class))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void noMechanismBeanIsPresent() {
        DecisionAssemblyTestSupport.baseRunner()
                .withClassLoader(new FilteredClassLoader(SuggestionSubmissionService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final List<String> mechanismBeanNames =
                            Arrays.asList(context.getBeanNamesForType(Object.class));
                    assertThat(mechanismBeanNames)
                            .noneMatch(DecisionAssemblyTestSupport::isMechanismNamed);
                    assertThat(context.getBeansOfType(SuggestionSubmissionService.class)).isEmpty();
                });
    }

    @Test
    void coreResidualsRemainInert() {
        DecisionAssemblyTestSupport.baseRunner()
                .withClassLoader(new FilteredClassLoader(SuggestionSubmissionService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 残留 ①：EventBus 与监听器链仍发射 —— 无订阅者（本机制监听器缺席）即无副作用，
                    // 语义方法零状态变异、零语义事件外发（内部只读探活允许）
                    final EventBus eventBus = context.getBean(EventBus.class);
                    assertThatCode(() -> eventBus.taskCreated(
                            mock(io.github.flowable.plus.core.domain.PlusTask.class), null))
                            .doesNotThrowAnyException();
                    // 残留 ②③：枚举与读侧类型仍在 core（结构存在、零写入）
                    assertThat(io.github.flowable.plus.core.enums.CommentType.DECISION_EVIDENCE)
                            .isNotNull();
                });
    }
}
