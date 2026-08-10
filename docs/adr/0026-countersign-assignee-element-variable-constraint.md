# ADR-0026: 会签节点 assignee 必须引用元素变量（建模约束体系）

**日期**: 2026-08-10
**状态**: 已接受

## 上下文

### 背景

jw-zhyg-api 排查"加签投票未计入会签结果"（实例 `1e401df2-947c-11f1-a3bd-085bd60729dd`）时发现：三个会签流程中，sealFabrication 的会签节点 assignee 写的是 `${assignee}`（行为正确），而 sealOperation / sealUsageBorrow 写的是 `${nextApprover}`（行为"恰好正确"，实为隐患）。

排查证实：该实例走 sealFabrication，加签任务 assignee 正确 **仅仅因为** 部署模型的 assignee 表达式引用的是元素变量 `assignee`。若写成 `${nextApprover}`，加签任务必然错分。

### 根因机制

1. 多实例节点进入时，引擎为每个子实例写入**元素变量**（`elementVariable`）；加签时 `AddMultiInstanceExecutionCmd` 也会为新建子实例写入变量。
2. 任务创建时，`UserTaskActivityBehavior.handleAssignments` 在**当前子执行**上求值 `flowable:assignee` 表达式。
3. 表达式 `${assignee}` → 命中子执行局部元素变量 → 任务分给"该子实例对应的人"。
4. 表达式 `${nextApprover}` → 子执行局部无此变量 → **沿作用域链向上查找** → 命中**流程实例级变量 `nextApprover`**（上一步流转设置的旧值，所有子实例共享）→ **所有加签任务错分给同一个人**。
5. 初始任务"恰好正确"：进入节点时 `nextApprover` 恰好等于第一个审批人（伪单例）。加签后必现错误，属于**隐性 bug**。

### 为什么变量名必须是 `assignee`

元素变量名本身不是引擎强制的（`elementVariable="owner"` 则写 `${owner}`），但**必须与 flowable-plus 加签写入的变量名一致**。`addCounterSigner` 当前实现固定写 `assignee`（见 `CounterSignWorkflow.java` 的 `executionVariables.put("assignee", assignee)`）。因此约定元素变量就叫 `assignee`。

## 决策

### 1. 建模约束：会签节点三处命名必须一致

会签节点（`multiInstanceLoopCharacteristics` 多实例节点）必须满足：

| 位置 | 要求 |
|------|------|
| BPMN 元素变量名 | `flowable:elementVariable="assignee"` |
| 任务 assignee 表达式 | `flowable:assignee="${assignee}"` |
| flowable-plus 加签写入变量名 | `assignee`（`addCounterSigner` 固定值，不可配置） |

**违反任意一处，加签功能失效**（加签任务错分给流程实例级 `nextApprover` 指向的人）。

**非会签节点不受此约束**：普通单例节点继续使用 `flowable:assignee="${nextApprover}"`，由流程变量 `nextApprover` 指定下一审批人。

### 2. BPMN 模板（会签节点标准写法）

```xml
<userTask id="countersignTask" name="..." flowable:assignee="${assignee}">
  <extensionElements>
    <jw:isCountersign>Y</jw:isCountersign>
    ...
  </extensionElements>
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="assigneeList"
      flowable:elementVariable="assignee" />
</userTask>
```

### 3. 排雷记录（jw-zhyg-api 已修正）

- `sealOperation.bpmn`：projectAuditor / financeManager / administrativeManager / auditor 共 4 个会签节点
- `sealUsageBorrow.bpmn`：legalReview / financialReview 共 2 个会签节点

两处均由 `${nextApprover}` 改为 `${assignee}`。上述流程当前 0 实例，未受影响；重新部署后仅影响新实例。

## 备选方案

- **改 flowable-plus 兼容 `${nextApprover}`**：让 `addCounterSigner` 同时写 `nextApprover` 变量。否决——会向子实例注入误导性的流程级变量，且多节点隔离性差（`nextApprover` 是全局语义，无法表达"每个实例各自的人"），违背最小改动与语义清晰原则。

## 后果

- **正面**：
  - 加签任务正确分配给各自的加签人，会签投票、轮次判定、审批历史展示正常
  - 约束体系固化，下游项目建模有据可查，避免"恰好正确"陷阱
  - 与 ADR-0022 双模式建模规范衔接（0022 定义模式与变量注入时机，0026 定义 assignee 表达式约束）
- **负面**：
  - 下游项目新增会签流程时必须正确建模（三处命名一致），建模步骤有硬性要求
- **风险**：
  - 低：若未来 flowable-plus 更改加签写入变量名，需同步更新 BPMN 模板并升级本 ADR
