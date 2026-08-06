# ADR-0020: 审批历史会签轮次边界统一使用 csRoundIndex，不再依赖 miBody

**日期**: 2026-08-06
**状态**: 已实现

## 上下文

`HistoryWorkflow.getApprovalHistory()` 中对多实例会签使用贪心归组算法（`isSameMultiInstanceGroup`），其中包含两套竞争的分组机制：

1. **miBody 边界**：`isMultiInstanceBodyActivity` 检测到 `multiInstanceBody` 类型时立即返回 `false`，切断当前组（L211-212，已删除）
2. **csRoundIndex 拆分**：`splitIntoExplicitRounds` 按 Task 局部变量 `csRoundIndex` 精确切分轮次（ADR-0019）

两套机制在不同运行时状态下产生不一致的结果：

- **流程已结束**：两轮会签的 miBody 均已完整写入 `ACT_HI_ACTINST` → 贪心算法在 miBody 处断开 → 产生两条 `ApprovalRecordVO`，各自只含一轮的 `countersignRecords`
- **流程进行中**：第二轮 miBody 可能尚未写入历史表（取决于 Flowable 内部写入时序） → 贪心算法未断开 → 两轮子记录归入同一 miGroup → 返回结构与已结束状态不一致

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

1. miBody 活动的 `taskId` 始终为 `null`，在 `buildMultiInstanceRecords`（L376-378）中被 `continue` 跳过，不会产生多余的 `CountersignSubRecord`
2. `splitIntoExplicitRounds` 已有完整的按 `csRoundIndex` 拆分逻辑，无需 miBody 辅助
3. 无 `csRoundIndex` 的兼容路径（默认 `roundIndex=0`）保持不变

### 边界验证

| 场景 | 预期行为 |
|------|----------|
| 单轮会签 | 所有子记录 csRoundIndex=0 → 1 条 ApprovalRecordVO |
| 多轮会签 | csRoundIndex 0/1/2... → 对应条数 ApprovalRecordVO |
| 无 csRoundIndex（兼容路径） | 默认 roundIndex=0 → 1 条 ApprovalRecordVO |
| 流程进行中 vs 已结束 | 返回结构一致（均按 csRoundIndex 拆分） |
| 同节点多轮，miBody 全写 vs 部分写 | 结果一致（miBody 被跳过，不影响分组） |

## 备选方案

- **保留下游做合并**：被否决 —— 结构不一致的根因在 flowable-plus 内部，不应由每个调用方各自处理
- **强制 Flowable 写入时序**：被否决 —— Flowable 历史表写入是内部实现细节，不可控

## 后果

- **正面**：审批历史返回结构在流程进行中和已结束时保持一致；下游无需额外合并逻辑
- **负面**：同一 miGroup 中可能包含多个 miBody 活动（无副作用，均被跳过）
