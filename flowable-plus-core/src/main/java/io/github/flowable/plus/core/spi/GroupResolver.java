package io.github.flowable.plus.core.spi;

import java.util.List;

/**
 * 候选组解析器，将候选组 ID 展开为组成员列表。
 *
 * <p>默认实现基于 Flowable IdentityService，用户可替换为自定义组织架构服务。</p>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface GroupResolver {

    /**
     * 根据组 ID 获取组成员用户 ID 列表。
     *
     * @param groupId 候选组 ID
     * @return 组成员用户 ID 列表，空组返回空列表
     */
    List<String> getGroupMembers(String groupId);
}
