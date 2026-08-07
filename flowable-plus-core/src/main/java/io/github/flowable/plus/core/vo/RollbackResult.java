package io.github.flowable.plus.core.vo;

import org.springframework.lang.Nullable;
import java.util.List;

/**
 * 回退策略的判断结果，描述目标节点和回退方式。
 *
 * <p>三种结果类型对应三个工厂方法：
 * <ul>
 *   <li>{@link #direct(String)} — 目标节点直接可用，无需特殊处理</li>
 *   <li>{@link #redirect(String, String)} — 目标节点为 MI 节点，需重定向至前置单例节点</li>
 *   <li>{@link #rebuild(String, List)} — 目标节点为 MI 节点，需原地重建多实例</li>
 * </ul>
 *
 * @author flowable-plus
 */
public class RollbackResult {

    /** 最终回退目标节点 ID */
    private final String targetActivityId;

    /** 重定向说明消息（redirect 场景），非重定向时为 null */
    @Nullable
    private final String redirectMessage;

    /** 原地重建的新审批人列表（rebuild 场景），非重建时为 null */
    @Nullable
    private final List<String> newAssigneeList;

    private RollbackResult(String targetActivityId, @Nullable String redirectMessage,
                           @Nullable List<String> newAssigneeList) {
        this.targetActivityId = targetActivityId;
        this.redirectMessage = redirectMessage;
        this.newAssigneeList = newAssigneeList;
    }

    /**
     * 直接回退：目标节点无需特殊处理，直接跳转即可。
     *
     * @param targetActivityId 目标节点 ID
     * @return RollbackResult
     */
    public static RollbackResult direct(String targetActivityId) {
        return new RollbackResult(targetActivityId, null, null);
    }

    /**
     * 重定向回退：目标节点为 MI 节点时，自动重定向至前置单例节点。
     *
     * @param targetActivityId 重定向后的目标节点 ID
     * @param message          重定向说明消息
     * @return RollbackResult
     */
    public static RollbackResult redirect(String targetActivityId, String message) {
        return new RollbackResult(targetActivityId, message, null);
    }

    /**
     * 原地重建回退：目标节点为 MI 节点时，获取新审批人列表原地重建多实例。
     *
     * @param targetActivityId 目标节点 ID
     * @param newAssigneeList  新审批人列表
     * @return RollbackResult
     */
    public static RollbackResult rebuild(String targetActivityId, List<String> newAssigneeList) {
        return new RollbackResult(targetActivityId, null, newAssigneeList);
    }

    public String getTargetActivityId() {
        return targetActivityId;
    }

    @Nullable
    public String getRedirectMessage() {
        return redirectMessage;
    }

    @Nullable
    public List<String> getNewAssigneeList() {
        return newAssigneeList;
    }
}
