package io.github.flowable.plus.core.enums;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DecisionContextSource} 的成员集与 {@code dropPriority} 契约（ADR-0042 第 6 节）。
 *
 * <p>{@code dropPriority} 数值越大越先丢；四成员互异、非零、留 10 间隔；顺序是<b>显式契约</b>，
 * 不依赖物理声明序。</p>
 */
public class DecisionContextSourceTest {

    /** 成员个数（穷举用） */
    private static final int MEMBER_COUNT = 4;

    /** 丢弃序的间隔纪律 */
    private static final int DROP_PRIORITY_INTERVAL = 10;

    private static final int PROCESS_VARIABLES_DROP_PRIORITY = 40;
    private static final int TASK_VARIABLES_DROP_PRIORITY = 30;
    private static final int TASK_METADATA_DROP_PRIORITY = 20;
    private static final int PROCESS_INSTANCE_METADATA_DROP_PRIORITY = 10;

    /** 归一化时要剥掉的分隔符 */
    private static final String UNDERSCORE = "_";
    private static final String HYPHEN = "-";

    /** 词形屈折的可选后缀 */
    private static final String PLURAL_SUFFIX = "s";
    private static final String PLURAL_SUFFIX_ES = "es";
    private static final String PLURAL_SUFFIX_IES = "ies";
    private static final String Y_SUFFIX = "y";

    @Test
    void membersAreExactlyFour() {
        assertThat(DecisionContextSource.values())
                .hasSize(MEMBER_COUNT)
                .containsExactly(
                        DecisionContextSource.PROCESS_VARIABLES,
                        DecisionContextSource.TASK_VARIABLES,
                        DecisionContextSource.TASK_METADATA,
                        DecisionContextSource.PROCESS_INSTANCE_METADATA);
    }

    @Test
    void dropPrioritiesAreDistinctAndNonZero() {
        final List<Integer> priorities = Arrays.stream(DecisionContextSource.values())
                .map(DecisionContextSource::getDropPriority)
                .collect(Collectors.toList());

        assertThat(priorities)
                .as("每个成员的 dropPriority 必须非零且为正")
                .allMatch(priority -> priority != 0 && priority > 0);
        assertThat(priorities)
                .as("每个成员的 dropPriority 必须满足「留 10 间隔」")
                .allMatch(priority -> priority % DROP_PRIORITY_INTERVAL == 0);
        assertThat(priorities)
                .as("四成员的 dropPriority 必须互异")
                .doesNotHaveDuplicates()
                .hasSize(MEMBER_COUNT);

        assertThat(DecisionContextSource.PROCESS_VARIABLES.getDropPriority())
                .isEqualTo(PROCESS_VARIABLES_DROP_PRIORITY);
        assertThat(DecisionContextSource.TASK_VARIABLES.getDropPriority())
                .isEqualTo(TASK_VARIABLES_DROP_PRIORITY);
        assertThat(DecisionContextSource.TASK_METADATA.getDropPriority())
                .isEqualTo(TASK_METADATA_DROP_PRIORITY);
        assertThat(DecisionContextSource.PROCESS_INSTANCE_METADATA.getDropPriority())
                .isEqualTo(PROCESS_INSTANCE_METADATA_DROP_PRIORITY);
    }

    @Test
    void membersHaveNoInflectionPairs() {
        final List<String> normalizedNames = Arrays.stream(DecisionContextSource.values())
                .map(source -> normalize(source.name()))
                .collect(Collectors.toList());

        assertThat(normalizedNames)
                .as("四成员归一后不得同名")
                .doesNotHaveDuplicates();
        assertThat(normalizedNames)
                .as("四成员归一后不得构成单复数形近对")
                .noneMatch(first -> normalizedNames.stream().anyMatch(second -> isInflectionPair(first, second)));
    }

    /** 归一：去分隔符（`_` / `-`）并统一小写 */
    private static String normalize(final String name) {
        return StringUtils.lowerCase(StringUtils.remove(StringUtils.remove(name, UNDERSCORE), HYPHEN));
    }

    /** 词形屈折三种：`+s`、`+es`、`y→ies`（自反比较恒不成立，故两两自比不影响结果） */
    private static boolean isInflectionPair(final String left, final String right) {
        final String pluralOfLeft = left + PLURAL_SUFFIX;
        final String pluralOfRight = right + PLURAL_SUFFIX;
        return StringUtils.equals(pluralOfLeft, right)
                || StringUtils.equals(pluralOfRight, left)
                || StringUtils.equals(left + PLURAL_SUFFIX_ES, right)
                || StringUtils.equals(right + PLURAL_SUFFIX_ES, left)
                || (StringUtils.endsWith(left, Y_SUFFIX)
                        && StringUtils.equals(StringUtils.chop(left) + PLURAL_SUFFIX_IES, right))
                || (StringUtils.endsWith(right, Y_SUFFIX)
                        && StringUtils.equals(StringUtils.chop(right) + PLURAL_SUFFIX_IES, left));
    }
}
