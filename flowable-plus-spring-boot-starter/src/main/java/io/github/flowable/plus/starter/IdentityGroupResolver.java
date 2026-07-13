package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.spi.GroupResolver;
import org.flowable.engine.IdentityService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Flowable IdentityService 的候选组解析器默认实现。
 *
 * <p>通过 {@code IdentityService.createUserQuery().memberOfGroup(groupId)}
 * 查询组成员列表。应用可通过声明同名 Bean 替换为自定义组织架构服务。</p>
 *
 * @author flowable-plus
 */
public class IdentityGroupResolver implements GroupResolver {

    private final IdentityService identityService;

    public IdentityGroupResolver(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public List<String> getGroupMembers(String groupId) {
        return identityService.createUserQuery()
                .memberOfGroup(groupId)
                .list()
                .stream()
                .map(org.flowable.idm.api.User::getId)
                .collect(Collectors.toList());
    }
}
