package io.github.flowable.plus.core.enums;

/**
 * CommentType → ApprovalAction 的静态转换工具类。
 *
 * <p>映射规则：
 * <ul>
 *   <li>一对一映射（AGREE, REJECT, WITHDRAW, REVOKE, COUNTER_SIGN_AGREE,
 *       COUNTER_SIGN_REJECT, ADD_SIGN, DELETE_SIGN, TRANSFER）</li>
 *   <li>RETURN → AGREE（退回在语义上等同于同意）</li>
 *   <li>AUTO_COMPLETE → AGREE（自动提交等同于同意）</li>
 *   <li>DELEGATE / RESOLVE_DELEGATE → 抛出 IllegalArgumentException</li>
 * </ul>
 */
public final class CommentTypeConverter {

    private CommentTypeConverter() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 将 CommentType 转换为展示层 ApprovalAction。
     *
     * @param commentType 内部评论类型，不能为 null
     * @return 对应的展示层 ApprovalAction
     * @throws IllegalArgumentException 如果 commentType 为 null 或无对应映射
     */
    public static ApprovalAction toApprovalAction(CommentType commentType) {
        if (commentType == null) {
            throw new IllegalArgumentException("commentType 不能为 null");
        }

        switch (commentType) {
            case AGREE:
                return ApprovalAction.AGREE;
            case REJECT:
                return ApprovalAction.REJECT;
            case RETURN:
                return ApprovalAction.AGREE;
            case WITHDRAW:
                return ApprovalAction.WITHDRAW;
            case REVOKE:
                return ApprovalAction.REVOKE;
            case COUNTER_SIGN_AGREE:
                return ApprovalAction.COUNTER_SIGN_AGREE;
            case COUNTER_SIGN_REJECT:
                return ApprovalAction.COUNTER_SIGN_REJECT;
            case ADD_SIGN:
                return ApprovalAction.ADD_SIGN;
            case DELETE_SIGN:
                return ApprovalAction.DELETE_SIGN;
            case TRANSFER:
                return ApprovalAction.TRANSFER;
            case AUTO_COMPLETE:
                return ApprovalAction.AGREE;
            case DELEGATE:
            case RESOLVE_DELEGATE:
            default:
                throw new IllegalArgumentException(
                        "CommentType " + commentType + " 没有对应的 ApprovalAction 映射");
        }
    }
}
