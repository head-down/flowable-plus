package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.enums.ApprovalAction;
import org.flowable.engine.task.Comment;

import java.util.List;

/**
 * Comment → Action 三级推断策略接口（ADR-0009）。
 *
 * <p>将 Flowable 底层 {@link Comment} 数据（CommentType + DeleteReason）统一推断为
 * 展示层 {@link ApprovalAction}，供 {@code HistoryWorkflow} 和 {@code ProcessQueryWorkflow}
 * 一致消费。</p>
 *
 * @author flowable-plus
 * @since 1.0
 */
public interface ActionInferenceStrategy {

    /**
     * 三级 Comment→Action 推断策略（ADR-0009）：
     * <ol>
     *   <li>特征提取：按 Comment 时间倒序扫描，取第一个匹配 {@code CommentType} 的值</li>
     *   <li>DeleteReason 兜底：无匹配 Comment 时，读取 deleteReason 语义</li>
     *   <li>活跃节点：无 deleteReason 时返回 null</li>
     * </ol>
     *
     * <p><b>不处理 START 动作</b>——START 由 startEvent + startUserId 构造，不经过此方法。</p>
     *
     * @param taskId        任务 ID（仅用于日志）
     * @param deleteReason  历史任务的 deleteReason，活跃节点为 null
     * @param taskComments  该任务的 Comment 列表，按时间倒序排列
     * @return 推断出的 ApprovalAction，活跃节点返回 null
     */
    ApprovalAction inferAction(String taskId, String deleteReason, List<Comment> taskComments);

    /**
     * 从 Comment 列表中查找第一个业务 Comment（ADR-0025）。
     *
     * <p>跳过非业务类型（如普通留言）与操作注释类型（ADD_SIGN / DELETE_SIGN / DELEGATE /
     * RESOLVE_DELEGATE / TRANSFER），仅返回承载审批人业务投票语义的 Comment
     * （AGREE / REJECT / COUNTER_SIGN_AGREE / COUNTER_SIGN_REJECT 等）。</p>
     *
     * @param taskComments 该任务的 Comment 列表，按时间倒序排列
     * @return 第一个匹配的业务 Comment，如果没有则返回 null
     */
    Comment findFirstBusinessComment(List<Comment> taskComments);

    /**
     * 从 Comment 列表中查找第一个操作注释（ADR-0025）。
     *
     * <p>操作注释（ADD_SIGN / DELETE_SIGN / TRANSFER 等）不参与 {@code comment} 槽位竞争，
     * 仅供 {@link #inferAction} 识别加签/减签等操作动作，并供上层展示操作信息。</p>
     *
     * @param taskComments 该任务的 Comment 列表，按时间倒序排列
     * @return 第一个匹配的操作注释 Comment，如果没有则返回 null
     */
    Comment findFirstOperationComment(List<Comment> taskComments);
}
