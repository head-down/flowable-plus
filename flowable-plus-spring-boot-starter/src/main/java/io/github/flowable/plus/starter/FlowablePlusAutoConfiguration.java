package io.github.flowable.plus.starter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Flowable Plus auto-configuration.
 *
 * @author flowable-plus
 */
@Configuration
@ConditionalOnClass(name = "org.flowable.engine.ProcessEngine")
@EnableConfigurationProperties(FlowablePlusProperties.class)
public class FlowablePlusAutoConfiguration {

}
