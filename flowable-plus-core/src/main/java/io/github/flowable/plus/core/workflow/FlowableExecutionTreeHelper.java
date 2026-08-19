package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.spi.ExecutionTreeHelper;

import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.ManagementService;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityManager;
import org.flowable.engine.impl.util.CommandContextUtil;

import java.util.Collections;

/**
 * {@link ExecutionTreeHelper} 的 Flowable 6.8 默认实现。
 *
 * <p>通过 {@link ManagementService#executeCommand(Command)} 进入引擎内部 Command 上下文，
 * 获取 {@link ExecutionEntityManager} 直接操作执行树，实现并行网关分支的剥离与清理。
 * Flowable 公共 API 不暴露 Execution 层级操作能力，此实现是必要的低层适配。</p>
 *
 * <p>Flowable 6 执行树中，并行网关分叉后各分支是父执行（流程实例或子流程 Scope）的直接子执行，
 * 不存在独立的并发 Scope（{@code isConcurrent} 为 Flowable 5 遗留字段，6.x 运行时恒为 false）。
 * 因此"剥离"的语义是：删除与当前执行同父的其他兄弟分支（幽灵分支），保留当前执行与父级。</p>
 *
 * @author flowable-plus
 */
public class FlowableExecutionTreeHelper implements ExecutionTreeHelper {

    private final ManagementService managementService;

    public FlowableExecutionTreeHelper(ManagementService managementService) {
        this.managementService = managementService;
    }

    @Override
    public void detachFromParallelGateway(String executionId, String deleteReason) {
        managementService.executeCommand((Command<Void>) commandContext -> {
            ExecutionEntityManager em = CommandContextUtil.getExecutionEntityManager(commandContext);
            ExecutionEntity currentExec = em.findById(executionId);
            if (currentExec == null) {
                return null;
            }
            ExecutionEntity parentExec = currentExec.getParent();

            // 并行分支判定：父执行下存在其他未结束的非 Scope 兄弟分支。
            // 仅统计非 Scope 执行——并行分支的路径执行恒为非 Scope，
            // 而事件子流程等待执行（scope=true）与补偿事件 Scope（eventScope=true）
            // 是宿主 Scope 的常驻结构性子执行，不属于并行分支，必须排除以免误删。
            // 串行流程（父执行仅有当前一个子执行）自然不满足，静默返回。
            // 多实例（会签）父执行除外——其回退由会签重定向逻辑处理（ADR-0021）
            if (parentExec == null || parentExec.isMultiInstanceRoot()
                    || !hasActiveSiblingBranch(parentExec, currentExec)) {
                return null;
            }

            // 删除其他并行分支（幽灵分支，连带其任务/变量/作业，叶子优先），
            // 保留当前执行、父级 Scope 与历史数据；cancel=true 派发 ACTIVITY_CANCELLED 事件
            em.deleteChildExecutions(parentExec,
                    Collections.singletonList(currentExec.getId()), null, deleteReason, true, null);

            return null;
        });
    }

    private boolean hasActiveSiblingBranch(ExecutionEntity parentExec, ExecutionEntity currentExec) {
        for (ExecutionEntity child : parentExec.getExecutions()) {
            if (!child.getId().equals(currentExec.getId()) && !child.isEnded()
                    && !child.isScope() && !child.isEventScope()) {
                return true;
            }
        }
        return false;
    }
}
