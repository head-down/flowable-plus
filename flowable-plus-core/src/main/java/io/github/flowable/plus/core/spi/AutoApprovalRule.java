package io.github.flowable.plus.core.spi;

import io.github.flowable.plus.core.domain.PlusTask;

import java.util.Map;

/**
 * 自动提交规则 SPI。
 *
 * <p>流程发起后，框架查询流程首任务并调用此规则评估是否应自动完成。
 * 返回非 null 字符串表示触发自动提交（返回值为审批意见），返回 null 表示不触发。</p>
 *
 * <p>多个规则以 OR 逻辑合并：任一规则返回非 null 即触发自动提交，
 * 审批意见取第一个非 null 结果。无规则 Bean 注册时行为不变，向后兼容。</p>
 *
 * <pre>
 * @Component
 * public class LowAmountAutoApproval implements AutoApprovalRule {
 *     public String evaluate(PlusTask task, Map&lt;String, Object&gt; variables) {
 *         if (task.getTaskDefinitionKey().equals("draft")) {
 *             return "发起申请已自动通过";
 *         }
 *         return null; // 非首审批节点，不触发
 *     }
 * }
 * </pre>
 *
 * <p>传入的 {@code variables} 为不可变浅层拷贝，请勿修改。
 * 如需根据流程变量做判断，可读取但不应写入。</p>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface AutoApprovalRule {

    /**
     * 评估是否应对当前首任务进行自动提交。
     *
     * @param task      首个审批任务
     * @param variables 流程变量不可变拷贝（只读）
     * @return 审批意见文本触发自动提交，null 表示不触发
     */
    String evaluate(PlusTask task, Map<String, Object> variables);
}
