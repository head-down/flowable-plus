package io.github.flowable.plus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会签功能配置属性。
 *
 * <p>对应 {@code flowable.plus.counter-sign.*} 前缀的配置项。
 * 通过 {@code flowable.plus.counter-sign.enabled=false} 可单独关闭会签回调机制，
 * 不影响审批、驳回、撤回等基础功能。</p>
 *
 * @author flowable-plus
 */
@ConfigurationProperties(prefix = "flowable.plus.counter-sign")
public class FlowablePlusCounterSignProperties {

    /**
     * 是否启用会签功能，默认 true。
     *
     * <p>设置为 false 后，会签操作的 SPI 回调不再触发，但 counterSign、
     * addCounterSigner、removeCounterSigner 等核心方法仍可正常调用。</p>
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
