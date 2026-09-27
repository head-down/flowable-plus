package io.github.flowable.plus.extension.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E17 —— 运行暂停控制面（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E17}）。
 *
 * <p><b>承哪些推入项</b>：{@code #36} 运行暂停控制面（{@code pause()} / {@code resume()} /
 * {@code isPaused()}；状态<b>由应用持有</b>、框架不持久化、不带管理端点、不读 Spring {@code Environment}）
 * + 命名宪章 §2.D.3 词尾禁（<b>不得</b>出现 {@code …Status} / {@code …State}）。</p>
 */
class DecisionRuntimeControlTest {

    /** 词尾禁的两个后缀（命名宪章 §2.D.3：状态用<b>判定语义</b>，不取状态快照词尾） */
    private static final List<String> BANNED_SUFFIXES = Arrays.asList("Status", "State");

    /** 控制面的公开方法数（{@code pause} / {@code resume} / {@code isPaused}） */
    private static final int CONTROL_METHOD_COUNT = 3;

    /** Spring 包的类名前缀（「不读 Environment / 不带管理端点」的机械面：签名里不得出现它们） */
    private static final String SPRING_PACKAGE_PREFIX = "org.springframework";

    @Test
    @DisplayName("控制状态由应用持有：状态住实例内存态、不持久化、不带管理端点、不读 Spring Environment")
    void controlStateIsOwnedByApplication() {
        assertThat(DecisionRuntimeControl.class.getDeclaredMethods())
                .as("控制面只暴露三个控制方法（取控制方法、不取配置属性 key）")
                .hasSize(CONTROL_METHOD_COUNT);

        final Field[] fields = DefaultDecisionRuntimeControl.class.getDeclaredFields();
        assertThat(Arrays.stream(fields).map(Field::getName).collect(Collectors.toList()))
                .as("状态只有一处承载位：进程内暂停位（无持久化槽位、无配置键、无静态残留）")
                .containsExactly("paused");
        assertThat(fields[0].getType()).as("进程内内存态取线程安全开关（决策线程读、应用线程写）")
                .isEqualTo(AtomicBoolean.class);
        assertThat(Modifier.isStatic(fields[0].getModifiers()))
                .as("静态位会把状态变成框架持有，违「状态由应用持有」")
                .isFalse();
        assertThat(Serializable.class.isAssignableFrom(DefaultDecisionRuntimeControl.class))
                .as("不实现序列化契约 ⇒ 状态不可能被持久化")
                .isFalse();

        // 状态归属的正面证据：两个实例互不影响（应用各自持有自己的那一份）
        final DecisionRuntimeControl first = new DefaultDecisionRuntimeControl();
        final DecisionRuntimeControl second = new DefaultDecisionRuntimeControl();
        first.pause();
        assertThat(second.isPaused()).as("应用持有的两份状态互不影响").isFalse();

        assertThat(springTypesInSignatures()).as("不读 Spring Environment、不带管理端点（签名零 Spring 类型）")
                .isEmpty();
    }

    @Test
    @DisplayName("暂停与恢复幂等且可查询：初值未暂停，重复 pause / resume 不改结局")
    void pauseAndResumeAreIdempotentAndQueryable() {
        final DecisionRuntimeControl control = new DefaultDecisionRuntimeControl();
        assertThat(control.isPaused()).as("初值 = 未暂停（机制默认在跑）").isFalse();

        control.pause();
        control.pause();
        assertThat(control.isPaused()).as("重复暂停幂等").isTrue();

        control.resume();
        control.resume();
        assertThat(control.isPaused()).as("重复恢复幂等").isFalse();
    }

    @Test
    @DisplayName("词尾禁：控制面不出现 …Status / …State 词尾的成员或类型名")
    void noStatusOrStateSuffixedMembers() {
        final List<String> memberNames = new ArrayList<>();
        memberNames.add(DecisionRuntimeControl.class.getSimpleName());
        memberNames.add(DefaultDecisionRuntimeControl.class.getSimpleName());
        for (final Method method : DecisionRuntimeControl.class.getDeclaredMethods()) {
            memberNames.add(method.getName());
        }
        for (final Field field : DefaultDecisionRuntimeControl.class.getDeclaredFields()) {
            memberNames.add(field.getName());
        }

        assertThat(memberNames)
                .as("状态用判定语义（isPaused），状态快照词尾不得出现")
                .noneMatch(name -> BANNED_SUFFIXES.stream().anyMatch(name::endsWith));
    }

    /** 跨两类型签名里出现的 Spring 类型（空 = 不读 Environment、不带管理端点）。 */
    private static List<String> springTypesInSignatures() {
        final List<String> springTypes = new ArrayList<>();
        for (final Method method : DecisionRuntimeControl.class.getMethods()) {
            collectSpringType(springTypes, method.getReturnType());
            for (final Class<?> parameterType : method.getParameterTypes()) {
                collectSpringType(springTypes, parameterType);
            }
        }
        for (final Field field : DefaultDecisionRuntimeControl.class.getDeclaredFields()) {
            collectSpringType(springTypes, field.getType());
        }
        return springTypes;
    }

    /** 收集 Spring 类型（控制面的「不读 Environment / 不带管理端点」机械面）。 */
    private static void collectSpringType(final List<String> sink, final Class<?> type) {
        if (type.getName().startsWith(SPRING_PACKAGE_PREFIX)) {
            sink.add(type.getName());
        }
    }
}
