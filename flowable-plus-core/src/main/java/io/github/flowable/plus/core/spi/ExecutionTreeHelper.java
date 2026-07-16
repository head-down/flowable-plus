package io.github.flowable.plus.core.spi;

/**
 * 执行树操作辅助 SPI，封装 Flowable 内部 Execution 树的底层操作。
 *
 * <p>默认实现基于 Flowable 内部 API（{@code ExecutionEntity} / {@code ExecutionEntityManager}），
 * 集中隔离引擎版本升级时的破坏性变更。应用可通过声明同名 Bean 替换为其他引擎版本实现。</p>
 *
 * <p>当前仅提供并行网关分支剥离能力：驳回至发起人时，需要将当前执行对象从并行网关 Scope
 * 中摘除并级联清理其他幽灵分支——Flowable 公共 API 不提供此能力。</p>
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
     * 将指定执行对象从并行网关 Scope 中移出，重新挂载到根执行对象下，
     * 并级联删除并行 Scope（含其他幽灵分支，保留历史数据）。
     *
     * <p>非并行网关场景（串行流程、子流程 Scope）不执行操作，静默返回。</p>
     *
     * @param executionId  当前执行对象 ID
     * @param deleteReason 删除原因（用于 Flowable 内部 deleteReason 参数）
     */
    void detachFromParallelGateway(String executionId, String deleteReason);
}
