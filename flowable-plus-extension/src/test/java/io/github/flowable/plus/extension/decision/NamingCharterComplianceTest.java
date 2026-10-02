package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionSubjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 —— 命名宪章的全宇宙机械守卫（探索工作区落点文件 §3.2 的 {@code E1}；规则唯一住所 =
 * {@code docs/impl/0042-naming-charter.md}）。
 *
 * <p><b>十条具名断言</b>（落点表逐字）：T1 绝对禁词（类型面 / 方法面）· T2 域词限制（类型名禁用、
 * 字段与方法面按 §4.4 登记集放行）· T3 取值字面量豁免（{@code DecisionSubjectType.AI} 锚点）·
 * 常量面 / 字段面无屈折对（§2.C.1：{@code +s} / {@code +es} / {@code y→ies}，既有豁免对
 * {@code operationComment(s)} 冻结且不得引入第三变体）· 信号名前缀与格式 · 命名空间 URI 成对与唯一 ·
 * 标识符宇宙三重防空转 · {@code src/test} 类型仍受 T1 · 新测试类全部落 surefire 默认 includes。</p>
 *
 * <p><b>标识符宇宙 = 固定清单</b>（命名宪章 §1.1 的推论；命中类名集合 == 固定清单）：本机制新增 /
 * 改动的类型，含 core 侧与 extension 侧、主源与 {@code src/test}（测试类型不受 §2.C / §2.D、
 * 仍受 T1 —— 判例留痕见宪章 §4.5）。规模下限 {@code MIN_SCANNED_TYPES} = 40 /
 * {@code MIN_SCANNED_IDENTIFIERS} = 200（数值唯一住所 = 探索工作区实现期默认数值 §3）；
 * 下限取低、<b>主守卫 = 反射命中的类名集合 == 固定清单</b>（改名即红，合法新增类型不打红）。</p>
 *
 * <p><b>切词与匹配（§2.B.1 机械面）</b>：标识符按驼峰与分隔符切词、统一小写后与禁词清单匹配 ——
 * 词级精确 + 连续词拼接（多词禁词条目如 {@code vertexai}）；「{@code metadata} 含 {@code meta}」
 * 一类子串假阳性由此排除。中文禁词条目（无词形结构）按原样子串匹配。</p>
 */
class NamingCharterComplianceTest {

    // ======================== 标识符宇宙（固定清单） ========================

    private static final String CORE = "io.github.flowable.plus.core.";

    private static final String EXT = "io.github.flowable.plus.extension.decision.";

