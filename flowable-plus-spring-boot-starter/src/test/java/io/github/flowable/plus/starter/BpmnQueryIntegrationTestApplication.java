package io.github.flowable.plus.starter;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 集成测试最小 Spring Boot 配置，支持 Flowable 嵌入式引擎 + H2。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = FlowablePlusAutoConfiguration.class)
public class BpmnQueryIntegrationTestApplication {
}
