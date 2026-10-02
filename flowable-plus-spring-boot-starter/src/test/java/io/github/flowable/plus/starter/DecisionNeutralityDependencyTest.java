package io.github.flowable.plus.starter;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 —— 中立性 ④（依赖方向，ADR-0042 第 4 节）。
 *
 * <p>形式（Q6(a)）：测试内断言<strong>直接依赖坐标清单 == 固定常量</strong>（surefire 工作目录 =
 * 模块 basedir，读 {@code pom.xml} 可靠）＋ <strong>禁词库入口类</strong>的负向存在性断言（覆盖传递依赖）。
 * 断言名形态与 {@code docs/impl/0042-verification-landings.md} §4 的 S3 行逐字一致。</p>
 *
 * <p>固定常量按<strong>订正后的账本终表</strong>书写（{@code module-and-build} §1.1 依据 1–8）：extension
 * 显式声明 {@code org.slf4j:slf4j-api}（观测面首次直接 import）与 {@code jackson-databind} /
 * {@code commons-lang3}；starter 新增 {@code flowable-plus-extension} 与 {@code micrometer-core} 各
 * {@code <optional>true</optional>}。test-scope 依赖计入断言集（本仓裁定：pom 声明面是账本的机械住所，
 * 坐标清单以模块自身 pom 的全部 {@code <dependency>} 声明为准 —— 不含父 POM 注入的共享项，如 lombok）。</p>
 */
class DecisionNeutralityDependencyTest {

    /** T1 绝对禁词（唯一住所 = {@link DecisionAssemblyTestSupport#T1_BANNED_WORDS}，命名宪章 §4.1） */
    private static final List<String> T1_BANNED_WORDS = DecisionAssemblyTestSupport.T1_BANNED_WORDS;

    /** 禁词库的 SDK 入口包根（资源路径形态；负向存在性覆盖传递依赖面） */
    private static final List<String> BANNED_VENDOR_ENTRY_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "com/openai",
            "com/theokanning/openai",
            "com/anthropic",
            "dev/ai4j",
            "com/azure/ai/openai",
            "com/google/cloud/vertexai",
            "software/amazon/awssdk/services/bedrock",
            "com/alibaba/dashscope",
            "com/baidu/aip",
            "com/deepseek"));

    /** extension 直接依赖坐标清单（module-and-build §1.1 订正终表；模块自身 pom 声明面） */
    private static final List<String> EXTENSION_DECLARED_DEPENDENCIES = Collections.unmodifiableList(Arrays.asList(
            "com.fasterxml.jackson.core:jackson-databind",
            "com.h2database:h2",
            "io.github.flowable.plus:flowable-plus-core",
            "org.apache.commons:commons-lang3",
            "org.apache.httpcomponents:httpclient",
            "org.assertj:assertj-core",
            "org.junit.jupiter:junit-jupiter",
            "org.mockito:mockito-core",
            "org.slf4j:slf4j-api"));

    /** starter 直接依赖坐标清单（同上） */
    private static final List<String> STARTER_DECLARED_DEPENDENCIES = Collections.unmodifiableList(Arrays.asList(
            "com.h2database:h2",
            "io.github.flowable.plus:flowable-plus-core",
            "io.github.flowable.plus:flowable-plus-extension",
            "io.micrometer:micrometer-core",
            "mysql:mysql-connector-java",
            "org.flowable:flowable-spring-boot-starter",
            "org.postgresql:postgresql",
            "org.springframework.boot:spring-boot-actuator",
            "org.springframework.boot:spring-boot-configuration-processor",
            "org.springframework.boot:spring-boot-starter",
            "org.springframework.boot:spring-boot-starter-actuator",
            "org.springframework.boot:spring-boot-starter-jdbc",
            "org.springframework.boot:spring-boot-starter-test",
            "org.springframework.security:spring-security-core",
            "org.testcontainers:junit-jupiter",
            "org.testcontainers:mysql",
            "org.testcontainers:postgresql",
            "org.testcontainers:testcontainers"));

    @Test
    void extensionDirectDependenciesEqualDeclaredSet() throws Exception {
        final List<String> declared = declaredDependenciesOf("../flowable-plus-extension/pom.xml");
        Collections.sort(declared);
        final List<String> expected = new ArrayList<>(EXTENSION_DECLARED_DEPENDENCIES);
        Collections.sort(expected);
        assertThat(declared).containsExactlyElementsOf(expected);
        assertCoordinatesFreeOfBannedWords(declared);
    }

    @Test
    void starterDirectDependenciesEqualDeclaredSet() throws Exception {
        final List<String> declared = declaredDependenciesOf("pom.xml");
        Collections.sort(declared);
        final List<String> expected = new ArrayList<>(STARTER_DECLARED_DEPENDENCIES);
        Collections.sort(expected);
        assertThat(declared).containsExactlyElementsOf(expected);
        assertCoordinatesFreeOfBannedWords(declared);
    }

    @Test
    void bannedVendorEntryClassesAreAbsent() {
        // 负向存在性：禁词库各厂商的 SDK 入口包根不得出现在任何类路径上（含传递依赖）
        final ClassLoader classLoader = DecisionNeutralityDependencyTest.class.getClassLoader();
        for (final String entryPackage : BANNED_VENDOR_ENTRY_PACKAGES) {
            assertThat(classLoader.getResource(entryPackage))
                    .as("类路径上不得出现禁词库厂商入口包：%s", entryPackage)
                    .isNull();
        }
    }

    // ======================== 内部件 ========================

    /** 解析一个模块 pom 的全部直接依赖坐标（groupId:artifactId，去重） */
    private static List<String> declaredDependenciesOf(final String pomPath) throws Exception {
        final File pomFile = new File(pomPath);
        assertThat(pomFile).as("被测模块 pom 必须存在（surefire 工作目录 = 模块 basedir）").exists();
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 禁外部实体（安全默认）
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final NodeList dependencies = factory.newDocumentBuilder().parse(pomFile)
                .getDocumentElement().getElementsByTagName("dependency");
        final List<String> coordinates = new ArrayList<>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            final Element dependency = (Element) dependencies.item(i);
            final String groupId = textOf(dependency, "groupId");
            final String artifactId = textOf(dependency, "artifactId");
            if (groupId != null && artifactId != null) {
                coordinates.add(groupId + ":" + artifactId);
            }
        }
        return coordinates;
    }

    private static String textOf(final Element parent, final String tagName) {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    /** 坐标的 groupId / artifactId 切词后不得命中 T1 禁词 */
    private static void assertCoordinatesFreeOfBannedWords(final List<String> coordinates) {
        for (final String coordinate : coordinates) {
            for (final String bannedWord : T1_BANNED_WORDS) {
                assertThat(coordinate.toLowerCase(Locale.ROOT))
                        .as("依赖坐标 %s 不得包含 T1 禁词 %s", coordinate, bannedWord)
                        .doesNotContain(bannedWord);
            }
        }
    }
}
