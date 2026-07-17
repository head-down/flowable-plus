# Phase 4 规划

## 范围

**审批模式深化**，聚焦以下子方向：

### 本期范围

| 子方向 | 优先级 | 说明 | 原始归属 |
|--------|--------|------|----------|
| 任意跳转 | P0 | 驳回/退回至任意历史审批节点，不再局限于上一审批节点或发起人节点 | Issue #1 原定三期 |
| 自动提交 | P1 | 基于规则的自动审批，发起流程后自动完成首任务，减少操作步骤 | Issue #1 原定三期 |

### 下期预留

| 方向 | 说明 |
|------|------|
| 委托 | 将任务处理权临时授予另一人，委派人可收回 | [Phase 5](phase-5.md) |
| 转办 | 将任务拥有权彻底转移给另一人 | [Phase 5](phase-5.md) |
| 前置驳回 | 候选人认领前直接驳回（本次排除：completeTask 已自动认领，场景不成立） |
| 子流程完整支持 | 驳回/跳转支持 CallActivity 子流程节点 |

## 术语约束

**驳回(reject)** vs **退回(return)**：行为完全一致（使用 `ChangeActivityStateBuilder` 回退），仅历史记录的 `commentType` 不同——`"REJECT"` 表示审批人不同意，`"RETURN"` 表示退回节点重新处理。审批轨迹查询时通过此字段区分操作类型。

## API 设计

### 任意跳转

```java
// === ApprovalOperations 新增方法 ===

/**
 * 驳回至指定历史审批节点。
 * 与 rejectTask 区别：目标节点不限于上一审批节点或发起人，支持任意历史 UserTask。
 *
 * @param taskId       当前待办任务ID
 * @param targetNodeId 目标节点的 activity definitionKey
 * @param reason       驳回原因
 */
void rejectToNode(String taskId, String targetNodeId, String reason);

/**
 * 退回至指定历史审批节点。
 * 与 rejectToNode 的区别仅在于 commentType = "RETURN"（非 "REJECT"）。
 *
 * @param taskId       当前待办任务ID
 * @param targetNodeId 目标节点的 activity definitionKey
 * @param reason       退回原因
 */
void returnToNode(String taskId, String targetNodeId, String reason);

/**
 * 查询当前任务可跳转的历史节点列表。
 * 返回该流程实例中当前任务之前的所有已完成的 UserTask 节点（BPMN 结构上游 + 历史确认）。
 *
 * @param taskId 当前待办任务ID
 * @return 可跳转的历史节点列表（按执行完成时间正序排列）
 */
List<JumpableNodeVO> getJumpableNodes(String taskId);
```

### JumpableNodeVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `nodeId` | String | activity definitionKey（BPMN XML id） |
| `nodeName` | String | 节点名称（来自 BPMN 模型） |
| `assignee` | String | 最近一次处理该节点的审批人 |
| `completeTime` | Date | 最近一次完成时间 |

### 自动提交

```java
/**
 * 自动审批规则。全局注入 Bean，在 startProcess 后自动完成匹配的首任务。
 *
 * <p>仅一层触发——发起人的首任务被自动完成后，下���产生的任务不级联触发。</p>
 * <p>无 AutoApprovalRule Bean 时不做自动提交。</p>
 */
@FunctionalInterface
public interface AutoApprovalRule {
    /**
     * 判断是否应自动完成当前任务。
     *
     * @param task       当前待办任务
     * @param variables  流程变量（不可变拷贝）
     * @return 审批意见，返回 null 表示不触发自动提交
     */
    String evaluate(PlusTask task, Map<String, Object> variables);
}
```

注入方式：`TaskWorkflow` 构造函数新增可选参数 `AutoApprovalRule`（允许 null）。

## 架构决策

### 任意跳转

