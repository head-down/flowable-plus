package io.github.flowable.plus.core.support;

import io.github.flowable.plus.core.spi.AssigneeResolver;

import java.util.Collections;
import java.util.List;

/**
 * 审批人解析注册表，用于收集 SPI {@link AssigneeResolver} 实现。
 *
 * <p>遍历所有已注册的 {@link AssigneeResolver} 实现，取第一个非空结果。
 * 无实现时返回空列表，触发 auto-rebuild 策略的降级路径。</p>
 *
 * @author flowable-plus
 */
public class AssigneeResolverRegistry {

    private final List<AssigneeResolver> resolvers;

    /**
     * 无 SPI 实现的兜底构造函数，resolve() 永远返回空列表。
     * 保留此构造函数以兼容单元测试场景。
     */
    public AssigneeResolverRegistry() {
        this.resolvers = Collections.emptyList();
    }

    /**
     * 注入 SPI 列表的构造函数，由自动配置调用。
     *
     * @param resolvers SPI 实现列表，可为 null
     */
    public AssigneeResolverRegistry(List<AssigneeResolver> resolvers) {
        this.resolvers = resolvers != null ? resolvers : Collections.emptyList();
    }

    /**
     * 解析指定节点的审批人列表。
     *
     * <p>按注册顺序依次调用每个 {@link AssigneeResolver}，
     * 返回第一个非空的审批人列表。无实现时返回空列表。</p>
     *
     * @param processInstanceId 流程实例 ID
     * @param targetActivityId  目标节点 ID
     * @return 审批人 ID 列表，空列表表示无可用审批人
     */
    public List<String> resolve(String processInstanceId, String targetActivityId) {
        for (AssigneeResolver resolver : resolvers) {
            List<String> assignees = resolver.resolveCountersignAssignees(processInstanceId, targetActivityId);
            if (assignees != null && !assignees.isEmpty()) {
                return assignees;
            }
        }
        return Collections.emptyList();
    }
}
