# 会签加签/减签/发起权限模型调研报告

**日期**: 2026-08-07
**状态**: 已定稿（修订：2026-08-07，作用域陷阱修正）
**触发**: 下游反馈加签权限校验使用了错误的模型（流程发起人而非会签发起人）

---

## 1. 现状分析

### 1.1 当前权限模型

`CounterSignWorkflow.validateCounterSignPermission()`（第450-470行）：

```
通过条件：当前会签节点活跃审批人 OR 流程发起人(startUserId)
失败      → PermissionDeniedException
```

| 操作 | 当前校验 |
|------|---------|
| `counterSign`（投票） | 必须是当前子任务的 assignee |
| `addCounterSigner`（加签/发起会签） | 活跃审批人 OR 流程发起人 |
| `removeCounterSigner`（减签） | 活跃审批人 OR 流程发起人 |
| `delegateTask`（委派） | 必须是当前子任务的 assignee |

### 1.2 ADR-0022 定义的两种模式

模式来源于 ADR-0022《会签建模双模式规范》：

| 特性 | 模式A：偶发性会签（伪单例） | 模式B：固定/前置会签 |
|------|---------------------------|---------------------|
| 初始 assigneeList | 只放 1 个 Owner | 预填完整列表 |
| 运行时初始活跃人数 | 1 | N (N > 1) |
| "发起会签"概念 | 有（Owner 通过 addCounterSigner 追加） | 无（直接进入多人投票） |
| 权限语义 | Owner 是"会签发起人" | 所有人平等，无"发起人" |

### 1.3 伪单例的运行时判断

`isMultiInstanceFinished()` 方法区分伪单例状态：

```
activeCount == 1 && finishedCount == 0 → 伪单例（未完成，可加签）
activeCount == 1 && finishedCount > 0  → 最后一人（即将完成）
activeCount == 0                       → 全部完成（可开新轮次）
```

### 1.4 当前存在的三个问题

#### 问题1：校验对象错误——使用了"流程发起人"

`startUserId` 是**流程实例**的发起人，与会签节点的"发起人"无必然关联：

> 场景：部门助理 userB 发起了一个采购流程，流程走到部门经理 userA。userA 是会签节点的 Owner（初始唯一审批人），他应该掌握加签控制权。但当前检查的是 `startUserId` = userB（流程发起人），而非 userA（会签发起人）。

#### 问题2：校验规则未区分两种模式

两种模式的权限语义不同，但当前校验一视同仁：

| 模式 | 合理权限模型 | 当前模型 |
|------|------------|---------|
| 模式A（伪单例） | 会签发起人控制 | 任意活跃审批人 OR 流程发起人 |
| 模式B（固定会签） | 活跃审批人自由操作 | 同上 |

模式A中，Owner 加签了 userB 和 userC 之后，userB/userC 是否应该有权再加签 userD？当前模型允许，但业务上 Owner 可能希望集中控制。

#### 问题3：校验范围未显式覆盖"发起会签"

虽然 `addCounterSigner` 也承担了"发起会签"的职责，但校验逻辑没有区分"首次发起"和"追加加签"的语义差异。

---

## 2. 主流平台调研

### 2.1 钉钉审批

**权限模型**：仅当前节点活跃审批人可加签

> "只有当前正在处理该节点待办任务的审批人，才能在任务处理界面看到并使用'加签'按钮"
> — 钉钉开放平台文档

- 加签权限跟随审批人身份
- 管理员可控制节点是否允许加签
- 不支持非审批人（包括流程发起人）远程加签

### 2.2 飞书审批

**权限模型**：审批人可加签

> "若开启了'允许审批人加签'，则审批人可以进行加签操作"
> — 飞书帮助中心

- 管理员管控开关
- 加签方式：并加签、后加签
- 不支持非审批人操作

### 2.3 Flowable 原生

Flowable 本身不定义加签/减签的权限模型，`addMultiInstanceExecution` / `deleteMultiInstanceExecution` 是底层 API，由应用层自行封装权限校验。

### 2.4 主流方案总结

| 平台 | 谁能加签 | 谁能减签 | 是否有"发起人"概念 |
|------|---------|---------|------------------|
| 钉钉 | 当前审批人 | 未明确 | 无 |
| 飞书 | 当前审批人 | 管理员配置 | 无 |
| 企业微信 | 当前审批人 | 未明确 | 无 |
| Flowable 原生 | 应用层定义 | 应用层定义 | 无 |

**主流做法一致**：加签/减签权限仅授予**当前节点的活跃审批人**，不存在"流程发起人旁路"或"会签发起人"的概念。

---

## 3. flowable-plus 的差异化定位

flowable-plus 的"偶发性会签"模式（模式A）是区别于主流平台的重要特性：

