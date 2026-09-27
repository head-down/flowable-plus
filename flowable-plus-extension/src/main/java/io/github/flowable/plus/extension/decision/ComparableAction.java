package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.ApprovalAction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 表态比较面（ADR-0042 第 12 节）：<b>人的一侧只认四个动作</b> —— 受理采纳判定时参与比较的
 * {@link ApprovalAction} 子集。
 *
 * <p><b>{@link #MEMBERS} 是子集的单一来源</b>：四个成员<b>显式列出</b>（不得由
 * {@code EnumSet.allOf} 或任何派生式生成），判定方法 {@link #isComparable(ApprovalAction)}
 * 与它<b>同源等价</b>（{@code isComparable(a) ⇔ MEMBERS.contains(a)}）。</p>
 *
 * <p><b>值域来源与所有权分离</b>：动作词汇的所有权在 core（{@link ApprovalAction}），白名单留在
 * extension —— core 未来新增投票动作时本子集<b>不会自动跟随</b>，该漂移由
 * {@code ComparableActionAvailabilityTest} 的镜像守卫测试管理（ADR-0042 第 2 节「已记的债」）。</p>
 *
 * <p><b>显式排除</b>（ADR-0042 第 12 节）：{@code RETURN} 退回 / {@code WITHDRAW} 收回待办 /
 * {@code INVALID} 作废 / {@code INITIATE_COUNTERSIGN} 发起会签，以及整个操作注释组。
 * <b>{@code AUTO_COMPLETE} 不可达只作结构事实陈述</b>：它是 {@code CommentType} 侧的取值、
 * 主仓<b>没有</b>同名 {@link ApprovalAction} —— 本条<b>不得</b>被读作「机制已排除自动提交」
 * （{@code CommentType=AUTO_COMPLETE} 经读侧映射后表现为动作层 {@code AGREE}，在无引擎内部类型
 * 信息的情况下机制不保证二者可判别，属<b>已知边界</b>）。</p>
 *
 * <p><b>可用动作 = 逐动作对齐 core 守卫</b>（不拍档位）：某动作「可用」的<b>定义</b>是
 * 「core 对应写入路径的守卫会放行」，故每行都能引一行 core 源码，且<b>零误拒</b>。
 * 三行映射见 {@link #isAvailableFor(ApprovalAction, boolean, boolean, boolean)}。</p>
 *
 * <p><b>实现细节的面</b>：可用动作映射是<b>包内</b>判定方法（消费者只有位点服务），
 * 不进公开面 —— 公开面只有常量 {@link #MEMBERS} 与判定 {@link #isComparable(ApprovalAction)}。</p>
 */
public final class ComparableAction {

    /**
     * 表态比较面的全部取值（恰四值；<b>显式枚举、单一来源</b>）。
     *
     * <p>不可修改集合；调用方不得据此推断「四个之外的动作非法」—— 它们只是<b>不参与比较</b>。</p>
     */
    public static final Set<ApprovalAction> MEMBERS = Collections.unmodifiableSet(EnumSet.of(
            ApprovalAction.AGREE,
            ApprovalAction.REJECT,
            ApprovalAction.COUNTER_SIGN_AGREE,
            ApprovalAction.COUNTER_SIGN_REJECT));

    private ComparableAction() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /**
     * 判断一个动作是否属表态比较面。
     *
     * @param action 动作，可空
     * @return 动作非 null 且属 {@link #MEMBERS} 时返回 true；null 或面外动作返回 false
     */
    public static boolean isComparable(final ApprovalAction action) {
        return action != null && MEMBERS.contains(action);
    }

    /**
     * 判断一个表态比较面动作对<b>当前任务</b>是否可用（<b>逐动作对齐 core 三个守卫</b>）。
     *
     * <p><b>三行映射</b>（每行引一行 core 源码；「可用」= 该守卫会放行）：</p>
     *
     * <table border="1" cellpadding="3" cellspacing="0" summary="可用动作与所镜像的 core 守卫">
     * <tr><th>候选动作</th><th>可用判据</th><th>所镜像的 core 守卫</th></tr>
     * <tr><td>{@code COUNTER_SIGN_AGREE} / {@code COUNTER_SIGN_REJECT}</td>
     *     <td>{@code modelMultiInstance}</td>
     *     <td>{@code CounterSignWorkflow.counterSign} → {@code TaskValidation.validateMultiInstance}（模型级）</td></tr>
     * <tr><td>{@code AGREE}</td>
     *     <td>{@code !runtimeMultiInstance}</td>
     *     <td>{@code TaskExecutionWorkflow.completeTask} → {@code validateNotMultiInstance(..., false)}（无豁免）</td></tr>
     * <tr><td>{@code REJECT}</td>
     *     <td>{@code !runtimeMultiInstance ∨ initiatorDecisionTask}</td>
     *     <td>{@code TaskExecutionWorkflow.rejectTask} → {@code validateNotMultiInstance(..., true)}</td></tr>
     * </table>
     *
     * <p><b>两个被否的候选口径</b>（如实登记，防日后「简化」）：① <b>通道口径</b>（会签 / 非会签两档）
     * 会把<b>伪单例</b>放行的会签两值与<b>折返后发起人决策任务</b>放行的 {@code REJECT} 判成准入失败
     * ⇒ 误拒计入错误指标；② <b>四档穷举</b>是拍出来的档位，每档答不出「凭什么是它」。</p>
     *
     * <p><b>布尔入参的三个事实</b>由 core {@code MultiInstanceDetector} 的三次判定提供
     * （{@code isMultiInstance} / {@code isRuntimeMultiInstance} / {@code isInitiatorDecisionTask}）；
     * 本方法只做映射、不触引擎，故真值表可逐格对拍。</p>
     *
     * @param action                候选动作，可空
     * @param modelMultiInstance    模型级多实例子任务（{@code isMultiInstance}）
     * @param runtimeMultiInstance  运行时多实例子任务（{@code isRuntimeMultiInstance}；伪单例为 {@code false}）
     * @param initiatorDecisionTask 折返后发起人决策任务（{@code isInitiatorDecisionTask}）
     * @return 该动作对当前任务可用时返回 true；面外动作或不可用时返回 false
     */
    static boolean isAvailableFor(final ApprovalAction action,
                                  final boolean modelMultiInstance,
                                  final boolean runtimeMultiInstance,
                                  final boolean initiatorDecisionTask) {
        if (action == null) {
            return false;
        }
        switch (action) {
            case COUNTER_SIGN_AGREE:
            case COUNTER_SIGN_REJECT:
                return modelMultiInstance;
            case AGREE:
                return !runtimeMultiInstance;
            case REJECT:
                return !runtimeMultiInstance || initiatorDecisionTask;
            default:
                return false;
        }
    }
}
