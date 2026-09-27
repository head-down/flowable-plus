package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionContextSource;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 —— 节点声明的常量面与解析面守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E2}）。
 *
 * <p><b>承哪些推入项</b>：四属性名与本机制命名空间<b>成对收口单一常量类</b>、命名空间唯一性（≠ 两个引擎
 * 保留命名空间）、属性名小驼峰；以及数据源 token 的<b>解析口径</b>（token = 枚举常量名原文、大小写敏感、
 * 仅两侧空白容忍）。</p>
 *
 * <p><b>纯值 / 纯解析，零引擎</b>：断言对象全部是常量、反射与字符串解析，不需要引擎的运行时行为或生成物
 * （按落点表 §1.1 的模块切分规则，这类断言住原模块）。</p>
 *
 * <p><b>契约面 pin 与源码唯一性</b>：常量值一旦被改即属契约变更（属性名是 ADR 冻结值），故本类既用反射钉
 * 「声明形状」、又用受限源码扫描钉「主源码里除常量类外别无裸字面量」（后者是「成对入常量 + 禁裸字面量」
 * 的机械面）。</p>
 */
class DecisionNodeDeclarationTest {

    /** ADR-0042 第 7 节 冻结的四个属性名（写进测试即为 pin：值被改则红） */
    private static final List<String> FROZEN_ATTRIBUTE_NAMES = Collections.unmodifiableList(Arrays.asList(
            "decisionEnabled", "decisionDataSources", "decisionTarget", "decisionPolicy"));

    /**
     * 小驼峰形（属性名大小写惯例，同主仓既有扩展属性与引擎原生属性）。
     *
     * <p>匹配规则：首字符为小写字母 {@code [a-z]}，其后为任意个字母或数字 {@code [a-zA-Z0-9]*}。
     * 满足 {@code decisionEnabled} / {@code decisionTarget}；不满足 {@code DecisionEnabled}（大驼峰）、
     * {@code decision_target}（下划线）、{@code decision-target}（连字符）。</p>
     */
    private static final String LOWER_CAMEL_CASE = "^[a-z][a-zA-Z0-9]*$";

    /** 主源码树（相对模块 basedir；surefire 工作目录 = 模块 basedir，故相对路径可靠） */
    private static final Path MAIN_SOURCES = Paths.get("src", "main", "java");

    /** 只扫 Java 源文件（javadoc 也住这里 —— 「含注释不得另拼字面量」才可判） */
    private static final String JAVA_SUFFIX = ".java";

    /** 防空转下限：访问源文件数低于它即视为扫描路径失效（主守卫仍是「命中文件集恒等」） */
    private static final int MIN_SCANNED_SOURCE_FILES = 5;

    /** 常量类在主源码树中的相对路径（唯一允许出现本机制命名空间与属性名字面量的文件） */
    private static final String CONSTANT_CLASS_FILE =
            "io/github/flowable/plus/extension/decision/DecisionNodeDeclaration.java";

