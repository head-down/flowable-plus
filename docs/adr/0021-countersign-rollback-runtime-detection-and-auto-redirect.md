# ADR-0021: 会签节点回退采用运行时判断 + 自动重定向策略

**日期**: 2026-08-06
**状态**: 已接受

## 上下文

### 当前行为

`TaskExecutionWorkflow.executeRollback()` 在驳回/撤回/跳转执行前，通过 `MultiInstanceDetector.isMultiInstanceNode()` 检测目标节点的 BPMN 模型是否配置了 `multiInstanceLoopCharacteristics`。如果是，则抛出 `InvalidTargetNodeException` 拦截：

```
"目标节点 X 是会签（多实例）节点，驳回/撤回/跳转至已完成的会签节点会破坏多实例计数器，不支持此操作"
```

### 存在的问题

1. **误拦**：节点在 BPMN 模型中配置了多实例，但运行时实际只有一个人处理（单例运行），也被拦截。典型场景是"回迁节点"在不同流程定义中时而单例时而多实例。

2. **体验差**：被拦截后用户只能靠 `rejectTaskToInitiator`（驳回至发起人）重新走全流程，无法直接回到会签节点的前置准备节点。

3. **模型判断与运行时脱节**：静态的 BPMN 配置无法反映动态的运行时状态。一个配置了 MI 的节点，运行时可能因为只分配了一个审批人而表现为单例。

### 为什么不能"直接跳回会签节点 + 重建多实例执行树"？

经过对 Flowable 6.8.0 引擎行为的深入分析：

1. `ChangeActivityStateBuilder.moveActivityIdTo()` 只改变执行指针，**不重建多实例执行树**。完成后目标节点上只有一个单例任务。
2. `RuntimeService.addMultiInstanceExecution()`（加签 API）**需要多实例根执行（multi-instance root execution）存在**。该执行树在原始会签完成后已被销毁，调用会直接报错。
3. 手工重建多实例执行树涉及 Flowable 内部 API（`ExecutionEntity`），脆弱且版本绑定。

因此，"直接跳回已完成会签节点并重新发起会签"在当前引擎约束下不可行。

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

- 历史任务数 <= 1：运行时单例，放行
- 历史任务数 > 1：运行时多实例，进入步骤 2

### 2. 自动重定向至前置单例节点

当目标节点运行时为真实多实例时，自动查找其前置单例 UserTask，将回退目标重定向到该节点：

```
resolveMultiInstancePredecessor(task, miActivityId):
  preds = nodeFinder.findPreviousNodes(defId, miActivityId, procInstId)
  过滤：preds 中模型配置为单例的节点
  if 过滤后 == 1: return 该节点  // 重定向
  else: return null               // 拦截
```

`findPreviousNodes` 使用 `STOP_AT_FIRST_USER_TASK` 策略，从 MI 节点往回查，天然停在第一个前置 UserTask。如果 BPMN 遵守"MI 节点前有单例前置节点"的规范，该节点必然是单例。

### 3. 行为矩阵

| 运行时状态 | 前置单例节点 | 行为 |
|-----------|------------|------|
| 单例（count <= 1） | — | 放行，直接回退 |
| 多实例（count > 1） | 存在且唯一 | 自动重定向至前置单例节点 |
| 多实例（count > 1） | 不存在或多个 | 拦截，抛出 InvalidTargetNodeException |

### 4. 策略接口化

将会签回退判断抽成 `CountersignRollbackStrategy` 接口（`io.github.flowable.plus.core.strategy` 包），与 `PreviousNodeResolutionStrategy` 并列：

```java
public interface CountersignRollbackStrategy {
    /**
     * 解析会签节点的回退目标。
     * @return 重定向后的目标节点 ID；
     *         null 表示拦截（应抛 InvalidTargetNodeException）；
     *         与原 targetActivityId 相同表示放行（无需处理）
     */
    String resolveRollbackTarget(PlusTask task, String targetActivityId);
}
```

两个实现：

- **AutoRedirectCountersignRollbackStrategy**（默认）：自动重定向 + 拦截降级，即本 ADR 描述的完整策略。
- **StrictCountersignRollbackStrategy**（备选）：模型检查 + 全部拦截，保持旧行为。供需要保守策略的项目使用。

### 5. 受影响的 API

- `rejectTask` / `withdrawTask`：`findPreviousNodes` 返回 MI 节点 → `executeRollback` 自动重定向
- `jumpToNode`：调用方指定 MI 节点 → `executeRollback` 自动重定向
- `getJumpableNodes`：可跳转列表中过滤运行时多实例节点

### 6. 错误信息改进

拦截时的错误提示从"破坏多实例计数器"改为明确引导：

```
"目标节点 X 在本流程实例中为多实例（会签）节点，直接跳转无法恢复多实例状态。
 建议驳回至该节点的前置准备节点，重新走完整流程。
 或使用驳回至发起人 (rejectTaskToInitiator) 重新提交"
```

## 备选方案

- **完全移除拦截**：接受直接跳转后产生的单例占位任务。被否决——该任务完成时 completionCondition 行为不确定，可能导致脏数据。
- **框架层重建多实例执行树**：通过 Flowable 内部 API 手工重建。被否决——脆弱、版本绑定、不可维护。
- **仅优化错误信息，不改判断逻辑**：被否决——不解决"回迁节点有时单例有时多例"的误拦问题。

## 后果

- **正面**：
  - 误拦修复：模型多实例 + 运行时单例的节点正常回退
  - 体验提升：遵从前置节点建模规范的 BPMN 自动重定向，用户无需感知
  - 可测试性：策略接口化后，公有 API 级别即可验证完整行为
  - 向后兼容：真多实例 + 无前置节点的场景仍拦截（安全底线不变）

- **负面**：
  - 运行时判断增加一次历史表 `count()` 查询（仅 MI 目标时触发，性能影响可忽略）
  - 重定向行为需要记录 INFO 日志，运维需关注日志确认回退路径

- **风险**：
  - 低：改动集中在 `TaskExecutionWorkflow` 内部，不触及 Flowable 引擎状态
