package io.github.flowable.plus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 决策接入的全局配置属性（ADR-0042 §7「全局默认面与 fail-closed」）。
 *
 * <p><b>十二键冻结</b>（{@code docs/impl/0042-module-and-build.md} §2.4）：全局开关 + 两个出站超时 +
 * 总预算 + 尝试次数 + 退避三项（点分族 {@code backoff.*}）+ 池四项（点分族 {@code executor.*}）。
 * 字段集<b>恒等</b>这十二键 —— 护栏阈值（读写护栏 / 载荷 clamp）<b>不进配置面</b>（G5）。</p>
 *
 * <p><b>全局开关不是条件装配语义</b>：{@code enabled} 是运行期门控（拉面闸门链 stage 1 / 位点服务入口），
 * <b>不参与装配条件</b> —— extension 存在时机制 Bean 一律注册，故<b>不写</b> {@code matchIfMissing}。
 * 这与主仓既有开关「默认 {@code true} + {@code matchIfMissing = true}」是<b>有意分歧</b>。</p>
 *
 * <p><b>B2 单一数值承载位</b>：数值字段初始化器<b>直接引用</b> {@link DecisionGuardrails} 常量，
 * 本类不重复写数字；构造时由装配代码 {@code Math.min(可设值, 硬上限)} 收口。
 * <b>已被接受的代价</b>：配置元数据可能缺 {@code defaultValue}（不影响绑定行为）。</p>
 */
@ConfigurationProperties("flowable.plus.decision")
public class FlowablePlusDecisionProperties {

    /** 全局启用开关（部署期配置状态；默认 {@code false} —— 默认关闭） */
    private boolean enabled = false;

    /** 每次尝试的出站连接超时（毫秒） */
    private long outboundConnectTimeout = DecisionGuardrails.OUTBOUND_CONNECT_TIMEOUT_MS;

    /** 每次尝试的出站读取超时（毫秒） */
    private long outboundReadTimeout = DecisionGuardrails.OUTBOUND_READ_TIMEOUT_MS;

    /** 整次决策总预算（毫秒，含全部重试，绑定实例存活期） */
    private long totalBudget = DecisionGuardrails.TOTAL_BUDGET_MS;

    /** 尝试次数上限 */
    private int maxAttempts = DecisionGuardrails.MAX_ATTEMPTS;

    /** 退避三项（点分族 {@code backoff.*}） */
    private final BackoffProperties backoff = new BackoffProperties();

    /** 专属有界池四项（点分族 {@code executor.*}） */
    private final ExecutorProperties executor = new ExecutorProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getOutboundConnectTimeout() {
        return outboundConnectTimeout;
    }

    public void setOutboundConnectTimeout(long outboundConnectTimeout) {
        this.outboundConnectTimeout = outboundConnectTimeout;
    }

    public long getOutboundReadTimeout() {
        return outboundReadTimeout;
    }

    public void setOutboundReadTimeout(long outboundReadTimeout) {
        this.outboundReadTimeout = outboundReadTimeout;
    }

    public long getTotalBudget() {
        return totalBudget;
    }

    public void setTotalBudget(long totalBudget) {
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

    public ExecutorProperties getExecutor() {
        return executor;
    }

    /**
     * 退避三项（{@code backoff.initial} / {@code backoff.multiplier} / {@code backoff.max}）。
     *
     * <p>嵌套 {@code public static} 类是主仓<b>首例</b>（如实登记，{@code module-and-build} §2.4）；
     * 点分族本身已排除「拍平」；取 {@code …Properties} 词尾以避与 JDK
     * {@code java.util.concurrent.Executor} 同名遮蔽（该词尾纪律只约束外层可见面，本类同族）。</p>
     */
    public static class BackoffProperties {

        /** 退避初值（毫秒） */
        private long initial = DecisionGuardrails.BACKOFF_INITIAL_MS;

        /** 退避倍数（比值，{@code double} —— {@code int} 会永久排掉 {@code 1.5} 一类取值） */
        private double multiplier = DecisionGuardrails.BACKOFF_MULTIPLIER;

        /** 退避上限（毫秒） */
        private long max = DecisionGuardrails.BACKOFF_MAX_MS;

        public long getInitial() {
            return initial;
        }

        public void setInitial(long initial) {
            this.initial = initial;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMax() {
            return max;
        }

        public void setMax(long max) {
            this.max = max;
        }
    }

    /**
     * 专属有界池四项（{@code executor.core-size} / {@code executor.max-size} /
     * {@code executor.queue-capacity} / {@code executor.thread-name-prefix}）。
     */
    public static class ExecutorProperties {

        /** 专属池核心线程数 */
        private int coreSize = DecisionGuardrails.EXECUTOR_CORE_SIZE;

        /** 专属池最大线程数 */
        private int maxSize = DecisionGuardrails.EXECUTOR_MAX_SIZE;

        /** 专属池队列容量 */
        private int queueCapacity = DecisionGuardrails.EXECUTOR_QUEUE_CAPACITY;

        /** 专属池线程名前缀（字符串无上限语义，不入 {@link DecisionGuardrails}，B1） */
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
