package io.github.flowable.plus.extension.decision;

import lombok.Getter;

import java.util.Objects;

/**
 * 出域策略的<b>显式结果位</b>（ADR-0042 第 6 节）：{@code permitted} / {@code payload} / {@code record}。
 *
 * <p><b>「说不」住显式结果位，不住 {@code null}</b>：{@code permitted = false} 表示策略按设计拦下
 * （合规拒绝，映 {@code POLICY_REJECTED}，<b>不计错误指标</b>）；策略<b>抛异常</b>表示系统故障
 * （映 {@code INTERNAL_ERROR}，<b>计错误指标</b>）。二者<b>严禁混用</b>。</p>
 *
 * <p><b>结果位互锁</b>（构造期强制）：{@code permitted = true ⇒ payload 必非空} —— 放行却不给内容，
 * 等于凭空造出「无载荷的出域」，撞 I2；故构造期直接抛 {@link IllegalArgumentException}，不给非法态
 * 留出生路径。</p>
 */
@Getter
public final class DecisionOutboundResult {

    /** 是否放行出域（<b>合规拒绝不计错误</b>） */
    private final boolean permitted;

    /** 放行时的出域载荷；{@code permitted = false} 时为空 */
    private final DecisionPayload payload;

    /** 加工事实（脱敏 / 截断），不承「说不」 */
    private final DecisionProcessingRecord record;

    /**
     * 构造出域结果。
     *
     * @param permitted 是否放行，{@code true} 时 {@code payload} 必非空
     * @param payload   出域载荷，{@code permitted = false} 时为 null
     * @param record    加工记录，不得为 null
     * @throws IllegalArgumentException {@code permitted = true} 而 {@code payload} 为空
     */
    public DecisionOutboundResult(final boolean permitted,
                                  final DecisionPayload payload,
                                  final DecisionProcessingRecord record) {
        if (permitted && payload == null) {
            throw new IllegalArgumentException("permitted = true 时出域载荷必非空：放行却不给内容等于造出无载荷的出域");
        }
        this.permitted = permitted;
        this.payload = payload;
        this.record = Objects.requireNonNull(record,
                "加工记录不得为 null：无加工时取 DecisionProcessingRecord.none()，不得以 null 表示");
    }
}
