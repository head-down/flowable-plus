package io.github.flowable.plus.core.spi;

import java.util.List;

/**
 * 会签审批人解析 SPI，供项目层注入自定义审批人获取逻辑。
 *
 * <p>当 {@code countersign-rollback-strategy=auto-rebuild} 时，
 * 引擎回调此接口获取新的会签审批人列表，原地重建多实例节点。
 * 未注册任何实现时，auto-rebuild 策略自动降级为 auto-redirect。</p>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface AssigneeResolver {

    /**
     * 解析指定会签节点的审批人列表。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskDefinitionKey 会签节点 definitionKey
     * @return 审批人 ID 列表，无审批人时返回空列表（不可为 null）
     */
    List<String> resolveCountersignAssignees(String processInstanceId, String taskDefinitionKey);
}
