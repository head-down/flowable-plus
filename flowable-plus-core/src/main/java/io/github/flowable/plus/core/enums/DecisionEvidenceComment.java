package io.github.flowable.plus.core.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 决策证据的常量类（与 {@link CommentType} 同包）。
 *
 * <p>承载证据行的<b>标记</b>与<b>证据组</b>，是写侧 / 读侧共用的单一来源：</p>
 *
 * <ul>
 *   <li>{@link #COMMENT_TYPE} 是唯一真值来源，标记由它的 {@code name()} <b>派生</b> ——
 *       标记与 {@code ACT_HI_COMMENT.TYPE_} <b>同源</b>，写侧读侧都无法自行拼装；</li>
 *   <li>载荷 = 纯前缀标记 {@code [SYSTEM:DECISION_EVIDENCE]} + ASCII-safe JSON；
 *       <b>标记与 JSON 之间无分隔符</b>，剥离判据 = {@link #hasMarker(String)} + {@link #stripMarker(String)}；</li>
 *   <li>{@link #EVIDENCE_COMMENT_TYPES} 是「证据组」的显式常量（与操作注释组平行、单一来源），
 *       供读侧按 {@code TYPE_} 推断排除；业务意见组保持<b>隐式补集</b>（ADR-0025 冻结），本机制不为它新增常量。</li>
 * </ul>
 *
 * <p><b>词同物异</b>：标记字面量里的 {@code SYSTEM} 意为「<b>框架自产评论</b>」，
 * 与 {@link DecisionSubjectType#SYSTEM}（离线批算 / 外部规则服务）<b>不是一回事</b>，二者不得互相引用或替代。</p>
 */
public final class DecisionEvidenceComment {

    /** 证据行的 {@link CommentType} 取值 —— 标记与证据组的唯一真值来源 */
    public static final CommentType COMMENT_TYPE = CommentType.DECISION_EVIDENCE;

    /** 标记前缀（私有：唯一构造来源在本类内） */
    private static final String MARKER_PREFIX = "[SYSTEM:";

    /** 标记后缀（私有：唯一构造来源在本类内） */
    private static final String MARKER_SUFFIX = "]";

    /** 证据组：读侧推断排除按本常量，不按裸字面量 */
    public static final Set<CommentType> EVIDENCE_COMMENT_TYPES = Collections.unmodifiableSet(
            EnumSet.of(COMMENT_TYPE));

    private DecisionEvidenceComment() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /**
     * 证据载荷标记。由 {@link #COMMENT_TYPE} 派生，构造来源唯一。
     */
    public static String marker() {
        return MARKER_PREFIX + COMMENT_TYPE.name() + MARKER_SUFFIX;
    }

    /**
     * 判断一条评论的 {@code FULL_MSG_} 是否带证据载荷标记。
     */
    public static boolean hasMarker(String fullMessage) {
        return fullMessage != null && fullMessage.startsWith(MARKER_PREFIX);
    }

    /**
     * 剥掉证据载荷标记，返回其后的 JSON 原文（无标记时原样返回）。
     */
    public static String stripMarker(String fullMessage) {
        if (!hasMarker(fullMessage)) {
            return fullMessage;
        }
        int end = fullMessage.indexOf(MARKER_SUFFIX, MARKER_PREFIX.length());
        if (end < 0) {
            // 标记不完整（截断 / 损坏）—— 骨架不实现容错，交由读侧护栏
            throw new UnsupportedOperationException("骨架：标记不完整时的容错归读侧护栏（见 #43）");
        }
        return fullMessage.substring(end + MARKER_SUFFIX.length());
    }
}