    @Test
    @DisplayName("四属性名与本机制命名空间成对定义在单一常量类内，主源码里别无裸字面量")
    void attributesAndUriAreDefinedInSingleConstantClass() throws NoSuchFieldException {
        final List<String> constantNames = Arrays.asList("NAMESPACE_URI", "NAMESPACE_PREFIX",
                "DECISION_ENABLED", "DECISION_DATA_SOURCES", "DECISION_TARGET", "DECISION_POLICY");
        for (final String constantName : constantNames) {
            final Field field = DecisionNodeDeclaration.class.getField(constantName);
            assertThat(Modifier.isPublic(field.getModifiers())).as("%s 必须是公开常量", constantName).isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).as("%s 必须是静态常量", constantName).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).as("%s 必须是终态常量", constantName).isTrue();
            assertThat(field.getType()).as("%s 必须是字符串常量", constantName).isEqualTo(String.class);
            assertThat(field.getDeclaringClass())
                    .as("成对收口：%s 必须住在常量类内", constantName)
                    .isEqualTo(DecisionNodeDeclaration.class);
        }
        // 源码面：命名空间按原样、四个属性名按「字符串直接量」形态，都只许出现在常量类这一个文件里
        assertThat(filesContaining(DecisionNodeDeclaration.NAMESPACE_URI))
                .as("命名空间是引擎按 URI 存储的识别锚点：只允许住常量类")
                .containsExactly(CONSTANT_CLASS_FILE);
        final List<String> attributeNames = Arrays.asList(
                DecisionNodeDeclaration.DECISION_ENABLED,
                DecisionNodeDeclaration.DECISION_DATA_SOURCES,
                DecisionNodeDeclaration.DECISION_TARGET,
                DecisionNodeDeclaration.DECISION_POLICY);
        attributeNames.forEach(attributeName -> assertThat(filesContaining(asStringLiteral(attributeName)))
                .as("「成对入常量 + 禁裸字面量」：属性名 %s 的字符串直接量只允许住常量类", attributeName)
                .containsExactly(CONSTANT_CLASS_FILE));
    }

    @Test
    @DisplayName("本机制命名空间与两个引擎保留命名空间（含前缀）都不相等")
    void uriIsNotAnEngineReservedNamespace() {
        // 判据取自引擎自身的常量表，而非测试里另抄一份保留名
        assertThat(DecisionNodeDeclaration.NAMESPACE_URI)
                .isNotEqualTo(BpmnXMLConstants.FLOWABLE_EXTENSIONS_NAMESPACE)
                .isNotEqualTo(BpmnXMLConstants.ACTIVITI_EXTENSIONS_NAMESPACE)
                .isNotBlank();
        assertThat(DecisionNodeDeclaration.NAMESPACE_PREFIX)
                .isNotEqualTo(BpmnXMLConstants.FLOWABLE_EXTENSIONS_PREFIX)
                .isNotEqualTo(BpmnXMLConstants.ACTIVITI_EXTENSIONS_PREFIX)
                .isNotBlank();
    }

    @Test
    @DisplayName("四个属性名是小驼峰，且取值等于 ADR 冻结值")
    void attributeNamesAreCamelCase() {
        final List<String> attributeNames = Arrays.asList(
                DecisionNodeDeclaration.DECISION_ENABLED,
                DecisionNodeDeclaration.DECISION_DATA_SOURCES,
                DecisionNodeDeclaration.DECISION_TARGET,
                DecisionNodeDeclaration.DECISION_POLICY);
        attributeNames.forEach(attributeName -> assertThat(attributeName)
                .as("属性名必须小驼峰")
                .matches(LOWER_CAMEL_CASE));
        assertThat(attributeNames)
                .as("属性名是 ADR 冻结值：改名即契约变更")
                .containsExactlyElementsOf(FROZEN_ATTRIBUTE_NAMES);
    }

    @Test
    @DisplayName("数据源 token 只认枚举常量名原文：大小写敏感、仅两侧空白容忍、空位不静默丢弃")
    void tokenParsingAcceptsEnumNameVerbatim() {
        assertThat(DecisionNodeDeclarationReader.parseDataSourceToken("PROCESS_VARIABLES"))
                .isEqualTo(DecisionContextSource.PROCESS_VARIABLES);
        assertThat(DecisionNodeDeclarationReader.parseDataSourceToken("  TASK_VARIABLES  "))
                .as("仅两侧空白容忍")
                .isEqualTo(DecisionContextSource.TASK_VARIABLES);
        assertThat(DecisionNodeDeclarationReader.parseDataSourceToken("process_variables"))
                .as("大小写敏感：小写抄写变体一律判未知，不静默归一")
                .isNull();
        assertThat(DecisionNodeDeclarationReader.parseDataSourceToken("Process_Variables")).isNull();
        assertThat(DecisionNodeDeclarationReader.parseDataSourceToken("PROCESS_VARIABLE")).isNull();
        assertThat(DecisionNodeDeclarationReader.parseDataSourceToken("")).isNull();
        assertThat(DecisionNodeDeclarationReader.parseDataSourceToken(null)).isNull();

        assertThat(DecisionNodeDeclarationReader.splitDataSourceTokens("PROCESS_VARIABLES, TASK_VARIABLES"))
                .as("逗号分隔 + 两侧裁剪")
                .containsExactly("PROCESS_VARIABLES", "TASK_VARIABLES");
        assertThat(DecisionNodeDeclarationReader.splitDataSourceTokens(",TASK_VARIABLES"))
                .as("空 token 不得静默丢弃（它是部署期阻断项）")
                .containsExactly("", "TASK_VARIABLES");
        assertThat(DecisionNodeDeclarationReader.splitDataSourceTokens("TASK_VARIABLES,"))
                .as("尾随空位同样保留")
                .containsExactly("TASK_VARIABLES", "");
        assertThat(DecisionNodeDeclarationReader.splitDataSourceTokens(""))
                .as("整值空串 = 显式空集（零 token），不是空 token 违规")
                .isEmpty();
        assertThat(DecisionNodeDeclarationReader.splitDataSourceTokens("   ")).isEmpty();
        assertThat(DecisionNodeDeclarationReader.splitDataSourceTokens(null)).isEmpty();
    }

    @Test
    @DisplayName("启用开关只认小写字面量 true / false，不做空白容忍")
    void enabledParsingAcceptsOnlyLowercaseLiterals() {
        assertThat(DecisionNodeDeclarationReader.parseEnabled("true")).isTrue();
        assertThat(DecisionNodeDeclarationReader.parseEnabled("false")).isFalse();
        assertThat(DecisionNodeDeclarationReader.parseEnabled("TRUE"))
                .as("大小写敏感：大写变体判非法（部署期即阻断）")
                .isNull();
        assertThat(DecisionNodeDeclarationReader.parseEnabled(" true "))
                .as("取值域未授予空白容忍，故带空白的取值判非法")
                .isNull();
        assertThat(DecisionNodeDeclarationReader.parseEnabled("1")).isNull();
        assertThat(DecisionNodeDeclarationReader.parseEnabled("")).isNull();
        assertThat(DecisionNodeDeclarationReader.parseEnabled(null)).isNull();
    }

    /**
     * 值的「字符串直接量」形态（带双引号）。
     *
     * <p>属性名的禁裸字面量判据取带引号形态，<b>不</b>取裸子串：属性名是普通英文词组，出现在<b>标识符片段</b>里
     * （如字段名 {@code decisionTargetKeys}）不是另一份字面量来源；只有源码 / 注释里另行拼出的字符串直接量才是
     * 「裸字面量」。命名空间没有这个歧义（不可能成为标识符片段），故按原样匹配。</p>
     *
     * @param value 字面量取值
     * @return 带双引号的形态
     */
    private static String asStringLiteral(final String value) {
        return "\"" + value + "\"";
    }

    /**
     * 扫主源码树，返回含指定<b>精确字面量</b>的源文件相对路径集合。
     *
     * @param literal 精确字面量（按子串匹配；javadoc 与源码同判）
     * @return 命中文件的相对路径（{@code /} 分隔）；无命中时为空列表
     */
    private static List<String> filesContaining(final String literal) {        return mainSourceFiles().stream()
                .filter(file -> containsLiteral(file, literal))
                .map(DecisionNodeDeclarationTest::relativePath)
                .collect(Collectors.toList());
    }

    /**
     * 枚举主源码树的 Java 文件，并执行防空转下限检查。
     */
    private static List<Path> mainSourceFiles() {
        try (Stream<Path> stream = Files.walk(MAIN_SOURCES)) {
            final List<Path> sourceFiles = stream.filter(Files::isRegularFile)
                    .filter(path -> StringUtils.endsWith(path.getFileName().toString(), JAVA_SUFFIX))
                    .collect(Collectors.toList());
            assertThat(sourceFiles.size())
                    .as("防空转：至少访问 %d 个源文件，否则扫描路径写错会让本守卫恒绿", MIN_SCANNED_SOURCE_FILES)
                    .isGreaterThanOrEqualTo(MIN_SCANNED_SOURCE_FILES);
            return sourceFiles;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 判断源文件是否含指定字面量。
     */
    private static boolean containsLiteral(final Path file, final String literal) {
        try {
            // 一次性整读取行：单文件 KB 级、判据是「任一行含字面量」，流式无收益
            return Files.readAllLines(file).stream().anyMatch(line -> StringUtils.contains(line, literal));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 相对主源码树的路径（{@code /} 分隔，跨平台可比）。
     */
    private static String relativePath(final Path file) {
        return StringUtils.replace(MAIN_SOURCES.relativize(file).toString(), "\\", "/");
    }
}
