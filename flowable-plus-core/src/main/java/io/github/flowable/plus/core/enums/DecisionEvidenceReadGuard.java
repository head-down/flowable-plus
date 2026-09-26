package io.github.flowable.plus.core.enums;

/**
 * 证据<b>读侧</b>解析护栏常量（ADR-0042 第 5 节）。
 *
 * <p>证据行内容可来自外部（出站响应 / 直提提交），属<b>不可信</b>输入，而基线 Jackson 不含
 * 解析限制设施，故读侧自设两道护栏：大小上限与嵌套深度上限。护栏<b>在解析之前</b>生效
 * （不得先整体解析再判大小）。</p>
 *
 * <p>{@link #MAX_PARSE_BYTES} <b>取等</b>于写入侧上界（{@link DecisionEvidenceWriteGuard#MAX_EVIDENCE_BYTES}）——
 * 写出侧上界一经成立，「写得进、读不出」即机械关闭，且「写出上界」只有一个数字权威。</p>
 *
 * <p>与写入侧护栏<b>成对而不同源</b>：本类两常量与 {@code DecisionEvidenceWriteGuard} 指不同的事实
 * （允许解析 vs 允许写入），更不与出域 clamp 的载荷上限同源。本类为<b>具名常量、非可配置</b>。</p>
 */
public final class DecisionEvidenceReadGuard {

    /** 读侧解析护栏：单条证据行的大小上限（字节） */
    public static final int MAX_PARSE_BYTES = 81_920;

    /** 读侧解析护栏：嵌套深度上限 */
    public static final int MAX_NESTING_DEPTH = 32;

    private DecisionEvidenceReadGuard() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}
