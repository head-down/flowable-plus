package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.enums.CommentTypeConverter;
import org.flowable.engine.task.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * ADR-0009 三级 Comment→Action 推断策略的默认实现。
 *
 * <p>三级策略：
 * <ol>
 *   <li>特征提取：按 Comment 时间倒序扫描，取第一个匹配 {@link CommentType} 的值 → 映射为 {@link ApprovalAction}</li>
 *   <li>DeleteReason 兜底：无匹配 Comment 时，读取 deleteReason 语义</li>
 *   <li>活跃节点：无 deleteReason、无结束时间 → 返回 null</li>
 * </ol>
 *
 * <p>ADR-0025 增强：CommentType 划分为<b>业务意见组</b>与<b>操作注释组</b>。
 * 业务意见组参与 {@code comment} 槽位竞争，操作注释组不参与（见 {@link #findFirstBusinessComment}）；
 * action 推断优先级为<b>业务意见 → 操作注释 → DeleteReason → null</b>（见 {@link #inferAction}）。</p>
 *
 * <p>迁移自 {@code HistoryWorkflow.inferAction} 和 {@code findFirstBusinessComment}。</p>
 *
 * @author flowable-plus
 * @since 1.0
 */
public class DefaultActionInferenceStrategy implements ActionInferenceStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultActionInferenceStrategy.class);

    /** 操作注释组（ADR-0025）：不参与 comment 槽位竞争，仅供 action 推断与操作信息展示 */
    private static final Set<CommentType> OPERATION_COMMENT_TYPES = Collections.unmodifiableSet(
            EnumSet.of(
                    CommentType.ADD_SIGN,
                    CommentType.DELETE_SIGN,
                    CommentType.DELEGATE,
                    CommentType.RESOLVE_DELEGATE,
                    CommentType.TRANSFER
            ));

    @Override
    public ApprovalAction inferAction(String taskId, String deleteReason, List<Comment> taskComments) {
        // 一级：业务意见特征提取（时间倒序扫描 Comment，跳过操作注释组）
        Comment businessComment = findFirstBusinessComment(taskComments);
        if (businessComment != null) {
            try {
                CommentType ct = CommentType.valueOf(businessComment.getType());
                return CommentTypeConverter.toApprovalAction(ct);
            } catch (IllegalArgumentException ignored) {
                // 不会被触发（findFirstBusinessComment 已验证），仅为防御
            }
        }

        // 二级：操作注释特征提取（ADR-0025）
        // 仅加签/减签等操作、无业务意见的任务（如"仅加签、未投票"的活跃任务）仍能推断出 ADD_SIGN / DELETE_SIGN
        Comment operationComment = findFirstOperationComment(taskComments);
        if (operationComment != null) {
            try {
                CommentType ct = CommentType.valueOf(operationComment.getType());
                return CommentTypeConverter.toApprovalAction(ct);
            } catch (IllegalArgumentException ignored) {
                // DELEGATE / RESOLVE_DELEGATE 无 ApprovalAction 映射，跳过
            }
        }

        // 三级：DeleteReason 兜底
        if ("completed".equals(deleteReason)) {
            return ApprovalAction.AGREE;
        }
        if ("deleted".equals(deleteReason)) {
            // "deleted" 含义太宽泛（驳回/撤回/撤销/转办均删除任务），
            // 无法在无 Comment 时精确推断，返回 null 表示未知
            return null;
        }
        if (deleteReason != null && !deleteReason.isEmpty()) {
            // 其他非标准 deleteReason（如管理员强杀），标记为终止
            log.warn("未知 deleteReason: taskId={}, deleteReason={}", taskId, deleteReason);
            return ApprovalAction.TERMINATE;
        }

        // 三级默认：活跃节点（无 deleteReason，无结束时间），action 为 null
        return null;
    }

    @Override
    public Comment findFirstBusinessComment(List<Comment> taskComments) {
        if (taskComments == null || taskComments.isEmpty()) {
            return null;
        }
        // 第一遍：优先匹配 INITIATE_COUNTERSIGN（发起会签优先级最高）
        // 因为 addCounterSigner + addComment("INITIATE_COUNTERSIGN")
        // 在同一毫秒内完成是常见模式，需要显式优先匹配。
        for (Comment comment : taskComments) {
            if ("INITIATE_COUNTERSIGN".equals(comment.getType())) {
                return comment;
            }
        }
        // 第二遍：匹配业务意见组 CommentType（ADR-0025：跳过操作注释组）
        for (Comment comment : taskComments) {
            String typeStr = comment.getType();
            if (typeStr == null) {
                continue;
            }
            try {
                CommentType ct = CommentType.valueOf(typeStr);
                if (!OPERATION_COMMENT_TYPES.contains(ct)) {
                    return comment;
                }
            } catch (IllegalArgumentException ignored) {
                // 非业务类型，跳过
            }
        }
        return null;
    }

    @Override
    public Comment findFirstOperationComment(List<Comment> taskComments) {
        if (taskComments == null || taskComments.isEmpty()) {
            return null;
        }
        for (Comment comment : taskComments) {
            String typeStr = comment.getType();
            if (typeStr == null) {
                continue;
            }
            try {
                CommentType ct = CommentType.valueOf(typeStr);
                if (OPERATION_COMMENT_TYPES.contains(ct)) {
                    return comment;
                }
            } catch (IllegalArgumentException ignored) {
                // 非操作类型，跳过
            }
        }
        return null;
    }
}
