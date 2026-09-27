package io.github.flowable.plus.extension.decision;

import java.util.Objects;

/**
 * 凭据材料（ADR-0042 第 7 节）：**不透明值类型** —— 防泄漏是<b>类型系统性质，不是纪律</b>。
 *
 * <p><b>三条设计约束</b>（守卫落点 = {@code E16}）：</p>
 *
 * <ul>
 *   <li><b>无有效 {@code toString()}</b> —— 固定脱敏，照抄进日志也拿不到材料；</li>
 *   <li><b>无状态提取方法</b> —— <b>公开面</b>没有任何可取出材料的访问器 / 字段 / 反序列化入口
 *       （应用以为 {@code credential.getSecret()} 一类调用在此类型上写不出来）；</li>
 *   <li><b>不实现序列化契约</b> —— 不进 Jackson / JDK 序列化，故不可能出现在证据载荷或日志 payload 里。</li>
 * </ul>
 *
 * <p><b>为何仍有包内转交点</b>：ADR-0042 第 7 节把凭据定义为「请求装饰载体，原样转交 Transport 缝」——
 * 可见面（应用 / 序列化 / 日志）是约束面，而框架自有的默认 Transport 与凭据住在<b>同一包</b>内，
 * 由包内可见的材料访问器完成实际的请求装饰（{@code HttpDecisionTransport}）。这条转交点
 * <b>不构成公开提取面</b>：应用自定义 Transport 拿到的仍是不透明对象，正如 ADR 所述
 * 「替换 Provider 缝后**同时失去框架侧凭据位点**」。</p>
 *
 * <p><b>凭据从不是载荷的一部分</b>：附着发生在装配与 clamp <b>之后</b>（结构保证）。</p>
 */
public final class DecisionCredential {

    /** 脱敏后的 {@code toString()} 固定形态（不含材料长度、不含任何可推断信息） */
    private static final String MASKED = "DecisionCredential[REDACTED]";

    /** 凭据材料（永不出现在公开面） */
    private final String material;

    private DecisionCredential(final String material) {
        this.material = Objects.requireNonNull(material, "凭据材料不得为 null");
    }

    /**
     * 由应用提供的原始材料构造凭据（<b>唯一公开构造入口</b>）。
     *
     * <p>构造即封口：材料进入本类型后，公开面再也取不回来。</p>
     *
     * @param material 凭据材料（如令牌原文），不得为 null
     * @return 不透明凭据
     */
    public static DecisionCredential of(final String material) {
        return new DecisionCredential(material);
    }

    /**
     * 包内转交点：把材料交给框架自有的默认 Transport 做请求装饰。
     *
     * <p><b>不属于公开面</b> —— 应用与序列化面都看不到它（E16 的提取面断言以公开成员为对象）。</p>
     *
     * @return 凭据材料
     */
    String material() {
        return material;
    }

    /**
     * 固定脱敏形态（防「一行 {@code log.info("failed {}", cred)} 即漏」）。
     *
     * @return 固定脱敏串
     */
    @Override
    public String toString() {
        return MASKED;
    }
}
