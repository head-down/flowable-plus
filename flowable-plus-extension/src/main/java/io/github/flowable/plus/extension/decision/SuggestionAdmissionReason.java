package io.github.flowable.plus.extension.decision;

/**
 * 建议提交的准入失败原因（ADR-0042 第 8 节 / 第 9 节第 9 条）。
 *
 * <p><b>闭集十三值</b>。准入校验<b>同步、按序、首个失败即抛</b>，故本枚举不承载「全部失败项」；
 * 抛出侧与异常载体的形态随位点服务落地。</p>
 *
 * <p><b>进错枚举的两类不在此列</b>：① 「锚点失效 / 实例已结束」是<b>写入期</b>失败（提交已发生、
 * 写入期落不下），走 {@link WriteDegradedCause}、<b>不占</b>本枚举；② 「全局关」不是失败
 * （推面提交为 no-op），本枚举<b>没有</b>这一项、也不得新造。</p>
 *
 * <p><b>唯一不物质化</b>：{@link #IDEMPOTENCY_KEY_REQUIRED} 是唯一降级为日志 + 指标、
 * <b>不</b>物质化为失败记录的准入失败（理由：无身份即无从判别，落记录会把同一第三方的反复重试
 * 变成无法归并的审计噪声）。</p>
 *
 * <p><b>本枚举的可见面</b>：本枚举<b>不落证据</b>、也<b>不并入</b>「失败类别 × 严重度」两轴
 * （不新增轴）；它的可见面 = 位点准入拒绝的独立计数信号上的 {@code reason} tag value
 * （取枚举常量名的小写蛇形，见 {@link DecisionMetrics#tagValue}）+ 异常载荷 + 观测字段。</p>
 *
 * <p>本机制首次落标识符的票为「工程基座与观测一族」：观测事实的 {@code admissionReason} 字段
 * 以本枚举为类型，故值域先行落地。</p>
 */
public enum SuggestionAdmissionReason {

    /** 锚点缺失：{@code taskId} 为空 */
    TASK_ID_REQUIRED,

    /** 幂等身份缺失（唯一不物质化的准入失败） */
    IDEMPOTENCY_KEY_REQUIRED,

    /** 建议动作缺失 */
    ACTION_REQUIRED,

    /** 建议摘要缺失（拒空摘要） */
    ACTION_SUMMARY_REQUIRED,

    /** 建议动作不属表态比较面 */
    ACTION_NOT_COMPARABLE,

    /** 建议动作与当前任务可用投票动作不匹配 */
    ACTION_NOT_AVAILABLE_FOR_TASK,

    /** 主体类型缺失 */
    SUBJECT_TYPE_REQUIRED,

    /** 出处组三字段未同 null 或同非 null */
    PROVENANCE_GROUP_INCONSISTENT,

    /** 直提下 {@code modelId} 必须为空 */
    MODEL_ID_NOT_ALLOWED_ON_DIRECT,

    /** 直提下 {@code inputSnapshot} 必须为空 */
    INPUT_SNAPSHOT_NOT_ALLOWED_ON_DIRECT,

    /** 直提下 {@code rationaleFacts} 须至少含一条 {@code BASIS_CODE} */
    BASIS_CODE_REQUIRED,

    /** {@code rationaleFacts} 元素非法 */
    RATIONALE_FACT_INVALID,

    /** {@code attestedDataSources} 三态或元素非法（禁 null 元素 / 禁重复元素） */
    ATTESTED_SOURCES_INVALID
}
