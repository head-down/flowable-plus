package io.github.flowable.plus.core.enums;

import io.github.flowable.plus.core.support.DefaultActionInferenceStrategy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommentType} 新取值的读侧穷举守卫（ADR-0042 第 5 节读侧硬清单第 1–2 行）。
 *
 * <p><b>靶心</b> = {@link CommentTypeConverter#toApprovalAction(CommentType)} 的 {@code default} 分支：
 * 该 {@code switch} 带 {@code default}，新增取值时<b>编译通过、运行时抛异常</b>。故本类必须同时钉住
 * 「取值有显式 {@code case}」（源码式）与「每个取值要么可映射、要么在已知不可映射集内」（行为式）。</p>
 *
 * <p><b>三组的形态</b>：两显式（操作注释组 {@code OPERATION_COMMENT_TYPES}、证据组
 * {@link DecisionEvidenceComment#EVIDENCE_COMMENT_TYPES}）＋ 业务意见组<b>隐式补集</b>
 * （既有负债，本机制不为它新增常量）。</p>
 */
public class CommentTypeExhaustivenessTest {

    /** 已知不可映射为 {@code ApprovalAction} 的取值（唯一合法落 {@code default} 者） */
    private static final Set<CommentType> KNOWN_UNMAPPED = Collections.unmodifiableSet(EnumSet.of(
            CommentType.DELEGATE,
            CommentType.RESOLVE_DELEGATE,
            CommentType.DECISION_EVIDENCE));

    /** 证据组的构造来源（源码式单一来源扫描的靶字面量） */
    private static final String EVIDENCE_GROUP_CONSTRUCTION = "EnumSet.of(COMMENT_TYPE)";

    @Test
    void everyCommentTypeIsMappedOrInKnownUnmappedSet() {
        final Set<CommentType> mapped = new LinkedHashSet<>();
        final Set<CommentType> unmapped = new LinkedHashSet<>();

        for (final CommentType commentType : CommentType.values()) {
            try {
                assertThat(CommentTypeConverter.toApprovalAction(commentType))
                        .as("映射结果不得为 null：%s", commentType)
                        .isNotNull();
                mapped.add(commentType);
            } catch (IllegalArgumentException expected) {
                assertThat(KNOWN_UNMAPPED)
                        .as("取值 %s 落 default 抛出，但它不在已知不可映射集内", commentType)
                        .contains(commentType);
                unmapped.add(commentType);
            }
        }

        final Set<CommentType> covered = new LinkedHashSet<>(mapped);
        covered.addAll(unmapped);
        assertThat(covered)
                .as("可映射集 ∪ 已知不可映射集必须穷举全部取值")
                .containsExactlyInAnyOrder(CommentType.values());
        assertThat(KNOWN_UNMAPPED)
                .as("已知不可映射集必须全部可达（不得留下过时条目）")
                .containsExactlyInAnyOrderElementsOf(unmapped);
    }

    @Test
    void decisionEvidenceHasExplicitCase() {
        final SourceScanSupport.ScanResult result =
                SourceScanSupport.scanMainSources("case DECISION_EVIDENCE:");

        assertThat(result.getVisitedFiles())
                .as("防空转：必须真的扫到源文件")
                .isGreaterThanOrEqualTo(SourceScanSupport.MIN_SCANNED_SOURCE_FILES);
        assertThat(result.getHitFileCount())
                .as("CommentTypeConverter 必须为 DECISION_EVIDENCE 配显式 case（否则落入 default）")
                .isEqualTo(1);
        assertThat(result.getSoleHitFile()).endsWith("CommentTypeConverter.java");
    }

    @Test
    void evidenceGroupIsExplicitEnumSetSingleSourced() {
        final Field group = declaredField(DecisionEvidenceComment.class, "EVIDENCE_COMMENT_TYPES");
        assertThat(group.getType()).isEqualTo(Set.class);
        assertThat(Modifier.isStatic(group.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(group.getModifiers())).isTrue();

        assertThat(DecisionEvidenceComment.EVIDENCE_COMMENT_TYPES)
                .as("证据组恰含唯一成员 DECISION_EVIDENCE")
                .containsExactly(CommentType.DECISION_EVIDENCE);
        assertThatThrownBy(() -> DecisionEvidenceComment.EVIDENCE_COMMENT_TYPES.add(CommentType.AGREE))
                .as("证据组必须不可变")
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(DecisionEvidenceComment.COMMENT_TYPE)
                .as("组与其成员共用一个真值来源")
                .isSameAs(CommentType.DECISION_EVIDENCE);

        final SourceScanSupport.ScanResult enumSetConstruction =
                SourceScanSupport.scanMainSources(EVIDENCE_GROUP_CONSTRUCTION);
        assertThat(enumSetConstruction.getVisitedFiles())
                .isGreaterThanOrEqualTo(SourceScanSupport.MIN_SCANNED_SOURCE_FILES);
        assertThat(enumSetConstruction.getHitFileCount())
                .as("证据组是显式 EnumSet，且构造来源唯一")
                .isEqualTo(1);
        assertThat(enumSetConstruction.getSoleHitFile()).endsWith("DecisionEvidenceComment.java");
    }

    @Test
    void businessGroupStaysImplicitComplement() {
        final Set<CommentType> operationGroup = operationCommentTypes();

        final Set<CommentType> business = EnumSet.noneOf(CommentType.class);
        for (final CommentType commentType : CommentType.values()) {
            if (!operationGroup.contains(commentType)
                    && !DecisionEvidenceComment.EVIDENCE_COMMENT_TYPES.contains(commentType)) {
                business.add(commentType);
            }
        }

        assertThat(business)
                .as("业务意见组必须非空（隐式补集不得被挤空）")
                .isNotEmpty();
        assertThat(business)
                .as("业务意见组与两个显式组互斥")
                .doesNotContainAnyElementsOf(operationGroup)
                .doesNotContainAnyElementsOf(DecisionEvidenceComment.EVIDENCE_COMMENT_TYPES);
        assertThat(business)
                .as("业务意见组含既有意见类取值")
                .contains(CommentType.AGREE, CommentType.REJECT, CommentType.RETURN);

        for (final Field field : Arrays.asList(CommentType.class.getDeclaredFields())) {
            assertThat(Set.class.isAssignableFrom(field.getType()))
                    .as("CommentType 不得为业务意见组新增显式常量（保持隐式补集）：%s", field.getName())
                    .isFalse();
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<CommentType> operationCommentTypes() {
        final Field field = declaredField(DefaultActionInferenceStrategy.class, "OPERATION_COMMENT_TYPES");
        try {
            field.setAccessible(true);
            return (Set<CommentType>) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("读取操作注释组失败", e);
        }
    }

    private static Field declaredField(final Class<?> owner, final String name) {
        try {
            return owner.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("缺少字段：" + owner.getName() + "#" + name, e);
        }
    }
}
