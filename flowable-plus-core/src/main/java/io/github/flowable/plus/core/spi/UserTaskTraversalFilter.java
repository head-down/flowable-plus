package io.github.flowable.plus.core.spi;

import org.flowable.bpmn.model.UserTask;

import java.util.Map;

/**
 * BPMN 正向遍历过滤器 SPI：在遍历 UserTask 节点时回调，决定是否收集该节点。
 *
 * <p>多个 Filter 以 AND 逻辑合并：任一 Filter 返回 false 即跳过该节点，
 * 所有 Filter 均返回 true（或无 Filter 注册时）正常收集。注册多个 Filter
 * 时无执行顺序保证，请勿依赖 Filter 间的顺序副作用。</p>
 *
 * <pre>
 * public class SkipInitiatorFilter implements UserTaskTraversalFilter {
 *     public boolean shouldInclude(UserTask userTask, Map&lt;String, Object&gt; variables) {
 *         // 根据 BPMN 扩展属性 isStartTask 跳过发起人节点
 *         String isStart = userTask.getAttributeValue("flowable", "isStartTask");
 *         return !"true".equals(isStart);
 *     }
 * }
 * </pre>
 *
 * <p>传入的 {@code variables} 为只读引用，请勿修改。</p>
 *
 * @author flowable-plus
 * @since 1.1.0
 */
@FunctionalInterface
public interface UserTaskTraversalFilter {

    /**
     * 正向遍历遇到 UserTask 时回调，决定是否收集该节点。
     *
     * @param userTask  BPMN UserTask 定义（只读，可访问 id/name/assignee/extensionElements 等属性）
     * @param variables 运行时变量上下文（只读，可能为 null）
     * @return true 表示收集此节点，false 表示跳过
     */
    boolean shouldInclude(UserTask userTask, Map<String, Object> variables);
}
