package io.github.flowable.plus.extension.decision;

import org.apache.commons.lang3.StringUtils;

/**
 * 出域策略 SPI（ADR-0042 第 6 节）：<b>单一扩展点、双向两方法</b>，做<b>内容级</b>加工
 * （内容选择 + 变形（脱敏 / 摘要）+ 策略级截断），与装配器的<b>数据源级</b>结构性最小化互补。
 *
 * <p><b>不带方向词</b>（词条明文「双向服务」）：同一策略既加工出域请求，也加工入站响应落盘前的形态；
 * 方向的差异住在<b>方法名</b>（{@link #applyOutbound(DecisionPayload)} / {@link #applyInbound(String)}）
 * 与两侧结果类型，不另起一套方向词类型。</p>
 *
 * <p><b>契约要求</b>（框架不核查、由应用自律）：</p>
 *
 * <ul>
 *   <li><b>确定性 / 纯</b>：同一装配载荷 ⇒ 同一出域载荷 + 同一加工记录；</li>
 *   <li><b>不得自身发起出域调用</b>（本地纯计算）；</li>
 *   <li><b>线程安全</b>（可被多个决策线程并发调用）；</li>
 *   <li><b>不给策略传节点身份</b> —— {@code decisionPolicy} 这个 key 本身就是建模期的声明，要按节点
 *       分流就注册不同策略（纯函数不污染路由逻辑）。</li>
 * </ul>
 *
 * <p><b>引用的三档防御</b>：部署期主闸校验 key 存在 → 启动期复核 → 运行期最后防御阻断；
 * 均不在本接口内。</p>
 */
public interface DecisionPolicy {

    /**
     * 策略的唯一 key（<b>与决策目标同型</b>：bean + 唯一 {@code key()} + 节点按 key 引用 + 部署期校验）。
     *
     * <p>节点声明以单值 {@code decisionPolicy} 引用它（<b>严格单策略引用</b>）；注册表按 key 收集，
     * <b>重复 key ⇒ 启动期 fail-fast</b>。</p>
     *
     * @return 唯一 key
     */
    String key();

    /**
     * 加工出域载荷（策略之后由框架施加硬上限 clamp，应用不可放大）。
     *
     * <p>「说不」请走 {@link DecisionOutboundResult#isPermitted()} 的显式结果位 —— <b>合规拒绝不计错误</b>；
     * 抛异常表示系统故障（<b>计错误指标</b>），二者严禁混用。</p>
     *
     * @param payload 已装配的决策载荷（四段定型外壳），不得为 null
     * @return 出域结果（放行 / 拒绝 + 内容位 + 加工记录）
     */
    DecisionOutboundResult applyOutbound(DecisionPayload payload);

    /**
     * 加工入站响应落盘前的形态（<b>可选</b>；缺省实现 = <b>透传</b>）。
     *
     * <p>缺省语义：入参有内容 ⇒ 可落盘且原样返回；无内容（null / 空串）⇒ 不可落盘（避免
     * {@code persistable = true} 与「无内容」自相矛盾的非法态）。</p>
     *
     * @param rawOutput 已冻结的原始输出（裸串），可空
     * @return 入站结果（可落盘 / 不可落盘 + 内容位 + 加工记录）
     */
    default DecisionInboundResult applyInbound(final String rawOutput) {
        return new DecisionInboundResult(StringUtils.isNotEmpty(rawOutput), rawOutput,
                DecisionProcessingRecord.none());
    }
}
