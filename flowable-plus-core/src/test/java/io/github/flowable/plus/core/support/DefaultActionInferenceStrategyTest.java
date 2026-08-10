package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.CommentType;
import org.flowable.engine.task.Comment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultActionInferenceStrategy} 的两遍扫描行为测试。
 *
 * <p>验证 ADD_SIGN 与 INITIATE_COUNTERSIGN 时间戳相同时，INITIATE_COUNTERSIGN 优先匹配；
 * ADR-0025 验证业务意见与操作注释分组解耦。
 *
 * @see <a href="https://github.com/head-down/flowable-plus/issues/63">Issue #63</a>
 * @see <a href="https://github.com/head-down/flowable-plus/issues/72">Issue #72</a>
 */
public class DefaultActionInferenceStrategyTest {

    private final DefaultActionInferenceStrategy strategy = new DefaultActionInferenceStrategy();

    @Test
    public void shouldReturnInitiateCountersignWhenAddSignAndInitiateCoexist() {
        Date sameTime = new Date();
        Comment addSign = createComment(CommentType.ADD_SIGN.name(), "加签内容", sameTime);
        Comment initiate = createComment(CommentType.INITIATE_COUNTERSIGN.name(), "发起会签", sameTime);
        List<Comment> comments = Arrays.asList(addSign, initiate);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result.getType()).isEqualTo(CommentType.INITIATE_COUNTERSIGN.name());
        assertThat(result.getFullMessage()).isEqualTo("发起会签");
    }

    @Test
    public void shouldReturnInitiateCountersignEvenWhenLastInList() {
        Date sameTime = new Date();
        Comment addSign = createComment(CommentType.ADD_SIGN.name(), "加签", sameTime);
        Comment agree = createComment(CommentType.AGREE.name(), "同意", sameTime);
        Comment initiate = createComment(CommentType.INITIATE_COUNTERSIGN.name(), "发起会签", sameTime);
        List<Comment> comments = Arrays.asList(addSign, agree, initiate);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result.getType()).isEqualTo(CommentType.INITIATE_COUNTERSIGN.name());
    }

    @Test
    public void shouldNotReturnAddSignAsBusinessCommentWhenOnlyAddSignExists() {
        Comment addSign = createComment(CommentType.ADD_SIGN.name(), "加签", new Date());
        List<Comment> comments = Collections.singletonList(addSign);

        // ADR-0025：ADD_SIGN 属于操作注释组，不参与 comment 槽位竞争
        assertThat(strategy.findFirstBusinessComment(comments)).isNull();
        // 但通过 findFirstOperationComment 可识别，action 推断仍为 ADD_SIGN
        Comment operationComment = strategy.findFirstOperationComment(comments);
        assertThat(operationComment).isNotNull();
        assertThat(operationComment.getType()).isEqualTo(CommentType.ADD_SIGN.name());
        assertThat(strategy.inferAction("t1", null, comments)).isEqualTo(ApprovalAction.ADD_SIGN);
    }

    @Test
    public void shouldReturnBusinessCommentWhenBusinessAndOperationCommentCoexist() {
        // ADR-0025：业务意见（时间早）+ ADD_SIGN（时间新）并存 → comment 槽位取业务意见
        Date earlyTime = new Date();
        Date lateTime = new Date(earlyTime.getTime() + 1000);
        Comment agree = createComment(CommentType.COUNTER_SIGN_AGREE.name(), "同意", earlyTime);
        Comment addSign = createComment(CommentType.ADD_SIGN.name(), "加签审批人: 003162", lateTime);
        List<Comment> comments = Arrays.asList(addSign, agree);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result.getType()).isEqualTo(CommentType.COUNTER_SIGN_AGREE.name());
        assertThat(result.getFullMessage()).isEqualTo("同意");
        // 操作注释独立可取
        Comment operationComment = strategy.findFirstOperationComment(comments);
        assertThat(operationComment).isNotNull();
        assertThat(operationComment.getType()).isEqualTo(CommentType.ADD_SIGN.name());
        // action 推断业务意见优先（任务真实投票动作），加签由 operationComment 承载
        assertThat(strategy.inferAction("t1", null, comments)).isEqualTo(ApprovalAction.COUNTER_SIGN_AGREE);
    }

    @Test
    public void shouldInferDeleteSignWhenOnlyDeleteSignExists() {
        Comment deleteSign = createComment(CommentType.DELETE_SIGN.name(), "移除审批人: 003162", new Date());
        List<Comment> comments = Collections.singletonList(deleteSign);

        assertThat(strategy.findFirstBusinessComment(comments)).isNull();
        Comment operationComment = strategy.findFirstOperationComment(comments);
        assertThat(operationComment).isNotNull();
        assertThat(strategy.inferAction("t1", null, comments)).isEqualTo(ApprovalAction.DELETE_SIGN);
    }

    @Test
    public void shouldReturnBusinessCommentWhenBusinessAndDeleteSignCoexist() {
        // ADR-0025：业务意见（时间早）+ DELETE_SIGN（时间新）并存 → comment 槽位取业务意见，action 取业务投票动作
        Date earlyTime = new Date();
        Date lateTime = new Date(earlyTime.getTime() + 1000);
        Comment reject = createComment(CommentType.COUNTER_SIGN_REJECT.name(), "不同意", earlyTime);
        Comment deleteSign = createComment(CommentType.DELETE_SIGN.name(), "移除审批人: 003162", lateTime);
        List<Comment> comments = Arrays.asList(deleteSign, reject);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result.getType()).isEqualTo(CommentType.COUNTER_SIGN_REJECT.name());
        assertThat(result.getFullMessage()).isEqualTo("不同意");
        Comment operationComment = strategy.findFirstOperationComment(comments);
        assertThat(operationComment).isNotNull();
        assertThat(operationComment.getType()).isEqualTo(CommentType.DELETE_SIGN.name());
        assertThat(strategy.inferAction("t1", null, comments)).isEqualTo(ApprovalAction.COUNTER_SIGN_REJECT);
    }

    @Test
    public void shouldReturnOtherCommentTypeNormally() {
        Comment agree = createComment(CommentType.AGREE.name(), "同意", new Date());
        List<Comment> comments = Collections.singletonList(agree);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result.getType()).isEqualTo(CommentType.AGREE.name());
    }

    @Test
    public void shouldReturnNullForNoBusinessComment() {
        Comment normalMsg = createComment("comment", "普通留言", new Date());
        List<Comment> comments = Collections.singletonList(normalMsg);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result).isNull();
    }

    @Test
    public void shouldReturnNullForEmptyOrNullList() {
        assertThat(strategy.findFirstBusinessComment(null)).isNull();
        assertThat(strategy.findFirstBusinessComment(new ArrayList<>())).isNull();
        assertThat(strategy.findFirstOperationComment(null)).isNull();
        assertThat(strategy.findFirstOperationComment(new ArrayList<>())).isNull();
        assertThat(strategy.findAllOperationComments(null)).isEmpty();
        assertThat(strategy.findAllOperationComments(new ArrayList<>())).isEmpty();
    }

    // ======================== ADR-0027：全部操作注释 findAllOperationComments ========================

    @Test
    public void shouldReturnAllOperationCommentsInAscendingTimeOrder() {
        // 同一任务连续两次加签（时间正序入参为 ADD_SIGN1 < ADD_SIGN2，倒序入参为 ADD_SIGN2, ADD_SIGN1）
        Date firstAddTime = new Date(1000);
        Date secondAddTime = new Date(2000);
        Comment addSign1 = createComment(CommentType.ADD_SIGN.name(), "加签审批人: 003161", firstAddTime);
        Comment addSign2 = createComment(CommentType.ADD_SIGN.name(), "加签审批人: 003162", secondAddTime);
        // 入参按现有约定为时间倒序
        List<Comment> comments = Arrays.asList(addSign2, addSign1);

        List<Comment> result = strategy.findAllOperationComments(comments);

        // 返回时间正序（最早在前），两次加签都保留
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFullMessage()).isEqualTo("加签审批人: 003161");
        assertThat(result.get(1).getFullMessage()).isEqualTo("加签审批人: 003162");
        assertThat(strategy.findFirstOperationComment(comments).getFullMessage()).isEqualTo("加签审批人: 003162");
    }

    @Test
    public void shouldMixMultipleOperationTypesInAscendingTimeOrder() {
        // 委派-收回委派循环：DELEGATE → RESOLVE_DELEGATE（时间正序）
        Date delegateTime = new Date(1000);
        Date resolveTime = new Date(2000);
        Comment delegate = createComment(CommentType.DELEGATE.name(), "委派给: userB", delegateTime);
        Comment resolve = createComment(CommentType.RESOLVE_DELEGATE.name(), "收回委派", resolveTime);
        // 倒序入参
        List<Comment> comments = Arrays.asList(resolve, delegate);

        List<Comment> result = strategy.findAllOperationComments(comments);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFullMessage()).isEqualTo("委派给: userB");
        assertThat(result.get(1).getFullMessage()).isEqualTo("收回委派");
    }

    @Test
    public void shouldReturnOnlyOperationCommentsWhenBusinessCommentsExist() {
        // 业务意见（时间早）+ 两条 ADD_SIGN（时间新）并存 → 只返回操作注释，不含业务意见
        Date agreeTime = new Date(1000);
        Date addSign1Time = new Date(2000);
        Date addSign2Time = new Date(3000);
        Comment agree = createComment(CommentType.COUNTER_SIGN_AGREE.name(), "同意", agreeTime);
        Comment addSign1 = createComment(CommentType.ADD_SIGN.name(), "加签审批人: 003161", addSign1Time);
        Comment addSign2 = createComment(CommentType.ADD_SIGN.name(), "加签审批人: 003162", addSign2Time);
        // 入参按接口约定为时间倒序
        List<Comment> comments = Arrays.asList(addSign2, addSign1, agree);

        List<Comment> result = strategy.findAllOperationComments(comments);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFullMessage()).isEqualTo("加签审批人: 003161");
        assertThat(result.get(1).getFullMessage()).isEqualTo("加签审批人: 003162");
    }

    @Test
    public void shouldReturnEmptyListWhenNoOperationComment() {
        Comment agree = createComment(CommentType.AGREE.name(), "同意", new Date());
        Comment normalMsg = createComment("comment", "普通留言", new Date());
        List<Comment> comments = Arrays.asList(normalMsg, agree);

        assertThat(strategy.findAllOperationComments(comments)).isEmpty();
    }

    @Test
    public void shouldReturnEmptyListForCommentWithNullType() {
        Comment commentWithNullType = createComment(null, "无类型", new Date());
        List<Comment> comments = Collections.singletonList(commentWithNullType);

        assertThat(strategy.findAllOperationComments(comments)).isEmpty();
    }

    @Test
    public void shouldReturnNullForCommentWithNullType() {
        Comment commentWithNullType = createComment(null, "无类型", new Date());
        List<Comment> comments = Collections.singletonList(commentWithNullType);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result).isNull();
        assertThat(strategy.findFirstOperationComment(comments)).isNull();
    }

    private static Comment createComment(String type, String fullMessage, Date time) {
        Comment comment = mock(Comment.class);
        when(comment.getType()).thenReturn(type);
        when(comment.getFullMessage()).thenReturn(fullMessage);
        when(comment.getTime()).thenReturn(time);
        return comment;
    }
}
