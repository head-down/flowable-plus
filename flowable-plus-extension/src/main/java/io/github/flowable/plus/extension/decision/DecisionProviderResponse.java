package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Provider 缝的响应（ADR-0042 第 7 节「5.3 默认 Provider 的字段级线上契约」）：<b>平铺</b>，不嵌套对象。
 *
 * <p><b>字段与证据 VO 同名同源</b>（{@code provider} / {@code chainStage} / {@code degraded} /
 * {@code modelId} / {@code suggestedAction} / {@code actionSummary} / {@code rationaleFacts} /
 * {@code rationaleNarrative}）—— 端到端可与证据面对齐。</p>
 *
 * <p><b>互斥性</b>：{@link #declined} 与 {@link #suggestedAction} <b>必居其一</b>；{@code declined} 是
 * 「模型主动不产出」的<b>显式位</b>，<b>不得由空字段反推</b>。皆缺或皆在 ⇒
 * {@link DecisionFailureKind#RESPONSE_UNPARSEABLE}。</p>
 *
 * <p><b>两个框架填字段</b>：{@link #provider} = 该次 {@code decisionTarget} 的 key（<b>不进响应体</b>，
 * 由 Provider 缝按目标填）；{@link #degraded} 在响应未自报时缺省 {@code false}（拉面路径的出处组要求三者
 * 同非 null ⇒ 缺省是「未自报降级」的确定性取值，而不是「未知」）。</p>
 *
 * <p><b>失败通道</b>：{@link #failureKind} 非 null 即本次出站调用失败（HTTP 状态 → 失败类别的映射在
 * Provider 缝内完成），此时其余业务字段一律为空。</p>
 *
 * <p><b>用量</b>：{@link #inputTokens} / {@link #outputTokens} 取自 provider 响应的 usage，
 * <b>缺失不猜</b>（保持 null，可见性出口 = 独立计数 {@code tokens.usage.missing}），也不设 {@code unknown}
 * 标记值。</p>
 */
@Getter
@Builder
public final class DecisionProviderResponse {

    /** 「模型主动不产出」的显式位；与 {@link #suggestedAction} 必居其一 */
    private final Boolean declined;

    /** 建议动作（表态比较面子集取值） */
    private final ApprovalAction suggestedAction;

    /** 建议摘要（产出时必填） */
    private final String actionSummary;

    /** 类型化依据（产出时必填；键取闭集） */
    private final List<DecisionRationaleFact> rationaleFacts;

    /** 文本兜底依据（产出时必填） */
    private final String rationaleNarrative;

    /** 模型标识（Provider 缝解析到即必填；直提列必须 null） */
    private final String modelId;

    /** 出处：provider 标识（框架填 = 决策目标的 key；不进响应体） */
    private final String provider;

    /** 链路阶段（响应自报、必填；缺失 ⇒ RESPONSE_UNPARSEABLE） */
    private final DecisionChainStage chainStage;

    /** 出处：是否降级（响应自报、可空；未自报按 false） */
    private final Boolean degraded;

    /** 响应体原文裸串（入站加工的入参形态） */
    private final String rawOutput;

    /** 入向 token 用量（缺失保持 null，不猜） */
    private final Long inputTokens;

    /** 出向 token 用量（缺失保持 null，不猜） */
    private final Long outputTokens;

    /** 本次出站调用的失败类别；非 null 即失败（其余业务字段为空） */
    private final DecisionFailureKind failureKind;

    /**
     * 失败响应（HTTP 状态 → 失败类别的映射在 Provider 缝内完成）。
     *
     * @param failureKind 失败类别（非 null）
     * @return 失败响应
     */
    public static DecisionProviderResponse failed(final DecisionFailureKind failureKind) {
        return DecisionProviderResponse.builder().failureKind(failureKind).build();
    }
}
