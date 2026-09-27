package io.github.flowable.plus.core.vo;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 读侧「锚点序未建立」的决策证据组载体（ADR-0042 第 9 节第 7 条「整锚点不判」的机械承载位）。
 *
 * <p><b>它承载什么</b>：读侧投影器在<b>无法建立</b>锚点内「最早在前」次序时（典型：任一证据行的
 * {@code ID_} 不可解析 —— 应用替换了 {@code IdGenerator}），仍<b>照常投影全部证据行</b>（不可判 ≠ 损坏、
 * ≠ 抑制），但以<b>本类型</b>而非普通列表交出 —— 引擎原样次序、未做读侧重排。内容与普通列表逐行相同，
 * 唯一差异是<b>列表序未被读侧建立</b>这一事实。</p>
 *
 * <p><b>谁消费它</b>：{@code DecisionReplayJudge}（重放判定面）。重放按<b>列表序</b>判定（最早 = 原行），
 * 而证据 VO 不携带任何时序 / 序号元数据，判定面无法从列表内容识别「次序是否可信」—— 故「未建序」
 * 必须由列表自身的类型承载：判定面对本类型<b>一律拒绝标注</b>（返回 {@code null}），兑现
 * 「{@code ID_} 不可解析 ⇒ 该锚点整体不判原 / 重放（拒绝标注）」。这一事实<b>不是字段、不是 JSON 键</b>
 * （它住列表的运行时类型，不占任何读侧硬清单槽位），{@code resolveReplayOf} 的方法形态不变。</p>
 *
 * <p><b>不可修改</b>：本类型构造即深拷贝入参，且不开放任何变更操作（{@code AbstractList} 缺省拒改）；
 * {@code ApprovalRecordVO#getDecisionEvidences()} / {@code CountersignSubRecord#getDecisionEvidences()}
 * 对它<b>原样透传</b>（不再二次包装，以免掩盖类型身份），其余列表仍走既有不可修改包装 —— 两条取值器
 * 的「恒不可修改」承诺在两条路径上都成立。</p>
 *
 * <p><b>登记</b>：实现细节承载位（不入术语表）；命名与豁免登记见探索工作区命名宪章 §4.5 的本票登记块。
 * {@code equals} / {@code hashCode} 沿 {@code AbstractList} 按内容计算 —— 与等长的普通列表相等，
 * 不影响任何按内容比较的既有对拍面。</p>
 */
public final class UnorderedDecisionEvidences extends AbstractList<DecisionEvidenceVO> {

    private final List<DecisionEvidenceVO> rows;

    private UnorderedDecisionEvidences(Collection<DecisionEvidenceVO> rows) {
        this.rows = new ArrayList<>(rows);
    }

    /**
     * 以「未建序」形态承载一批已投影的证据行。
     *
     * @param rows 已投影的证据行（内容完整；次序为引擎原样、未做读侧重排）
     * @return 未建序证据组
     */
    public static UnorderedDecisionEvidences of(Collection<DecisionEvidenceVO> rows) {
        return new UnorderedDecisionEvidences(rows);
    }

    @Override
    public DecisionEvidenceVO get(int index) {
        return rows.get(index);
    }

    @Override
    public int size() {
        return rows.size();
    }
}
