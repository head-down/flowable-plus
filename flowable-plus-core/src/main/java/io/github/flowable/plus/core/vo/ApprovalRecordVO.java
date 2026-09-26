package io.github.flowable.plus.core.vo;

import io.github.flowable.plus.core.enums.ApprovalAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
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
     * 决策证据组（ADR-0042 第 5 节）：**只读**、独立字段，不参与人工意见（{@code comment} /
     * {@code operationComment} / {@code operationComments}）的槽位竞争。
     *
     * <p><b>挂载层级</b>：证据挂**该 {@code taskId} 所属的那条记录** —— 非会签节点（读侧无子记录）
     * 挂本字段；会签节点挂 {@link CountersignSubRecord#decisionEvidences}，此时本字段**恒空**。
     * 同一条证据不重复挂两层。</p>
     *
     * <p><b>恒返回空集合</b>（软回退 —— 与同 VO {@code operationComments} 的 {@code null} 惯例
     * **有意分歧**）：移除 extension 后结构仍在、内容为空，下游拿到空集合而非编译 / 运行错误。</p>
     *
     * <p><b>已知边界</b>：① <b>证据行数 ≠ 决策次数</b>；② 「锚点失效」与「实例已结束」两种写入失败
     * 在本字段上**不可见**（降级为日志 + 指标）；③ 判别式不可解析或载荷损坏的行**只跳过该条证据投影**，
     * 审批轨迹行本身不消失。</p>
     */
    @Builder.Default
    private List<DecisionEvidenceVO> decisionEvidences = new ArrayList<>();

    /**
     * 读侧派生判定：返回该条证据在同锚点内所重放的**原行**；本身是原行 ⇒ 返回 {@code null}。
     *
     * <p>这是**派生事实**、不是持久化属性 —— 故**不落字段、不作 JSON 键、不给独立 API**；
     * 词尾取判定语义（ADR-0042 第 9 节 + 命名宪章 §2.D.3）。判别作用域 = **同一锚点内**。</p>
     *
     * <p>判序（实现期）：锚点内按 {@code TIME_} 升序重排（引擎实际返回降序且无次级键），同毫秒按
     * {@code Long.parseLong(ID_)} 数值升序；{@code ID_} 不可解析 ⇒ 该锚点整体不判。</p>
     *
     * <p><b>骨架说明</b>：判序与 tie-break 属实现期产物，本骨架不实现。</p>
     *
     * @param evidence 待判定的证据行
     * @return 该证据所重放的原行；本身是原行时返回 {@code null}
     */
    public DecisionEvidenceVO resolveReplayOf(DecisionEvidenceVO evidence) {
        throw new UnsupportedOperationException("骨架：重放判序归实现期（见 #43）");
    }
}
