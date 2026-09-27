package io.github.flowable.plus.core.vo;

import java.util.List;

/**
 * 重放判定的共用实现（`ApprovalRecordVO` / `CountersignSubRecord` 两个承载面共用同一套判定，
 * 避免同一逻辑在两处各写一遍）。
 *
 * <p><b>判定语义</b>：同一锚点内按键分组，<b>最早 = 原行</b>，其余为「重放」并指向原行。
 * 「最早」的次序由读侧挂载时建立（锚点内 {@code TIME_} 升序、同毫秒按数值 {@code ID_} 升序），
 * 故此处只认<b>列表序</b>。</p>
 *
 * <p>它是<b>派生事实</b>，不落字段、不作 JSON 键；判别作用域 = 同一锚点内，<b>跨锚点不承诺</b>
 * （ADR-0042 第 9 节 + 命名宪章 §2.D.3）。包内可见：不构成公开命名面。</p>
 */
final class DecisionReplayJudge {

    private DecisionReplayJudge() {
    }

    /**
     * 判定一条证据是否为重放，是则返回其原行。
     *
     * @param anchorEvidences 该锚点（同一条记录）的证据组，按「最早在前」排列
     * @param evidence        待判定的证据行
     * @return 该证据所重放的原行；本身是原行、身份为空、或锚点内无同键原行时返回 {@code null}
     */
    static DecisionEvidenceVO resolveReplayOf(List<DecisionEvidenceVO> anchorEvidences,
                                              DecisionEvidenceVO evidence) {
        if (evidence == null || evidence.getIdempotencyKey() == null) {
            return null;
        }
        DecisionEvidenceVO original = anchorEvidences.stream()
                .filter(candidate -> evidence.getIdempotencyKey().equals(candidate.getIdempotencyKey()))
                .findFirst()
                .orElse(null);
        return original == evidence ? null : original;
    }
}
