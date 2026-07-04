# Phase 4 规划

## 范围

**审批模式深化**，聚焦以下子方向：

### 本期范围

| 子方向 | 优先级 | 说明 | 原始归属 |
|--------|--------|------|----------|
| 任意跳转 | P0 | 驳回至任意历史审批节点，不再局限于上一审批节点或发起人节点 | Issue #1 原定三期 |
| 自动提交 | P1 | 基于规则的自动审批（如金额<1000自动同意），无需人工干预 | Issue #1 原定三期 |
| 前置驳回 | P2 | 审批人在处理前直接退回，无需认领 | Phase 2 下期预留 |

### 下期预留

| 方向 | 说明 |
|------|------|
| 委托 | 将任务处理权临时授予另一人，委派人可收回 |
| 转办 | 将任务拥有权彻底转移给另一人 |
| 子流程完整支持 | 驳回/跳转支持 CallActivity 子流程节点 |

## API 设计

### 任意跳转

```java
/**
 * 驳回至指定历史审批节点。
 * 目标节点必须是该流程实例已经历过的 UserTask，否则抛出 NotFoundException。
 *
 * @param taskId     当前待办任务ID
 * @param targetNodeId 目标节点的 activity definitionKey
 * @param reason     驳回原因
 */
void rejectToNode(String taskId, String targetNodeId, String reason);

/**
 * 查询当前任务可驳回的目标节点列表。
 * 返回该流程实例中当前任务之前的所有已完成的 UserTask 节点。
 *
 * @param taskId 当前待办任务ID
 * @return 可驳回至的历史节点列表（按流程执行顺序排列）
 */
List<TargetNodeVO> getRejectTargets(String taskId);
```

### TargetNodeVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `nodeId` | String | activity definitionKey |
| `nodeName` | String | 节点名称（来自 BPMN 模型） |
| `assignee` | String | 当时处理该节点的审批人 |
| `completeTime` | Date | 完成时间 |

### 自动提交

```java
/**
 * 注册自动审批规则。
 * 当任务分配时，若匹配规则则自动完成审批。
 */
@FunctionalInterface
public interface AutoApprovalRule {
    /**
     * 判断当前任务是否应自动审批。
     *
     * @param task        待审批的任务
     * @param variables   流程变量
     * @return 自动审批意见，返回 null 表示不触发自动审批
     */
    String evaluate(Task task, Map<String, Object> variables);
}

/**
 * 注册自动审批规则。
 * 仅在应用层实现并注入 AutoApprovalRule Bean 时生效。
 */
void registerAutoApprovalRule(AutoApprovalRule rule);
```

### 前置驳回

```java
/**
 * 审批人直接驳回任务，无需先认领。
 * 与 rejectTask 的区别：rejectTask 需要 currentAssignee 校验，
 * 前置驳回仅校验候选人资格。
 *
 * @param taskId 待办任务ID
 * @param reason 驳回原因
 */
void rejectTaskBeforeClaim(String taskId, String reason);
```

## 架构决策

- **任意跳转实现路线**：扩展 NodeFinder 的查找范围，从"仅找上一审批节点"扩展到"查找所有已完成的历史 UserTask 节点"。驳回跳转仍使用 `ChangeActivityStateBuilder`（同 ADR-0001 路线）。
- **自动提交触发时机**：在 `AutoApprovalRule` 接口的调用点——当任务创建/分配时（通过 Flowable 的 TaskListener），若匹配规则则自动调用 `completeTask`。不在 `FlowablePlus` 核心方法中耦合。
- **前置驳回**：校验维度与 `rejectTask` 不同——`rejectTask` 需要 `currentAssignee` 匹配，前置驳回需要候选组匹配。不新增独立方法，通过 `rejectTask(taskId, reason, true)` 的重载或参数控制。

## 决策记录

- 2026-07-04：四期聚焦审批模式深化。委托、转办、子流程延至后续版本。
- 2026-07-04：任意跳转和自动提交是 Issue #1 原定的三期内容，因查询与视图提前纳入 Phase 3 而被推至 Phase 4。

## 实现切片

| Slice | 内容 | 优先级 | 状态 |
|-------|------|--------|------|
| S1: 任意跳转 | `rejectToNode` + `getRejectTargets`，NodeFinder 扩展历史节点收集能力 | P0 | 待开发 |
| S2: 自动提交 | `AutoApprovalRule` SPI + TaskListener 集成 | P1 | 待开发 |
| S3: 前置驳回 | 候选人直接驳回，无需认领 | P2 | 待开发 |
| S4: 测试 + 文档 | 集成测试、使用文档 | — | 待开发 |

## 范围外

- 委托 / 转办（Phase 2 下期预留 C 类，将延至 Phase 5 之后）
- 子流程完整支持
- 跳转到非 UserTask 节点
