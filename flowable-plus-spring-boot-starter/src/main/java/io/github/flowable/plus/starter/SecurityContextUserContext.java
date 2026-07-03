package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.spi.UserContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 基于 Spring Security 的 {@link UserContext} 默认实现。
 *
 * <p>从 {@link SecurityContextHolder} 中读取当前认证用户 ID。
 * 当无认证信息时返回 {@code "anonymous"}。</p>
 *
 * <p>应用可通过声明同名 Bean 覆盖此默认实现，
 * 适配其他认证框架（如 Shiro、自定义 Token 等）。</p>
 *
 * @author flowable-plus
 */
public class SecurityContextUserContext implements UserContext {

    @Override
    public String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "anonymous";
        }
        return auth.getName();
    }
}
