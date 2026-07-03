package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.spi.UserContext;

/**
 * 基于系统属性的 {@link UserContext} 兜底实现。
 *
 * <p>当 classpath 上不存在 Spring Security 且未自定义 UserContext Bean 时生效。
 * 从系统属性 {@code flowable.plus.user-id} 读取当前用户 ID，
 * 未设置时返回 {@code "system"}。</p>
 *
 * <p>生产环境应自行注入认证框架对应的 UserContext 实现。</p>
 *
 * @author flowable-plus
 */
public class SystemPropertyUserContext implements UserContext {

    @Override
    public String getCurrentUserId() {
        String userId = System.getProperty("flowable.plus.user-id");
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }
        return "system";
    }
}
