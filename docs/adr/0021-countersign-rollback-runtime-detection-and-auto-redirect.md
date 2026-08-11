# ADR-0021: 会签节点回退采用运行时判断 + 原地重建策略

**日期**: 2026-08-06
**状态**: 已接受（修订：2026-08-06）

## 修订记录

| 日期 | 变更 |
|------|------|
| 2026-08-06 | 初版：自动重定向至前置单例节点 |
| 2026-08-06 | 修订：发现 `moveActivityIdTo` 到 MI 节点可触发 Flowable 原地重建 MI 执行树，决策从"自动重定向"改为"原地重建" |

## 上下文

### 当前行为

`TaskExecutionWorkflow.executeRollback()` 在驳回/撤回/跳转执行前，通过 `MultiInstanceDetector.isMultiInstanceNode()` 检测目标节点的 BPMN 模型是否配置了 `multiInstanceLoopCharacteristics`。如果是，则抛出 `InvalidTargetNodeException` 拦截：

```
"目标节点 X 是会签（多实例）节点，驳回/撤回/跳转至已完成的会签节点会破坏多实例计数器，不支持此操作"
```

### 存在的问题

1. **误拦**：节点在 BPMN 模型中配置了多实例，但运行时实际只有一个人处理（单例运行），也被拦截。典型场景是"回迁节点"在不同流程定义中时而单例时而多实例。

2. **体验差**：被拦截后用户只能靠 `rejectTaskToInitiator`（驳回至发起人）重新走全流程，无法直接回到会签节点。

3. **模型判断与运行时脱节**：静态的 BPMN 配置无法反映动态的运行时状态。一个配置了 MI 的节点，运行时可能因为只分配了一个审批人而表现为单例。

### 核心发现：Flowable 的"隐藏能力"

经过对 Flowable 6.8.0 引擎源码的深入分析，发现了一个官方未重点宣传但在源码中确实可行的路径：**如果 `collectionExpression` 引用的变量（如 `${assigneeList}`）在 `moveActivityIdTo` 调用时已存在且有值，Flowable 会在目标 MI 节点自动重建完整的多实例执行树。**

### 核心发现：Flowable 的"隐藏能力"

经过对 Flowable 6.8.0 引擎源码的深入分析，发现了一个官方未重点宣传但在源码中确实可行的路径：**如果 `collectionExpression` 引用的变量（如 `${assigneeList}`）在 `moveActivityIdTo` 调用时已存在且有值，Flowable 会在目标 MI 节点自动重建完整的多实例执行树。**

**关键条件**：`collectionExpression`（例如 `${assigneeList}`）在调用前必须可解析且值有效。如果 `assigneeList` 为空，`createInstances()` 返回 0，Flowable 会调用 `cleanupMiRoot()` **跳过该节点**——这不是期望的行为。

完整的源码级调用链（8 个内部类、含精确行号和代码摘录）、一步法 vs 两步法对比、版本差异风险分析，详见独立的技术验证报告：

> [Flowable 6.8.0 多实例节点原地重建技术验证报告](../flowable-mi-rebuild-verification.md)

## 决策

### 1. 运行时判断替代模型判断

将拦截依据从 BPMN 模型配置改为**运行时历史记录**：

```
isMultiInstanceAtRuntime(task, activityId) :=
  historyService.createHistoricTaskInstanceQuery()
    .processInstanceId(task.getProcessInstanceId())
    .taskDefinitionKey(activityId)
    .count() > 1
```

- 历史任务数 <= 1：运行时单例，直接放行
- 历史任务数 > 1：运行时真正多实例，进入步骤 2

### 2. 默认行为：自动重定向至前置单例节点

当目标节点运行时为真正多实例时，自动查找 MI 节点的前置单例 UserTask，将回退目标重定向到该节点：

```
resolveMultiInstancePredecessor(task, miActivityId):
  preds = nodeFinder.findPreviousNodes(defId, miActivityId, procInstId)
  过滤：preds 中模型配置为单例的节点
  if 过滤后 == 1: return 该节点  // 重定向
  else: return null               // 拦截
```

`findPreviousNodes` 使用 `STOP_AT_FIRST_USER_TASK` 策略，从 MI 节点往回查，天然停在第一个前置 UserTask。如果 BPMN 遵守"MI 节点前有单例前置节点"的规范（ADR-0022 模式 A/B），该节点必然是单例。

