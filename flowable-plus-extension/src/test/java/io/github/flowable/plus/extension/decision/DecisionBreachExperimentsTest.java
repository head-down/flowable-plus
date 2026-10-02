package io.github.flowable.plus.extension.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E21 —— 击穿实验的元守卫（探索工作区 {@code docs/impl/0042-verification-landings.md} §3.2 的
 * {@code E21}；内部形态唯一住所 = {@code docs/impl/0042-kill-switch-experiments.md} §4.2）。
 *
 * <p><b>六条具名断言</b>（逐字）：母实验规模与名逐字对账（准入条件 ⑥）· 主落点具名与反射可命中 ·
 * 八主落点覆盖 I1–I4 且互异 · 变体母实验名在册 · 常量无结论字段（「扩实验集不扩结论集」的机械面）·
 * 主落点源文件受限扫描（准入条件 ①）。</p>
 *
 * <p><b>计数守卫的形态（钉死）</b>：{@code #experimentsAreExactlyTheEightNamedTargets} 只对
 * {@code EXPERIMENTS} 断言「恰八」—— <b>不得</b>出现「常量表总行数 == 8」一类断言（合法变体会将其打红，
 * 而变体通道的全部意义正是允许事后扩充实验集）。</p>
 *
 * <p><b>源码扫描的范围（实现期披露）</b>：靶子①③的主落点在 starter 侧，本模块的测试类路径与
 * surefire 工作目录都够不到其源文件 —— 该两行的准入①面由 starter 侧守卫承接（其落点票关闭时登记）。
 * 本模块可扫的六份主落点源文件 + 坐标常量源文件 + 本守卫源文件 = <b>访问源文件数 ≥ 8</b> 的防空转下限
 * （数值唯一住所 = 探索工作区 {@code docs/impl/0042-implementation-plan.md} §3）。扫描用字面量取
 * <b>带括号形态</b>（{@code setAccessible} 与 {@code getDeclaredField} 后接半角开括号）—— 后者不误伤本守卫自己用的
 * {@code getDeclaredFields()}（取字段集做结构断言，不是撬私有状态）。</p>
 */
class DecisionBreachExperimentsTest {

    /** 执行面 §3 的八项靶子名（逐字；唯一出处 = docs/research/kill-switch-2-acceptance-gate.md §3） */
    private static final List<String> EIGHT_NAMED_TARGETS = Arrays.asList(
            "无证据推进", "越域出站", "影子活动", "故障注入", "重放", "覆盖", "三层关闭", "校验例外");

    /** 四条承重不变量的闭集 */
    private static final Set<String> ALL_FOUR_INVARIANTS =
            new HashSet<>(Arrays.asList("I1", "I2", "I3", "I4"));

    /** 已声明的母实验坐标集（「常量字段集恰等已声明坐标集」的对照面） */
    private static final Set<String> EXPERIMENT_COORDINATE_FIELDS = new HashSet<>(Arrays.asList(
            "targetName", "invariant", "landingModule", "landingClassName", "landingAssertion"));

    /** 已声明的变体坐标集 */
    private static final Set<String> VARIANT_COORDINATE_FIELDS = new HashSet<>(Arrays.asList(
            "motherExperiment", "sameBoundary", "sameBreachMechanism", "sameAssertionFace", "landing"));

    /** 结论字段禁用形态（「不扩结论集」的机械面：坐标常量里不得出现任何一类结论字段） */
    private static final List<String> FORBIDDEN_VERDICT_FIELD_PARTS =
            Arrays.asList("verdict", "hit", "status");

    /**
     * 撬私有状态的调用形态（带括号，见类 javadoc 的实现期披露）。字面量以<b>拼接</b>构造 ——
     * 本守卫自身的源文件也在被扫之列，直接书写会让守卫击中自己的定义（自指自红）。
     */
    private static final List<String> FORBIDDEN_PRIVATE_ACCESS_LITERALS = Arrays.asList(
            "set" + "Accessible" + "(", "getDeclaredField" + "(");

    /** 非公开包 import 形态（引擎 / 工具的 impl / internal 包不在契约化位面上） */
    private static final Pattern NON_PUBLIC_IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?[\\w.]+\\.(impl|internal)\\.");

    /** 本测试树的源码根（surefire 工作目录 = 模块 basedir） */
    private static final Path TEST_SOURCE_ROOT = Paths.get(
            "src", "test", "java", "io", "github", "flowable", "plus", "extension", "decision");

    /** 击穿实验坐标常量类名与守卫类名（与主落点源文件一并进入受限扫描的访问面） */
    private static final List<String> SCANNED_SUPPORT_SOURCES = Arrays.asList(
            "DecisionBreachExperiments", "DecisionBreachExperimentsTest");

    /** 本模块反射可命中的包名（主落点类全在此包内） */
    private static final String LANDING_PACKAGE = "io.github.flowable.plus.extension.decision.";

    @Test
    @DisplayName("EXPERIMENTS 恰为八项母实验，实验名与执行面 §3 八项靶子名逐字相同")
    void experimentsAreExactlyTheEightNamedTargets() {
        // 只对 EXPERIMENTS 断言「恰八」；不对常量表总行数断言 —— 合法变体只增 VARIANTS。
        assertThat(DecisionBreachExperiments.EXPERIMENTS)
                .as("母实验规模恒为八项")
                .hasSize(8);
        List<String> names = DecisionBreachExperiments.EXPERIMENTS.stream()
                .map(experiment -> experiment.targetName)
                .collect(Collectors.toList());
        assertThat(names)
                .as("八项实验名必须与执行面 §3 的八项靶子名逐字相同（准入条件 ⑥）")
                .containsExactlyElementsOf(EIGHT_NAMED_TARGETS);
    }

    @Test
    @DisplayName("每项主落点具名非空；本模块落点反射可命中；跨模块落点在本模块不可见（模块位双向判真）")
    void everyExperimentNamesItsMainLanding() {
        assertThat(DecisionBreachExperiments.EXPERIMENTS)
                .allSatisfy(experiment -> {
                    assertThat(experiment.landingClassName)
                            .as("实验「%s」的主落点类名必须非空", experiment.targetName)
                            .isNotBlank();
                    assertThat(experiment.landingModule)
                            .as("实验「%s」的主落点模块位必须取闭集两值之一", experiment.targetName)
                            .isIn(DecisionBreachExperiments.MODULE_EXTENSION,
                                    DecisionBreachExperiments.MODULE_STARTER);
                });

        for (final DecisionBreachExperiments.BreachExperiment experiment : DecisionBreachExperiments.EXPERIMENTS) {
            Class<?> landingClass = landingClassOrNull(experiment.landingClassName);
            if (DecisionBreachExperiments.MODULE_EXTENSION.equals(experiment.landingModule)) {
                assertThat(landingClass)
                        .as("本模块主落点必须反射可命中（防改名漂移）：%s", experiment.landingClassName)
                        .isNotNull();
            } else {
                assertThat(landingClass)
                        .as("starter 侧主落点在本模块类路径上必须不可见（模块位失真即红）：%s",
                                experiment.landingClassName)
                        .isNull();
            }
            if (landingClass != null && experiment.landingAssertion != null) {
                assertThat(methodNamed(landingClass, experiment.landingAssertion))
                        .as("主落点断言名必须在该类上反射可命中：%s#%s",
                                experiment.landingClassName, experiment.landingAssertion)
                        .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("八主落点坐标互异，且归属不变量满射覆盖 I1–I4")
    void mainLandingsCoverAllFourInvariantsAndStayDistinct() {
        Set<String> coordinates = new HashSet<>();
        for (final DecisionBreachExperiments.BreachExperiment experiment : DecisionBreachExperiments.EXPERIMENTS) {
            String coordinate = experiment.landingModule + ":" + experiment.landingClassName
                    + (experiment.landingAssertion == null ? "" : "#" + experiment.landingAssertion);
            assertThat(coordinates.add(coordinate))
                    .as("八主落点必须互异（一实验一断言，不合并）：%s 重复", coordinate)
                    .isTrue();
        }
        Set<String> invariants = DecisionBreachExperiments.EXPERIMENTS.stream()
                .map(experiment -> experiment.invariant)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(invariants)
                .as("归属不变量必须满射覆盖 I1–I4（每条不变量 ≥1 项靶子）")
                .containsExactlyInAnyOrderElementsOf(ALL_FOUR_INVARIANTS);
    }

    @Test
    @DisplayName("变体行的母实验名必须落在 EXPERIMENTS 的实验名集合内")
    void variantsCarryTheirMotherExperimentName() {
        Set<String> motherNames = DecisionBreachExperiments.EXPERIMENTS.stream()
                .map(experiment -> experiment.targetName)
                .collect(Collectors.toSet());
        for (final DecisionBreachExperiments.BreachVariant variant : DecisionBreachExperiments.VARIANTS) {
            assertThat(variant.motherExperiment)
                    .as("变体的母实验名必须在册（不得新增断言面 / 不变量 / 准入条件）")
                    .isIn(motherNames);
            assertThat(variant.sameBoundary).isNotBlank();
            assertThat(variant.sameBreachMechanism).isNotBlank();
            assertThat(variant.sameAssertionFace).isNotBlank();
            assertThat(variant.landing).isNotBlank();
        }
        // VARIANTS 当前为空是登记态（可空列表）：本断言对空集恒真，变体登记后即具判定力。
        assertThat(DecisionBreachExperiments.VARIANTS).isNotNull();
    }

    @Test
    @DisplayName("常量字段集恰等已声明坐标集，不含 verdict / hit / status 一类结论字段")
    void experimentsCarryNoVerdictField() {
        assertFieldSetExactly(DecisionBreachExperiments.BreachExperiment.class,
                EXPERIMENT_COORDINATE_FIELDS);
        assertFieldSetExactly(DecisionBreachExperiments.BreachVariant.class,
                VARIANT_COORDINATE_FIELDS);
    }

    @Test
    @DisplayName("主落点源文件受限扫描：无撬私有状态调用、无非公开包 import，访问源文件数 ≥ 8")
    void breachLandingSourcesAvoidPrivateStateAccess() throws IOException {
        List<Path> visitedSources = new ArrayList<>();
        for (final DecisionBreachExperiments.BreachExperiment experiment : DecisionBreachExperiments.EXPERIMENTS) {
            if (!DecisionBreachExperiments.MODULE_EXTENSION.equals(experiment.landingModule)) {
                continue;
            }
            visitedSources.add(TEST_SOURCE_ROOT.resolve(experiment.landingClassName + ".java"));
        }
        for (final String supportSource : SCANNED_SUPPORT_SOURCES) {
            visitedSources.add(TEST_SOURCE_ROOT.resolve(supportSource + ".java"));
        }
        assertThat(visitedSources)
                .as("访问源文件数下限（防空转；六份本模块主落点 + 坐标常量 + 本守卫）")
                .hasSizeGreaterThanOrEqualTo(8);
        for (final Path source : visitedSources) {
            assertSourceAvoidsPrivateStateAccess(source);
        }
    }

    /**
     * 单份源文件的受限扫描：命中数 == 0（无撬私有状态调用、无非公开包 import）。
     *
     * @param source 源文件路径（相对模块 basedir）
     */
    private static void assertSourceAvoidsPrivateStateAccess(Path source) throws IOException {
        assertThat(source).as("被扫描的落点源文件必须存在").exists();
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        String displayName = source.getFileName().toString();
        for (final String forbidden : FORBIDDEN_PRIVATE_ACCESS_LITERALS) {
            List<String> offending = lines.stream()
                    .filter(line -> line.contains(forbidden))
                    .collect(Collectors.toList());
            assertThat(offending)
                    .as("击穿实验的构造只许引用被验框架的公开类型（准入条件 ①）；%s 出现撬私有状态调用「%s」",
                            displayName, forbidden)
                    .isEmpty();
        }
        List<String> nonPublicImports = lines.stream()
                .filter(line -> NON_PUBLIC_IMPORT.matcher(line).matches())
                .collect(Collectors.toList());
        assertThat(nonPublicImports)
                .as("击穿实验承载文件不得 import 非公开包（准入条件 ①）：%s", displayName)
                .isEmpty();
    }

    /** 声明字段名集合必须恰等已声明坐标集（新增任何字段 —— 尤其结论字段 —— 即红）。 */
    private static void assertFieldSetExactly(Class<?> rowType, Set<String> expectedCoordinates) {
        Set<String> actual = new HashSet<>();
        for (final Field field : rowType.getDeclaredFields()) {
            String fieldName = field.getName().toLowerCase();
            for (final String forbiddenPart : FORBIDDEN_VERDICT_FIELD_PARTS) {
                assertThat(fieldName)
                        .as("坐标常量不得携带结论字段（「不扩结论集」的机械面）：%s.%s",
                                rowType.getSimpleName(), field.getName())
                        .doesNotContain(forbiddenPart);
            }
            actual.add(field.getName());
        }
        assertThat(actual)
                .as("%s 的字段集必须恰等已声明坐标集（并列承载位不兼职表达另一件事）",
                        rowType.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(expectedCoordinates);
    }

    /** 按简单名在本机制包内解析落点类；不可命中（含跨模块不可见）返回 null。 */
    private static Class<?> landingClassOrNull(String landingClassName) {
        try {
            return Class.forName(LANDING_PACKAGE + landingClassName);
        } catch (ClassNotFoundException notVisibleHere) {
            return null;
        }
    }

    /** 按名在类上找方法（测试类的断言方法是包内可见，不走 getMethod）。 */
    private static Method methodNamed(Class<?> landingClass, String assertionName) {
        for (final Method method : landingClass.getDeclaredMethods()) {
            if (method.getName().equals(assertionName)) {
                return method;
            }
        }
        return null;
    }
}
