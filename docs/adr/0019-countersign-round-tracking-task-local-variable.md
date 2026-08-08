# ADR-0019: 会签多轮次追踪采用 Task 局部变量 csRoundIndex

**日期**: 2026-08-04
**状态**: 已实现（修订：2026-08-08）

## 修订记录

| 日期 | 变更 |
|------|------|
| 2026-08-04 | 初版：csRoundIndex 方案 + 调用方变量时序约束 |
| 2026-08-08 | 时序约束内化：`addCounterSigner` 打标阶段同时为操作者任务写入 `csRoundIndex`，调用方不再需要手动为发起任务打标。修复隐患 C——原始审批人（owner）因运行时缺显式轮次，被 `isMultiInstanceFinished` 误判"本轮将尽"而开启新一轮 |

## 上下文

ADR-0003 指出多轮次会签"需在 API 层包装"。此前尝试通过读侧启发式推断——按 `nrOfInstances` 历史变化 + `loopCounter` 切分轮次——但该方案存在天然局限：

- `addMultiInstanceExecution` 每次调用仅将 `nrOfInstances` 递增，不产生新的 miBody 边界
- `nrOfInstances` 是执行级变量，无法区分"加签追加审批人"和"一轮完成后重新发起"
- 启发式推断依赖时间窗口匹配，加签场景下推断不稳定

因此需要一个写侧显式标记机制，读侧据此精确分组。

## 决策

### 1. Task 局部变量（csRoundIndex），非 Execution 级变量

Flowable 6.8.0 源码（反编译验证）中：

| 作用域 | `isPropagateToHistoricVariable()` | 写入 ACT_HI_VARINST? |
|--------|-----------------------------------|---------------------|
| `ExecutionEntityImpl` | `return false`（硬编码，第 904-906 行） | **否** |
| `TaskEntityImpl` | `return true` | **是** |

`addMultiInstanceExecution` 的 `executionVariables` 是执行级局部变量，不会写入历史表，读侧无法查询。因此改用 `taskService.setVariableLocal(taskId, "csRoundIndex", roundIndex)`，将轮次索引持久化到 `ACT_HI_VARINST`（关联 taskId），读侧通过 `HistoricVariableInstanceQuery` 查询。

### 2. 写侧批量 N→1 降维

`addMultiInstanceExecution` 在 `@Transactional` 内同步执行，新建 Task 进入 `DbSqlSession` 一级缓存。先批量调用 `addMultiInstanceExecution`，再一次性 `taskService.createTaskQuery().list()` + 内存过滤匹配新审批人，将 N 次 DB 查询降为 1 次：

```java
// 批量加签（不查询）
for (String assignee : newAssignees) {
    runtimeService.addMultiInstanceExecution(activityId, processInstanceId, Map.of("assignee", assignee));
}
// 一次性查询 + 内存过滤 + 统一打标
// 2026-08-08 修订：同时为操作者任务（发起任务，taskId 即传入参数）打标，见修订记录
List<Task> activeTasks = taskService.createTaskQuery()
        .processInstanceId(processInstanceId).taskDefinitionKey(activityId).active().list();
for (Task t : activeTasks) {
    if (newAssigneeSet.contains(t.getAssignee()) || t.getId().equals(发起任务Id)) {
        taskService.setVariableLocal(t.getId(), CS_ROUND_INDEX_VAR, newRoundIndex);
    }
}
```

> **轮次语义（2026-08-08 修订）**：内化打标后，操作者任务在首次加签即获显式 `csRoundIndex`，此后本轮内再次加签均并入当前轮。因此**同一执行周期内 `csRoundIndex` 恒为 0**，`csRoundIndex > 0` 不再自然产生（仅手工写入或旧数据存在）；多轮（跨周期）通过折返重新进入节点产生，且由周期边界（`findCurrentCycleBoundary`）重置为 0。读侧按 `csRoundIndex` 分组时，跨周期的多轮记录需依赖周期信息区分。

### 3. 确定下一轮次索引：自引用而非 nrOfInstances 计数

