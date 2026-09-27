package io.github.flowable.plus.starter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 —— 中立性 ⑤（装配面，ADR-0042 第 3 节的承重面）+ 启动期零指标。
 *
 * <p>判据形态 = 闭集事实：机制 Bean 集合<b>有 extension 时恒等于显式常量集</b>、<b>无 extension 时空集</b>、
 * 集合内类名过 T1 切词扫描。断言名形态与 {@code docs/impl/0042-verification-landings.md} §4 的 S2 行逐字一致。</p>
 */
class NeutralityAssemblyTest {

    private ApplicationContextRunner baseRunner() {
        return DecisionAssemblyTestSupport.baseRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }

    /** 收集上下文中的机制 Bean 名：bean 名落在机制命名面，或 bean 类住机制包 */
    private static List<String> mechanismBeanNames(final org.springframework.context.ConfigurableApplicationContext context) {
        final List<String> names = new ArrayList<>();
        for (final String name : context.getBeanNamesForType(Object.class)) {
            if (DecisionAssemblyTestSupport.isMechanismNamed(name)
                    || beanClassNameOf(context, name).startsWith("io.github.flowable.plus.extension.decision.")) {
                names.add(name);
            }
        }
        return names;
    }

    /** bean 类名（类型不可定时回落到实例类） */
    private static String beanClassNameOf(final org.springframework.context.ConfigurableApplicationContext context,
                                          final String name) {
        final Class<?> beanType = context.getType(name) != null
                ? context.getType(name)
                : context.getBean(name).getClass();
        return String.valueOf(beanType);
    }

    @Test
    void automaticallyConfiguredMechanismBeansEqualDeclaredSet() {
        baseRunner().run(context -> {
            assertThat(context).hasNotFailed();
            final List<String> mechanismBeans = mechanismBeanNames(context);
            Collections.sort(mechanismBeans);
            final List<String> declared = new ArrayList<>(DecisionAssemblyTestSupport.DECLARED_MECHANISM_BEAN_NAMES);
            Collections.sort(declared);
            assertThat(mechanismBeans).containsExactlyElementsOf(declared);
        });
    }

    @Test
    void mechanismBeanSetIsEmptyWithoutExtension() {
        baseRunner().withClassLoader(new FilteredClassLoader(io.github.flowable.plus.extension.decision.SuggestionSubmissionService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(mechanismBeanNames(context)).isEmpty();
                });
    }

    @Test
    void noAutowiredMechanismBeanClassNameCarriesDomainWords() {
        baseRunner().run(context -> {
            assertThat(context).hasNotFailed();
            for (final String name : mechanismBeanNames(context)) {
                final String beanClassName = beanClassNameOf(context, name);
                // T1 切词扫描：驼峰切词后与禁词清单比对（类名不得携带能唯一指向厂商 / 产品的词）
                final List<String> tokens = tokenize(beanClassName);
                for (final String token : tokens) {
                    assertThat(DecisionAssemblyTestSupport.T1_BANNED_WORDS)
                            .as("机制 Bean 类名 %s 切词 %s 命中 T1 禁词", beanClassName, token)
                            .doesNotContain(token);
                }
            }
        });
    }

    @Test
    void noDecisionMetersAreRegisteredBeforeFirstObservation() {
        baseRunner().run(context -> {
            assertThat(context).hasNotFailed();
            final MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.getMeters())
                    .noneMatch(meter -> meter.getId().getName().startsWith("flowable.plus.decision."));
        });
    }

    /** 驼峰 / 下划线 / 点切词（去空 token；手写形态见支撑类 javadoc 的账本偏离说明） */
    private static List<String> tokenize(final String identifier) {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (final char c : identifier.toCharArray()) {
            if (Character.isUpperCase(c) || c == '_' || c == '.' || c == '-' || c == '$') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }
            current.append(Character.toLowerCase(c));
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
