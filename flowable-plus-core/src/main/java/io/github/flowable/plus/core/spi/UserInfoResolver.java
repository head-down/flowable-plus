package io.github.flowable.plus.core.spi;

import io.github.flowable.plus.core.vo.UserInfo;

import java.util.Collection;
import java.util.Map;

/**
 * 用户信息解析器 SPI，将用户 ID 映射为完整用户信息（昵称、部门等）。
 *
 * <p>与 {@link IdentityResolver} 的差异：IdentityResolver 只做
 * userId→userName 单维字符串映射，本接口提供更丰富的用户画像字段。</p>
 *
 * <p>此为可选 SPI，应用未注入时 {@code getApprovalPersonnel} 返回的
 * PersonnelInfo 中 nickName/deptId/deptName 为 null（降级兼容）。</p>
 *
 * <p>示例自定义实现：</p>
 * <pre>{@code
 * @Bean
 * public UserInfoResolver userInfoResolver(UserService userService) {
 *     return userIds -> {
 *         List<SysUser> users = userService.listByIds(userIds);
 *         return users.stream().collect(Collectors.toMap(
 *             SysUser::getId,
 *             u -> UserInfo.builder()
 *                 .nickName(u.getNickName())
 *                 .deptId(u.getDeptId())
 *                 .deptName(u.getDeptName())
 *                 .build()
 *         ));
 *     };
 * }
 * }</pre>
 *
 * @author flowable-plus
 * @since 1.1
 */
@FunctionalInterface
public interface UserInfoResolver {

    /**
     * 批量查询用户信息。
     *
     * @param userIds 用户 ID 集合
     * @return userId→UserInfo 映射，不存在的 userId 可不出现在结果中
     */
    Map<String, UserInfo> resolveBatch(Collection<String> userIds);
}