- **钉钉/飞书**：会签人员在设计时已确定，审批人之间平等
- **flowable-plus 模式A**：会签人员由 Owner 在运行时动态决定，Owner 天然拥有控制权

这种差异化需求导致**不能简单套用主流平台的"任意审批人可操作"模型**。

---

## 4. 方案分析

### 4.1 核心概念：会签发起人 (countersignInitiator)

**定义**：模式A中，MI 节点启动时的唯一审批人。

**生命周期**：
- 诞生：伪单例状态下首次调用 `addCounterSigner` 时
- 持久化：记录为带节点后缀的流程变量 `countersignInitiator_<taskDefinitionKey>`
- 消亡：流程实例结束

**与流程发起人的区别**：

| 概念 | 来源 | 作用域 | 示例 |
|------|------|--------|------|
| 流程发起人 | `HistoryService.startUserId` | 整个流程实例 | userB 发起采购申请 |
| 会签发起人 | `countersignInitiator_<key>` | 特定 MI 节点 | userA 是部门经理会签节点的 Owner |

> **命名统一**：`countersignInitiator` 沿用 `TaskExecutionOperations` Javadoc 中已确立的领域术语，避免新造 `csInitiator` 等缩写。

### 4.2 变量作用域陷阱（致命缺陷）

#### 问题

若将 `countersignInitiator` 设为**全局流程变量**（Process Instance 级别），在多会签节点流程中将发生灾难性冲突：

```
流程: [技术会签] → [财务会签]

技术会签节点: Owner 张三发起会签
  → runtimeService.setVariable("countersignInitiator", "张三")  ← 全局写入

技术会签完成，流程推进到财务会签

财务会签节点: Owner 李四调用 addCounterSigner
  → runtimeService.getVariable("countersignInitiator") → "张三"
  → 李四 != "张三"
  → 💥 PermissionDeniedException: 李四被拒绝！
```

#### 修复方案：带节点后缀的变量

```java
// 变量命名模式
static final String INITIATOR_VAR_PREFIX = "countersignInitiator";

// 使用示例
String varName = INITIATOR_VAR_PREFIX + "_" + taskDefinitionKey;
// 技术会签 → countersignInitiator_technicalReview
// 财务会签 → countersignInitiator_financeReview
```

**作用域方案对比**：

| 方案 | 变量名 | 优点 | 缺点 |
|------|--------|------|------|
| 全局 + taskDefinitionKey 后缀 | `countersignInitiator_<key>` | 简单、`getVariable` 直接读取、天然跨节点隔离 | 变量名动态拼接 |
| Task 局部变量 | `setVariableLocal(taskId, ...)` | 完美隔离 | task 完成后变量进历史表，跨轮次查询需跨表 |
| Execution 局部变量 | `setVariableLocal(execId, ...)` | 作用域正确 | 需找到 MI 根执行 ID，父子继承链复杂 |

**推荐**：全局 + `taskDefinitionKey` 后缀。代码最简洁、无跨表查询、天然隔离。

### 4.3 两种模式的权限模型

#### 模式A：伪单例（countersignInitiator 已设置）

```
加签/减签权限：仅会签发起人
投票权限    ：当前子任务的 assignee
委派权限    ：当前子任务的 assignee
```

**理由**：
- Owner 发起会签，掌握控制权
- 被加签的人不应再越权加签他人
- 保持审批链的可控性

#### 模式B：固定会签（countersignInitiator 未设置）

```
加签/减签权限：任意活跃审批人
投票权限    ：当前子任务的 assignee
委派权限    ：当前子任务的 assignee
```

**理由**：
- 所有审批人平等，无"发起人"
- 与钉钉/飞书等主流平台一致
- 框架保持最低限度的权限约束

### 4.4 countersignInitiator 的设置时机

| 触发点 | 条件 | 操作 |
|--------|------|------|
| `addCounterSigner` 首次调用 | 伪单例状态（activeCount=1, finishedCount=0） | 设 `countersignInitiator_<key>` = 当前用户 |
| `addCounterSigner` 后续调用 | 变量已存在 | 校验当前用户 == countersignInitiator |
| `removeCounterSigner` | 变量存在 | 校验当前用户 == countersignInitiator |
| `removeCounterSigner` | 变量不存在 | 校验当前用户 in 活跃审批人列表 |

### 4.5 边界情况

| 场景 | 行为 |
|------|------|
| 模式A + 非会签发起人加签 | 拒绝，抛出 PermissionDeniedException |
| 模式A + 非会签发起人减签 | 拒绝，抛出 PermissionDeniedException |
| 模式B + 任意活跃审批人加签/减签 | 通过 |
| 模式A + countersignInitiator 本人投票 | 通过（当前子任务 assignee 校验不变） |
| 模式B + 非活跃审批人加签/减签 | 拒绝（不在活跃审批人列表中） |
| 多轮会签（countersignInitiator 已设置） | 后续轮次仍由原始 countersignInitiator 控制 |
| 多会签节点（技术→财务） | 各自维护 `countersignInitiator_<各自key>`，互不干扰 |
| 模式A 初态 + addCounterSigner 全部跳过（重复人） | 不写变量，下次仍可设置 |

