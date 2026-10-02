package io.github.flowable.plus.extension.decision;

/**
 * 位点服务（ADR-0042 第 2 节契约面清单第 1 项）：接收<b>一条建议提交</b>的推契约本体。
 *
 * <p><b>公开面只有一个方法</b>：{@link #submit(SuggestionSubmission)}，返回 {@code void}，只产
 * {@code SUGGESTION_PRODUCED}。{@code NO_SUGGESTION_BY_POLICY} / {@code SUGGESTION_FAILED}
 * 由拉管线经 extension 内部写入器物质化，<b>不进公开 API</b> —— 这守住 ADR-0042 第 2 节定案 3
 * 「不允许无动作、仅证据提交」，并堵死「应用自造 {@code SUGGESTION_FAILED} 行」污染审计面与
 * 错误指标的通路（<b>不是</b>禁止框架自身落行）。</p>
 *
 * <p><b>返回 {@code void} 的理由</b>：ADR-0042 第 8 节定案 3「只承诺可观察、不提供分派」；
 * 「是否重放」是读侧派生事实，不在写入点回显（回显会造出第二真相）。</p>
 *
 * <p><b>独立接口注入、不进 {@code FlowablePlus} 门面</b>（ADR-0010 / ADR-0042 第 2 节定案 5）；
 * 「不引 extension ⇒ 零 Bean、零行为变化」是<b>结构保证</b>（接口与实现全住 extension，
 * 类不存在即写不出来）。</p>
 *
 * <p><b>同步准入失败</b>以 {@link SuggestionAdmissionException} 抛出（首个失败即抛）；
 * <b>全局开关关闭</b>时 {@link #submit(SuggestionSubmission)} 是 <b>no-op 而非失败</b>
 * （不落记录、不抛准入异常 —— 开关是部署期配置状态、经构造缝注入、取构造期定值）。</p>
 */
public interface SuggestionSubmissionService {

    /**
     * 提交一条建议（建议动作 + 显式依据）。
     *
     * <p>准入通过时把本次提交物质化为一条决策证据行；准入失败时<b>先落行（若带齐三项前置）再抛</b>。</p>
     *
     * @param submission 建议提交（调用方自述），不得为 null
     * @throws SuggestionAdmissionException 准入失败（同步、按序、首个失败即抛）
     */
    void submit(SuggestionSubmission submission);
}
