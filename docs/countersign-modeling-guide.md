# BPMN 会签建模与变量注入规范

flowable-plus 为会签（多实例）节点提供两种接入模式，覆盖不同的业务场景和人员确定时机。本文档说明两种模式的 BPMN 配置方式、变量注入时机、前端交互差异，以及与 `AssigneeResolver` SPI 的集成方式。

## 目录

- [前置知识](#前置知识)
- [核心约束：assignee 必须引用元素变量](#核心约束assignee-必须引用元素变量)
- [模式概览](#模式概览)
- [模式 A：偶发性会签（动态加签）](#模式-a偶发性会签动态加签)
- [模式 B：固定/前置会签（预填充列表）](#模式-b固定前置会签预填充列表)
- [AssigneeResolver SPI 集成](#assigneeResolver-spi-集成)
- [前端交互差异](#前端交互差异)
- [常见问题](#常见问题)
- [相关文档](#相关文档)

## 前置知识

会签节点在 BPMN 中定义为带有 `<multiInstanceLoopCharacteristics>` 的 `userTask`，通过 `flowable:collection` 指定审批人列表变量，通过 `flowable:elementVariable` 指定每个子任务的审批人变量名。

```xml
<userTask id="countersignTask" flowable:assignee="${assignee}">
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="${assigneeList}"
      flowable:elementVariable="assignee">
    <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

> 关于 `completionCondition` 表达式的详细说明，参见 [CompletionCondition 表达式编写指南](./completion-condition.md)。

## 核心约束：assignee 必须引用元素变量

> 本约束为会签节点的**硬性建模要求**，违反将导致加签任务错分。来源与机制详见 [ADR-0026](./adr/0026-countersign-assignee-element-variable-constraint.md)。

会签节点的 `flowable:assignee` **必须写 `${assignee}`**（引用元素变量，与 `flowable:elementVariable` 同名）。**三处命名必须一致**：

| 位置 | 要求 |
|------|------|
| `flowable:elementVariable` | `assignee` |
| `flowable:assignee` 表达式 | `${assignee}` |
| `addCounterSigner` 加签写入的变量名 | `assignee`（框架固定值，不可配置） |

**为什么必须**：任务创建时，引擎在**当前子执行**上求值 assignee 表达式（`UserTaskActivityBehavior.handleAssignments`）。`${assignee}` 命中子执行局部的元素变量，把任务分给"该子实例对应的人"；若写成 `${nextApprover}` 等其他变量，引擎会**沿作用域链向上查找**，命中流程实例级变量（上一步流转设置的旧值，所有子实例共享同一个），导致**所有加签任务错分给同一个人**，加签人收不到任务。

**"恰好正确"陷阱**：节点首次进入时，流程级 `nextApprover` 恰好等于第一个审批人（伪单例 `assigneeList=[第一审批人]`），初始任务看起来正常；**一旦加签**（`addMultiInstanceExecution` 创建新子执行）必现错分，是隐性 bug。

**非会签节点不受此约束**：普通单例节点继续使用 `flowable:assignee="${nextApprover}"`，由流程变量 `nextApprover` 指定下一审批人。

## 模式概览

| 维度 | 模式 A：偶发性会签 | 模式 B：固定/前置会签 |
|------|-------------------|---------------------|
| 初始审批人数 | 1 人（Owner） | 多人（完整列表） |
| 人员确定时机 | 运行时动态加签 | 流程启动/上游提交时预填 |
| 前端 UI | 伪单例 → 多人投票 | 直接进入多人投票 |
| 适用场景 | 审批人事先不确定 | 审批人由部门/角色/岗位固定 |
| 是否需要 SPI | 否 | 可选（`AssigneeResolver` 自动填充） |

**BPMN 配置完全相同**，区别在于 `assigneeList` 流程变量的注入方式和时机。

## 模式 A：偶发性会签（动态加签）

### 场景描述

审批人事先不确定，由发起人或当前审批人根据实际情况动态选择会签人。

### 变量注入时机

发起流程或上游节点提交时，`assigneeList` 只放 1 个 Owner：

```java
Map<String, Object> variables = new HashMap<>();
variables.put("assigneeList", Collections.singletonList("发起人"));
runtimeService.startProcessInstanceByKey("myProcess", variables);
```

### BPMN 配置样例

```xml
<process id="dynamicCountersignProcess" name="动态加签流程">
  <startEvent id="start" />
  <userTask id="applyTask" name="发起申请" flowable:assignee="${initiator}" />

  <!-- 会签节点 — 初始仅 1 人 -->
  <userTask id="countersignTask" name="会签审批"
      flowable:assignee="${assignee}">
    <multiInstanceLoopCharacteristics isSequential="false"
        flowable:collection="${assigneeList}"
        flowable:elementVariable="assignee">
      <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
    </multiInstanceLoopCharacteristics>
  </userTask>

  <userTask id="finalApprove" name="终审" flowable:assignee="${manager}" />
  <endEvent id="end" />

  <sequenceFlow sourceRef="start" targetRef="applyTask" />
  <sequenceFlow sourceRef="applyTask" targetRef="countersignTask" />
  <sequenceFlow sourceRef="countersignTask" targetRef="finalApprove" />
  <sequenceFlow sourceRef="finalApprove" targetRef="end" />
</process>
```

### 运行时行为

1. 流程到达会签节点时，仅 Owner 看到待办任务
2. Owner 通过 `addCounterSigner` 动态追加审批人：
   ```java
   flowablePlus.addCounterSigner(taskId, Arrays.asList("userA", "userB"));
   ```
3. 新审批人收到待办任务，开始多人投票
4. 通过 `counterSign` 逐人投票：
   ```java
   flowablePlus.counterSign(taskId, true, variables, "同意");
   ```
5. 可通过 `removeCounterSigner` 移除未投票的审批人

## 模式 B：固定/前置会签（预填充列表）

### 场景描述

审批人在流程设计时就已确定（如固定部门角色、固定岗位等），不需要运行时动态调整。

### 变量注入方式

> **推荐**：使用 `AssigneeResolver` SPI 自动注 入，避免在各入口代码中重复编写人员获取逻辑。

**方式一：AssigneeResolver SPI（推荐）**

在 BPMN 中配置 `TaskListener`，框架在任务创建时自动从 SPI 获取审批人列表：

```xml
<userTask id="countersignTask" name="会签审批"
    flowable:assignee="${assignee}">
  <extensionElements>
    <flowable:taskListener event="create"
        delegateExpression="${countersignAssigneesListener}" />
  </extensionElements>
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="${assigneeList}"
      flowable:elementVariable="assignee">
    <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

下游项目实现 SPI：
```java
@Component
public class OrgAssigneeResolver implements AssigneeResolver {
    @Override
    public List<String> resolveCountersignAssignees(
            String processInstanceId, String taskDefinitionKey) {
        // 从组织架构/角色/岗位获取审批人列表
        return orgService.getApproversByRole("部门经理");
    }
}
```

> 详细说明见 [AssigneeResolver SPI 集成](#assigneeResolver-spi-集成)。

**方式二：业务代码手动注入**

在上游节点提交时，将 `assigneeList` 作为流程变量传入：

```java
Map<String, Object> variables = new HashMap<>();
variables.put("assigneeList", Arrays.asList("userA", "userB", "userC"));
taskService.complete(taskId, variables);
```

### BPMN 配置样例

```xml
<process id="fixedCountersignProcess" name="固定会签流程">
  <startEvent id="start" />
  <userTask id="applyTask" name="发起申请" flowable:assignee="${initiator}" />

  <!-- 会签节点 — TaskListener 自动填充 assigneeList -->
  <userTask id="countersignTask" name="会签审批"
      flowable:assignee="${assignee}">
    <extensionElements>
      <flowable:taskListener event="create"
          delegateExpression="${countersignAssigneesListener}" />
    </extensionElements>
    <multiInstanceLoopCharacteristics isSequential="false"
        flowable:collection="${assigneeList}"
        flowable:elementVariable="assignee">
      <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
    </multiInstanceLoopCharacteristics>
  </userTask>

  <userTask id="finalApprove" name="终审" flowable:assignee="${manager}" />
  <endEvent id="end" />

  <sequenceFlow sourceRef="start" targetRef="applyTask" />
  <sequenceFlow sourceRef="applyTask" targetRef="countersignTask" />
  <sequenceFlow sourceRef="countersignTask" targetRef="finalApprove" />
  <sequenceFlow sourceRef="finalApprove" targetRef="end" />
</process>
```

### 运行时行为

1. 流程到达会签节点时，`CountersignAssigneesListener` 检测 `assigneeList` 为空
2. 调用 `AssigneeResolverRegistry` 获取审批人列表
3. Flowable 自动创建多实例子任务，所有审批人收到待办
4. "发起会签"按钮自动隐藏（`activeCount > 1`）
5. 通过 `counterSign` 逐人投票

## AssigneeResolver SPI 集成

### 接口定义

```java
@FunctionalInterface
public interface AssigneeResolver {
    List<String> resolveCountersignAssignees(
        String processInstanceId, String taskDefinitionKey);
}
```

### 调用时机

`CountersignAssigneesListener` 在 MI 节点的 `TaskListener(create)` 事件中触发，**仅当 `assigneeList` 为空或 null 时**才调用 SPI：

```
MI 节点创建子任务
  ├── assigneeList 已存在且非空 → 跳过，不做任何处理
  └── assigneeList 为空/null
       └── 调用 AssigneeResolverRegistry.resolve()
            ├── 有结果 → 写入 assigneeList，Flowable 自动创建子任务
            └── 无结果 → 不做处理（assigneeList 保持为空）
```

### 多解析器链

`AssigneeResolverRegistry` 按注册顺序依次调用每个 `AssigneeResolver` 实现，取第一个非空结果。多个 `@Component AssigneeResolver` 实现会自动收集。

### 与会签回退策略的协同

| 回退策略 | 是否需要 AssigneeResolver | 说明 |
|----------|-------------------------|------|
| `auto-redirect`（默认） | 否 | 重定向至前置单例节点，不依赖 SPI |
| `auto-rebuild` | 是 | SPI 提供新 assigneeList → 原地重建 MI |
| `strict` | 否 | 遇 MI 全部拦截 |

## 前端交互差异

| 交互点 | 模式 A（偶发） | 模式 B（固定） |
|--------|--------------|--------------|
| 初始审批人列表 | 1 人（伪单例 UI） | 多人 |
| "发起会签/加签"按钮 | 可见 | 隐藏 |
| "减签"操作 | 可见 | 可见（对未投票人） |
| 投票方式 | 逐人 `counterSign` | 逐人 `counterSign` |
| 驳回方式 | `counterSign(taskId, false, ...)` | `counterSign(taskId, false, ...)` |
| 撤回方式 | `withdraw(taskId)` | `withdraw(taskId)` |
| 会签人列表展示 | 可编辑（加/减） | 只读 |

## 常见问题

### Q: 两种模式的 BPMN 配置真的完全相同吗？

是的。区别仅在于 `assigneeList` 流程变量的注入方式：
- 模式 A：启动时只放 1 人，后续通过 API 加签
- 模式 B：启动时预填完整列表（或通过 SPI 自动填充）

### Q: "发起会签"按钮的显隐逻辑？

框架通过 `CounterSignWorkflow.isMultiInstanceFinished(task)` 判断当前轮次是否已完成。当 `activeCount > 1` 时返回 `false`（表示当前还在会签中），"发起会签"按钮应隐藏。

### Q: AssigneeResolver 返回空列表会发生什么？

`CountersignAssigneesListener` 不会修改 `assigneeList`。此时该 MI 节点没有审批人，流程将停滞。建议下游项目在实现 SPI 时保证返回有效的审批人列表。

### Q: 可以在同一个流程中混用两种模式吗？

可以。不同 MI 节点可以独立选择是否配置 `CountersignAssigneesListener`。例如：节点 A 使用 `TaskListener` 自动填充，节点 B 由业务代码手动注入。

## 相关文档

- [CompletionCondition 表达式编写指南](./completion-condition.md)
- [ADR-0022：会签建模双模式规范与 AssigneeResolver 扩展点](./adr/0022-countersign-dual-mode-modeling-and-assignee-resolver-spi.md)