`determineNextRoundIndex` 查询历史 `csRoundIndex` 变量的 `max + 1`，按 `taskDefinitionKey` 范围过滤以避免多会签节点交叉污染。相比规格草案中的 `nrOfInstances` 计数方式，自引用与读侧策略一致，且避免了对 Flowable 内部执行模型的依赖。

### 4. 读侧两级分组策略

```
对每个子记录:
  roundByTaskId.get(taskId) != null  → 使用显式值    (路径1: 加签人，主路径)
  无显式值                           → 默认 round = 0 (路径2: 原始审批人隐式轮次)
```

csRoundIndex 随本方案首次引入，不存在历史流程数据中无此变量的场景，因此无需 nrOfInstances 降级路径。

## 备选方案

- **读侧纯启发式推断（nrOfInstances + loopCounter）**：不可作为主路径——无法区分加签与新轮次，且 flowable-plus 为首个生产版本，无历史数据兼容负担
- **Execution 级 executionVariables**：被否决——`ExecutionEntityImpl.isPropagateToHistoricVariable()` 硬编码返回 `false`，变量不持久化到历史表，读侧无法查询
- **新增自定义表追踪轮次**：被否决——flowable-plus 定位为"贴近引擎的增强工具包"，不应强制用户创建自定义表（与 ADR-0003 决策一致）

## 注意事项：调用方变量时序约束

`addCounterSigner` 内部通过 `determineNextRoundIndex()` 从 `ACT_HI_VARINST` 查询历史 `csRoundIndex` 的最大值来确定下一轮次。因此**调用方不得在调用 `addCounterSigner` 之前将当前任务的 `csRoundIndex` 写入 `ACT_HI_VARINST`**，否则 `determineNextRoundIndex` 会"读到还没发生的轮次"，导致新子任务被赋予错误的轮次索引。

### 正确调用时序（2026-08-08 起内化打标）

自 2026-08-08 修订起，`addCounterSigner` 打标阶段会**同时为操作者任务与新增审批人**写入 `csRoundIndex`，调用方无需再手动为发起任务打标：

```java
// 1. 调用 addCounterSigner（内部完成操作者任务 + 新增审批人的统一打标）
counterSignWorkflow.addCounterSigner(taskId, assignees);

// 2. 最后完成当前任务（可选，若操作者后续不再加签）
taskService.complete(taskId);
```

> **兼容说明**：调用方仍可保留旧的"调用后手动 `setVariableLocal(taskId, csRoundIndex, ...)`"写法，重复写入同值无副作用。

### 错误示例（轮次偏移）

```java
// 错误：在 addCounterSigner 之前设置 csRoundIndex
taskService.setVariableLocal(taskId, "csRoundIndex", 1);  // ← 写入 ACT_HI_VARINST
counterSignWorkflow.addCounterSigner(taskId, assignees);   // ← determineNextRoundIndex 读到 max=1，返回 2
// 新子任务被赋予 csRoundIndex=2（应为 1）→ 审批历史显示为第 3 轮
```

### 根因

`setVariableLocal` 会将变量写入 `ACT_HI_VARINST` 历史表（即使任务仍活跃），而 `determineNextRoundIndex` 正是通过查询此表来推断轮次的。时序错误会导致"自引用污染"——当前任务的 csRoundIndex 被错误地纳入历史查询范围。

## 建模约束：折返路径需经过中间节点（隐患 D，2026-08-08）

`findCurrentCycleBoundary` 依赖历史时间线切分"执行周期"：折返（驳回/退回/跳转）重新进入会签节点后，新周期任务与上一周期之间**必须隔着非本节点 key 的中间任务**（如 `confirmTask`/回迁节点），边界才能落在正确位置。

若建模让会签节点**直接环回自己**（折返路径无中间节点），时间线上同 key 任务连续，边界会退化为全历史最早任务，导致周期重置失效、轮次沿用上一周期全局 `max+1`。该约束行为由单元测试 `testAddCounterSignerDirectLoopKeepsGlobalMaxRound` 固定，作为文档化的建模指引。

## 后果

- **正面**：轮次追踪精确可靠，不依赖启发式推断；写读一致；N→1 降维减少 DB 开销
- **负面**：每轮加签需额外一次 `setVariableLocal` 调用（在批量打标时分摊）
