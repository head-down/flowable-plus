package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.DecisionContextSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 框架硬上限 clamp（ADR-0042 第 6 节）：出域与入站两方向共用的<b>最后一道</b>大小闸。
 *
 * <p><b>单位</b> = 载荷（provider 缝格式化之前的 provider-中立形态）序列化后的 <b>UTF-8 字节数</b>；
 * <b>硬上限 = {@value #MAX_PAYLOAD_BYTES} 字节（32 KiB）</b>，是两方向同一上限的唯一住所。</p>
 *
 * <p><b>只许调低、不可放大</b>：构造期取 {@code Math.min(requestedMaxBytes, MAX_PAYLOAD_BYTES)} ——
 * 任何请求上限都<b>不可能</b>把有效上限抬到框架硬上限之上（v1 无应用侧调参口，由管线传硬上限本身）。</p>
 *
 * <p><b>收缩语义 = 整段丢弃 + 兜底拒绝</b>：出域方向按 {@link DecisionContextSource#getDropPriority()}
 * （<b>数值越大越先丢</b>、与物理声明序无关）<b>整段</b>丢弃至满足上限；<b>丢到全空仍超</b> ⇒ 抛
 * {@link DecisionClampRejectedException}（拒绝出域）。<b>段内永不动刀</b> —— 段内截断会造出「看起来真实、
 * 实则残缺」的值而污染审计。入站方向<b>同一上限、同样不截断</b>：超限 ⇒ 返回 {@code null}。</p>
 *
 * <p><b>实现细节，不入机制术语表</b>（命名宪章 §4.5）：住所 = extension 包内，消费者只有管线与
 * 入站加工。出域与入站共用<b>本组件</b>（clamp），但<b>不共用策略</b>（策略住 {@code DecisionPolicy}，
 * 入站加工不依赖它）。</p>
 */
final class DecisionClamp {

    /** 载荷硬上限（UTF-8 字节；32 KiB）。两方向同一上限的唯一住所，非可配置。 */
    static final int MAX_PAYLOAD_BYTES = 32_768;

    /** 丢弃序：按 {@code dropPriority} 降序（数值越大越先丢；显式排序，不依赖枚举物理声明序） */
    private static final Comparator<DecisionContextSource> DROP_ORDER =
            Comparator.comparingInt(DecisionContextSource::getDropPriority).reversed();

    /** 有效上限（已收口为「不超过 {@link #MAX_PAYLOAD_BYTES}」） */
    private final int maxBytes;

    /** 载荷序列化器（计量口径 = 序列化后的 UTF-8 字节数） */
    private final ObjectMapper objectMapper;

    /**
     * 构造 clamp。
     *
     * @param requestedMaxBytes 请求上限（字节），正数；有效上限 = {@code min(请求值, 硬上限)} —— 只许调低不可放大
     * @param objectMapper      载荷序列化器，不得为 null
     */
    DecisionClamp(final int requestedMaxBytes, final ObjectMapper objectMapper) {
        if (requestedMaxBytes <= 0) {
            throw new IllegalArgumentException("请求上限必须为正数：" + requestedMaxBytes);
        }
        this.maxBytes = Math.min(requestedMaxBytes, MAX_PAYLOAD_BYTES);
        this.objectMapper = Objects.requireNonNull(objectMapper, "载荷序列化器不得为 null");
    }

    /**
     * 出域 clamp：把载荷整段丢弃至满足上限，段内永不动刀。
     *
     * @param payload 待收缩载荷，不得为 null
     * @return 收缩后的载荷（未超限时原样返回）；丢到全空仍超 ⇒ 抛 {@link DecisionClampRejectedException}
     */
    DecisionPayload clampOutbound(final DecisionPayload payload) {
        Objects.requireNonNull(payload, "待收缩载荷不得为 null");
        if (byteSize(payload) <= maxBytes) {
            return payload;
        }
        DecisionPayload current = payload;
        for (final DecisionContextSource source : dropOrder()) {
            if (byteSize(current) <= maxBytes) {
                break;
            }
            current = dropSegment(current, source);
        }
        if (byteSize(current) > maxBytes) {
            throw new DecisionClampRejectedException(
                    "丢到全空仍超上限，拒绝出域：有效上限=" + maxBytes + "，实际=" + byteSize(current));
        }
        return current;
    }

    /**
     * 入站 clamp：同一上限、同样不截断。
     *
     * @param rawOutput 入站裸串，可空
     * @return 未超限时原样返回；超限返回 {@code null}（不抛、不截断）；入参为 null 时返回 null
     */
    String clampInbound(final String rawOutput) {
        if (rawOutput == null || utf8Length(rawOutput) <= maxBytes) {
            return rawOutput;
        }
        return null;
    }

    /**
     * 丢弃序 = {@code dropPriority} 降序（显式排序，防 IDE 重排引线上雪崩）。
     *
     * @return 丢弃序上的全部来源
     */
    private static List<DecisionContextSource> dropOrder() {
        final List<DecisionContextSource> sources = new ArrayList<>(EnumSet.allOf(DecisionContextSource.class));
        sources.sort(DROP_ORDER);
        return sources;
    }

    /**
     * 整段丢弃一个来源：把对应段置为 {@code null}（未声明态），<b>绝不</b>改动段内内容。
     *
     * @param payload 当前载荷
     * @param source  被丢弃的来源
     * @return 该段置空后的新载荷
     */
    private static DecisionPayload dropSegment(final DecisionPayload payload, final DecisionContextSource source) {
        switch (source) {
            case PROCESS_VARIABLES:
                return new DecisionPayload(null, payload.getTaskVariables(),
                        payload.getTaskMetadata(), payload.getProcessInstanceMetadata());
            case TASK_VARIABLES:
                return new DecisionPayload(payload.getProcessVariables(), null,
                        payload.getTaskMetadata(), payload.getProcessInstanceMetadata());
            case TASK_METADATA:
                return new DecisionPayload(payload.getProcessVariables(), payload.getTaskVariables(),
                        null, payload.getProcessInstanceMetadata());
            case PROCESS_INSTANCE_METADATA:
                return new DecisionPayload(payload.getProcessVariables(), payload.getTaskVariables(),
                        payload.getTaskMetadata(), null);
            default:
                throw new IllegalStateException("未知的数据源：" + source);
        }
    }

    /**
     * 载荷的序列化 UTF-8 字节数。
     *
     * @param payload 载荷
     * @return 序列化后的 UTF-8 字节数
     */
    private int byteSize(final DecisionPayload payload) {
        try {
            // 直接取字节（不中转 String）：计量口径本就是 UTF-8 字节数
            return objectMapper.writeValueAsBytes(payload).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("决策载荷序列化失败：clamp 计量口径依赖它", e);
        }
    }

    /**
     * 字符串的 UTF-8 字节数。
     *
     * @param value 字符串
     * @return UTF-8 字节数
     */
    private static int utf8Length(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
