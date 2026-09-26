package io.github.flowable.plus.core.enums;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 受限源码扫描的测试支撑（core 测试树内部使用）。
 *
 * <p>用于「构造来源唯一」一类的源码式守卫：扫本模块主源码树中<b>精确字面量</b>的命中位置与命中行原文。
 * surefire 的工作目录 = 模块 basedir，故 {@code src/main/java} 是稳定相对路径。</p>
 *
 * <p><b>防空转</b>：调用方必须同时断言 {@link ScanResult#getVisitedFiles()} 不低于
 * {@link #MIN_SCANNED_SOURCE_FILES} —— 否则路径写错时扫描会静默返回空集、守卫<b>永绿</b>。</p>
 */
final class SourceScanSupport {

    /** 主源码树（相对模块 basedir） */
    private static final Path MAIN_SOURCES = Paths.get("src", "main", "java");

    /** 防空转下限：一次扫描至少访问这么多源文件，否则视为路径失效 */
    static final int MIN_SCANNED_SOURCE_FILES = 5;

    private SourceScanSupport() {
    }

    /**
     * 扫描主源码树，找出包含指定精确字面量的文件与命中行原文。
     *
     * @param literal 精确字面量（按子串匹配）
     * @return 扫描结果
     */
    static ScanResult scanMainSources(final String literal) {
        if (!Files.isDirectory(MAIN_SOURCES)) {
            throw new IllegalStateException("主源码树不存在：" + MAIN_SOURCES.toAbsolutePath());
        }
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(MAIN_SOURCES)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        final Map<String, List<String>> hits = new LinkedHashMap<>();
        for (final Path file : files) {
            final List<String> hitLines = scanFile(file, literal);
            if (!hitLines.isEmpty()) {
                hits.put(MAIN_SOURCES.relativize(file).toString().replace('\\', '/'), hitLines);
            }
        }
        return new ScanResult(files.size(), hits);
    }

    private static List<String> scanFile(final Path file, final String literal) {
        final List<String> hitLines = new ArrayList<>();
        try {
            // 一次性整读：源码文本短（单文件 KB 级），且需按行产出命中行原文，流式无收益
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (final String line : lines) {
                if (line.contains(literal)) {
                    hitLines.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hitLines;
    }

    /** 一次受限源码扫描的结果 */
    static final class ScanResult {

        private final int visitedFiles;
        private final Map<String, List<String>> hits;

        ScanResult(final int visitedFiles, final Map<String, List<String>> hits) {
            this.visitedFiles = visitedFiles;
            this.hits = hits;
        }

        /** 实际访问的源文件数（防空转下限用） */
        int getVisitedFiles() {
            return visitedFiles;
        }

        /** 命中文件数 */
        int getHitFileCount() {
            return hits.size();
        }

        /** 命中总位置数（跨文件求和） */
        int getHitCount() {
            return hits.values().stream().mapToInt(List::size).sum();
        }

        /** 唯一命中文件的相对路径；命中文件数不为 1 时抛出 */
        String getSoleHitFile() {
            if (hits.size() != 1) {
                throw new AssertionError("期望恰好 1 个命中文件，实际 " + hits.size() + " 个：" + hits.keySet());
            }
            return hits.keySet().iterator().next();
        }

        /** 全树唯一命中行的原文；命中位置数不为 1 时抛出 */
        String getSoleHitLine() {
            final List<String> all = new ArrayList<>();
            hits.values().forEach(all::addAll);
            if (all.size() != 1) {
                throw new AssertionError("期望恰好 1 个命中位置，实际 " + all.size() + " 个");
            }
            return all.get(0);
        }
    }
}
