package io.github.flowable.plus.core.spi;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 身份解析器，将用户 ID 映射为用户名称。
 *
 * <p>默认兜底实现为 userId→userId（直接返回用户 ID 作为名称），
 * 应用可通过声明同名 Bean 替换为自定义身份服务
 * （如查询用户表、LDAP、第三方用户中心等）。</p>
 *
 * <p>示例自定义实现：</p>
 * <pre>{@code
 * @Bean
 * public IdentityResolver ldapIdentityResolver(LdapService ldap) {
 *     return userId -> ldap.findDisplayName(userId);
 * }
 * }</pre>
 *
 * @author flowable-plus
 * @since 1.0
 */
@FunctionalInterface
public interface IdentityResolver {

    /**
     * 根据用户 ID 解析用户名称。
     *
     * @param userId 用户 ID
     * @return 用户名称，若无法解析则建议返回 userId 本身作为兜底
     */
    String resolve(String userId);

    /**
     * 批量解析用户名称。
     *
     * <p>默认实现为逐个调用 {@link #resolve(String)}，
     * 实现类可覆写以提供批量查询优化（如一次 SQL 查多个用户）。</p>
     *
     * @param userIds 用户 ID 集合
     * @return userId→userName 的映射，key 为传入的 userId
     */
    default Map<String, String> resolveBatch(Collection<String> userIds) {
        Map<String, String> result = new HashMap<>();
        if (userIds != null) {
            for (String userId : userIds) {
                result.put(userId, resolve(userId));
            }
        }
        return result;
    }
}
