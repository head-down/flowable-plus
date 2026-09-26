package io.github.flowable.plus.core.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据<b>写入侧</b>护栏的上界约束（ADR-0042 第 5 / 6 节）。
 *
 * <p>{@link DecisionEvidenceWriteGuard#MAX_EVIDENCE_BYTES} 是单条证据行的最大允许尺寸，写入前校验、
 * 超限拒绝写入。其取值派生自出域硬上限的两方向之和 —— 该下界约束的数值不等式<b>跨模块</b>
 * （需要 extension 侧的出域 clamp 常量），故住 extension 的 clamp 守卫；
 * 本类只钉 core 侧可达的部分：常量具名、为正。</p>
 */
public class DecisionEvidenceWriteGuardTest {

    @Test
    void writeGuardConstantIsNamedAndPositive() throws Exception {
        ConstantFieldAssertions.assertPublicStaticFinalNumericConstant(
                DecisionEvidenceWriteGuard.class.getDeclaredField("MAX_EVIDENCE_BYTES"),
                DecisionEvidenceWriteGuard.class);

        assertThat(DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES)
                .as("单条证据行的最大允许尺寸必须为正")
                .isPositive();
    }
}
