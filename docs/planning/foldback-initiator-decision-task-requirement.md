# 需求：折返后发起人决策任务放行常规操作（驳回/返回/跳转/撤回）

**提交方**: jw-zhyg-api（下游接入方）
**日期**: 2026-08-13
**类型**: 功能变更请求
**优先级**: 待定
**状态**: 评审中

---

## 方案决策要点（评审结论）

- **识别变量**：使用 flowable-plus 自产流程变量 `countersignInitiator_<taskDefinitionKey>`，**无 fallback 裸变量**；
- **否决 SPI**（`CountersignInitiatorResolver`）：上游仅有动态会签（模式A）、无固定会签（模式B），SPI 无使用者，属死接口风险；识别数据全部是 flowable-plus 自产，零业务系统私有依赖；
- **放行范围**：`rejectTask` / `rejectTaskToInitiator` / `jumpToNode` / `withdrawTask` 放行；`completeTask` 保持拦截；
- **上游已确认**：折返重建时 `assigneeList=[发起人]` 中的"发起人"即为 `countersignInitiator_<key>` 变量的值，识别口径一致。

---

## 一、背景

flowable-plus 的会签折返机制：会签（模式A，动态会签）节点投票结束后，
由上游 jw-zhyg-api 用 `moveActivityIdTo` 将执行流拉回会签节点，以
`assigneeList=[会签发起人]` 重建 MI，生成"折返后发起人决策任务"。该任务运行时特征：

- 活跃任务数 == 1（只有发起人一个）；
- `assignee` == 会签发起人，对应流程变量 `countersignInitiator_<taskDefinitionKey>`
  （模式A下由 `trySetCounterSignInitiator` 在首次加签时写入，见 CounterSignWorkflow.java:46、452-464）；
- 该节点全局历史任务数 > 1（含上一轮会签投票任务）。

按 ADR-0034 的运行时判定，该任务为非伪单例（历史任务数 > 1）→
`TaskValidation.validateNotMultiInstance` 将其判定为"运行时多实例"，
常规操作全部被拦截，报"任务 XX 是多实例子任务，请使用会签操作(counterSign)"。

## 二、问题描述

发起人在折返决策任务上只剩两条路径：同意放行（上游 `completePseudoSingletonByEngine`）、
重新发起会签。以下场景无路可走：

1. 会签中有不同意见，发起人认为需要退回修改数据后重新提交；
2. 数据填写有误，需要驳回到发起人节点重提；
3. 需要驳回上一节点重审，或返回/跳转到指定历史节点调整处理人。

"退回重提"是刚需，当前被引擎层一刀切拦死。

## 三、变更需求

### 3.1 识别条件：发起人单持 MI 决策任务

```
isMultiInstance(task) == true
&& 活跃任务数 == 1
&& 该节点全局历史任务数 > 1
&& 当前任务 assignee == 流程变量 countersignInitiator_<taskDefinitionKey>
```

- **无 fallback 裸变量**：模式A下该变量由 flowable-plus 自己写入；折返仅发生在投票结束后，
  模式A投票必经加签，变量必然存在。真遇缺失（理论不可达）→ 不识别 → 保持拦截，属安全侧失败。
- **不引入 SPI**：理由见"方案决策要点"。

### 3.2 放行范围

对折返后发起人决策任务放行以下常规操作：

- `rejectTask`（驳回上一节点）
- `rejectTaskToInitiator`（驳回到发起人）
- `jumpToNode`（返回/跳转指定历史节点）
- `withdrawTask`（撤回；仍受其自身"上一节点审批人"权限校验约束）

**`completeTask` 保持拦截**：上游"同意"路径已通过 `completePseudoSingletonByEngine`
独立处理，且有意绕过 `eventBus.taskCompleted`（折返期间 NodeUpdateListener 需跳过
业务表节点更新）。放行 `completeTask` 需确认 eventBus 触发链对折返任务无副作用，
为稳妥起见本次不放行。

### 3.3 保持拦截的场景

| 场景 | 判据 | 结果 |
|---|---|---|
| 会签剩最后 1 人未投 | assignee 是投票人而非发起人，不满足 assignee == countersignInitiator | 拦截，必须走 counterSign |
| 模式B固定会签 | 无 `countersignInitiator_<key>` 变量 | 不识别 → 拦截 |

"会签剩最后 1 人未投"的拦截依赖模式A不变量：发起人加签后其待办消失、不投票，
因此最后一个未投票人不可能是发起人。该不变量为上游用法约定，需在 Javadoc 中显式记录。

### 3.4 改动点

| 文件 | 改动 |
|---|---|
| `MultiInstanceDetector` | 新增判定方法（如 `isInitiatorDecisionTask`），复用活跃/历史计数查询 + 一次变量读取；变量读取复用已注入的 `TaskService`（`getVariable` 可读流程级变量），**避免构造签名再次变化** |
| `TaskValidation` | `validateNotMultiInstance` 增加"是否豁免发起人决策任务"的语义（新增重载或布尔参数） |
| `TaskExecutionWorkflow` | `rejectTask` / `rejectTaskToInitiator` / `jumpToNode` / `withdrawTask` 四个入口使用豁免校验；`completeTask` 保持现有校验 |

## 四、验收标准（集成测试）

沿用 `RuntimeMultiInstanceIntegrationTest` 的断言方式（含"无孤立 miBody/子执行残留"检查）。

1. 折返后发起人决策任务上 `rejectTask` → 流程回到上一节点，目标节点出现新待办，无 miBody 残留；
2. 同上 `rejectTaskToInitiator` → 回到发起人节点；
3. 同上 `jumpToNode` → 跳回指定历史节点；
4. 同上 `withdrawTask` → 撤回（上一节点审批人校验通过的前提下）；
5. **会签剩最后 1 人未投**（投票人持任务）→ 仍拦截，`counterSign` 正常完成；
6. 折返后发起人决策任务上 `completeTask` → 仍拦截；
7. 发起人重新提交 → 重新进入会签节点任务干净重建，可正常 `counterSign` 完成；
8. 无 `countersignInitiator_<key>` 变量的场景（未加签/伪单例路径）→ 不识别，保持拦截；
9. `checkActiveParallelBranch` 在折返决策任务上不误报。

## 五、影响与风险

| 项 | 说明 |
|----|------|
| 上游行为变化 | 折返决策任务上 4 个操作从拦截变为放行（本次目标）。**需上游确认 `eventBus.taskRejected` / `taskJumped` / `taskWithdrawn` 对 NodeUpdateListener 无副作用**（与 completeTask 同源风险，不可省） |
| `checkActiveParallelBranch` | 折返任务在重建 MI body 内，父执行兄弟数可能 > 1 → 可能误报"存在并行分支"，需测试验证（见验收标准 9） |
| `withdrawTask` 权限校验 | 上一节点审批人校验在折返后的解析是否正确需测试验证 |
| 引擎安全性 | 折返决策任务与伪单例在引擎层执行同一动作（移走唯一活跃子任务），无孤立 miBody 残留的引擎行为已由 ADR-0034 集成测试验证，安全性等价 |
| 其他调用方 | 无变化：识别条件仅命中"发起人单持 MI 决策任务" |
| 内测期 | 无人使用（仅 jw-zhyg-api），行为变更风险低 |

## 六、实现后需记录的 ADR

实现完成并评审通过后，应新增 ADR-0035 记录本决策：识别条件、放行范围、
否决 SPI / 否决 fallback 的权衡、"会签剩最后 1 人未投"保持拦截的依据。
ADR 中不包含实现细节与测试清单（本文件为方案载体）。
