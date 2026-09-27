package io.github.flowable.plus.core.vo;

import io.github.flowable.plus.core.enums.ApprovalAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 会签子记录 VO，用于表示会签节点中单个参与者的投票记录。
 *
 * <p>该 VO 不可嵌套 -- ApprovalRecordVO 中持有 List&lt;CountersignSubRecord&gt;，
 * 但 CountersignSubRecord 自身不再包含子记录列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountersignSubRecord {

    /** 任务 ID */
    private String taskId;

    /** 节点 definitionKey */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 操作类型 */
    private ApprovalAction action;

    /** 操作人 ID */
    private String actorId;

    /** 操作人名称 */
    private String actorName;

    /** 审批意见 */
    private String comment;

    /** 操作注释（ADR-0025）：加签/减签等操作记录文本，如 "加签审批人: xxx"，与 {@code comment} 语义解耦 */
    private String operationComment;

    /** 全部操作注释（ADR-0027）：该任务全部操作注释文本，按时间正序排列（最早在前），无操作注释时为 null */
    private List<String> operationComments;

    /** 任务开始时间 */
    private Date startTime;

    /** 任务结束时间（当前节点为 null） */
    private Date endTime;

    /** 耗时（毫秒） */
    private Long duration;

    /**
     * 会签轮次索引，用于多轮加签场景的分组展示。
     *
     * <p>写侧通过 Task 局部变量 {@code csRoundIndex} 显式标记，读侧据此分轮次。
     * 有显式值直接使用，无则默认 round = 0（原始审批人隐式轮次）。
     */
    private Integer roundIndex;

    /**
     * 决策证据组（ADR-0042 第 5 节）：<b>只读</b>独立字段，不占人工意见槽位。
     *
     * <p><b>挂载层级</b>：会签节点上证据挂本字段；此时父 VO
     * {@link ApprovalRecordVO#getDecisionEvidences()} <b>恒空</b>，同一条证据不重复挂两层。</p>
     *
     * <p><b>恒返回空集合</b>（软回退，与同 VO {@code operationComments} 的 {@code null} 惯例有意分歧）——
     * 由 {@link #getDecisionEvidences()} 的取值器保证。已知边界（证据行数 ≠ 决策次数 / 两种写入失败
     * 不可见 / 损坏行只跳过该条投影）同 {@link ApprovalRecordVO#getDecisionEvidences()}。</p>
     */
    private List<DecisionEvidenceVO> decisionEvidences;

    /**
     * 决策证据组的取值器。<b>恒返回空集合</b>（软回退）；且恒为<b>不可修改</b>包装，调用方无法借它
     * 改写记录内部状态。
     *
     * @return 本子记录的决策证据组，永不为 {@code null}
     */
    public List<DecisionEvidenceVO> getDecisionEvidences() {
        return decisionEvidences != null
                ? Collections.unmodifiableList(decisionEvidences)
                : Collections.emptyList();
    }

    /**
     * 读侧派生判定：返回该条证据在<b>同一锚点内</b>所重放的<b>原行</b>；本身是原行 ⇒ 返回 {@code null}。
     *
     * <p>语义与形态同 {@link ApprovalRecordVO#resolveReplayOf(DecisionEvidenceVO)}；本方法挂在
     * <b>证据实际所在的层级</b>（会签节点 = 子记录）。</p>
     *
     * @param evidence 待判定的证据行
     * @return 该证据所重放的原行；本身是原行、身份为空、或本子记录内无同键原行时返回 {@code null}
     */
    public DecisionEvidenceVO resolveReplayOf(DecisionEvidenceVO evidence) {
        return DecisionReplayJudge.resolveReplayOf(getDecisionEvidences(), evidence);
    }
}
