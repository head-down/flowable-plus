package io.github.flowable.plus.extension.decision;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 八靶子具名击穿实验的<b>坐标常量</b>（测试专用类型，非测试类；探索工作区
 * {@code docs/impl/0042-verification-landings.md} §3.1，内部形态唯一住所 =
 * {@code docs/impl/0042-kill-switch-experiments.md} §4.2）。
 *
 * <p><b>两个并列承载位，不得合并</b>：</p>
 * <ul>
 *   <li>{@link #EXPERIMENTS} —— <b>母实验</b>，恒为<b>八项</b>（实验名 = 靶子名，与执行面 §3 八项
 *       靶子名逐字相同 / 归属不变量 / 主落点坐标）；</li>
 *   <li>{@link #VARIANTS} —— <b>变体</b>，可空列表（母实验名 / 四栏对账 / 落点）。</li>
 * </ul>
 *
 * <p><b>为何两个并列承载位</b>：合并为一个集合再加标记，就是让同一个承载位兼职表达两件事
 * （「是不是母实验」与「实验内容」）；两个并列则「扩实验集、不扩结论集」在常量层是字面事实 ——
 * {@code EXPERIMENTS} 恒八，合法变体只增 {@code VARIANTS}。<b>禁用</b>形态：{@code isVariant}
 * 布尔标记、以「母实验名为空」兼职区分。字段集只含<b>坐标</b>（名 / 归属 / 落点），不含
 * {@code verdict} / {@code hit} / {@code status} 一类结论字段 —— 元守卫
 * {@code DecisionBreachExperimentsTest} 机械承担。</p>
 *
 * <p><b>落点坐标含模块位</b>：靶子①③的主落点在 starter 侧（{@code S5}），本模块（extension）的
 * 测试类路径上看不到它 —— 模块位让元守卫对「本模块落点反射可命中」与「跨模块落点不可见」
 * 两个方向都能判真，而非对不可见者静默放过。变体登记（同构变体通道）当前为空 ——
 * {@code VARIANTS} 可空是登记态，非结论。</p>
 */
final class DecisionBreachExperiments {

    /** 落点模块位：extension（本模块，主落点类对元守卫反射可命中） */
    static final String MODULE_EXTENSION = "extension";

    /** 落点模块位：starter（跨模块，本模块类路径不可见；其准入①面由 starter 侧守卫承接） */
    static final String MODULE_STARTER = "starter";

    /**
     * 母实验，恒为八项。实验名 = 靶子名，与执行面 §3（{@code docs/research/kill-switch-2-acceptance-gate.md}）
     * 八项靶子名逐字相同；主落点（承裁定）与探索工作区 {@code docs/impl/0042-kill-switch-experiments.md} §2.1
     * 逐格一致。
     */
    static final List<BreachExperiment> EXPERIMENTS = Collections.unmodifiableList(Arrays.asList(
            new BreachExperiment("无证据推进", "I1", MODULE_STARTER,
                    "DecisionTwoPathIsolationIntegrationTest", "submissionNeverAdvancesProcessState"),
            new BreachExperiment("越域出站", "I2", MODULE_EXTENSION,
                    "DecisionContextAssemblerTest", null),
            new BreachExperiment("影子活动", "I4", MODULE_STARTER,
                    "DecisionTwoPathIsolationIntegrationTest", "probeStaysInactiveUnderDefaultOff"),
            new BreachExperiment("故障注入", "I3", MODULE_EXTENSION,
                    "DecisionOutcomeMappingTest", null),
            new BreachExperiment("重放", "I3", MODULE_EXTENSION,
                    "DecisionEvidenceReadOrderTest", null),
            new BreachExperiment("覆盖", "I3", MODULE_EXTENSION,
                    "ComparableActionAvailabilityTest", null),
            new BreachExperiment("三层关闭", "I4", MODULE_EXTENSION,
                    "DecisionDisabledEquivalenceTest", null),
            new BreachExperiment("校验例外", "I4", MODULE_EXTENSION,
                    "DecisionNodeDeclarationValidatorTest", null)));

    /**
     * 变体（同构变体通道），可空列表；登记格式 = 母实验名 + 四栏对账（同一承重边界 / 同一越界机理 /
     * 命中同一断言面 / 落点）。变体<b>不得</b>填新的不变量、新的断言面、新的准入条件。
     */
    static final List<BreachVariant> VARIANTS = Collections.emptyList();

    private DecisionBreachExperiments() {
    }

    /** 母实验坐标行：靶子名 / 归属不变量 / 主落点坐标（模块 + 类名 + 断言名，断言名可空 = 落点承整类） */
    static final class BreachExperiment {

        /** 实验名（= 靶子名，执行面 §3 逐字） */
        final String targetName;

        /** 归属不变量（I1–I4） */
        final String invariant;

        /** 主落点模块位（extension = 本模块 / starter = 跨模块） */
        final String landingModule;

        /** 主落点类名（简单名） */
        final String landingClassName;

        /** 主落点断言名；落点承整类（无单一具名断言）时为 null */
        final String landingAssertion;

        BreachExperiment(String targetName, String invariant, String landingModule,
                         String landingClassName, String landingAssertion) {
            this.targetName = targetName;
            this.invariant = invariant;
            this.landingModule = landingModule;
            this.landingClassName = landingClassName;
            this.landingAssertion = landingAssertion;
        }
    }

    /** 变体坐标行：母实验名 + 四栏对账 + 落点（判定效力与母实验同等，是否命中归维护者裁定） */
    static final class BreachVariant {

        /** 母实验名（必须落在 {@code EXPERIMENTS} 的实验名集合内） */
        final String motherExperiment;

        /** 同一承重边界（能力 / 失效 / 退出，与母实验相同） */
        final String sameBoundary;

        /** 同一越界机理（手段类别相同，而非同一字段） */
        final String sameBreachMechanism;

        /** 命中同一断言面（母实验的断言名，不得新增断言面） */
        final String sameAssertionFace;

        /** 变体落点（类名#断言名 或等价具名形态） */
        final String landing;

        BreachVariant(String motherExperiment, String sameBoundary, String sameBreachMechanism,
                      String sameAssertionFace, String landing) {
            this.motherExperiment = motherExperiment;
            this.sameBoundary = sameBoundary;
            this.sameBreachMechanism = sameBreachMechanism;
            this.sameAssertionFace = sameAssertionFace;
            this.landing = landing;
        }
    }
}
