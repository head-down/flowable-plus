package io.github.flowable.plus.extension.decision;

/**
 * 位点服务（ADR-0042 第 2 节定案 5 / 第 8 节定案 4）：推契约本体。
 *
 * <p><b>公开面只有一个方法</b>：{@link #submit(SuggestionSubmission)}，返回 {@code void}。
 * 只产 {@code SUGGESTION_PRODUCED}；{@code NO_SUGGESTION_BY_POLICY} / {@code SUGGESTION_FAILED}
 * 由<b>拉管线</b>经 extension 内部写入器物质化，<b>不进公开 API</b> —— 堵死「应用自造
 * {@code SUGGESTION_FAILED} 行」污染审计面与错误指标的通路。</p>
 *
 * <p>返回 {@code void} 的理由：ADR-0042 第 8 节定案 3「只承诺可观察、不提供分派」；
 * 「是否重放」是<b>读侧派生</b>事实，不在写入点回显（回显会造出第二真相）。</p>
 *
 * <p><b>独立接口注入、不进 {@code FlowablePlus} 门面</b>（ADR-0010）。</p>
 *
 * <p><b>准入失败</b> = 同步抛 {@link SuggestionAdmissionException} + 位点服务自身不落记录；
 * <b>缺幂等身份</b>是唯一不物质化为 {@code SUGGESTION_FAILED} 的例外。
 * 任务不存在 / 实例已结束 ⇒ 走既有「锚点失效」槽位（日志 + 指标、不成行、不产生
 * {@code SUGGESTION_FAILED}），<b>不占</b>原因枚举。</p>
 *
 * <p><b>全局关时的行为</b>：Bean <b>常驻</b>（应用仍可注入），{@code submit(...)} 直接返回 ——
 * <b>不落记录、不抛异常</b>（「全局关」不是失败，原因枚举里没有这一项）。</p>
 *
 * <p><b>骨架说明</b>：本接口的实现类名在任何决议中<b>均未登记</b>，故本骨架不落实现类
 * —— 见 #43 决议的发现②。</p>
 */
public interface SuggestionSubmissionService {

    /**
     * 提交一条建议（建议动作 + 决策证据，一体提交）。
     *
     * <p>只留痕，<b>不推进流程</b>；该提交以只读决策证据组形态进入审批轨迹（独立字段，
     * 不占人工意见槽位）。</p>
     *
     * @param submission 调用方自述的写入契约，不可为 null
     * @throws SuggestionAdmissionException 准入校验未通过（同步、按序、首个失败即抛）
     */
    void submit(SuggestionSubmission submission);
}
