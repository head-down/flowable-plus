# ADR-0035: 折返后发起人决策任务放行常规驳回/返回/跳转/撤回

**日期**: 2026-08-13
**状态**: 已接受

## 上下文

会签（模式A，动态会签）节点投票结束后，上游 jw-zhyg-api 用 `moveActivityIdTo` 将执行流拉回
会签节点，以 `assigneeList=[会签发起人]` 重建多实例，生成"折返后发起人决策任务"。该任务运行时特征：

- 活跃任务数 == 1（只有发起人一个）；
- `assignee` == 会签发起人，对应流程变量 `countersignInitiator_<taskDefinitionKey>`
  （模式A下由 `trySetCounterSignInitiator` 在首次加签时写入）；
- 该节点全局历史任务数 > 1（含上一轮会签投票任务）。

按 ADR-0034 的运行时判定，该任务为非伪单例（历史任务数 > 1）→ 常规操作全部被拦截，
报"任务 XX 是多实例子任务，请使用会签操作(counterSign)"。发起人在折返决策任务上只剩
两条路径：同意放行（上游 `completePseudoSingletonByEngine`）、重新发起会签。以下场景无路可走：

1. 会签中有不同意见，发起人认为需要退回修改数据后重新提交；
2. 数据填写有误，需要驳回到发起人节点重提；
3. 需要驳回上一节点重审，或返回/跳转到指定历史节点调整处理人。

"退回重提"是刚需，被引擎层一刀切拦死（需求载体：
`docs/planning/foldback-initiator-decision-task-requirement.md`）。

## 决策

### 1. 识别条件：发起人单持 MI 决策任务

在 `MultiInstanceDetector` 新增 `isInitiatorDecisionTask` 判定：

```
isMultiInstance(task) == true
&& 活跃任务数 == 1
&& 该节点全局历史任务数 > 1
&& 当前任务 assignee == 流程变量 countersignInitiator_<taskDefinitionKey>
```

### 2. 识别变量无 fallback、不引入 SPI

- **无 fallback 裸变量**：模式A下 `countersignInitiator_<key>` 由 flowable-plus 自己写入；
  折返仅发生在投票结束后，模式A投票必经加签，变量必然存在。真遇缺失（理论不可达）→
  不识别 → 保持拦截，属安全侧失败。
- **不引入 SPI**（否决 `CountersignInitiatorResolver`）：上游仅有动态会签（模式A）、
  无固定会签（模式B），SPI 无使用者，属死接口风险（同 ADR-0029/0030 收敛方向）；
  识别数据全部是 flowable-plus 自产，零业务系统私有依赖。

### 3. 放行范围

对折返后发起人决策任务放行以下常规操作：

- `rejectTask`（驳回上一节点）
- `rejectTaskToInitiator`（驳回到发起人）
- `jumpToNode`（返回/跳转指定历史节点）
- `withdrawTask`（撤回；仍受其自身"上一节点审批人"权限校验约束）

**`completeTask` 保持拦截**：上游"同意"路径已通过 `completePseudoSingletonByEngine`
独立处理，且有意绕过 `eventBus.taskCompleted`（折返期间 NodeUpdateListener 需跳过
业务表节点更新）。放行 `completeTask` 需确认 eventBus 触发链对折返任务无副作用，
为稳妥起见不放行。

### 4. "会签剩最后 1 人未投"保持拦截的依据

该场景 assignee 是投票人而非发起人，不满足 `assignee == countersignInitiator_<key>`，
自然不命中识别条件。此拦截依赖模式A不变量（上游用法约定）：**发起人加签后其待办消失、
不投票，因此最后一个未投票人不可能是发起人**。

## 兼容性

| 接入方形态 | 兼容情况 |
|-----------|---------|
| 上游 jw-zhyg-api（折返决策任务） | ⚠️ 行为变化：4 个操作从拦截变为放行，本次目标 |
| 其他调用方 | ✅ 无变化：识别条件仅命中"发起人单持 MI 决策任务"，其余场景保持拦截 |
| `MultiInstanceDetector` 构造签名 | ✅ 不变：变量读取复用已注入的 `TaskService`（`getVariable` 可读流程级变量） |
| 会签侧 | ✅ 行为不变：counterSign/加签/减签/委派校验不受影响 |

## 后果

- **正面**：
  - 折返决策任务获得可用"退回重提/驳回/跳转/撤回"路径，满足"退回重提"刚需；
  - 无 SPI、无 fallback，识别口径单一（flowable-plus 自产变量），无死接口风险；
  - `completeTask` 保持拦截，规避 eventBus 触发链对折返任务的潜在副作用。
- **负面 / 风险**：
  - 折返决策任务与伪单例在引擎层执行同一动作（移走唯一活跃子任务），无孤立 miBody
    残留的引擎行为已由 ADR-0034 集成测试验证，安全性等价；
  - `withdrawTask` 上一节点审批人校验在折返后的解析、`checkActiveParallelBranch` 在
    重建 MI body 内的行为需测试验证（集成测试覆盖，均通过）；
  - 内测期无人使用（仅 jw-zhyg-api），行为变更风险低。
