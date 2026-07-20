package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.spi.UserContext;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 集成测试共享配置，提供 ThreadLocal 切换用户的 {@link UserContext} 实现。
 *
 * <p>所有集成测试通过 import 此配置避免多 @TestConfiguration 同名 Bean 冲突。</p>
 */
@TestConfiguration
public class SharedTestConfiguration {

    @Bean
    @Primary
    UserContext testUserContext() {
        return new BpmnQueryIntegrationTest.DynamicUserContext();
    }
}
