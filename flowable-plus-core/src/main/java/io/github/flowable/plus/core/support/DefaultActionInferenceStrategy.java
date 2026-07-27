package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.CommentType;
import io.github.flowable.plus.core.enums.CommentTypeConverter;
import org.flowable.engine.task.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
 * <p>迁移自 {@code HistoryWorkflow.inferAction} 和 {@code findFirstBusinessComment}。
 *
 * @author flowable-plus
 * @since 1.0
 */
public class DefaultActionInferenceStrategy implements ActionInferenceStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultActionInferenceStrategy.class);

    @Override
    public ApprovalAction inferAction(String taskId, String deleteReason, List<Comment> taskComments) {
        // 一级：特征提取（时间倒序扫描 Comment）
        Comment businessComment = findFirstBusinessComment(taskComments);
        if (businessComment != null) {
            try {
                CommentType ct = CommentType.valueOf(businessComment.getType());
                return CommentTypeConverter.toApprovalAction(ct);
            } catch (IllegalArgumentException ignored) {
                // 不会被触发（findFirstBusinessComment 已验证），仅为防御
            }
        }

        // 二级：DeleteReason 兜底
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
        for (Comment comment : taskComments) {
            String typeStr = comment.getType();
            if (typeStr != null) {
                try {
                    CommentType.valueOf(typeStr);
                    return comment;
                } catch (IllegalArgumentException ignored) {
                    // 非业务类型，跳过
                }
            }
        }
        return null;
    }
}