- **权限模型**：通过 `validateCurrentUserIsAssignee` 校验，与 `rejectTask` 一致。当前节点审批人才能执行任意跳转，发起人不能远程跳转（发起人的权限由 `revokeProcess` 覆盖）。
- **实现路线**：BPMN 反向遍历 + 历史数据确认。先从 BPMN 结构回溯收集所有上游 UserTask（过滤下游节点天然被排除），再通过 `HistoricActivityInstance` 确认节点确实执行过并获取完成时间。
- **不能纯历史查询的原因**：当流程有跳转操作时（如 D 跳回 A），历史数据包含 D，但 D 是当前节点的下游而非上游，不应出现在可跳转列表中。
- **去重**：同一 nodeId 多次完成（循环审批）只返回最后一次执行的 assignee 和 completeTime。
- **回退机制**：仍使用 `ChangeActivityStateBuilder`（`moveActivityIdTo(currentKey, targetNodeId)`），同 ADR-0001 路线。
- **方法归属**：`ApprovalOperations` 接口新增 `rejectToNode`、`returnToNode`、`getJumpableNodes`。

### NodeFinder 新增方法

```java
/**
 * 从当前节点反向查找所有已完成的 UserTask 节点 ID。
 *
 * <p>实现逻辑：
 * 1. 从当前节点 BPMN traceBackward，递归收集所有上游 UserTask（不停止在最近一个）
 * 2. 通过 HistoricActivityInstance 确认节点已完成，获取完成时间
 * 3. 按 endTime 正序排序返回
 *
 * @return 按完成时间正序排列的历史 UserTask ID 列表
 */
List<String> findCompletedUserTasks(String processDefinitionId, String currentActivityId,
                                     String processInstanceId);
```

### 自动提交

- **触发点**：`TaskWorkflow.startProcess` 内部，在 `identityService.setAuthenticatedUserId(null)` 之前触发。
- **规则引擎**：全局 `AutoApprovalRule` Bean（最多一个，多个时 Spring 默认抛 `NoUniqueBeanDefinitionException`）。
- **仅一层**：自动完成发起人的首任务后，不递归自动完成下游任务。
- **变量保护**：`evaluate` 中传入的 `Map<String, Object>` 是流程变量的不可变拷贝，rule 实现不应修改。
- **降级**：自动提交失败（如规则抛异常）降级为不触发，不影响主流程。

## 决策记录

- 2026-07-04：四期聚焦审批模式深化。委托、转办→ [Phase 5](phase-5.md)，子流程延至后续版本。
- 2026-07-04：任意跳转和自动提交是 Issue #1 原定的三期内容，因查询与视图提前纳入 Phase 3 而被推至 Phase 4。
- 2026-07-13：任意跳转拆分为 `rejectToNode`(REJECT) 和 `returnToNode`(RETURN) 两个显式方法，行为一致仅 commentType 不同。
- 2026-07-13：`getRejectTargets` 改名为 `getJumpableNodes`，VO 改为 `JumpableNodeVO`，语义中立。
- 2026-07-13：自动提交为框架内嵌（startProcess 扩展），非 TaskListener，仅一层。
- 2026-07-13：前置驳回从 Phase 4 移除——completeTask 已自动认领，该场景不存在。
- 2026-07-13：可跳转节点收集采用 BPMN 反向遍历 + 历史确认，不依赖纯历史查询（跳转场景有数据污染风险）。

## 实现切片

| Slice | 内容 | 优先级 | 状态 |
|-------|------|--------|------|
| S1: 任意跳转 | `rejectToNode` + `returnToNode` + `getJumpableNodes`，NodeFinder 新增 `findCompletedUserTasks`，`JumpableNodeVO` | P0 | 已完成 |
| S2: 自动提交 | `AutoApprovalRule` SPI + `TaskWorkflow.startProcess` 内嵌触发 | P1 | 已完成 |
| S3: 测试 + 文档 | TaskWorkflow 单元测试（新增方法）、集成测试、使用文档 | — | 已完成 |

## 范围外

- 前置驳回（本次移除）
- 委托 / 转办（[Phase 5](phase-5.md)）
- 子流程完整支持
- 跳转到非 UserTask 节点
