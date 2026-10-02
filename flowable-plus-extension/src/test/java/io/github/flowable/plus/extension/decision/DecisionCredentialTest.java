package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E16 —— 凭据材料与凭据失效的折叠（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E16}）。
 *
 * <p><b>承哪些推入项</b>：{@code #36} 不透明类型三约束（<b>无有效 {@code toString()}</b> /
 * <b>无状态提取方法</b> / <b>不实现序列化契约</b>）+ 解析侧任何失败<b>折叠进</b>
 * {@code OUTBOUND_CREDENTIAL_INVALID}（与 Transport 的 401 / 403 同一枚举）。</p>
 *
 * <p><b>「无状态提取方法」的判据面（一处必须写明）</b>：约束的对象是<b>公开面</b> —— 类型系统性质防的是
 * 应用代码与序列化面（「一行 {@code log.info("failed {}", cred)} 就漏」），而框架自有的默认 Transport
 * 与凭据同包，其请求装饰走包内转交点（见 {@link DecisionCredential} 的 javadoc）。故本断言的扫描面 =
 * 公开方法（{@code getMethods()}）与公开字段，包内成员不计入公开提取面。</p>
 */
class DecisionCredentialTest {

    /** 凭据材料哨兵：只出现在凭据侧，绝不得出现在公开面 / 载荷 / 响应字段里 */
    private static final String CREDENTIAL_SENTINEL = "CREDENTIAL-MATERIAL-MUST-NOT-LEAK-1";

    /** 公开面的全部方法（{@code of} 工厂 + {@code toString} 掩码；无任何取材料的方法） */
    private static final List<String> EXPECTED_PUBLIC_METHODS = Arrays.asList("of", "toString");

    /** 凭据失效的两处入口状态（折叠的靶心） */
    private static final List<Integer> CREDENTIAL_REJECT_STATUSES = Arrays.asList(401, 403);

    @Test
    @DisplayName("无有效 toString()：固定脱敏、不随材料变化")
    void hasNoMeaningfulToString() {
        final DecisionCredential credential = DecisionCredential.of(CREDENTIAL_SENTINEL);
        assertThat(credential.toString())
                .as("脱敏形态不得包含材料")
                .doesNotContain(CREDENTIAL_SENTINEL);
        assertThat(DecisionCredential.of("another-material").toString())
                .as("不同材料给出同一脱敏形态 ⇒ toString 不承载任何可推断信息")
                .isEqualTo(credential.toString());
    }

    @Test
    @DisplayName("无状态提取方法：公开面没有任何取出材料的成员")
    void exposesNoStateExtractionMethod() {
        final List<String> publicMethodNames = Arrays.stream(DecisionCredential.class.getMethods())
                .filter(method -> method.getDeclaringClass() == DecisionCredential.class)
                .map(Method::getName)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        assertThat(publicMethodNames)
                .as("公开面只有工厂与脱敏 toString（无 getter / 无提取器）")
                .containsExactlyElementsOf(EXPECTED_PUBLIC_METHODS);
        assertThat(DecisionCredential.class.getFields())
                .as("公开字段同样是提取面，不得存在")
                .isEmpty();
        assertThat(Arrays.stream(DecisionCredential.class.getMethods())
                .filter(method -> method.getDeclaringClass() == DecisionCredential.class)
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0)
                .map(Method::getName)
                .collect(Collectors.toList()))
                .as("零参实例方法只有 toString（有 getter 即可一行取出材料）")
                .containsExactly("toString");
    }

    @Test
    @DisplayName("不实现序列化契约：不进 JDK 序列化、也不被 Jackson 序列化")
    void doesNotImplementSerializationContract() {
        assertThat(Serializable.class.isAssignableFrom(DecisionCredential.class))
                .as("不实现 JDK 序列化契约")
                .isFalse();
        assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(DecisionCredential.of(CREDENTIAL_SENTINEL)))
                .as("Jackson 无可序列化属性 ⇒ 凭据不可能成为载荷 / 日志 payload 的成员")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("解析侧失败折叠进 OUTBOUND_CREDENTIAL_INVALID：与 Transport 的 401 / 403 同一枚举")
    void resolverFailureCollapsesIntoCredentialInvalid() {
        // ① 解析器抛异常 ⇒ 折叠（不新增枚举），且出站调用不发生（凭据附着在调用之前）
        final StubDecisionTransport withThrowingResolver =
                new StubDecisionTransport(StubDecisionTransport.PRODUCED_FIXTURE);
        final DecisionProviderResponse resolutionFailed = new DefaultDecisionProvider(withThrowingResolver,
                targetKey -> {
                    throw new IllegalStateException("凭据解析失败（现场：目标不可用）");
                }).send(request());
        assertThat(resolutionFailed.getFailureKind())
                .as("解析侧任何失败折叠进同一取值")
                .isEqualTo(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID);
        assertThat(withThrowingResolver.getCallCount())
                .as("凭据解析失败 ⇒ 出站调用不发生")
                .isZero();

        // ② 401 与 ③ 403 ⇒ 同一取值（同 severity、同重试语义）
        for (final int status : CREDENTIAL_REJECT_STATUSES) {
            final StubDecisionTransport rejected = new StubDecisionTransport(status, "{}");
            assertThat(new DefaultDecisionProvider(rejected, null).send(request()).getFailureKind())
                    .as("Transport 的 %s 折叠进同一取值", status)
                    .isEqualTo(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID);
        }

        // ④ 解析器缺失 / 返回 null 是合法态：出站调用照常发生（由远端兜底）
        final StubDecisionTransport withoutCredential =
                new StubDecisionTransport(StubDecisionTransport.PRODUCED_FIXTURE);
        final DecisionProviderResponse delivered = new DefaultDecisionProvider(withoutCredential,
                targetKey -> null).send(request());
        assertThat(delivered.getFailureKind()).as("缺失凭据是合法态").isNull();
        assertThat(withoutCredential.getLastRequest().getCredential())
                .as("无凭据时请求不带凭据")
                .isNull();
    }

    /** 构造一份最小出站请求（决策目标 + 四段载荷）。 */
    private static DecisionProviderRequest request() {
        final DecisionTarget target = new DecisionTarget() {

            @Override
            public String key() {
                return DecisionFixtures.PROVIDER;
            }

            @Override
            public String url() {
                return "https://decision.example.invalid/v1/suggest";
            }
        };
        final DecisionPayload payload = new DecisionPayload(Collections.emptyMap(), Collections.emptyMap(),
                new TaskMetadata(DecisionFixtures.TASK_ID, "任务", DecisionFixtures.NODE_ID, null, null),
                new ProcessInstanceMetadata(DecisionFixtures.PROCESS_INSTANCE_ID, "definition-key-static",
                        null, null, null));
        return new DecisionProviderRequest(target, payload);
    }
}
