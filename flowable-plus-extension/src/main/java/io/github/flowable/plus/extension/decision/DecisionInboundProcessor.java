package io.github.flowable.plus.extension.decision;

import java.util.Objects;

/**
 * 入站加工（ADR-0042 第 6 节 / 拉管线决议第十一节）：拉面入站与<b>直提</b>共用的兜底路径。
 *
 * <p><b>只共用 clamp，不共用策略</b>（结构保证）：本组件<b>只持 {@link DecisionClamp}</b>、
 * <b>不持</b> {@link DecisionPolicy} —— 策略 key 属节点声明，而推面与声明解耦 ⇒ 框架无从取得，
 * 故直提的入站加工<b>不经出域策略</b>；策略的入站方法（{@code applyInbound}）由拉管线在调用本组件
 * <b>之前</b>自行施加。</p>
 *
 * <p><b>入站载荷的内容卫生 = 提交方自律</b>（与「数据源声明退化为自律」同一条逻辑：框架无从核查 ⇒
 * 退化为自律），故本组件只施加与出域同一上限的<b>硬上限 clamp</b>：超限 ⇒ 返回 {@code null}
 * （映 {@code rawOutput = null} + {@code NO_PAYLOAD} + {@code INBOUND_PROCESSING_FAILED}）。</p>
 *
 * <p><b>实现细节，不入机制术语表</b>（命名宪章 §4.5）。</p>
 */
final class DecisionInboundProcessor {

    /** 共用的硬上限 clamp（与出域同源，不共用策略） */
    private final DecisionClamp clamp;

    /**
     * 构造入站加工组件。
     *
     * @param clamp 硬上限 clamp，不得为 null
     */
    DecisionInboundProcessor(final DecisionClamp clamp) {
        this.clamp = Objects.requireNonNull(clamp, "入站加工必须持有共用的 clamp");
    }

    /**
     * 加工入站裸串（只施加硬上限 clamp；内容卫生归提交方自律）。
     *
     * @param rawOutput 入站裸串，可空
     * @return 未超限时原样返回；超限返回 {@code null}；入参为 null 时返回 null
     */
    String process(final String rawOutput) {
        return clamp.clampInbound(rawOutput);
    }
}
