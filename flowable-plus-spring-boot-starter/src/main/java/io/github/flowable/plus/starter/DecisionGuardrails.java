package io.github.flowable.plus.starter;

/**
 * 框架硬上限常量（AI 决策接入，ADR-0042 第 10 节「框架硬上限可由应用调低、不可放大」）。
 *
 * <p><b>住所与边界</b>（{@code docs/impl/0042-module-and-build.md} §2.4 附）：</p>
 *
 * <ul>
 *   <li><b>包级可见顶层类</b>（{@code final} + 私有构造），与 {@link FlowablePlusDecisionProperties} /
 *       两个配置类同包；不住公开面 ⇒ 消费者只有 starter 的装配代码（池 / 超时 / 退避 / 总预算的构造点），
 *       <b>extension 不得引用</b>（extension 零 Spring、不得读装配面配置），core 更不得；</li>
 *   <li><b>B1</b>：恰承载 {@code flowable.plus.decision.*} 的<b>十个数值键</b>的硬上限；
 *       不含 {@code enabled}（布尔开关无「上限」语义）、不含 {@code executor.thread-name-prefix}
 *       （字符串无上限）；</li>
 *   <li><b>B2</b>：单一数值承载位 —— 属性类的数值字段初始化器<b>直接引用</b>本类常量，属性类不重复写数字
 *       （已被接受的代价：配置元数据可能缺 {@code defaultValue}）；</li>
 *   <li><b>B3</b>：不跨界承载 —— core 的读写护栏常量与 extension 的载荷 clamp 上限<b>不住这里</b>
 *       （不同的事实面，不与配置面共用常量类）；</li>
 *   <li><b>B4</b>：无第二消费者 —— 不得成为任何公开 API 的默认值来源，只服务构造期收口。</li>
 * </ul>
 *
 * <p><b>数值唯一住所</b> = {@code docs/impl/0042-implementation-plan.md} §3.3；本类的值同时是
 * 对应配置键的硬上限与属性类字段的默认值（框架默认可调低、不可放大）。</p>
 *
 * <p><b>超上限行为</b>：{@code Math.min(可设值, 硬上限)} 静默收口 + 每个被收口的键一条启动期
 * {@code WARN}（记原值与上限），<b>不</b> fail-fast。</p>
 */
final class DecisionGuardrails {

    /** {@code outbound-connect-timeout} 的硬上限（毫秒） */
    static final long OUTBOUND_CONNECT_TIMEOUT_MS = 5_000L;

    /** {@code outbound-read-timeout} 的硬上限（毫秒） */
    static final long OUTBOUND_READ_TIMEOUT_MS = 30_000L;

    /** {@code total-budget} 的硬上限（毫秒） */
    static final long TOTAL_BUDGET_MS = 60_000L;

    /** {@code max-attempts} 的硬上限 */
    static final int MAX_ATTEMPTS = 3;

    /** {@code backoff.initial} 的硬上限（毫秒） */
    static final long BACKOFF_INITIAL_MS = 500L;

    /** {@code backoff.multiplier} 的硬上限（比值，{@code double}） */
    static final double BACKOFF_MULTIPLIER = 2.0D;

    /** {@code backoff.max} 的硬上限（毫秒） */
    static final long BACKOFF_MAX_MS = 5_000L;

    /** {@code executor.core-size} 的硬上限 */
    static final int EXECUTOR_CORE_SIZE = 2;

    /** {@code executor.max-size} 的硬上限 */
    static final int EXECUTOR_MAX_SIZE = 4;

    /** {@code executor.queue-capacity} 的硬上限 */
    static final int EXECUTOR_QUEUE_CAPACITY = 20;

    private DecisionGuardrails() {
    }
}
