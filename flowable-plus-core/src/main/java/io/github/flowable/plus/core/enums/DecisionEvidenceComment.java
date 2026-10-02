package io.github.flowable.plus.core.enums;

import org.apache.commons.lang3.StringUtils;

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
 *   <li>载荷 = 纯前缀标记（见 {@link #marker()}）+ ASCII-safe JSON；
 *       <b>标记与 JSON 之间无分隔符</b>，剥离判据 = {@link #hasMarker(String)} + {@link #stripMarker(String)}
 *       —— JSON 必以 <code>{</code> 开头，故与标记无歧义；</li>
 *   <li>{@link #EVIDENCE_COMMENT_TYPES} 是「证据组」的显式常量（与操作注释组平行、单一来源），
 *       供读侧按 {@code TYPE_} 推断排除；业务意见组保持<b>隐式补集</b>，本机制不为它新增常量。</li>
 * </ul>
 *
 * <p><b>标记字面量唯一</b>：标记前缀与后缀只在本类内声明一次（{@link #MARKER_PREFIX} / {@link #MARKER_SUFFIX}，
 * 均为私有），公开面只暴露 {@link #marker()} / {@link #hasMarker(String)} / {@link #stripMarker(String)}。
 * 任何其他源码（含注释）不得另行拼写标记字面量。</p>
 *
 * <p><b>匹配粒度</b>：读侧先按 {@code TYPE_} 挑出证据行，{@link #hasMarker(String)} 只再确认标记前缀 ——
 * 「是否本机制的证据行」由 {@code TYPE_} 承担，「从何处切分标记与 JSON」由本类承担，二者不重复判定。</p>
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
     *
     * @return 标记字面量（标记前缀 + 存库 {@code TYPE_} 取值名 + 标记后缀）
     */
    public static String marker() {
        return MARKER_PREFIX + COMMENT_TYPE.name() + MARKER_SUFFIX;
    }

    /**
     * 判断一条评论的 {@code FULL_MSG_} 是否带证据载荷标记前缀。
     *
     * @param fullMessage 评论全文，可为 null
     * @return 带标记前缀返回 true；null 或不带标记前缀返回 false
     */
    public static boolean hasMarker(final String fullMessage) {
        return StringUtils.startsWith(fullMessage, MARKER_PREFIX);
    }

    /**
     * 剥掉证据载荷标记，返回其后的 JSON 原文。
     *
     * <p>无标记时原样返回。标记<b>不完整</b>（有前缀但缺后缀 —— 截断 / 损坏）时同样原样返回：
     * 该行随后必然过不了 JSON 解析，由读侧容错条款「跳过该条有效证据投影」处置，
     * 本方法不抛异常、不把损坏行升级为读侧故障。</p>
     *
     * @param fullMessage 评论全文，可为 null
     * @return 标记之后的 JSON 原文；无标记或标记不完整时原样返回（含 null）
     */
    public static String stripMarker(final String fullMessage) {
        if (!hasMarker(fullMessage) || !StringUtils.contains(fullMessage, MARKER_SUFFIX)) {
            return fullMessage;
        }
        return StringUtils.substringAfter(fullMessage, MARKER_SUFFIX);
    }
}
