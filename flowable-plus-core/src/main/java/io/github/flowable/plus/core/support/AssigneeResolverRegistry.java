package io.github.flowable.plus.core.support;

import java.util.Collections;
import java.util.List;

/**
 * 审批人解析注册表，用于收集 SPI {@link io.github.flowable.plus.core.spi.ApproverResolver} 实现。
 *
 * <p>当前为空壳实现，{@link #resolve(String, String)} 永远返回空列表。
 * 后续 Ticket 将注入 SPI 实现，支持自动重建会签时获取新审批人列表。</p>
 *
 * @author flowable-plus
 */
public class AssigneeResolverRegistry {

    /**
     * 解析指定节点的审批人列表。
     *
     * <p>当前返回空列表，后续通过收集 {@link io.github.flowable.plus.core.spi.ApproverResolver}
     * Bean 提供实际审批人解析能力。</p>
     *
     * @param processDefinitionId 流程定义 ID
     * @param targetActivityId    目标节点 ID
     * @return 空列表（等待后续 Ticket 实现）
     */
    public List<String> resolve(String processDefinitionId, String targetActivityId) {
        return Collections.emptyList();
    }
}
