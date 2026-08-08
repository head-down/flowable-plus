# ADR-0020: 审批历史会签轮次边界统一使用 csRoundIndex，不再依赖 miBody

**日期**: 2026-08-06
**状态**: 已实现（修订：2026-08-08）

## 修订记录

| 日期 | 变更 |
|------|------|
| 2026-08-06 | 初版：移除 `isSameMultiInstanceGroup` 中的 miBody 边界判断，轮次统一由 csRoundIndex 切分 |
| 2026-08-08 | 论证修正：原上下文"miBody 历史活动写入 ACT_HI_ACTINST"基于错误前提。实测 6.8.0 中 `multiInstanceBody` 字符串在全引擎源码**零出现**，历史表不存在 miBody 活动。决策本身不变，仅修正论证 |

## 上下文

`HistoryWorkflow.getApprovalHistory()` 中对多实例会签使用贪心归组算法（`isSameMultiInstanceGroup`），其中包含两套竞争的分组机制：

1. **miBody 边界（错误前提）**：原实现通过 `isMultiInstanceBodyActivity` 检测 `multiInstanceBody` 类型来切断轮次组。但实测 Flowable 6.8.0 中 **`multiInstanceBody` 字符串在全引擎源码零出现**，多实例节点的 `ACT_HI_ACTINST` 记录就是各子实例的 `userTask` 活动（taskId 与子任务关联），**根本不存在 miBody 历史活动**，该边界在 6.8.0 下永不触发
2. **csRoundIndex 拆分**：`splitIntoExplicitRounds` 按 Task 局部变量 `csRoundIndex` 精确切分轮次（ADR-0019）

真正的结构不一致风险：多轮会签仅凭 `baseId` 贪心归组会把**多轮**子记录合并进同一条 `ApprovalRecordVO`，且该行为与流程运行状态无关（无论进行中还是已结束，只要存在多轮就合并）。

下游调用方（jw-zhyg-api）因此需要额外实现 `mergeAdjacentCountersignRounds` 合并逻辑，并同时处理"已是一条"和"需要合并"两种情况。

## 决策

**移除 `isSameMultiInstanceGroup` 中的 `isMultiInstanceBodyActivity` 提前返回**，让贪心归组仅按 `baseId` 相同归入同一组。轮次边界统一由 `splitIntoExplicitRounds` 按 `csRoundIndex` 处理。

### 修改点

`HistoryWorkflow.isSameMultiInstanceGroup()`（单方法）：

- **删除**：`isMultiInstanceBodyActivity` 提前返回（原 L211-212）
- **保留**：同 `baseId` 判断 + `isMultiInstanceNode` 判断

```java
// 删除这段
if (isMultiInstanceBodyActivity(currentActivityType)) {
    return false;
}
```

### 为什么安全

1. 贪心归组仅负责把同一 `baseId` 的活动聚拢，轮次边界完全交由 `splitIntoExplicitRounds` 按 `csRoundIndex` 切分，二者职责单一、无竞争
2. `splitIntoExplicitRounds` 已有完整的按 `csRoundIndex` 拆分逻辑：有显式值使用显式值，无显式值默认 `roundIndex=0`（原始审批人隐式轮次）
3. 无关联任务的活动（taskId 为 null）在 `buildMultiInstanceRecords` 中被 `continue` 跳过，不会产生多余的 `CountersignSubRecord`（6.8.0 下多实例子实例均有关联任务，此为防御性过滤）
4. `isMultiInstanceBodyActivity` 在 6.8.0 下恒返回 false，删除其边界作用不改变任何 6.8.0 行为；方法本身保留作版本防御（防未来 Flowable 版本引入新活动类型）

### 边界验证

| 场景 | 预期行为 |
|------|----------|
| 单轮会签 | 所有子记录 csRoundIndex=0 → 1 条 ApprovalRecordVO |
| 多轮会签 | csRoundIndex 0/1/2... → 对应条数 ApprovalRecordVO |
| 无 csRoundIndex（兼容路径） | 默认 roundIndex=0 → 1 条 ApprovalRecordVO |
| 流程进行中 vs 已结束 | 返回结构一致（均按 csRoundIndex 拆分） |

## 备选方案

- **保留下游做合并**：被否决 —— 结构不一致的根因在 flowable-plus 内部，不应由每个调用方各自处理
- **依赖 miBody 边界**：被否决 —— 6.8.0 下该边界根本不存在（源码实测），无法作为切分依据

## 后果

- **正面**：审批历史按 `csRoundIndex` 精确切分轮次，返回结构稳定、与流程运行状态无关；下游无需额外合并逻辑
- **负面**：同一 miGroup 中可能包含跨轮次的活动（由 `splitIntoExplicitRounds` 二次切分，无副作用）
