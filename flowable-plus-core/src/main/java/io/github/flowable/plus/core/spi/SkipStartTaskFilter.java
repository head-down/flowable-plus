package io.github.flowable.plus.core.spi;

import org.flowable.bpmn.model.UserTask;

import java.util.Map;

/**
 * 默认遍历过滤器：跳过 BPMN 中标记了 {@code flowable:isStartTask = "true"} 扩展属性的发起人节点。
 *
 * <p>该实现为 {@link UserTaskTraversalFilter} 的内建默认策略，通过 Spring 自动配置注册。
 * 若用户在 Spring 容器中声明了任意自定义 {@code UserTaskTraversalFilter} Bean，
 * 此默认实现将自动失效，由用户自定义的 Filter 完全接管过滤逻辑。</p>
 *
 * <h3>默认行为</h3>
 * <ul>
 *   <li>BPMN UserTask 的扩展属性 {@code flowable:isStartTask = "true"} → 跳过</li>
 *   <li>无该扩展属性或值为非 {@code "true"} → 正常收集</li>
 *   <li>不依赖 {@code variables} 参数，所有场景行为一致</li>
 * </ul>
 *
 * <h3>自定义替换</h3>
 * <pre>{@code
 * // 定义自己的 Filter Bean 后，本默认实现会自动卸载：
 * @Component
 * public class MyCustomFilter implements UserTaskTraversalFilter {
 *     public boolean shouldInclude(UserTask userTask, Map<String, Object> variables) {
 *         return true; // 自定义逻辑
 *     }
 * }
 * }</pre>
 *
 * @see UserTaskTraversalFilter
 * @author flowable-plus
 * @since 1.1.0
 */
public class SkipStartTaskFilter implements UserTaskTraversalFilter {

    private static final String NAMESPACE_FLOWABLE = "flowable";
    private static final String ATTR_IS_START_TASK = "isStartTask";
    private static final String VALUE_TRUE = "true";

    /**
     * 读取 BPMN 扩展属性判断是否为发起人节点。
     *
     * @param userTask  BPMN UserTask 定义
     * @param variables 运行时变量（本实现忽略）
     * @return {@code true} 表示收集，{@code false} 表示跳过
     */
    @Override
    public boolean shouldInclude(UserTask userTask, Map<String, Object> variables) {
        String isStart = userTask.getAttributeValue(NAMESPACE_FLOWABLE, ATTR_IS_START_TASK);
        return !VALUE_TRUE.equals(isStart);
    }
}
