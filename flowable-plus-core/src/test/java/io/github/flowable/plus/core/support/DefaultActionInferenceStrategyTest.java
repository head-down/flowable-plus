package io.github.flowable.plus.core.support;

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
 * {@link DefaultActionInferenceStrategy#findFirstBusinessComment} 的两遍扫描行为测试。
 *
 * <p>验证 ADD_SIGN 与 INITIATE_COUNTERSIGN 时间戳相同时，INITIATE_COUNTERSIGN 优先匹配。
 *
 * @see <a href="https://github.com/head-down/flowable-plus/issues/63">Issue #63</a>
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
    public void shouldReturnAddSignWhenOnlyAddSignExists() {
        Comment addSign = createComment(CommentType.ADD_SIGN.name(), "加签", new Date());
        List<Comment> comments = Collections.singletonList(addSign);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result.getType()).isEqualTo(CommentType.ADD_SIGN.name());
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
    }

    @Test
    public void shouldReturnNullForCommentWithNullType() {
        Comment commentWithNullType = createComment(null, "无类型", new Date());
        List<Comment> comments = Collections.singletonList(commentWithNullType);

        Comment result = strategy.findFirstBusinessComment(comments);
        assertThat(result).isNull();
    }

    private static Comment createComment(String type, String fullMessage, Date time) {
        Comment comment = mock(Comment.class);
        when(comment.getType()).thenReturn(type);
        when(comment.getFullMessage()).thenReturn(fullMessage);
        when(comment.getTime()).thenReturn(time);
        return comment;
    }
}