    private static final List<String> UNIVERSE = Collections.unmodifiableList(Arrays.asList(
            // ---- core 主源（新增 / 改动）----
            CORE + "vo.ApprovalRecordVO",
            CORE + "vo.CountersignSubRecord",
            CORE + "vo.DecisionEvidenceVO",
            CORE + "vo.DecisionRationaleFact",
            CORE + "vo.DecisionReplayJudge",
            CORE + "vo.UnorderedDecisionEvidences",
            CORE + "workflow.DecisionEvidenceRowProjector",
            CORE + "workflow.NewlyReadyTaskEmitter",
            CORE + "enums.CommentType",
            CORE + "enums.DecisionChainStage",
            CORE + "enums.DecisionCompleteness",
            CORE + "enums.DecisionContextSource",
            CORE + "enums.DecisionEvidenceComment",
            CORE + "enums.DecisionEvidenceReadGuard",
            CORE + "enums.DecisionEvidenceWriteGuard",
            CORE + "enums.DecisionFailureKind",
            CORE + "enums.DecisionOutcome",
            CORE + "enums.DecisionPolicyReason",
            CORE + "enums.DecisionRationaleFactKey",
            CORE + "enums.DecisionSubjectType",
            CORE + "event.EventBus",
            CORE + "event.TaskCreatedEvent",
            // ---- extension 主源（决策包全部类型）----
            EXT + "ComparableAction",
            EXT + "DecisionAssemblyResult",
            EXT + "DecisionClamp",
            EXT + "DecisionClampRejectedException",
            EXT + "DecisionContextAssembler",
            EXT + "DecisionContextSnapshot",
            EXT + "DecisionCredential",
            EXT + "DecisionCredentialResolver",
            EXT + "DecisionDefaultContextSources",
            EXT + "DecisionEvidenceDraft",
            EXT + "DecisionEvidenceWriter",
            EXT + "DecisionInboundProcessor",
            EXT + "DecisionInboundResult",
            EXT + "DecisionMetrics",
            EXT + "DecisionMetricsRecorder",
            EXT + "DecisionNodeDeclaration",
            EXT + "DecisionNodeDeclarationReader",
            EXT + "DecisionNodeDeclarationValidator",
            EXT + "DecisionObservation",
            EXT + "DecisionObservationEmitter",
            EXT + "DecisionObserver",
            EXT + "DecisionOutcomeMapping",
            EXT + "DecisionOutboundResult",
            EXT + "DecisionPayload",
            EXT + "DecisionPipeline",
            EXT + "DecisionPolicy",
            EXT + "DecisionProcessingRecord",
            EXT + "DecisionProvider",
            EXT + "DecisionProviderRequest",
            EXT + "DecisionProviderResponse",
            EXT + "DecisionRuntimeControl",
            EXT + "DecisionSeverity",
            EXT + "DecisionTarget",
            EXT + "DecisionTaskCreatedListener",
            EXT + "DecisionTransport",
            EXT + "DecisionTransportRequest",
            EXT + "DecisionTransportResponse",
            EXT + "DefaultDecisionProvider",
            EXT + "DefaultDecisionRuntimeControl",
            EXT + "DefaultSuggestionSubmissionService",
            EXT + "HttpDecisionTransport",
            EXT + "ProcessInstanceMetadata",
            EXT + "SuggestionAdmissionException",
            EXT + "SuggestionAdmissionReason",
            EXT + "SuggestionSubmission",
            EXT + "SuggestionSubmissionService",
            EXT + "TaskMetadata",
            EXT + "WriteDegradedCause",
            // ---- extension 测试树（含本票新增）----
            EXT + "ComparableActionAvailabilityTest",
            EXT + "DecisionBreachExperiments",
            EXT + "DecisionBreachExperimentsTest",
            EXT + "DecisionClampTest",
            EXT + "DecisionContextAssemblerTest",
            EXT + "DecisionCredentialTest",
            EXT + "DecisionDisabledEquivalenceTest",
            EXT + "DecisionEvidenceReadOrderTest",
            EXT + "DecisionEvidenceSubmissionTest",
            EXT + "DecisionEvidenceWriterTest",
            EXT + "DecisionFixtures",
            EXT + "DecisionNodeDeclarationTest",
            EXT + "DecisionNodeDeclarationValidatorTest",
            EXT + "DecisionObservationContractTest",
            EXT + "DecisionOutcomeMappingTest",
            EXT + "DecisionPayloadTest",
            EXT + "DecisionPipelineTest",
            EXT + "DecisionPolicyTest",
            EXT + "DecisionProviderContractTest",
            EXT + "DecisionRuntimeControlTest",
            EXT + "DecisionStubContractTest",
            EXT + "ExtensionTestEngine",
            EXT + "NamingCharterComplianceTest",
            EXT + "StubDecisionTransport",
            EXT + "SuggestionAdmissionContractTest"));

    /**
     * core 测试树的新增类型（简单名）。它们不在本模块的类路径上（core 不发布 test-jar），
     * 反射不可达 —— T1 约束仍适用（宪章 §1.1 判例），以名录扫描承担；「命中类名集合 == 固定清单」
     * 的反射守卫只覆盖本模块可见的宇宙。core 测试类型的屈折 / 字段面由 core 自身的落点承担。
     */
    private static final List<String> CORE_TEST_TYPE_NAMES = Arrays.asList(
            "CommentTypeExhaustivenessTest",
            "HistoryWorkflowEvidenceReadTest",
            "ConstantFieldAssertions",
            "DecisionContextSourceTest",
            "DecisionEvidenceMarkerTest",
            "DecisionEvidenceReadGuardTest",
            "DecisionEvidenceVOContractTest",
            "DecisionEvidenceWriteGuardTest",
            "SourceScanSupport",
            "TaskCreatedEventContractTest",
            "DecisionEvidenceTestFixtures");

    /** 反射命中的类名集合（防空转第二重：== 固定清单，改名即红） */
    private static final List<Class<?>> HIT_CLASSES = UNIVERSE.stream()
            .map(NamingCharterComplianceTest::load)
            .collect(Collectors.toList());

