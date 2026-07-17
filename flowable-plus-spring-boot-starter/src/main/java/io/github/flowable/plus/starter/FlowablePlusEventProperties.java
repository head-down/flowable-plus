package io.github.flowable.plus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件模块配置属性。
 *
 * @author flowable-plus
 */
@ConfigurationProperties(prefix = "flowable.plus.event")
public class FlowablePlusEventProperties {

    /** 是否启用事件发布，默认 true */
    private boolean enabled = true;

    /** 是否异步发布事件，默认 true */
    private boolean async = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }
}
