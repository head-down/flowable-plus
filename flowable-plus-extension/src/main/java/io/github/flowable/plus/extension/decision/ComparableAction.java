package io.github.flowable.plus.extension.decision;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import io.github.flowable.plus.core.enums.ApprovalAction;

/**
 * 表态比较面（ADR-0042 第 12 节 / `CONTEXT.md`「表态比较面」）。
 *
 * <p>采纳判定做「同值 / 异值」比较时，<b>人的一侧</b>允许出现的动作集合 —— 四个值：
 * {@link ApprovalAction#AGREE} / {@link ApprovalAction#REJECT} /
 * {@link ApprovalAction#COUNTER_SIGN_AGREE} / {@link ApprovalAction#COUNTER_SIGN_REJECT}。</p>
 *
 * <p><b>面外</b>的动作不参与比较，一律判为 {@code NOT_COMPARABLE}。承重条款是 {@code AUTO_COMPLETE}
 * 必须排除 —— 但该排除<b>不在动作读取结果层成立</b>：{@code AUTO_COMPLETE} 属 {@code CommentType} 层
 * （主仓<b>无</b>同名 {@code ApprovalAction}），经读侧映射后表现为 {@code action=AGREE}，
 * 故本机制<b>不能</b>对 {@code AUTO_COMPLETE} 作独立动作层断言。这一不可判别性属<b>已知边界</b>，
 * <b>不得</b>读作「机制已排除 {@code AUTO_COMPLETE}」。</p>
 *
 * <p>白名单留在 extension（词汇所有权在 core）⇒ 由 extension 侧守卫测试管理漂移；
 * 不在 core 放「无消费者的常量」来消除漂移。</p>
 */
public final class ComparableAction {

    /** 表态比较面四值 —— 单一来源 */
    public static final Set<ApprovalAction> MEMBERS = Collections.unmodifiableSet(
            EnumSet.of(
                    ApprovalAction.AGREE,
                    ApprovalAction.REJECT,
                    ApprovalAction.COUNTER_SIGN_AGREE,
                    ApprovalAction.COUNTER_SIGN_REJECT
            ));

    private ComparableAction() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /**
     * 该动作是否属于表态比较面。与 {@link #MEMBERS} 同源等价。
     *
     * @param action 待判定的动作，可为 {@code null}
     * @return 属于表态比较面时为 {@code true}
     */
    public static boolean isComparable(ApprovalAction action) {
        return action != null && MEMBERS.contains(action);
    }
}
