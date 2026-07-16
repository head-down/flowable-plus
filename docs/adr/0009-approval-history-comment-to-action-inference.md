# 审批历史 Comment → ApprovalAction 推断采用三级策略

审批历史查询中，将 Flowable 底层 `Comment.type`（CommentType 枚举）推断为展示层 `ApprovalAction` 的算法采用"互斥终结 + 特征提取 + DeleteReason 兜底"三级策略，而非简单的单向映射。

## 背景

Phase 6 的 `getApprovalHistory` 需要从三张历史表（`ACT_HI_ACTINST`、`ACT_HI_TASKINST`、`ACT_HI_COMMENT`）聚合出完整的审批时间线，其中每个节点的操作类型（`ApprovalAction`）需要通过 Comment 记录推断。

流程审批过程中可能存在过程性 Comment（草稿、普通留言），且部分异常终结场景（管理员强杀、系统静默流转）可能不携带明确的业务 Comment。

## 决策

对每个历史任务节点，按以下三级策略依次推断其 `ApprovalAction`：

1. **特征提取**：按 Comment 时间倒序扫描，取第一个匹配 `CommentType` 枚举值的 Comment → 映射为 `ApprovalAction`
   - 跳过非业务类型的过程性留言
   - 基于状态互斥性：同一 `taskId` 下不可能同时存在 `AGREE` 和 `REJECT` 两种终结态 Comment（Flowable 底层 `complete` 与 `moveActivityIdTo` 物理互斥）

2. **DeleteReason 兜底**：若无匹配 Comment → 降级读取 `HistoricTaskInstance.deleteReason`
   - `"completed"` → `ApprovalAction.AGREE`
   - `"deleted"` → 结合上下文判断（`REVOKE` / `WITHDRAW` / `REJECT`）
   - `null` → 标记为当前活跃节点（`action` 可为 null）

3. **START 特殊处理**：从 `startEvent` 活动实例 + `HistoricProcessInstance.startUserId` 构造，不依赖 Comment

## 考虑的方案

| 方案 | 结论 | 理由 |
|------|------|------|
| 单向映射（`CommentType` → `ApprovalAction` 1:1）| 不采纳 | 无法处理过程性留言干扰和异常终结场景 |
| `List.first()` 取第一条 Comment | 不采纳 | 第一条可能是用户留言而非业务操作记录 |
| 时间正序扫描 Comment | 不采纳 | 过程性留言可能出现在业务操作之前，需从终结态倒查 |

## 后果

- 推断逻辑与 `CommentType` 枚举强耦合，新增操作类型时需同步更新推断链
- 管理员强杀场景的 `deleteReason` 可能为任意字符串，仅 `"completed"` 和 `"deleted"` 两种值可标准推断，其余需标记为异常并记录日志
- 所有审批操作必须在 `ACT_HI_COMMENT` 中写入 `CommentType` 枚举值（已通过 #33 强制统一）
