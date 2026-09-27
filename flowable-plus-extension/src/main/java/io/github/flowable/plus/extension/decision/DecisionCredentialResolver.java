package io.github.flowable.plus.extension.decision;

/**
 * 凭据解析 SPI（ADR-0042 第 7 节「凭据位点」，<b>只服务默认 Provider</b>）：单一 Bean、按
 * {@code decisionTarget} 的 key 路由、<b>每次调用前实时解析、绝不缓存</b>。
 *
 * <p><b>缺失 = 合法</b>：返回 {@code null} 等价「本次调用不需要认证材料」，由远端 401 / 403 兜底。
 * 未提供实现（Bean 缺席）同样是合法装配态。</p>
 *
 * <p><b>解析侧失败折叠</b>：抛异常与返回不可用材料都不新增枚举 —— 统一折叠进
 * {@link io.github.flowable.plus.core.enums.DecisionFailureKind#OUTBOUND_CREDENTIAL_INVALID}
 * （与 Transport 返回的 401 / 403 同一取值、同 severity、同重试语义）。</p>
 *
 * <p><b>不缓存的理由</b>：缓存会让凭据轮换对框架不可见，而框架无权判断 TTL。</p>
 *
 * <p><b>应用一旦替换 Provider 缝，本 SPI 即不再被调用</b> —— 凭据的获取与注入完全由应用自建 Provider 自理
 * （框架既不解析、也不附着、更不感知）。</p>
 */
public interface DecisionCredentialResolver {

    /**
     * 按目标 key 实时解析凭据。
     *
     * @param decisionTargetKey 本次出站调用的决策目标 key
     * @return 凭据；无需认证材料时返回 {@code null}（合法）
     */
    DecisionCredential resolve(String decisionTargetKey);
}
