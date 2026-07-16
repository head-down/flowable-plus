package io.github.flowable.plus.core.spi;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdentityResolver SPI 接口单元测试。
 *
 * <p>验证 FunctionalInterface 语义、resolveBatch 默认行为
 * 以及极端边界情况。</p>
 *
 * @author flowable-plus
 */
class IdentityResolverTest {

    // ======================== FunctionalInterface 语义验证 ========================

    @Test
    void testLambdaExpression() {
        IdentityResolver resolver = userId -> "User_" + userId;
        assertThat(resolver.resolve("001")).isEqualTo("User_001");
    }

    @Test
    void testMethodReference() {
        IdentityResolver resolver = this::upperCaseResolve;
        assertThat(resolver.resolve("hello")).isEqualTo("HELLO");
    }

    @Test
    void testAnonymousClass() {
        IdentityResolver resolver = new IdentityResolver() {
            @Override
            public String resolve(String userId) {
                return "[" + userId + "]";
            }
        };
        assertThat(resolver.resolve("test")).isEqualTo("[test]");
    }

    @Test
    void testDefaultImplementationIdentity() {
        // 验证兜底实现：userId→userId
        IdentityResolver resolver = userId -> userId;
        assertThat(resolver.resolve("anyUser")).isEqualTo("anyUser");
        assertThat(resolver.resolve("admin")).isEqualTo("admin");
    }

    // ======================== resolveBatch 默认行为验证 ========================

    @Test
    void testResolveBatchDefaultBehavior() {
        IdentityResolver resolver = userId -> "Name_" + userId;
        Map<String, String> result = resolver.resolveBatch(Arrays.asList("1", "2", "3"));
        assertThat(result)
                .containsEntry("1", "Name_1")
                .containsEntry("2", "Name_2")
                .containsEntry("3", "Name_3")
                .hasSize(3);
    }

    @Test
    void testResolveBatchEmptyCollection() {
        IdentityResolver resolver = userId -> "Name_" + userId;
        Map<String, String> result = resolver.resolveBatch(Collections.emptyList());
        assertThat(result).isEmpty();
    }

    @Test
    void testResolveBatchNullCollection() {
        // 防御性：null 输入应返回空 Map 而非 NPE
        IdentityResolver resolver = userId -> "Name_" + userId;
        Map<String, String> result = resolver.resolveBatch(null);
        assertThat(result).isEmpty();
    }

    @Test
    void testResolveBatchSingleElement() {
        IdentityResolver resolver = userId -> "Name_" + userId;
        Map<String, String> result = resolver.resolveBatch(Collections.singletonList("only"));
        assertThat(result)
                .containsEntry("only", "Name_only")
                .hasSize(1);
    }

    // ======================== resolveBatch 调用 resolve 次数验证 ========================

    @Test
    void testResolveBatchInvokesResolveForEachElement() {
        AtomicInteger counter = new AtomicInteger(0);
        IdentityResolver resolver = userId -> {
            counter.incrementAndGet();
            return "User_" + userId;
        };
        resolver.resolveBatch(Arrays.asList("a", "b", "c"));

        assertThat(counter.get()).isEqualTo(3);
    }

    // ======================== 自定义 resolveBatch 覆写示例验证 ========================

    @Test
    void testCustomResolveBatchOverride() {
        // 模拟实现类覆写 resolveBatch 以一次批量查询完成所有映射
        IdentityResolver batchResolver = new IdentityResolver() {
            @Override
            public String resolve(String userId) {
                throw new UnsupportedOperationException("不应调用单个 resolve");
            }

            @Override
            public Map<String, String> resolveBatch(Collection<String> userIds) {
                // 模拟一次 SQL 查询返回所有结果
                return Collections.singletonMap("test", "TestUser");
            }
        };

        Map<String, String> result = batchResolver.resolveBatch(Collections.singletonList("test"));
        assertThat(result).containsEntry("test", "TestUser");
    }

    // ======================== 辅助方法 ========================

    private String upperCaseResolve(String userId) {
        return userId.toUpperCase();
    }
}
