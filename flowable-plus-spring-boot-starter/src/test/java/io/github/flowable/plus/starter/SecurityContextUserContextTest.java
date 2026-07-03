package io.github.flowable.plus.starter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecurityContextUserContext} 单元测试：
 * 验证从 Spring Security 上下文读取用户 ID 的三种场景。
 */
class SecurityContextUserContextTest {

    private SecurityContextUserContext userContext;

    @BeforeEach
    void setUp() {
        userContext = new SecurityContextUserContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testReturnsAnonymousWhenNoAuthentication() {
        assertThat(userContext.getCurrentUserId()).isEqualTo("anonymous");
    }

    @Test
    void testReturnsAnonymousWhenNotAuthenticated() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(null, null));
        assertThat(userContext.getCurrentUserId()).isEqualTo("anonymous");
    }

    @Test
    void testReturnsUserIdWhenAuthenticated() {
        Authentication auth = new TestingAuthenticationToken("zhangsan", null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertThat(userContext.getCurrentUserId()).isEqualTo("zhangsan");
    }
}
