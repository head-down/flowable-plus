package io.github.flowable.plus.core.workflow;

import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.ManagementService;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityManager;
import org.flowable.engine.impl.util.CommandContextUtil;

/**
 * {@link ExecutionTreeHelper} 的 Flowable 6.8 默认实现。
 *
 * <p>通过 {@link ManagementService#executeCommand(Command)} 进入引擎内部 Command 上下文，
 * 获取 {@link ExecutionEntityManager} 直接操作执行树，实现并行网关分支的剥离与清理。
 * Flowable 公共 API 不暴露 Execution 层级操作能力，此实现是必要的低层适配。</p>
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
            ExecutionEntity scopeExec = currentExec.getParent();

            // 仅处理并行网关 Scope（isConcurrent=true），
            // 跳过根执行（串行流程 parent 即为 ProcessInstance，其 parent 为 null）
            // 和子流程 Scope（isConcurrent=false，强行 Reparent 会摧毁子流程上下文）
            if (scopeExec != null && scopeExec.isConcurrent()) {
                ExecutionEntity rootExec = scopeExec.getParent();
                // Reparent: 从并行网关 Scope 移除，挂到根执行下
                scopeExec.getExecutions().remove(currentExec);
                currentExec.setParent(rootExec);
                rootExec.addChildExecution(currentExec);
                // 级联删除 Scope（连带其他幽灵分支，保留历史数据）
                em.deleteExecutionAndRelatedData(scopeExec, deleteReason, false, true);
            }

            return null;
        });
    }
}
