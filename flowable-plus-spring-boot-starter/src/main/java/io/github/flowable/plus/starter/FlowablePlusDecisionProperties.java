package io.github.flowable.plus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 决策接入机制的配置载体（ADR-0042 第 7 节「全局默认面与 fail-closed」）。
 *
 * <p><b>全部扁平非机密值</b>；框架硬上限<b>只能调低、不可放大</b>。</p>
 *
 * <p><b>全局启用开关不是条件装配语义</b> —— 它<b>不参与装配条件</b>（validator 始终生效），
 * 故<b>不写 {@code matchIfMissing}</b>；它就是本类的一个字段，初始化器 {@code = false}。
 * 与主仓既有开关「默认 {@code true} + {@code matchIfMissing = true}」是<b>有意分歧</b>。</p>
 *
 * <p><b>骨架说明</b>：① 嵌套静态类是主仓首例（无先例），取 {@code …Properties} 词尾以避与 JDK
 * {@code java.util.concurrent.Executor} 同名遮蔽；② 数值键的最终「硬上限收口」形态（属性类字段
 * 初始化器引用包内常量类 {@code DecisionGuardrails}）属实现期产物，本骨架直接写默认值 —— 见 #43 决议。</p>
 */
@ConfigurationProperties(prefix = "flowable.plus.decision")
public class FlowablePlusDecisionProperties {

    /** 全局启用开关（部署期配置状态；默认关 —— 与主仓 `matchIfMissing` 惯例有意分歧） */
    private boolean enabled = false;

    /** 每次尝试的出站连接超时 */
    private Duration outboundConnectTimeout = Duration.ofSeconds(5);

    /** 每次尝试的出站读取超时 */
    private Duration outboundReadTimeout = Duration.ofSeconds(30);

    /** 整次决策总预算（含全部重试，绑定实例存活期） */
    private Duration totalBudget = Duration.ofSeconds(60);

    /** 尝试次数上限 */
    private int maxAttempts = 3;

    /** 指数退避 */
    private BackoffProperties backoff = new BackoffProperties();

    /** 机制专属有界线程池 */
    private ExecutorProperties executor = new ExecutorProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getOutboundConnectTimeout() {
        return outboundConnectTimeout;
    }

    public void setOutboundConnectTimeout(Duration outboundConnectTimeout) {
        this.outboundConnectTimeout = outboundConnectTimeout;
    }

    public Duration getOutboundReadTimeout() {
        return outboundReadTimeout;
    }

    public void setOutboundReadTimeout(Duration outboundReadTimeout) {
        this.outboundReadTimeout = outboundReadTimeout;
    }

    public Duration getTotalBudget() {
        return totalBudget;
    }

    public void setTotalBudget(Duration totalBudget) {
        this.totalBudget = totalBudget;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public BackoffProperties getBackoff() {
        return backoff;
    }

    public void setBackoff(BackoffProperties backoff) {
        this.backoff = backoff;
    }

    public ExecutorProperties getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorProperties executor) {
        this.executor = executor;
    }

    /**
     * 退避参数（点分族 {@code backoff.*}）。
     */
    public static class BackoffProperties {

        /** 退避初值 */
        private Duration initial = Duration.ofMillis(500);

        /** 退避倍数 */
        private int multiplier = 2;

        /** 退避上限 */
        private Duration max = Duration.ofSeconds(5);

        public Duration getInitial() {
            return initial;
        }

        public void setInitial(Duration initial) {
            this.initial = initial;
        }

        public int getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(int multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMax() {
            return max;
        }

        public void setMax(Duration max) {
            this.max = max;
        }
    }

    /**
     * 专属线程池参数（点分族 {@code executor.*}）。
     *
     * <p>该池 = <b>本机制专属</b>（不复用事件执行器 —— 后者的 {@code CallerRunsPolicy} 会使事件回调
     * 可能在流程事务内执行，撞「事务外」定案）；拒绝策略 {@code AbortPolicy}，池满 ⇒ 日志 + 独立计数、
     * <b>不落证据行</b>。</p>
     */
    public static class ExecutorProperties {

        /** 核心线程数 */
        private int coreSize = 2;

        /** 最大线程数 */
        private int maxSize = 4;

        /** 队列容量 */
        private int queueCapacity = 20;

        /** 线程名前缀 */
        private String threadNamePrefix = "flowable-plus-decision-";

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
