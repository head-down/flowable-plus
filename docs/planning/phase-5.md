# Phase 5 规划

> **阶段编号说明**：委派与转办是一期至四期 PRD 中被反复提及的"后续版本"功能。因四期（任意跳转）正在实施，五期（流程可视化）属展示类与操作类不搭，将委派/转办插入为轻量 Phase 5 快速交付。原五期顺延为 [Phase 6](phase-6.md)。

## 范围

**任务委派与转办**，聚焦以下子方向：

### 本期范围

| 子方向 | 优先级 | 说明 | 原始归属 |
|--------|--------|------|----------|
| 委派 (Delegate) | P0 | 审批人将任务临时委托给另一人处理，委派人可收回 | Issue #1 原定"后续版本" |
| 转办 (Transfer) | P1 | 审批人将任务拥有权彻底转移给另一人 | Issue #1 原定"后续版本" |

## API 设计

```java
// === ApprovalOperations 新增方法 ===

/**
 * 将任务委派给另一人处理。委派后原审批人成为 owner，被委派人成为 assignee。
 * 委派人可通过 resolveDelegate 收回任务。
 *
 * @param taskId          当前待办任务ID
 * @param delegateUserId  被委派的用户ID
 * @param reason          委派原因
 */
void delegateTask(String taskId, String delegateUserId, String reason);

/**
 * 收回已委派的任务。将 assignee 重置为 owner（委派人）。
 * 仅委派人（owner）可操作。
 *
 * @param taskId 当前待办任务ID
 */
void resolveDelegate(String taskId);

/**
 * 将任务转办给另一人。转办后任务拥有权彻底转移，原审批人失去控制权。
 * 与委派的区别：不可收回，owner 一并变更为目标用户。
 *
 * @param taskId           当前待办任务ID
 * @param transferUserId   转办目标用户ID
 * @param reason           转办原因
 */
void transferTask(String taskId, String transferUserId, String reason);
```

### 语义区分

| 操作 | 底层 API | owner 变更 | 可收回 | commentType |
|------|---------|-----------|--------|-------------|
| 委派 | `TaskService.delegateTask` | 否 | 是 | `DELEGATE` |
| 收回委派 | `TaskService.resolveTask` | 否 | — | `RESOLVE_DELEGATE` |
| 转办 | `TaskService.setAssignee` | 是 | 否 | `TRANSFER` |

## 架构决策

### 底层实现

- **委派**: `TaskService.delegateTask(taskId, delegateUserId)` — Flowable 原生支持，委派后原审批人成为 owner
- **收回**: `TaskService.resolveTask(taskId)` — 将 assignee 重置为 owner
- **转办**: `TaskService.setAssignee(taskId, transferUserId)` — 直接变更 assignee，变更后由 Phase 6 的流程历史通过 commentType 追溯操作语义

### 权限模型

通过 `TaskService.createTaskQuery().taskId(taskId).singleResult()` 获取任务后校验：

| 操作 | 权限要求 |
|------|---------|
| `delegateTask` | 当前用户是 assignee |
| `resolveDelegate` | 当前用户是 owner |
| `transferTask` | 当前用户是 assignee |

### 审批意见

通过 `TaskService.addComment(taskId, task.getProcessInstanceId(), reason)` 记录操作原因，commentType 区分操作类型。

### 模块范围

仅涉及 `flowable-plus-core`。Starter 模块无需变更——无新的自动配置 Bean。

### 方法归属

`ApprovalOperations` 接口新增 `delegateTask`、`resolveDelegate`、`transferTask`。

## 决策记录

- 2026-07-14：委派与转办从 Issue #1 的"后续版本"正式纳入 Phase 5，定位轻量 Phase 快速交付。
- 2026-07-14：委派 (delegateTask) 和收回 (resolveDelegate) 为 P0，转办 (transferTask) 为 P1。
- 2026-07-14：底层直接使用 Flowable 原生 `TaskService` 方法，无需自定义逻辑。权限校验 + 审批意见为 flowable-plus 封装层的主要附加值。

## 实现切片

| Slice | 内容 | 优先级 | 状态 |
|-------|------|--------|------|
| S1: 委派 + 收回 | `delegateTask` + `resolveDelegate` | P0 | 已完成 |
| S2: 转办 | `transferTask` | P1 | 已完成 |
| S3: 测试 + 文档 | 单元测试、权限校验、commentType 验证 | — | 已完成 |

S1 优先实现——委派和收回构成闭环的临时授权操作。

## 范围外

- 批量委派/转办（一次操作多个任务）
- 委派链（A 委派给 B，B 再委派给 C）
- 基于规则的自动委派（如根据组织架构自动委派给上级）
- 委派超时自动收回
- 管理员/流程管理员代为转办
- 前端委派按钮 UI
