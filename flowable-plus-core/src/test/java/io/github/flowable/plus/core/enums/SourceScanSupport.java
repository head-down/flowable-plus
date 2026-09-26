package io.github.flowable.plus.core.enums;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 受限源码扫描的测试支撑（core 测试树内部使用）。
 *
 * <p>用于「构造来源唯一」一类的源码式守卫：扫本模块主源码树中<b>精确字面量</b>的每个命中位置
 * （文件相对路径 + 命中行原文）。surefire 的工作目录 = 模块 basedir，故 {@code src/main/java} 是稳定相对路径。</p>
 *
 * <p><b>无命中的文件不进结果</b> —— 由流的结构表达（{@code flatMap} 后零元素），不做手写判空。</p>
 *
 * <p><b>防空转（内建）</b>：访问源文件数低于 {@link #MIN_SCANNED_SOURCE_FILES} 时<b>直接失败</b>，
 * 不返回空集 —— 否则路径写错会让守卫静默<b>永绿</b>。下限在扫描器内一次执行，调用方无需重复断言。</p>
 */
final class SourceScanSupport {

    /** 主源码树（相对模块 basedir） */
    private static final Path MAIN_SOURCES = Paths.get("src", "main", "java");

    /** 只在 Java 源文件里找（javadoc 也住这里） */
    private static final String JAVA_SUFFIX = ".java";

    /** Windows 路径分隔符 → 统一成正斜杠，便于断言后缀 */
    private static final String BACKSLASH = "\\";

    /** 防空转下限：一次扫描至少访问这么多源文件，否则视为路径失效 */
    static final int MIN_SCANNED_SOURCE_FILES = 5;

    private SourceScanSupport() {
    }

    /**
     * 扫描主源码树，返回含指定精确字面量的每个命中位置。
     *
     * @param literal 精确字面量（按子串匹配）
     * @return 命中列表（文件相对路径 + 命中行原文）
     * @throws IllegalStateException 主源码树缺失或访问文件数低于防空转下限
     */
    static List<Hit> scanMainSources(final String literal) {
        if (!Files.isDirectory(MAIN_SOURCES)) {
            throw new IllegalStateException("主源码树不存在：" + MAIN_SOURCES.toAbsolutePath());
        }
        final List<Path> files;
        try (Stream<Path> stream = Files.walk(MAIN_SOURCES)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> StringUtils.endsWith(path.getFileName().toString(), JAVA_SUFFIX))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (files.size() < MIN_SCANNED_SOURCE_FILES) {
            throw new IllegalStateException("防空转：仅访问到 " + files.size() + " 个源文件（下限 "
                    + MIN_SCANNED_SOURCE_FILES + "），疑似路径失效：" + MAIN_SOURCES.toAbsolutePath());
        }
        return files.stream()
                .flatMap(file -> hitLinesOf(file, literal).map(line -> new Hit(relativePath(file), line)))
                .collect(Collectors.toList());
    }

    private static String relativePath(final Path file) {
        return StringUtils.replace(MAIN_SOURCES.relativize(file).toString(), BACKSLASH, "/");
    }

    private static Stream<String> hitLinesOf(final Path file, final String literal) {
        try {
            // 一次性整读：源码文本短（单文件 KB 级），且需按行产出命中行原文，流式无收益
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> StringUtils.contains(line, literal));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一个命中位置：文件相对路径 + 命中行原文 */
    static final class Hit {

        private final String path;
        private final String line;

        Hit(final String path, final String line) {
            this.path = path;
            this.line = line;
        }

        /** 文件相对路径（`/` 分隔） */
        String getPath() {
            return path;
        }

        /** 命中行原文 */
        String getLine() {
            return line;
        }
    }
}
