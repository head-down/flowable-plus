package io.github.flowable.plus.core.spi;

import org.flowable.bpmn.model.UserTask;

import java.util.Map;

/**
 * BPMN 正向遍历过滤器：在遍历 UserTask 节点时回调，决定是否收集该节点。
 *
 * <h3>作用范围</h3>
 * <p>在 {@code NodeFinder.findDownstreamUserTasks()} 等正向遍历 API 中，每遇到一个 UserTask
 * 都会调用本 SPI 进行过滤。被跳过的节点不会被收集到
 * 结果列表中，但不影响遍历深度 — 遍历仍会穿过它继续探索后续节点。</p>
 *
 * <h3>合并策略：AND 逻辑</h3>
 * <p>多个 Filter 以 <b>AND 逻辑</b>合并：任一 Filter 返回 {@code false} 则该节点被跳过，
 * 只有所有 Filter 都返回 {@code true}（或无任何 Filter 注册）时才会收集该节点。
 * 无 Filter 注册时保持原有行为（全部收集），向后兼容。</p>
 * <p><b>注意：</b>多个 Filter 的执行顺序不保证，请勿依赖 Filter 之间的顺序副作用。</p>
 *
 * <h3>注册方式</h3>
 * <p>实现本接口并标注 Spring {@code @Component} 注解即可自动生效，无需额外配置：</p>
 * <pre>{@code
 * @Component
 * public class MyFilter implements UserTaskTraversalFilter {
 *     ...
 * }
 * }</pre>
 * <p>框架通过 {@code @Autowired(required = false) List<UserTaskTraversalFilter>}
 * 自动收集所有注册为 Spring Bean 的实现。</p>
 *
 * <h3>开箱即用</h3>
 * <p>框架已内置 {@link SkipInitiatorNodeFilter} 默认实现，自动跳过 BPMN 中标记了
 * {@code flowable:isStartTask = "true"} 扩展属性的发起人节点。
 * <b>无需编写任何代码即可生效。</b>如需自定义过滤规则，注册自己的
 * {@code UserTaskTraversalFilter} Bean 即可自动替换默认实现。</p>
 *
 * <h3>使用示例</h3>
 *
 * <h4>示例一：替换默认实现</h4>
 * <p>如果默认的 {@link SkipInitiatorNodeFilter} 不符合需求（如改为根据节点名称判断），
 * 声明自定义 Filter Bean 即可自动替换：</p>
 * <pre>{@code
 * @Component
 * public class CustomFilter implements UserTaskTraversalFilter {
 *     @Override
 *     public boolean shouldInclude(UserTask userTask, Map<String, Object> variables) {
 *         // 例如：跳过名称包含"发起"的节点
 *         String name = userTask.getName();
 *         return name == null || !name.contains("发起");
 *     }
 * }
 * }</pre>
 *
 * <h4>示例二：根据流程变量条件过滤</h4>
 * <p>某些场景下，审批节点的可见性取决于运行时变量。例如，金额小于 1000 时跳过某审批节点：</p>
 * <pre>{@code
 * @Component
 * public class AmountFilter implements UserTaskTraversalFilter {
 *     @Override
 *     public boolean shouldInclude(UserTask userTask, Map<String, Object> variables) {
 *         if (variables == null) return true;
 *         Object amount = variables.get("amount");
 *         if (amount instanceof Number && ((Number) amount).doubleValue() < 1000) {
 *             // 金额小于 1000，跳过标记为审核的节点
 *             String skip = userTask.getAttributeValue("flowable", "skipWhenBelowThreshold");
 *             return !"true".equals(skip);
 *         }
 *         return true;
 *     }
 * }
 * }</pre>
 *
 * <h4>示例三：多个 Filter 协同工作（AND 合并）</h4>
 * <p>假设同时注册了 {@code CustomFilter} 和 {@code AmountFilter}，
 * 某个节点必须同时通过两个 Filter（都返回 {@code true}）才会被收集。例如：</p>
 * <ul>
 *   <li>节点 A（name="发起人审批", skipWhenBelowThreshold=true, amount=500）
 *       → CustomFilter 返回 false → <b>跳过</b></li>
 *   <li>节点 B（name="部门审批", skipWhenBelowThreshold=true, amount=500）
 *       → CustomFilter 返回 true, AmountFilter 返回 false → <b>跳过</b></li>
 *   <li>节点 C（name="部门审批", skipWhenBelowThreshold=true, amount=2000）
 *       → 两个 Filter 都返回 true → <b>收集</b></li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>{@code variables} 为只读引用，请勿修改其中的值</li>
 *   <li>{@code variables} 可能为 {@code null}（如未传入变量上下文），实现时需做空判断</li>
 *   <li>通过 {@link UserTask#getAttributeValue(String, String)} 可读取 BPMN 扩展属性，
 *       命名空间通常为 {@code "flowable"}</li>
 *   <li>Filter 返回 {@code false} 只影响该节点是否被收集，不影响后续节点继续遍历</li>
 * </ul>
 *
 * @author flowable-plus
 * @since 1.1.0
 */
@FunctionalInterface
public interface UserTaskTraversalFilter {

    /**
     * 正向遍历遇到 UserTask 时回调，决定是否收集该节点。
     *
     * @param userTask  BPMN UserTask 定义（只读），可访问 id、name、assignee、candidateGroups、
     *                  extensionElements 及扩展属性等
     * @param variables 运行时流程变量上下文（只读），可能为 {@code null}
     * @return {@code true} 表示收集此节点，{@code false} 表示跳过
     */
    boolean shouldInclude(UserTask userTask, Map<String, Object> variables);
}
