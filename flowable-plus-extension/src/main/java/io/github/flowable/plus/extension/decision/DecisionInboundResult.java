package io.github.flowable.plus.extension.decision;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 出域策略入站方向的<b>显式结果位</b>（ADR-0042 第 6 节）：{@code persistable} / {@code rawOutput} /
 * {@code record}。
 *
 * <p><b>「说不」住显式结果位，不住 {@code null}</b>：{@code persistable = false} 表示策略判定政策性
 * <b>不可落盘</b>（映 {@code RESTRICTED}，<b>不计错误指标</b>）；策略加工<b>抛异常</b>表示入站加工失败
 * （映 {@code INBOUND_PROCESSING_FAILED}，<b>计错误指标</b>）。二者<b>严禁混用</b>。</p>
 *
 * <p><b>结果位互锁</b>（构造期强制）：{@code persistable = true ⇒ rawOutput 必有内容}（非 null 且非空串）。
 * 入参形态 = 已冻结字段 {@code rawOutput} 的形态（<b>裸 {@code String}</b>，不套壳）。</p>
 */
@Getter
public final class DecisionInboundResult {

    /** 是否可落盘（<b>政策性拒绝不计错误</b>） */
    private final boolean persistable;

    /** 入站加工后的裸串；不可落盘时为空 */
    private final String rawOutput;

    /** 加工事实（脱敏 / 截断），不承「说不」 */
    private final DecisionProcessingRecord record;

    /**
     * 构造入站结果。
     *
     * @param persistable 是否可落盘，{@code true} 时 {@code rawOutput} 必有内容
     * @param rawOutput   入站加工后的裸串，{@code persistable = false} 时为空
     * @param record      加工记录，不得为 null
     * @throws IllegalArgumentException {@code persistable = true} 而 {@code rawOutput} 无内容
     */
    public DecisionInboundResult(final boolean persistable,
                                 final String rawOutput,
                                 final DecisionProcessingRecord record) {
        if (persistable && StringUtils.isEmpty(rawOutput)) {
            throw new IllegalArgumentException("persistable = true 时入站内容必非空：可落盘却说没内容，读侧无从区分");
        }
        this.persistable = persistable;
        this.rawOutput = rawOutput;
        this.record = Objects.requireNonNull(record,
                "加工记录不得为 null：无加工时取 DecisionProcessingRecord.none()，不得以 null 表示");
    }
}
