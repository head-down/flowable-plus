package io.github.flowable.plus.extension.decision;

/**
 * 准入失败异常（ADR-0042 第 8 节定案 4）。
 *
 * <p><b>单一 unchecked 异常</b>，自带机器可读的拒绝原因与 {@code taskId} 上下文；
 * <b>不继承</b> core {@code FlowablePlusException} 一族（extension 自持）。</p>
 *
 * <p>语义是「<b>本次提交没有发生</b>」—— 位点服务当即抛出、<b>自身不落记录</b>；
 * 唯一的物质化责任在拉管线（接住并转成失败事实）。</p>
 *
 * <p>三条被否的替代形态（审计留痕）：多异常子类（无法按原因分派）、
 * 复用 {@code IllegalArgumentException}（分类不出原因）、checked 异常（异步拉管线里会被包成
 * {@code ExecutionException}）。</p>
 */
public class SuggestionAdmissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 拒绝原因（闭集） */
    private final SuggestionAdmissionReason reason;

    /** 锚点上下文（可能为 null —— 锚点缺失本身就是一种准入失败） */
    private final String taskId;

    public SuggestionAdmissionException(SuggestionAdmissionReason reason, String taskId) {
        super("建议提交未通过准入校验：reason=" + reason + ", taskId=" + taskId);
        this.reason = reason;
        this.taskId = taskId;
    }

    public SuggestionAdmissionReason getReason() {
        return reason;
    }

    public String getTaskId() {
        return taskId;
    }
}
