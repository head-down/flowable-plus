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
 * 审批记录 VO，表示审批历史中的单条记录（发起、同意、驳回、撤回、撤销、转办、加签、减签、终止等）。
 *
 * <p>会签节点通过 {@code countersignRecords} 字段携带每位参与者的投票子记录。
 * 普通节点该字段为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRecordVO {

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

    /** 会签子记录（会签节点非 null，普通节点为 null） */
    private List<CountersignSubRecord> countersignRecords;

    /**
     * 决策证据组（ADR-0042 第 5 节）：<b>只读</b>独立字段，不参与人工意见（{@code comment} /
     * {@code operationComment} / {@code operationComments}）的槽位竞争。
     *
     * <p><b>挂载层级</b>：证据挂<b>该 {@code taskId} 所属的那条记录</b> —— 非会签节点（读侧无子记录）
     * 挂本字段；会签节点挂 {@link CountersignSubRecord#getDecisionEvidences()}，此时本字段<b>恒空</b>。
     * 同一条证据不重复挂两层。</p>
     *
     * <p><b>恒返回空集合</b>（软回退）：移除 extension 后结构仍在、内容为空，下游拿到空集合而非编译 /
     * 运行错误。与同 VO {@code operationComments} 的 {@code null} 惯例<b>有意分歧</b> —— 该分歧由
     * {@link #getDecisionEvidences()} 的取值器保证，构造路径（builder / 全参 / 无参）一律成立。</p>
     *
     * <p><b>已知边界</b>：① <b>证据行数 ≠ 决策次数</b>（重复到达照记多行、只标注不抑制）；
     * ② 「锚点失效」与「实例已结束」两种写入失败在本字段上<b>不可见</b>（降级为日志 + 指标）；
     * ③ 判别式不可解析或载荷损坏的行<b>只跳过该条证据投影</b>，审批轨迹行本身不消失。</p>
     */
    private List<DecisionEvidenceVO> decisionEvidences;

    /**
     * 决策证据组的取值器。<b>恒返回空集合</b> —— 未挂载证据时返回空集合而非 {@code null}（软回退，
     * 见字段 javadoc 的「有意分歧」）；且恒为<b>不可修改</b>包装，调用方无法借它改写记录内部状态。
     *
     * @return 本记录的决策证据组，永不为 {@code null}
     */
    public List<DecisionEvidenceVO> getDecisionEvidences() {
        return decisionEvidences != null
                ? Collections.unmodifiableList(decisionEvidences)
                : Collections.emptyList();
    }

    /**
     * 读侧派生判定：返回该条证据在<b>同一锚点内</b>所重放的<b>原行</b>；本身是原行 ⇒ 返回 {@code null}。
     *
     * <p>这是<b>派生事实</b>、不是持久化属性 —— 故<b>不落字段、不作 JSON 键、不给独立 API</b>；词尾取
     * 判定语义（ADR-0042 第 9 节 + 命名宪章 §2.D.3）。判别作用域 = 同一锚点（同一条记录）内，
     * <b>跨锚点不承诺</b>。</p>
     *
     * <p><b>判序</b>：锚点内按 {@code TIME_} 升序、同毫秒按数值 {@code ID_} 升序重排后由读侧挂载，
     * 故「原行」= 本记录列表中<b>首次出现该幂等身份</b>的那一行（读侧负责建立该序）。</p>
     *
     * @param evidence 待判定的证据行
     * @return 该证据所重放的原行；本身是原行、身份为空、或本记录内无同键原行时返回 {@code null}
     */
    public DecisionEvidenceVO resolveReplayOf(DecisionEvidenceVO evidence) {
        return DecisionReplayJudge.resolveReplayOf(getDecisionEvidences(), evidence);
    }
}
