package io.github.flowable.plus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for flowable-plus.
 *
 * @author flowable-plus
 */
@ConfigurationProperties(prefix = "flowable.plus")
public class FlowablePlusProperties {

    /**
     * Whether to enable flowable-plus enhancement features.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