    /** 规模下限（数值唯一住所 = 实现期默认数值汇总） */
    private static final int MIN_SCANNED_TYPES = 40;

    private static final int MIN_SCANNED_IDENTIFIERS = 200;

    // ======================== 禁词清单（命名宪章 §4.1 / §2.B.2） ========================

    /** T1 绝对禁词（小写；多词条目按连续词拼接匹配；中文按原样子串匹配） */
    private static final List<String> T1_WORDS = Arrays.asList(
            "openai", "anthropic", "google", "meta", "microsoft", "nvidia", "mistral", "cohere",
            "alibaba", "baidu", "deepseek", "gpt", "claude", "gemini", "llama", "qwen",
            "ernie", "palm", "bedrock", "azureopenai", "vertexai");

    /** T1 中文禁词条目（原样子串匹配） */
    private static final List<String> T1_CJK_WORDS = Arrays.asList("百炼", "文心", "克劳德");

    /** T2 域词（类型名禁用；字段 / 方法面按 §4.4 登记集放行） */
    private static final List<String> T2_WORDS = Arrays.asList("ai", "model", "llm", "agent");

    /** T2 替换测试通过的字段登记集（宪章 §4.4；方法名去 get/set 前缀后同表） */
    private static final Set<String> T2_PASSED_FIELDS = new HashSet<>(Arrays.asList(
            "modelId", "provider", "rawOutput"));

    /** 形近既有豁免对（宪章 §4.3 #1：既有对冻结，不得引入第三变体） */
    private static final Set<String> INFLECTION_EXEMPT_PAIR = new HashSet<>(Arrays.asList(
            "operationcomment", "operationcomments"));

    // ======================== 测试 ========================

    @Test
    @DisplayName("标识符宇宙非空且闭合：规模下限 + 反射命中类名集合 == 固定清单 + 扫描标识符总量下限")
    void identifierUniverseIsNotEmpty() {
        assertThat(HIT_CLASSES).hasSize(UNIVERSE.size()).hasSizeGreaterThanOrEqualTo(MIN_SCANNED_TYPES);
        long identifierCount = 0;
        for (final Class<?> type : HIT_CLASSES) {
            identifierCount += 1 + type.getDeclaredFields().length + type.getDeclaredMethods().length;
        }
        assertThat(identifierCount).as("扫描到的标识符总量下限（防空转）")
                .isGreaterThanOrEqualTo(MIN_SCANNED_IDENTIFIERS);
    }

