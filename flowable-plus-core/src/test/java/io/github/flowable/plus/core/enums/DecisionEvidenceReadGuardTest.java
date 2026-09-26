package io.github.flowable.plus.core.enums;

import io.github.flowable.plus.core.vo.DecisionEvidenceTestFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据<b>读侧</b>解析护栏的约束（ADR-0042 第 5 节）。
 *
 * <p>core 侧可达的三条：两常量成对且为正；{@code MAX_PARSE_BYTES} 覆盖写入侧上界（「写得进、读不出」机械关闭）；
 * 嵌套深度上限覆盖最深合法 fixture 的<b>实测</b>深度。</p>
 *
 * <p><b>落点边界（如实登记）</b>：另有三条契约不住本类 —— 「读侧常量与出域 clamp 不同源」与
 * 「写入上界 ≥ 两方向 clamp 上限之和」都要读 extension 侧的出域 clamp 常量，而 core 不得依赖 extension，
 * 故住 extension 的 clamp 守卫；「护栏在解析之前生效」扫的是读侧解析实现，随之落地。
 * 本类只用 core 可名的类型与 Java 常量 fixture（不引金样本文件）。</p>
 */
public class DecisionEvidenceReadGuardTest {

    /** 防空转下限：fixture 与量法都必须真的产生嵌套，否则阈值断言恒真 */
    private static final int MIN_EXPECTED_FIXTURE_DEPTH = 3;

    @Test
    void readGuardConstantsArePairedAndPositive() throws Exception {
        final Field parseBytes = DecisionEvidenceReadGuard.class.getDeclaredField("MAX_PARSE_BYTES");
        final Field nestingDepth = DecisionEvidenceReadGuard.class.getDeclaredField("MAX_NESTING_DEPTH");

        for (final Field field : Arrays.asList(parseBytes, nestingDepth)) {
            assertThat(Modifier.isPublic(field.getModifiers()))
                    .as("%s 必须是公开具名常量", field.getName())
                    .isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(field.getType()).isIn(int.class, long.class);
            assertThat(field.getDeclaringClass()).isEqualTo(DecisionEvidenceReadGuard.class);
        }

        assertThat(DecisionEvidenceReadGuard.MAX_PARSE_BYTES)
                .as("解析大小上限必须为正")
                .isPositive();
        assertThat(DecisionEvidenceReadGuard.MAX_NESTING_DEPTH)
                .as("解析深度上限必须为正")
                .isPositive();
    }

    @Test
    void parseBytesCoverTheWriteGuardUpperBound() {
        assertThat(DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES)
                .as("写入侧上界必须为正，否则本不等式无意义")
                .isPositive();
        assertThat(DecisionEvidenceReadGuard.MAX_PARSE_BYTES)
                .as("写出侧上界一经成立，读侧解析上限必须不低于它（机械关闭「写得进、读不出」）")
                .isGreaterThanOrEqualTo(DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES);
    }

    @Test
    void nestingDepthCoversDeepestFixture() throws Exception {
        final String evidenceJson = DecisionEvidenceTestFixtures.toJson(
                DecisionEvidenceTestFixtures.maximalDirectSubmission());
        final String payloadJson = DecisionEvidenceTestFixtures.toJson(
                DecisionEvidenceTestFixtures.deepestLegalPayload());

        final int evidenceDepth = DecisionEvidenceTestFixtures.maxNestingDepth(evidenceJson);
        final int payloadDepth = DecisionEvidenceTestFixtures.maxNestingDepth(payloadJson);
        final int measuredDepth = Math.max(evidenceDepth, payloadDepth);

        assertThat(evidenceDepth)
                .as("防空转：证据行 fixture 必须真有嵌套，量法必须真的量到深度")
                .isGreaterThanOrEqualTo(MIN_EXPECTED_FIXTURE_DEPTH);
        assertThat(payloadDepth)
                .as("防空转：载荷 fixture 必须含四段定壳与变量命名空间")
                .isGreaterThanOrEqualTo(MIN_EXPECTED_FIXTURE_DEPTH);
        assertThat(DecisionEvidenceReadGuard.MAX_NESTING_DEPTH)
                .as("深度上限必须覆盖最深合法 fixture 的实测深度（证据行 %d / 载荷 %d）",
                        evidenceDepth, payloadDepth)
                .isGreaterThanOrEqualTo(measuredDepth);
    }
}
