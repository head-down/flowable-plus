# CompletionCondition 表达式编写指南

Flowable 多实例（Multi-Instance）节点通过 `completionCondition` 表达式决定何时结束并行/串行审批循环。本文档提供面向会签/或签场景的表达式模板和最佳实践。

## 表达式语法

`completionCondition` 使用 JUEL（Java Unified Expression Language）表达式，书写在 BPMN XML 的 `<multiInstanceLoopCharacteristics>` 元素中：

```xml
<multiInstanceLoopCharacteristics isSequential="false">
  <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
</multiInstanceLoopCharacteristics>
```

## 内置变量

| 变量 | 说明 |
|------|------|
| `nrOfInstances` | 本轮会签的审批人总数 |
| `nrOfActiveInstances` | 尚未完成（未投票）的子任务数 |
| `nrOfCompletedInstances` | 已完成（已投票）的子任务数 |
| `loopCounter` | 当前子任务索引（串行模式下有用） |

## 预置表达式模板

### 会签（全票通过）

所有审批人同意后流程才推进。

```
${nrOfCompletedInstances >= nrOfInstances}
```

**适用场景**：请假审批、合同签署等需要全员同意的严格流程。

**BPMN 示例**：
```xml
<userTask id="counterSignTask" name="部门审批"
    flowable:assignee="${assignee}">
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="approvers" flowable:elementVariable="assignee">
    <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

### 或签（任一通过）

任一审批人同意后流程即推进。

```
${nrOfCompletedInstances >= 1}
```

**适用场景**：备用审批、快速审批等满足其一即推进的流程。

### 过半数通过

超过半数审批人同意后流程推进。

```
${nrOfCompletedInstances > nrOfInstances / 2}
```

**适用场景**：民主表决、评审会议等多数票决策场景。

### 自定义通过比例

按比例计算所需的通过人数。

```
${nrOfCompletedInstances >= nrOfInstances * 0.6}
```

60% 通过率阈值。可根据业务需要调整比例。

### 组合条件：驳回人数达标即否决

当驳回人数（未完成数 <= 特定值）达到阈值时判定为否决。

```
${nrOfActiveInstances <= 1}
```

表示反驳人数已耗尽，剩余未投票人数为 0 或 1。

## API 层使用

在 Flowable Plus 中，会签和或签在 BPMN 层通过 `completionCondition` 区分，在 API 层统一使用 `counterSign()` 方法：

```java
// 会签/或签投票
flowablePlus.counterSign(taskId, true, variables, "同意");  // true=同意
flowablePlus.counterSign(taskId, false, null, "不同意");     // false=驳回

// 加签
flowablePlus.addCounterSigner(taskId, Arrays.asList("user4", "user5"));

// 减签
flowablePlus.removeCounterSigner(taskId, "user3");
```

`counterSign()` 内部自动写入 AGREE（同意）或 COUNTER_SIGN_REJECT（驳回）类型的审批意见，通过 `CounterSignCallback` SPI 通知业务系统。

## 备注

- `nrOfInstances` 在加签/减签后不会自动更新。若需动态人数，建议使用 `nrOfActiveInstances + nrOfCompletedInstances` 的组合。
- 并行多实例（`isSequential="false"`）适合大多数审批场景；串行多实例（`isSequential="true"`）适用于需要按顺序审批并有理由跳过的场景。
- completionCondition 在每一个子任务完成后评估一次。
