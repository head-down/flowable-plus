package io.github.flowable.plus.core.enums;

/**
 * 证据<b>写入侧</b>护栏常量（ADR-0042 第 5 / 6 节）。
 *
 * <p>写入侧上界 = <b>单条证据行的最大允许尺寸</b>（标记 + JSON 整行）。写入前校验，超限<b>拒绝写入</b>
 * ⇒ 归 {@code SUGGESTION_FAILED} + {@code failureKind = INTERNAL_ERROR}（零新增枚举值、零新增槽位）。</p>
 *
 * <p>取值<b>派生自</b>出域硬上限的两方向之和（2 × 32 KiB）＋ 固定结构开销余量（16 KiB：标记与
 * {@code rationaleNarrative} / {@code rationaleFacts} / {@code actionSummary} 一类固定字段），
 * 即 80 KiB —— 单一真相源仍是最初的 32 KiB，本类不引入第二个独立数字。</p>
 *
 * <p>与读侧护栏（{@link DecisionEvidenceReadGuard}）<b>成对而不同源</b>：二者各指不同的事实
 * （允许写入 vs 允许解析），不共用承载位。本类为<b>具名常量、非可配置</b> —— 三个阈值都不进配置面。</p>
 */
public final class DecisionEvidenceWriteGuard {

    /** 单条证据行的最大允许尺寸（字节） */
    public static final int MAX_EVIDENCE_BYTES = 81_920;

    private DecisionEvidenceWriteGuard() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}
