package io.github.flowable.plus.core.spi;

/**
 * 执行树操作辅助 SPI，封装 Flowable 内部 Execution 树的底层操作。
 *
 * <p>默认实现基于 Flowable 内部 API（{@code ExecutionEntity} / {@code ExecutionEntityManager}），
 * 集中隔离引擎版本升级时的破坏性变更。应用可通过声明同名 Bean 替换为其他引擎版本实现。</p>
 *
 * <p>当前仅提供并行网关分支剥离能力：驳回至发起人时，需要清理与当前执行并行的
 * 其他幽灵分支——Flowable 公共 API 不提供此能力。</p>
 *
 * <pre>
 * public class Flowable7ExecutionTreeHelper implements ExecutionTreeHelper {
 *     // Flowable 7.x 内部 API 适配实现
 * }
 * </pre>
 *
 * @author flowable-plus
 */
@FunctionalInterface
public interface ExecutionTreeHelper {

    /**
     * 剥离并行网关分支：删除与当前执行同父的其他并行分支执行（幽灵分支，
     * 连带其任务/变量/作业），保留当前执行、父级 Scope 与历史数据。
     *
     * <p>Flowable 6 执行树中，并行分叉后各分支是父执行（流程实例或子流程 Scope）的
     * 直接子执行，本方法清理当前执行的兄弟分支，使其可单独回退（如驳回至发起人）。</p>
     *
     * <p>非并行分支场景（串行流程、子流程内单路径、多实例会签、仅存在事件子流程等
     * 结构性子执行的情况）不执行操作，静默返回。</p>
     *
     * @param executionId  当前执行对象 ID
     * @param deleteReason 删除原因（用于 Flowable 内部 deleteReason 参数）
     */
    void detachFromParallelGateway(String executionId, String deleteReason);
}
