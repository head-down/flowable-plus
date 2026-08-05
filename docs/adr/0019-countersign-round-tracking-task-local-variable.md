# ADR-0019: 会签多轮次追踪采用 Task 局部变量 csRoundIndex

**日期**: 2026-08-04
**状态**: 已实现

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
List<Task> activeTasks = taskService.createTaskQuery()
        .processInstanceId(processInstanceId).taskDefinitionKey(activityId).active().list();
for (Task t : activeTasks) {
    if (newAssigneeSet.contains(t.getAssignee())) {
        taskService.setVariableLocal(t.getId(), CS_ROUND_INDEX_VAR, newRoundIndex);
    }
}
```

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

## 后果

- **正面**：轮次追踪精确可靠，不依赖启发式推断；写读一致；N→1 降维减少 DB 开销
- **负面**：每轮加签需额外一次 `setVariableLocal` 调用（在批量打标时分摊）
