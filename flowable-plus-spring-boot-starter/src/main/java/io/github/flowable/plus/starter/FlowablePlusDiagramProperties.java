package io.github.flowable.plus.starter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 流程图生成配置属性。
 *
 * <p>对应 {@code flowable.plus.diagram.*} 前缀的配置项。
 * 默认使用"宋体"确保中文流程节点名称正常渲染，
 * 各应用可按需配置为系统实际安装的中文字体（如微软雅黑、思源黑体等）。</p>
 *
 * @author flowable-plus
 */
@Data
@ConfigurationProperties(prefix = "flowable.plus.diagram")
public class FlowablePlusDiagramProperties {

    /**
     * 活动节点字体名称，默认"宋体"。
     */
    private String activityFont = "宋体";

    /**
     * 标签/连线字体名称，默认"宋体"。
     */
    private String labelFont = "宋体";

    /**
     * 注解字体名称，默认"宋体"。
     */
    private String annotationFont = "宋体";
}