    @Test
    @DisplayName("T1 / T2：类型名切词后无绝对禁词、无域词")
    void typeNameFreeOfDomainWords() {
        for (final Class<?> type : HIT_CLASSES) {
            assertThat(scanWords(type.getSimpleName(), T1_WORDS, T1_CJK_WORDS, true))
                    .as("类型名不得携带 T1 绝对禁词：%s", type.getSimpleName())
                    .isEmpty();
            assertThat(scanWords(type.getSimpleName(), T2_WORDS, Collections.emptyList(), true))
                    .as("类型名不得携带 T2 域词（ADR-0042 §3 ①）：%s", type.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("T1 / T2：方法名无绝对禁词；域词仅 §4.4 登记族放行")
    void methodNamesFreeOfDomainWords() {
        for (final Class<?> type : HIT_CLASSES) {
            for (final java.lang.reflect.Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                assertThat(scanWords(name, T1_WORDS, T1_CJK_WORDS, true))
                        .as("方法名不得携带 T1 绝对禁词：%s#%s", type.getSimpleName(), name)
                        .isEmpty();
                List<String> t2 = scanWords(name, T2_WORDS, Collections.emptyList(), true);
                if (!t2.isEmpty()) {
                    assertThat(T2_PASSED_FIELDS)
                            .as("方法 %s#%s 携带 T2 域词，必须属替换测试登记族（§4.4）", type.getSimpleName(), name)
                            .containsAnyOf(name, decapitalizeAccessor(name));
                }
            }
        }
    }

    @Test
    @DisplayName("T2 字段面：域词仅 §4.4 登记族放行（枚举取值走 T3 豁免，不在字段面）")
    void t3ExemptLiteralsAreEnumMembers() {
        // T3 的锚点：「AI」作为 subjectType 的取值是枚举成员，不是命名绑定 —— 豁免的机械前提
        assertThat(enumConstantNames(DecisionSubjectType.class))
                .as("T3 豁免锚点：AI 必须是 DecisionSubjectType 的枚举成员")
                .contains("AI");
        // 字段面（跳过枚举取值）：携带 T2 域词的字段必须属登记族
        for (final Class<?> type : HIT_CLASSES) {
            if (type.isEnum()) {
                continue;
            }
            for (final Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    // 常量名（含维度键常量）不属 §2.B.3 的「必填性联动」面；其名录已由宪章 §4.5 登记
                    continue;
                }
                List<String> t2 = scanWords(field.getName(), T2_WORDS, Collections.emptyList(), true);
                if (!t2.isEmpty()) {
                    assertThat(T2_PASSED_FIELDS)
                            .as("字段 %s.%s 携带 T2 域词，必须属替换测试登记族且可空（§4.4 / §2.B.3）",
                                    type.getSimpleName(), field.getName())
                            .contains(field.getName());
                }
            }
        }
    }

    @Test
    @DisplayName("常量面：同一类型内无单复数屈折对（§2.C.1 + §2.C.2 硬域；src/test 类型不适用）")
    void constantNamesNotSingularPluralWithinType() {
        for (final Class<?> type : HIT_CLASSES) {
            if (isTestType(type)) {
                continue;
            }
            List<String> constants = new ArrayList<>();
            for (final Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
                    constants.add(field.getName());
                }
            }
            assertThat(inflectionPairs(constants))
                    .as("常量面不得出现屈折对：%s", type.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("字段面：同一类型内无单复数屈折对；既有豁免对不得引入第三变体（src/test 类型不适用）")
    void fieldNamesNotSingularPluralWithinType() {
        for (final Class<?> type : HIT_CLASSES) {
            if (isTestType(type)) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            for (final Field field : type.getDeclaredFields()) {
                if (!(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()))) {
                    fields.add(field.getName());
                }
            }
            assertThat(inflectionPairs(fields))
                    .as("字段面不得出现屈折对：%s", type.getSimpleName())
                    .isEmpty();
            // 既有豁免对的第三变体禁令（宪章 §4.3 #1）
            List<String> family = fields.stream()
                    .map(NamingCharterComplianceTest::normalize)
                    .filter(name -> name.startsWith("operationcomment"))
                    .collect(Collectors.toList());
            if (!family.isEmpty()) {
                assertThat(family)
                        .as("operationComment / operationComments 既有豁免对冻结，不得引入第三变体：%s",
                                type.getSimpleName())
                        .containsExactlyInAnyOrderElementsOf(INFLECTION_EXEMPT_PAIR);
            }
        }
    }

    @Test
    @DisplayName("信号名：九个信号值带机制前缀、全小写点分隔、无 METRIC / COUNTER 后缀")
    void signalNamesPrefixedAndFormatted() {
        final String prefix = "flowable.plus.decision.";
        Map<String, String> signals = new HashMap<>();
        for (final Field field : DecisionMetrics.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                if (value != null && value.startsWith(prefix)) {
                    signals.put(field.getName(), value);
                }
            } catch (IllegalAccessException unreachable) {
                throw new AssertionError("DecisionMetrics 的信号常量必须可反射读取", unreachable);
            }
        }
        assertThat(signals)
                .as("信号名恰为九个（可观测面闭集；名与值两侧受 T1 约束）")
                .hasSize(9);
        signals.forEach((constantName, value) -> {
            assertThat(value)
                    .as("信号值必须全小写点分隔（无下划线 / 大写 / 空白）：%s = %s", constantName, value)
                    .matches("[a-z.0-9]+");
            assertThat(constantName)
                    .as("信号名常量取 SCREAMING_SNAKE_CASE：%s", constantName)
                    .matches("[A-Z][A-Z0-9_]*");
            for (final String bannedSuffix : Arrays.asList("metric", "counter")) {
                assertThat(value.endsWith(bannedSuffix))
                        .as("信号名不得携带 METRIC / COUNTER 一类后缀：%s", value)
                        .isFalse();
            }
            assertThat(scanWords(constantName, T1_WORDS, T1_CJK_WORDS, true))
                    .as("信号名常量不得携带 T1 绝对禁词（名值两侧受 T1 约束）：%s", constantName)
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("命名空间：URI / 前缀成对收口单一常量类、不撞引擎保留命名空间、主源无裸字面量")
    void namespaceUriIsUniqueAndPairedWithPrefix() throws IOException {
        // 成对收口单一常量类
        assertThat(DecisionNodeDeclaration.NAMESPACE_URI).isNotBlank();
        assertThat(DecisionNodeDeclaration.NAMESPACE_PREFIX).isNotBlank();
        // 唯一性（§2.E.2）：不等于两个引擎保留命名空间
        assertThat(DecisionNodeDeclaration.NAMESPACE_URI)
                .as("URI 不得等于引擎保留命名空间")
                .isNotIn("http://flowable.org/bpmn", "http://activiti.org/bpmn");
        assertThat(DecisionNodeDeclaration.NAMESPACE_URI)
                .as("URI 须含本框架的稳定标识路径段")
                .contains("flowable.plus");
        // 禁裸字面量（§2.E.1；判据取带引号形态，宪章 §4.5 判例 ①）：带引号的 URI / 前缀 / 属性名
        // 只允许出现在收口常量类的源文件里（扫描域 = extension 主源）
        List<String> literals = Arrays.asList(
                "\"" + DecisionNodeDeclaration.NAMESPACE_URI + "\"",
                "\"" + DecisionNodeDeclaration.NAMESPACE_PREFIX + "\"",
                "\"" + DecisionNodeDeclaration.DECISION_ENABLED + "\"",
                "\"" + DecisionNodeDeclaration.DECISION_DATA_SOURCES + "\"",
                "\"" + DecisionNodeDeclaration.DECISION_TARGET + "\"",
                "\"" + DecisionNodeDeclaration.DECISION_POLICY + "\"");
        Map<String, List<Path>> hits = new HashMap<>();
        try (Stream<Path> sources = java.nio.file.Files.walk(Paths.get("src", "main", "java"))) {
            for (final Path source : sources.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                for (final String literal : literals) {
                    if (text.contains(literal)) {
                        hits.computeIfAbsent(literal, key -> new ArrayList<>()).add(source);
                    }
                }
            }
        }
        Path declarationSource = Paths.get("src", "main", "java", "io", "github", "flowable", "plus",
                "extension", "decision", "DecisionNodeDeclaration.java").toAbsolutePath().normalize();
        hits.forEach((literal, files) -> assertThat(files)
                .as("机制命名空间 / 属性名的带引号字面量只许住在收口常量类：%s", literal)
                .allSatisfy(path -> assertThat(path.toAbsolutePath().normalize())
                        .isEqualTo(declarationSource)));
    }

    @Test
    @DisplayName("src/test 类型仍受 T1（不受 §2.C / §2.D，判例留痕见宪章 §4.5）")
    void testTypesStillSubmitToT1() {
        // core 测试树类型（本模块类路径不可见）：名录 T1 扫描
        for (final String coreTestType : CORE_TEST_TYPE_NAMES) {
            assertThat(scanWords(coreTestType, T1_WORDS, T1_CJK_WORDS, true))
                    .as("core 测试类型仍受 T1 约束：%s", coreTestType)
                    .isEmpty();
        }
        List<Class<?>> testTypes = HIT_CLASSES.stream()
                .filter(type -> type.getName().contains("Test")
                        || DecisionFixtures.class.equals(type)
                        || ExtensionTestEngine.class.equals(type)
                        || StubDecisionTransport.class.equals(type)
                        || DecisionBreachExperiments.class.equals(type))
                .collect(Collectors.toList());
        assertThat(testTypes).as("测试类型子集必须真实进入宇宙（防空转）").isNotEmpty();
        for (final Class<?> type : testTypes) {
            assertThat(scanWords(type.getSimpleName(), T1_WORDS, T1_CJK_WORDS, true))
                    .as("测试类型仍受 T1 绝对禁词约束：%s", type.getSimpleName())
                    .isEmpty();
            for (final Field field : type.getDeclaredFields()) {
                assertThat(scanWords(field.getName(), T1_WORDS, T1_CJK_WORDS, true))
                        .as("测试类型字段仍受 T1 约束：%s.%s", type.getSimpleName(), field.getName())
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("新测试类全部落 surefire 默认 includes（CI 零配置的结构保证）")
    void allNewTestClassesMatchSurefireIncludes() throws IOException {
        Path testRoot = Paths.get("src", "test", "java");
        try (Stream<Path> sources = java.nio.file.Files.walk(testRoot)) {
            List<Path> testSources = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
            assertThat(testSources).as("extension 测试树必须真实存在（防空转）").isNotEmpty();
            for (final Path source : testSources) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                if (text.contains("@Test")) {
                    String simpleName = source.getFileName().toString().replace(".java", "");
                    assertThat(simpleName)
                            .as("含 @Test 的测试类必须以 Test 结尾（surefire 默认 includes）：%s", simpleName)
                            .endsWith("Test");
                }
            }
        }
    }

    // ======================== 切词与判定辅助 ========================

    /** 测试类型判定（src/test 树；对它们 §2.C / §2.D 不适用，T1 仍适用 —— 宪章 §4.5 判例）。 */
    private static boolean isTestType(Class<?> type) {
        return type.getSimpleName().endsWith("Test")
                || type.getSimpleName().equals("DecisionFixtures")
                || type.getSimpleName().equals("ExtensionTestEngine")
                || type.getSimpleName().equals("StubDecisionTransport")
                || type.getSimpleName().equals("DecisionBreachExperiments");
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException broken) {
            throw new AssertionError("标识符宇宙的固定清单必须全部反射可命中（改名即红，先改清单）：" + className,
                    broken);
        }
    }

    /**
     * 切词后匹配：词级精确 + 连续词拼接（多词禁词条目）；中文条目按原样子串匹配。
     *
     * @return 命中的禁词列表（空 = 干净）
     */
    private static List<String> scanWords(String identifier, List<String> words,
                                          List<String> cjkWords, boolean allowRunJoins) {
        List<String> tokens = splitWords(identifier);
        List<String> hits = new ArrayList<>();
        for (final String word : words) {
            if (tokens.contains(word)) {
                hits.add(word);
            } else if (allowRunJoins) {
                // 多词条目：检查连续词拼接（如 vertex + ai == vertexai）
                for (int start = 0; start < tokens.size(); start++) {
                    StringBuilder joined = new StringBuilder();
                    for (int end = start; end < tokens.size(); end++) {
                        joined.append(tokens.get(end));
                        if (joined.toString().equals(word)) {
                            hits.add(word);
                        }
                    }
                }
            }
        }
        for (final String cjk : cjkWords) {
            if (identifier.contains(cjk)) {
                hits.add(cjk);
            }
        }
        return hits;
    }

    /** 驼峰 + 分隔符切词，统一小写。 */
    private static List<String> splitWords(String identifier) {
        String spaced = identifier.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return Arrays.stream(spaced.split("[^A-Za-z0-9]+"))
                .filter(token -> !token.isEmpty())
                .map(token -> token.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    /** 归一化：去分隔符、统一小写（§2.C.1）。 */
    private static String normalize(String identifier) {
        return identifier.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** 找出单复数屈折对（{@code +s} / {@code +es} / {@code y→ies}）；既有豁免对不计。 */
    private static List<String> inflectionPairs(List<String> names) {
        Set<String> normalized = names.stream()
                .map(NamingCharterComplianceTest::normalize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> violations = new ArrayList<>();
        for (final String singular : normalized) {
            for (final String pluralForm : Arrays.asList(singular + "s", singular + "es",
                    singular.substring(0, singular.length() - 1) + "ies")) {
                if (pluralForm.endsWith("ies") && !singular.endsWith("y")) {
                    continue;
                }
                if (normalized.contains(pluralForm)
                        && !INFLECTION_EXEMPT_PAIR.contains(singular)
                        && !INFLECTION_EXEMPT_PAIR.contains(pluralForm)) {
                    violations.add(singular + " / " + pluralForm);
                }
            }
        }
        return violations;
    }

    private static String decapitalizeAccessor(String methodName) {
        for (final String prefix : Arrays.asList("get", "set", "is")) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                return Character.toLowerCase(methodName.charAt(prefix.length()))
                        + methodName.substring(prefix.length() + 1);
            }
        }
        return methodName;
    }

    private static List<String> enumConstantNames(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.toList());
    }
}
