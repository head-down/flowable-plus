# 公开 API 准入标准：禁止裸透传 Flowable 原生方法

新增公开 API 必须带有领域增值逻辑（审批语义、权限校验、事件集成等），禁止将 Flowable 原生 API 不加修饰地包装为公开方法。

## 背景

2026-07-30，下游团队提出一批待新增的 API：

| 提案 API | Flowable 原生等价调用 |
|----------|----------------------|
| `getVariable` / `setVariable` | `runtimeService.getVariable(id, name)` / `setVariable(id, name, val)` |
| `getActiveTasks(instanceId)` | `taskService.createTaskQuery().processInstanceId(id).active().list()` |
| `suspendProcess` / `activateProcess` | `runtimeService.suspendProcessInstanceById(id)` / `activateProcessInstanceById(id)` |
| `isTaskActive(taskId)` | `taskService.createTaskQuery().taskId(id).active().count() > 0` |

这些 API 的直接实现都是对 Flowable 原生方法的一行委托，零领域逻辑。

需要明确：**flowable-plus 是中式审批模式的领域增强工具，还是 Flowable 引擎的便捷包装层？**

## 决策

**flowable-plus 是领域增强工具，不是通用 API 包装层。** 新增公开 API 采用以下准入标准：

### 必须满足：领域增值

每个公开 API 必须在透传之外带有**至少一项**领域增值：

- **审批语义**：驳回、撤回、撤销、会签等中式审批概念（已有示例：`rejectTask`、`withdrawTask`、`counterSign`）
- **权限校验**：assignee / 发起人 / 上一节点审批人身份校验（已有示例：所有写操作方法）
- **事件集成**：通过 `ProcessEventListener` 发布领域事件
- **VO 转换**：将 Flowable 原生对象转换为框架定义的领域 VO（已有示例：`queryTodoTasks`、`getApprovalHistory`）
- **BPMN 模型操作**：节点遍历、多实例检测、网关判断等（已有示例：`getNextTaskNodes`、`getJumpableNodes`）
- **批量化**：消除 N+1 查询或提供批量语义（已有示例：`batchQueryProcessSummaries`）

### 反模式：裸透传

如果去掉透传代码后方法体为空白，该 API 不应存在。典型反模式：

```java
// 反模式：不应作为公开 API
public Object getVariable(String instanceId, String name) {
    return runtimeService.getVariable(instanceId, name);
}
```

Flowable 的 `RuntimeService`、`TaskService`、`HistoryService` 本身就是设计给下游直接注入使用的公开 API——不需要再包一层。

### 本次四提案的具体结论

| API | 结论 | 理由 |
|-----|------|------|
| `getVariable` / `setVariable` | 不实现 | 裸透传。变量读写已内嵌在 `startProcess` 和 `completeTask` 的 `vars` 参数中。下游直接注入 `RuntimeService` |
| `getActiveTasks` | 不实现 | 裸透传。项目内部（`TaskQueryModule`、`TaskValidation`）已内聚处理撤回/驳回的前置校验 |
| `suspendProcess` / `activateProcess` | 不实现（当前形态） | 裸透传。若未来需要加上权限模型 + 事件发布等增值逻辑，可重新评估并纳入 `ProcessLifecycleOperations` |
| `isTaskActive` | 不实现 | 裸透传。已有 `ProcessSummaryVO` 和 `ApprovalRecordVO` 提供更丰富的状态信息 |

## 后果

- 下游与 flowable-plus 之间有一道清晰的边界线：领域增强找 flowable-plus，引擎直调用 Flowable 原生 API
- 下游需要同时理解两个 API 层（flowable-plus + Flowable 原生），而非期待 flowable-plus 把整颗引擎包成一个巨物
- 新增 API 的评审标准从"有用吗"升级为"有领域增值吗"
- 当多项目反复出现同样的裸透传需求时，应优先审视是否存在**跨项目的薄壳代码**，通过 `EngineAccessOperations` 等便利接口收敛——但收敛的前提仍然是多个项目已验证了该模式的价值