---

## 5. 推荐方案

### 5.1 方案概述

引入 `countersignInitiator` 概念，通过 `taskDefinitionKey` 后缀隔离不同节点，区分两种模式的权限校验。

```
validateCounterSignPermission(task):
    varName = "countersignInitiator_" + task.taskDefinitionKey
    initiator = getVariable(varName)

    if initiator 存在（模式A）:
        if 当前用户 == initiator → 通过
        else → PermissionDeniedException

    else（模式B）:
        if 当前用户在活跃审批人列表中 → 通过
        else → PermissionDeniedException
```

### 5.2 具体改动

#### 5.2.1 CounterSignWorkflow 改动

1. **新增常量**：
   ```java
   /** 会签发起人变量名前缀，使用时拼接 taskDefinitionKey 以隔离节点 */
   static final String COUNTERSIGN_INITIATOR_PREFIX = "countersignInitiator";
   ```

2. **重写 `validateCounterSignPermission`**：
   - 移除 `startUserId` 流程发起人校验
   - 通过 `countersignInitiator_<taskDefinitionKey>` 判断模式
   - 模式A：仅 countersignInitiator 可操作
   - 模式B：活跃审批人可操作

3. **`addCounterSigner` 中设置 countersignInitiator**：
   - 在伪单例状态首次加签时，写入 `countersignInitiator_<key>` 流程变量

#### 5.2.2 接口文档更新

`CounterSignOperations` 接口的 Javadoc 从"流程发起人"更新为"会签发起人（模式A）或活跃审批人（模式B）"。

#### 5.2.3 测试更新

| 测试类别 | 覆盖场景 |
|---------|---------|
| 模式A | countersignInitiator 本人加签/减签 → 通过 |
| 模式A | 被加签人（非 countersignInitiator）加签 → 拒绝 |
| 模式A | 流程发起人（非 countersignInitiator）加签 → 拒绝 |
| 模式B | 活跃审批人加签/减签 → 通过 |
| 模式B | 非活跃审批人加签/减签 → 拒绝 |
| 作用域 | 多会签节点各自维护独立的 countersignInitiator，互不干扰 |
| 模式B | countersignInitiator 未设置，活跃审批人加签后变量仍不存在 |

### 5.3 方案对比

| 维度 | 当前方案 | 推荐方案 |
|------|---------|---------|
| 校验对象 | startUserId（流程发起人） | countersignInitiator（会签发起人）+ 活跃审批人 |
| 模式感知 | 无（一视同仁） | 有（模式A/B 不同规则） |
| 模式A 控制权 | 分散（任意审批人可操作） | 集中（仅 countersignInitiator） |
| 模式B 开放度 | 高（+流程发起人旁路） | 适中（仅活跃审批人） |
| 多节点隔离 | N/A | taskDefinitionKey 后缀天然隔离 |
| 与主流平台一致性 | 偏离 | 模式B 一致 |

---

## 6. 备选方案

### 6.1 方案B：countersignInitiator + 活跃审批人（宽松版）

```
模式A: countersignInitiator OR 活跃审批人 → 通过
模式B: 活跃审批人 → 通过
```

**区别**：模式A中被加签的人也可以再加签他人。更开放，但与"会签发起人集中控制"的语义冲突。

### 6.2 方案C：仅活跃审批人（极简版）

```
统一规则: 活跃审批人 → 通过
```

与钉钉/飞书完全一致，但放弃了 flowable-plus 模式A的差异化优势。

### 6.3 方案D：SPI 回调扩展

将权限校验抽成 `CounterSignPermissionValidator` SPI，由下游项目自行实现。

```java
@FunctionalInterface
public interface CounterSignPermissionValidator {
    boolean isAuthorized(String userId, String processInstanceId,
                         String taskDefinitionKey, String operation);
}
```

**优点**：最大灵活度
**缺点**：增加接入复杂度，框架不再提供默认行为

---

## 7. 建议

推荐 **方案5.1（countersignInitiator 严格模式，带 taskDefinitionKey 后缀）**，理由：

1. **语义正确**：countersignInitiator 精确表达了模式A中"会签发起人"的概念
2. **安全合理**：模式A集中控制，模式B平等协作
3. **作用域安全**：taskDefinitionKey 后缀天然隔离多节点，消除全局变量灾难
4. **命名统一**：沿用 `TaskExecutionOperations` 已确立的 `countersignInitiator` 领域术语
5. **兼容主流**：模式B 与钉钉/飞书一致
6. **改动可控**：改动集中在 `CounterSignWorkflow` 一个类，不涉及 SPI 扩展
