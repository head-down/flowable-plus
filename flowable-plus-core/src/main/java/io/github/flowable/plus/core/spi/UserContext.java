package io.github.flowable.plus.core.spi;

/**
 * 用户上下文 SPI，用于获取当前操作用户 ID。
 *
 * <p>由应用层实现，框架通过此接口获取当前用户身份，
 * 用于权限校验、操作日志记录等场景。</p>
 *
 * <p>这是一个 {@link FunctionalInterface}，
 * 允许通过 lambda 表达式快速提供实现。</p>
 */
@FunctionalInterface
public interface UserContext {

    /**
     * 获取当前操作用户的 ID。
     *
     * @return 当前��户 ID，不可为 null
     */
    String getCurrentUserId();
}
