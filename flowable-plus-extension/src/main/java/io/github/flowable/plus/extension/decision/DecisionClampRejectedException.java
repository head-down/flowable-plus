package io.github.flowable.plus.extension.decision;

/**
 * 出域 clamp 的<b>兜底拒绝</b>（ADR-0042 第 6 节）：按 {@code dropPriority} 丢到全空仍超上限时的硬闸信号。
 *
 * <p>承载面 = 包内实现细节；管线接住它并映射为 {@code SUGGESTION_FAILED} / {@code INTERNAL_ERROR}
 * （「拒绝出域 ⇒ 出站调用不发生」，符合 I2）。</p>
 *
 * <p><b>不</b>用于入站方向 —— 入站超限的承载是 {@code rawOutput = null}（不抛、不截断）。</p>
 */
final class DecisionClampRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造兜底拒绝信号。
     *
     * @param message 拒绝原因（含现场：有效上限与丢段后仍超的事实）
     */
    DecisionClampRejectedException(final String message) {
        super(message);
    }
}