> 选择此策略为默认的原因：不依赖 Flowable 版本特定的引擎行为（6.8.1+ 存在已知 Bug），跨版本兼容性最好。

### 3. 可选的增强：原地重建（需配置启用）

如果项目锁定 Flowable 6.8.0 且配置了 `countersign-rollback-strategy=auto-rebuild`，则启用原地重建路径：通过 `AssigneeResolver` SPI（ADR-0022）获取新的 `assigneeList`，设为流程变量后直接 `moveActivityIdTo` 到 MI 节点，由 Flowable 引擎自动重建 MI 执行树：

```
handleMultiInstanceRollback(task, targetMI):
  // Step 1: 通过 SPI 获取新的会签人员列表
  List<String> newAssignees = assigneeResolverRegistry.resolve(
      task.getProcessInstanceId(), targetMI);

  if (newAssignees == null || newAssignees.isEmpty()) {
      // 降级：无 assigneeList → 回退到自动重定向
      return resolveMultiInstancePredecessor(task, targetMI);
  }

  // Step 2: 设置 assigneeList 为流程变量
  runtimeService.setVariable(task.getProcessInstanceId(), "assigneeList", newAssignees);

  // Step 3: 直接跳回 MI 节点，Flowable 自动重建 MI 执行树
  runtimeService.createChangeActivityStateBuilder()
      .processInstanceId(task.getProcessInstanceId())
      .moveActivityIdTo(task.getTaskDefinitionKey(), targetMI)
      .changeState();
```

> 源码级验证见 [技术验证报告](../flowable-mi-rebuild-verification.md)。此模式不设为默认，因为强依赖 Flowable 6.8.0，升级风险高。

### 4. 行为矩阵（默认策略）

| 运行时状态 | 前置单例节点 | 行为 |
|-----------|------------|------|
| 单例（count <= 1） | — | 直接 `moveActivityIdTo` 到原目标 |
| 多实例（count > 1） | 存在且唯一 | 自动重定向至前置单例节点 |
| 多实例（count > 1） | 不存在或多个 | 拦截，抛出 `InvalidTargetNodeException` |

> **注意**：上表是默认策略（`AutoRedirectCountersignRollbackStrategy`）的行为矩阵。`AutoRebuildCountersignRollbackStrategy` 策略在上方增加了"原地重建"路径（需要 `AssigneeResolver` 和额外配置启用），详见第 5 节。

### 5. 策略接口化与可配置

将会签回退判断抽成 `CountersignRollbackStrategy` 接口（`io.github.flowable.plus.core.strategy` 包）：

```java
public interface CountersignRollbackStrategy {
    /**
     * 解析会签节点的回退目标。
     * @return RollbackResult 包含目标节点 ID 和可选的预设备份流程变量
     */
    RollbackResult resolveRollbackTarget(
        PlusTask task, String targetActivityId);
}
```

三个实现为**包私有类**，统一通过工厂 `CountersignRollbackStrategies` 创建
（`strict` / `autoRedirect` / `autoRebuild` 三个静态工厂方法），调用方只面对接口与工厂。
共享的前置单例节点解析工具 `resolveMultiInstancePredecessor` 亦收敛在该工厂类中。
通过 `flowable.plus.countersign-rollback-strategy` 配置项切换策略：

#### 5.1 AutoRedirectCountersignRollbackStrategy（默认）

框架默认策略，不依赖 Flowable 版本特定行为：

1. 运行时判断是否为多实例（历史任务 count > 1）
2. 是 → 查找前置单例节点 → 存在且唯一 → 重定向
3. 无前置节点 → 拦截

> 选择依据：此策略不依赖 `moveActivityIdTo` 到 MI 节点的引擎重建行为，跨 Flowable 版本兼容性最好。

#### 5.2 AutoRebuildCountersignRollbackStrategy（可选）

仅在项目明确配置时启用，适应于锁定 Flowable 6.8.0 且有 `AssigneeResolver` SPI 实现的场景：

1. 运行时判断是否为多实例
2. 是 → 尝试 `AssigneeResolver` → 有值则返回 `RollbackResult.rebuild(targetMI, variables)`
3. 无 AssigneeResolver → 降级到自动重定向（同默认策略）
4. 无前置节点 → 拦截

> **版本注意**：此策略依赖 Flowable 6.8.0 的 `moveActivityIdTo` 到 MI 节点自动重建 MI 执行树的行为。该行为在 6.8.1+ 版本中存在已知 Bug（见 [技术验证报告](../flowable-mi-rebuild-verification.md) 第 5 节）。启用前请确保 Flowable 版本锁定为 6.8.0。

