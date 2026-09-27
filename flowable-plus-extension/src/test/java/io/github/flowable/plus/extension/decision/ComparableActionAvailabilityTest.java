package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.support.TaskValidation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E11 —— 表态比较面与可用动作（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E11}）。
 *
 * <p><b>承哪些推入项</b>：{@code #33} 的可用动作三行映射<b>真值表逐格</b>
 * （{@code isMultiInstance} / {@code isRuntimeMultiInstance} / {@code isInitiatorDecisionTask}
 * 三布尔组合 × 四动作）+ <b>镜像漂移守卫</b>（镜像来源 = core 三个守卫
 * {@code TaskValidation.validateMultiInstance} / {@code validateNotMultiInstance(…, false)} /
 * {@code validateNotMultiInstance(…, true)}）+ {@link ComparableAction#MEMBERS} <b>显式且恰好四值</b>
 * + {@code isComparable} 与 {@code MEMBERS.contains} <b>同源等价</b> + {@code AUTO_COMPLETE} 不可达
 * （它属 {@code CommentType} 侧）。</p>
 *
 * <p><b>「可用」的定义</b>（{@code #33} 决议 §一.4）= 「core 对应写入路径的守卫会放行」。故本类把
 * 镜像与<b>真 core 守卫</b>逐格对拍（mock 引擎检测对象），映射表与 core 语义任一侧漂移都会打红。</p>
 *
 * <p><b>已知边界的文档纪律</b>：{@code AUTO_COMPLETE} 的断言<b>只断「它不是 {@link ApprovalAction}
 * 的取值」这一结构事实</b> —— 它经读侧映射后表现为动作层 {@code AGREE}，机制<b>不保证</b>与人工
 * {@code AGREE} 可判别，故<b>不得</b>出现「机制已排除自动提交」一类断言（ADR-0042 第 12 节）。</p>
 */
class ComparableActionAvailabilityTest {

    /** 表态比较面的四个动作（与本类无关的第三个真相来源：这里的期望值按 {@code #33} 的书面值域独立写出） */
    private static final Set<ApprovalAction> DECLARED_MEMBERS = EnumSet.of(
            ApprovalAction.AGREE,
            ApprovalAction.REJECT,
            ApprovalAction.COUNTER_SIGN_AGREE,
            ApprovalAction.COUNTER_SIGN_REJECT);

    /** 镜像漂移对拍用的锚点任务（三个布尔事实全部由 mock 检测对象提供，本对象只作守卫的入参） */
    private static final PlusTask TASK = new PlusTask("task-20260927-e11", "procdef-1", "userTask-decide",
            "process-20260927-e11", "assignee-1", null, "决策任务", "exec-1", null);

    @Test
    @DisplayName("可用动作真值表逐格：八个布尔组合 × 四动作，逐格等于三行映射")
    void truthTableMatchesCoreGuardsCellByCell() {
        final List<String> cells = new ArrayList<>();
        for (final boolean modelMultiInstance : booleans()) {
            for (final boolean runtimeMultiInstance : booleans()) {
                for (final boolean initiatorDecisionTask : booleans()) {
                    for (final ApprovalAction action : DECLARED_MEMBERS) {
                        final boolean expected = expectedByThreeRowMapping(action, modelMultiInstance,
                                runtimeMultiInstance, initiatorDecisionTask);
                        final boolean actual = ComparableAction.isAvailableFor(action,
                                modelMultiInstance, runtimeMultiInstance, initiatorDecisionTask);
                        assertThat(actual)
                                .as("可用动作真值表格：动作=%s 模型多实例=%s 运行时多实例=%s 发起人决策=%s",
                                        action, modelMultiInstance, runtimeMultiInstance, initiatorDecisionTask)
                                .isEqualTo(expected);
                        cells.add(action + "/" + modelMultiInstance + runtimeMultiInstance
                                + initiatorDecisionTask);
                    }
                }
            }
        }
        assertThat(cells)
                .as("防空转：真值表必须真的覆盖全部格子（八组合 × 四动作 = 32 格）")
                .hasSize(32)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("镜像漂移守卫：mock 引擎对象对拍真 core 守卫，逐格「守卫放行 ⇔ 镜像判可用」")
    void mirrorDoesNotDriftFromCoreTaskValidation() {
        final List<String> cells = new ArrayList<>();
        for (final boolean modelMultiInstance : booleans()) {
            for (final boolean runtimeMultiInstance : booleans()) {
                for (final boolean initiatorDecisionTask : booleans()) {
                    final MultiInstanceDetector detector = mockDetector(modelMultiInstance,
                            runtimeMultiInstance, initiatorDecisionTask);
                    for (final ApprovalAction action : DECLARED_MEMBERS) {
                        final boolean guardAllows = coreGuardAllows(action, detector);
                        final boolean mirrorAllows = ComparableAction.isAvailableFor(action,
                                modelMultiInstance, runtimeMultiInstance, initiatorDecisionTask);
                        assertThat(mirrorAllows)
                                .as("镜像不得漂移：动作=%s 的三行映射必须等于真 core 守卫的放行结论"
                                        + "（模型多实例=%s 运行时多实例=%s 发起人决策=%s）",
                                        action, modelMultiInstance, runtimeMultiInstance, initiatorDecisionTask)
                                .isEqualTo(guardAllows);
                        cells.add(action + "/" + modelMultiInstance + runtimeMultiInstance
                                + initiatorDecisionTask);
                    }
                }
            }
        }
        assertThat(cells)
                .as("防空转：漂移对拍必须真的覆盖全部格子")
                .hasSize(32)
                .doesNotHaveDuplicates();

        // 三行映射各自被真守卫放行过至少一次（否则「对拍」可能退化成逐格皆 false 的真空成立）
        assertThat(availabilityOf(ApprovalAction.COUNTER_SIGN_AGREE))
                .as("会签两值必须在某个组合上被真 core 守卫放行")
                .contains(true);
        assertThat(availabilityOf(ApprovalAction.AGREE))
                .as("AGREE 必须在某个组合上被真 core 守卫放行")
                .contains(true);
        assertThat(availabilityOf(ApprovalAction.REJECT))
                .as("REJECT 必须在某个组合上被真 core 守卫放行")
                .contains(true);
    }

    @Test
    @DisplayName("MEMBERS 显式且恰好四值：内容与书面值域逐一相等，且是动作面的真子集")
    void membersIsExplicitAndExactlyFour() {
        assertThat(ComparableAction.MEMBERS)
                .as("表态比较面的成员集必须与书面值域逐一相等（显式枚举，不得由派生式生成）")
                .containsExactlyInAnyOrderElementsOf(DECLARED_MEMBERS);
        assertThat(ComparableAction.MEMBERS)
                .as("表态比较面恰四值")
                .hasSize(4);
        assertThat(EnumSet.allOf(ApprovalAction.class))
                .as("防空转：比较面必须是动作面的真子集（否则「子集」二字无信息量）")
                .hasSizeGreaterThan(4)
                .containsAll(ComparableAction.MEMBERS);
    }

    @Test
    @DisplayName("isComparable 与 MEMBERS.contains 同源等价：逐动作取值相等，null 判否")
    void isComparableIsSameSourcedAsMembersContains() {
        for (final ApprovalAction action : EnumSet.allOf(ApprovalAction.class)) {
            assertThat(ComparableAction.isComparable(action))
                    .as("同源等价：isComparable(%s) 必须等于 MEMBERS.contains(%s)", action, action)
                    .isEqualTo(ComparableAction.MEMBERS.contains(action));
        }
        assertThat(ComparableAction.isComparable(null))
                .as("null 不属比较面（缺动作由准入规则单独判，不留到比较面）")
                .isFalse();
    }

    @Test
    @DisplayName("AUTO_COMPLETE 不属动作面：只断「它不是 ApprovalAction 取值」的结构事实")
    void autoCompleteIsNotAnApprovalAction() {
        final Set<String> actionNames = EnumSet.allOf(ApprovalAction.class).stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(actionNames)
                .as("结构事实：AUTO_COMPLETE 是 CommentType 侧取值，主仓没有同名 ApprovalAction"
                        + " —— 本条不是「机制已排除自动提交」（经读侧映射后它表现为动作层 AGREE，"
                        + "与人工 AGREE 的可判别性属已知边界）")
                .doesNotContain("AUTO_COMPLETE");

        final Set<String> commentTypeNames = EnumSet.allOf(CommentType.class).stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(commentTypeNames)
                .as("防空转：该名字在 CommentType 侧确实存在，否则上一条接近真空成立")
                .contains("AUTO_COMPLETE");

        assertThat(ComparableAction.MEMBERS.stream().map(Enum::name).collect(Collectors.toSet()))
                .as("比较面里也不可能有它（它根本不是动作取值）")
                .doesNotContain("AUTO_COMPLETE");
    }

    // ======================== 辅助 ========================

    /** 书面三行映射（{@code #33} 决议 §一.4）—— 测试侧独立写出的期望值。 */
    private static boolean expectedByThreeRowMapping(final ApprovalAction action,
                                                     final boolean modelMultiInstance,
                                                     final boolean runtimeMultiInstance,
                                                     final boolean initiatorDecisionTask) {
        switch (action) {
            case COUNTER_SIGN_AGREE:
            case COUNTER_SIGN_REJECT:
                return modelMultiInstance;
            case AGREE:
                return !runtimeMultiInstance;
            case REJECT:
                return !runtimeMultiInstance || initiatorDecisionTask;
            default:
                return false;
        }
    }

    /**
     * 真 core 守卫的放行结论：逐动作调用 core 对应写入路径的那一个守卫。
     *
     * <p>映射来自 {@code #33} 决议 §一.4 的「所镜像的 core 守卫」列，与仓内源码逐条对应：
     * 会签两值 → {@code counterSign}；{@code AGREE} → {@code completeTask}（无豁免）；
     * {@code REJECT} → {@code rejectTask}（豁免发起人决策任务）。</p>
     */
    private static boolean coreGuardAllows(final ApprovalAction action, final MultiInstanceDetector detector) {
        try {
            switch (action) {
                case COUNTER_SIGN_AGREE:
                case COUNTER_SIGN_REJECT:
                    TaskValidation.validateMultiInstance(detector, TASK, TASK.getId(), "会签");
                    return true;
                case AGREE:
                    TaskValidation.validateNotMultiInstance(detector, TASK, TASK.getId());
                    return true;
                case REJECT:
                    TaskValidation.validateNotMultiInstance(detector, TASK, TASK.getId(), true);
                    return true;
                default:
                    return false;
            }
        } catch (IllegalArgumentException blocked) {
            return false;
        }
    }

    /** 某个动作在八个布尔组合上的镜像结论（防空转用）。 */
    private static List<Boolean> availabilityOf(final ApprovalAction action) {
        final List<Boolean> results = new ArrayList<>();
        for (final boolean modelMultiInstance : booleans()) {
            for (final boolean runtimeMultiInstance : booleans()) {
                for (final boolean initiatorDecisionTask : booleans()) {
                    results.add(ComparableAction.isAvailableFor(action, modelMultiInstance,
                            runtimeMultiInstance, initiatorDecisionTask));
                }
            }
        }
        return results;
    }

    private static MultiInstanceDetector mockDetector(final boolean modelMultiInstance,
                                                     final boolean runtimeMultiInstance,
                                                     final boolean initiatorDecisionTask) {
        final MultiInstanceDetector detector = mock(MultiInstanceDetector.class);
        when(detector.isMultiInstance(any())).thenReturn(modelMultiInstance);
        when(detector.isRuntimeMultiInstance(any())).thenReturn(runtimeMultiInstance);
        when(detector.isInitiatorDecisionTask(any())).thenReturn(initiatorDecisionTask);
        return detector;
    }

    private static List<Boolean> booleans() {
        return Arrays.asList(Boolean.FALSE, Boolean.TRUE);
    }
}
