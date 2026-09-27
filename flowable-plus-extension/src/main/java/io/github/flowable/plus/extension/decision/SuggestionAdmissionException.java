package io.github.flowable.plus.extension.decision;

/**
 * 建议提交的<b>准入失败</b>（ADR-0042 第 8 节第 4 条）：位点服务同步、按序、<b>首个失败即抛</b>的
 * 单一 unchecked 异常，自带闭集原因与锚点上下文。
 *
 * <p><b>为何是单一异常 + 闭集原因</b>（而非多异常子类 / 复用 {@code IllegalArgumentException} /
 * checked 异常）：① 多异常子类<b>无法按原因分派</b> —— 准入失败并非一律不落行（三项前置带齐者落行），
 * 调用方（拉管线）必须能读原因；② 复用 {@code IllegalArgumentException} <b>分类不出原因</b>；
 * ③ checked 异常在异步拉管线里会被包成 {@code ExecutionException}、把分类信息埋掉。</p>
 *
 * <p><b>不继承</b> core {@code FlowablePlusException} 一族（extension 自持）：本机制住 extension、
 * 与 core 既有异常族分属两个硬域（与「决策失败不进流程状态」同向）。</p>
 *
 * <p><b>落行与抛出是两件事</b>：带齐三项前置（{@code taskId} ∧ {@code idempotencyKey} ∧
 * {@code subjectType} 皆非空）的准入失败，由位点服务<b>在抛出之前</b>物质化为
 * {@code SUGGESTION_FAILED} / {@code SITE_ADMISSION_REJECTED} 的失败行；缺任一前置者只退
 * 日志 + 指标、不落行（判据是<b>状态驱动</b>，不由原因枚举反推）。故本异常的 {@link #getReason()}
 * <b>不等于</b>「是否落行」的开关。</p>
 */
public class SuggestionAdmissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 准入失败原因（闭集十三值；本次提交的<b>首个</b>失败项） */
    private final SuggestionAdmissionReason reason;

    /** 锚点任务标识（可空 —— 「缺锚点」正是原因之一） */
    private final String taskId;

    /**
     * 构造准入失败异常。
     *
     * @param reason 准入失败原因（闭集十三值），不得为 null
     * @param taskId 锚点任务标识，可空（原因恰为缺锚点时为空）
     */
    public SuggestionAdmissionException(final SuggestionAdmissionReason reason, final String taskId) {
        super("建议提交被位点服务拒绝：" + reason + "（taskId=" + taskId + "）");
        this.reason = reason;
        this.taskId = taskId;
    }

    /**
     * 准入失败原因。
     *
     * @return 原因（闭集十三值）
     */
    public SuggestionAdmissionReason getReason() {
        return reason;
    }

    /**
     * 锚点任务标识。
     *
     * @return 锚点；缺锚点时为空
     */
    public String getTaskId() {
        return taskId;
    }
}