#### 5.3 StrictCountersignRollbackStrategy（备选）

模型检查 + 全部拦截，保持旧行为。供需要保守策略的项目使用。

#### 5.4 配置方式

```yaml
# application.yml
flowable:
  plus:
    countersign-rollback-strategy: auto-redirect  # 默认值，无需显式配置
    # countersign-rollback-strategy: auto-rebuild  # 启用原地重建策略（需锁定 Flowable 6.8.0）
    # countersign-rollback-strategy: strict        # 启用严格拦截策略（旧行为）
```

### 6. 受影响的 API

- `rejectTask` / `withdrawTask`：`findPreviousNodes` 返回 MI 节点 → `executeRollback` 调用策略接口处理
- `jumpToNode`：调用方指定 MI 节点 → `executeRollback` 调用策略接口处理
- `getJumpableNodes`：可跳转列表中过滤运行时多实例节点（count > 1 且无前置单例节点的 MI 节点被过滤）

### 7. 错误信息

拦截时的错误提示：

```
"目标节点 X 在本流程实例中为多实例（会签）节点，且 BPMN 中不存在唯一前置单例准备节点。
 建议驳回至该节点的前置准备节点，重新走完整流程。
 或使用驳回至发起人 (rejectTaskToInitiator) 重新提交。
 若需直接回到会签节点原地重建，请配置 countersign-rollback-strategy=auto-rebuild
 并确保 Flowable 版本锁定为 6.8.0。"
```

### 8. 原地重建模式的数据完整性（仅 auto-rebuild 策略）

启用 `auto-rebuild` 策略时：

- assigneeList 设置和 `moveActivityIdTo` 必须在同一个流程操作上下文中执行
- `runtimeService.setVariable()` + `changeState()` 是两次引擎命令，推荐在外部包裹 `@Transactional` 保证原子性
- Flowable 的 `CommandContext` 模型中，同一事务内的多次命令操作共享同一个 `CommandContext`，变量设置对后续的 `changeState` 命令可见
- 该模式强依赖 Flowable 6.8.0，升级前必须回归测试（见 [技术验证报告](../flowable-mi-rebuild-verification.md) 第 5 节）

## 备选方案

- **两步法（moveActivityIdTo(A, A)）**：先 `moveActivityIdTo(current, A)` 产生单例壳，再设 assigneeList，再 `moveActivityIdTo(A, A)` 重建。被否决——Step 1 若 assigneeList 为空会导致执行跳过节点，Step 3 无法找到活跃执行。额外复杂度无收益。

- **完全移除拦截**：接受直接跳转后产生的单例占位任务。被否决——该任务完成时 completionCondition 行为不确定，可能导致脏数据。

- **框架层手工重建多实例执行树**：通过 Flowable 内部 API（`ExecutionEntity` 等）手工重建。被否决——脆弱、版本绑定、不可维护。

- **仅优化错误信息，不改判断逻辑**：被否决——不解决"回迁节点有时单例有时多例"的误拦问题。

- **始终自动重定向**：（初版决策）不探索 Flowable 原地重建能力。被修订——`moveActivityIdTo` 到 MI 节点的自动重建能力经过源码级验证可行。

## 后果

- **正面**：
  - 误拦修复：模型多实例 + 运行时单例的节点正常回退
  - 默认安全：`auto-redirect` 策略不依赖 Flowable 版本特定行为，跨版本兼容
  - 渐进增强：需要原地重建的项目可通过 `auto-rebuild` 配置项启用（需锁定 Flowable 6.8.0）
  - 可测试性：策略接口化后，每种策略可独立测试
  - 降级闭环：无前置节点时拦截报错，错误信息引导到兜底方案（rejectTaskToInitiator）

- **负面**：
  - 运行时判断增加一次历史表 `count()` 查询（仅 MI 目标时触发，性能影响可忽略）
  - 默认行为（自动重定向）需要 BPMN 建模遵守"MI 节点前有单例准备节点"规范
  - `auto-rebuild` 策略需额外配置 + 版本锁定，增加了项目接入的理解成本

- **风险**：
  - 低：默认策略改动集中在 `TaskExecutionWorkflow` 内部和新增策略类，不触及 Flowable 引擎内部状态
  - `auto-rebuild` 策略的引擎行为依赖在 6.8.1+ 中存在已知 Bug，配置项文档已明确标注版本要求
