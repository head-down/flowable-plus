package io.github.flowable.plus.core.enums;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        final List<Integer> priorities = new ArrayList<>();
        for (final DecisionContextSource source : DecisionContextSource.values()) {
            assertThat(source.getDropPriority())
                    .as("成员 %s 的 dropPriority 必须非零且为正", source)
                    .isNotZero()
                    .isPositive();
            assertThat(source.getDropPriority() % DROP_PRIORITY_INTERVAL)
                    .as("成员 %s 的 dropPriority 必须满足「留 10 间隔」", source)
                    .isZero();
            priorities.add(source.getDropPriority());
        }

        final Set<Integer> distinct = new HashSet<>(priorities);
        assertThat(distinct)
                .as("四成员的 dropPriority 必须互异")
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
        final List<DecisionContextSource> members = Arrays.asList(DecisionContextSource.values());
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                final String left = normalize(members.get(i).name());
                final String right = normalize(members.get(j).name());
                assertThat(left)
                        .as("%s 与 %s 归一后不得同名", members.get(i), members.get(j))
                        .isNotEqualTo(right);
                assertThat(isInflectionPair(left, right))
                        .as("%s 与 %s 不得构成单复数形近对", members.get(i), members.get(j))
                        .isFalse();
            }
        }
    }

    private static String normalize(final String name) {
        return name.replace("_", "").replace("-", "").toLowerCase();
    }

    /** 词形屈折三种：+s、+es、y→ies */
    private static boolean isInflectionPair(final String left, final String right) {
        return (left + "s").equals(right)
                || (right + "s").equals(left)
                || (left + "es").equals(right)
                || (right + "es").equals(left)
                || (left.endsWith("y") && (left.substring(0, left.length() - 1) + "ies").equals(right))
                || (right.endsWith("y") && (right.substring(0, right.length() - 1) + "ies").equals(left));
    }
}
