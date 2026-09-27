package io.github.flowable.plus.extension.decision;

/**
 * 运行暂停控制面（ADR-0042 第 10 节「资源保护与激活控制」）：机制<b>已激活</b>、但在运行期被暂止
 * 拉取的状态。
 *
 * <p><b>与「启用 / 禁用」严格分开</b>（铁律：禁用 = 无记录；暂停 = 有记录）：
 * 暂停的结局是「按政策未产出」（{@code policyReason = SUSPENDED}）⇒ <b>留记录</b>且<b>不计错误</b>；
 * 禁用是部署期配置状态 ⇒ 机制未激活、<b>不留任何记录</b>。</p>
 *
 * <p><b>状态由应用持有</b>：进程内内存态，框架<b>不持久化、不带管理端点、不读 Spring {@code Environment}</b>。
 * 应用在此之上自建熔断（框架只给计数信号 + 本暂停位）。</p>
 *
 * <p><b>控制面取控制方法、不取配置属性 key</b>：运行暂停是运行期动作，不属部署期配置面。</p>
 *
 * <p><b>命名</b>：判定方法取 {@code isPaused} 的<b>判定语义</b>，<b>不取</b> {@code …Status} /
 * {@code …State} 词尾（那是状态快照语义，与「查询判定」不是一件事）。</p>
 */
public interface DecisionRuntimeControl {

    /**
     * 暂止拉取（幂等；已暂停时再调不改结局）。
     */
    void pause();

    /**
     * 恢复拉取（幂等；未暂停时再调不改结局）。
     */
    void resume();

    /**
     * 查询当前是否处于暂停态。
     *
     * @return 暂停中返回 true
     */
    boolean isPaused();
}
