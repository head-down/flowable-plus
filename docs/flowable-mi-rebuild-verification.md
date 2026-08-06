# Flowable 6.8.0 多实例节点原地重建技术验证报告

**版本**: 1.0  
**日期**: 2026-08-06  
**适用 Flowable 版本**: 6.8.0  
**关联决策**: [ADR-0021: 会签节点回退采用运行时判断 + 原地重建策略](adr/0021-countersign-rollback-runtime-detection-and-auto-redirect.md)

---

## 1. 问题背景

### 1.1 业务诉求

工作流系统中存在"会签完成后，审批人驳回/撤回/跳转回到同一个会签（多实例）节点"的需求。例如：

- 一个"项目评审"会签节点在完成后，发起人发现评审结论有问题，需要重新发起评审
- 驳回路径经过一个"回迁"节点，该节点在某些流程中是单例、某些流程中是多实例

### 1.2 官方立场空白

Flowable 6.8.0 的官方文档和 3.16.0 JavaDoc 对以下两个问题均保持沉默：

- `ChangeActivityStateBuilder.moveActivityIdTo()` 是否支持跳转到**已完成**的多实例节点？
- 跳转到 MI 节点后，引擎会如何处理 `multiInstanceLoopCharacteristics`？

官方测试 `ChangeStateForMultiInstanceTest` 覆盖了 4 个跳转到 MI 节点的场景，**全部是跳到尚未开始的 MI 节点**，无任何已完成 MI 节点的测试覆盖。

### 1.3 社区分歧

| 来源 | 描述 | 版本 |
|------|------|------|
| [GitHub #1674](https://github.com/flowable/flowable-engine/issues/1674) | MI 跳转支持在 PR #1343 中引入，**仅限活跃 MI** | 6.4.1+ |
| [GitHub #3944](https://github.com/flowable/flowable-engine/issues/3944) | MI-to-MI 跳转在 **6.8.1-7.0.1 版本中存在 Bug**：`ACT_RU_TASK` 无新任务，流程中断 | 6.8.1-7.0.1 |
| [CSDN 博客](https://blog.csdn.net/li_wen_jin/article/details/142414538) | 确认 7.0.1 Bug 根因为 `AbstractDynamicStateManager.createEmbeddedSubProcessAndExecutions` 方法变更 | 7.0.1 |
| [CSDN 问答](https://ask.csdn.net/questions/9476379) | 报告 `FlowableException: Cannot jump to activity — not in valid state` | 未指明版本 |
| 社区共识 | 6.7.x-6.8.0 版本 MI 跳转行为正常，6.8.1+ 版本存在已知 Bug | — |

**本文档的验证结论仅适用于 Flowable 6.8.0。** 升级到更高版本时必须回归测试此行为。

---

## 2. 验证结论

### 2.1 结论陈述

**Flowable 6.8.0 在满足前提条件的情况下，`moveActivityIdTo(current, targetMI)` 会触发引擎自动重建目标 MI 节点的多实例执行树。**

### 2.2 前提条件

| 条件 | 要求 | 不满足的后果 |
|------|------|-------------|
| 1. 目标节点配置了 `multiInstanceLoopCharacteristics` | BPMN 模型中存在 | 不会触发 MI 逻辑，视为普通节点 |
| 2. `collectionExpression` 引用的变量已设为非空 | 调用 `moveActivityIdTo` 前 `runtimeService.setVariable()` | `createInstances()` 返回 0 → `cleanupMiRoot()` → 执行跳过节点 |
| 3. Flowable 版本为 6.8.0 | 使用 locked 版本 | 6.8.1+ 版本 `createEmbeddedSubProcessAndExecutions` 已变更（见第 5 节） |

### 2.3 行为摘要

当条件满足时，引擎的行为链为：

```
setVariable(assigneeList) → moveActivityIdTo(current, targetMI) → changeState()
  → 删除当前 execution 及关联数据
  → 在目标节点创建新的 execution
  → ContinueProcessOperation 检测到 MI 特性
  → 创建全新的 MI root execution（旧 MI root 早已不存在）
  → MultiInstanceActivityBehavior.execute() 检测到 collectionElementIndexVariable == null
  → createInstances() 重新解析 collectionExpression 创建 N 个子实例
  → 完成：全新的多实例执行树
```

### 2.4 关键约束

- **assigneeList 必须非空**。如果为空，`resolveNrOfInstances()` 返回 0，`cleanupMiRoot()` 会删除 MI root 并让执行跳过该节点继续前进——这不是期望的行为。
- **旧 MI 数据不残留**。`doMoveExecutionState` 调用 `deleteExecutionAndRelatedData` 清理所有旧执行/任务/变量/IdentityLink，新 MI 执行树是全新创建的。

---

## 3. 完整调用链（源码级追踪）

以下分析基于 `flowable-engine-6.8.0-sources.jar` 中的源码，并标注了每个类的 Jar 内路径和关键行号。

### 3.1 入口：ChangeActivityStateBuilderImpl.moveActivityIdTo()

**类**: `org.flowable.engine.impl.runtime.ChangeActivityStateBuilderImpl`  
**Jar 内路径**: `org/flowable/engine/impl/runtime/ChangeActivityStateBuilderImpl.java`

```java
// 第 80-86 行
@Override
public ChangeActivityStateBuilder moveActivityIdTo(String currentActivityId, String newActivityId) {
    return moveActivityIdTo(currentActivityId, newActivityId, null, null);
}

public ChangeActivityStateBuilder moveActivityIdTo(String currentActivityId, String newActivityId,
        String newAssigneeId, String newOwnerId) {
    moveActivityIdList.add(new MoveActivityIdContainer(
        currentActivityId, newActivityId, newAssigneeId, newOwnerId));
    return this;
}
```

**行为**: 构建阶段不做任何 source==target 检查，不验证目标节点是否为 MI。仅将参数包装为 `MoveActivityIdContainer` 加入列表。

### 3.2 命令执行：ChangeActivityStateCmd → DefaultDynamicStateManager

**类**: `org.flowable.engine.impl.cmd.ChangeActivityStateCmd`  
**Jar 内路径**: `org/flowable/engine/impl/cmd/ChangeActivityStateCmd.java`

```java
// 第 32-38 行
DynamicStateManager dynamicStateManager = CommandContextUtil
    .getProcessEngineConfiguration(commandContext).getDynamicStateManager();
dynamicStateManager.moveExecutionState(changeActivityStateBuilder, commandContext);
```

委托给 `DefaultDynamicStateManager.moveExecutionState()`，进入抽象父类 `AbstractDynamicStateManager` 的核心逻辑。

### 3.3 执行解析：AbstractDynamicStateManager.resolveActiveExecutions()

**类**: `org.flowable.engine.impl.dynamic.AbstractDynamicStateManager`  
**Jar 内路径**: `org/flowable/engine/impl/dynamic/AbstractDynamicStateManager.java`

```java
// 第 238-260 行（伪代码摘要）
protected List<ExecutionEntity> resolveActiveExecutions(String processInstanceId,
        String activityId, CommandContext commandContext) {
    // 查找 processInstanceId 下 currentActivityId == activityId 的执行
    List<ExecutionEntity> executions = childExecutions.stream()
        .filter(e -> e.getCurrentActivityId() != null)
        .filter(e -> e.getCurrentActivityId().equals(activityId))
        .collect(Collectors.toList());
    if (executions.isEmpty()) {
        throw new FlowableException("Active execution could not be found with activity id " + activityId);
    }
    return executions;
}
```

**关键**: MI 完成后其执行树已被物理删除，`targetMI` 上无活跃执行。`moveActivityIdTo(current, targetMI)` 实际找到的是 **current** 的活动执行（由 source activityId 解析），然后将其指针改为 targetMI。

### 3.4 删除与重建：AbstractDynamicStateManager.doMoveExecutionState()

**类**: `org.flowable.engine.impl.dynamic.AbstractDynamicStateManager`  
**Jar 内路径**: `org/flowable/engine/impl/dynamic/AbstractDynamicStateManager.java`

```java
// 第 331-430 行（伪代码摘要）
protected void doMoveExecutionState(...) {
    for (MoveExecutionEntityContainer container : ...) {
        prepareMoveExecutionEntityContainer(container, ...);
        // currentFlowElement 和 newFlowElement 可能是同一个 BPMN 元素

        for (ExecutionEntity execution : executionsToMove) {
            // 步骤 A: 删除子执行（若为 MI root，删除所有子实例）
            executionEntityManager.deleteChildExecutions(execution, ...);

            // 步骤 B: 删除当前 execution 及关联数据（task, job, variable, identity link）
            executionEntityManager.deleteExecutionAndRelatedData(execution, ...);

            // 步骤 C: 清理父 SubProcess scope（若需要）
            ExecutionEntity continueParent = deleteParentExecutions(...);
        }

        // 步骤 D: 创建全新的子 execution
        List<ExecutionEntity> newChildExecutions =
            createEmbeddedSubProcessAndExecutions(...);

        // 步骤 E: 调度继续执行
        for (ExecutionEntity newChild : newChildExecutions) {
            CommandContextUtil.getAgenda(commandContext)
                .planContinueProcessOperation(newChild);
        }
    }
}
```

**关键**: `deleteExecutionAndRelatedData` 会清理所有关联数据——任务、定时器、变量、候选人/候选组、事件订阅。新 execution 是全新的，不携带任何旧状态。

**版本注意**: 步骤 D 的 `createEmbeddedSubProcessAndExecutions` 方法在 6.8.1 中被修改，这是导致社区报告 Bug 的根因（见第 5 节）。

### 3.5 继续处理：ContinueProcessOperation.continueThroughFlowNode()

**类**: `org.flowable.engine.impl.agenda.ContinueProcessOperation`  
**Jar 内路径**: `org/flowable/engine/impl/agenda/ContinueProcessOperation.java`

```java
// 第 94-120 行
protected void continueThroughFlowNode(FlowNode flowNode) {
    execution.setActive(true);

    // ... 子流程处理 ...

    if (flowNode instanceof Activity
            && ((Activity) flowNode).hasMultiInstanceLoopCharacteristics()) {
        // ★ 检测到 MI 特性 → 进入 MI 同步执行
        executeMultiInstanceSynchronous(flowNode);

    } else if (forceSynchronousOperation || !flowNode.isAsynchronous()) {
        executeSynchronous(flowNode);

    } else {
        executeAsynchronous(flowNode);
    }
}
```

**关键**: 这是触发 MI 重建的第一个判断点。引擎通过 `hasMultiInstanceLoopCharacteristics()` 检测 BPMN 模型（不是运行时状态），发现目标节点配置了 MI → 调用 `executeMultiInstanceSynchronous()`。

### 3.6 MI 检测与重建：ContinueProcessOperation.executeMultiInstanceSynchronous()

**类**: `org.flowable.engine.impl.agenda.ContinueProcessOperation`  
**Jar 内路径**: `org/flowable/engine/impl/agenda/ContinueProcessOperation.java`

```java
// 第 149-186 行
protected void executeMultiInstanceSynchronous(FlowNode flowNode) {
    // 触发 ExecutionListener START 事件
    if (CollectionUtil.isNotEmpty(flowNode.getExecutionListeners())) {
        try {
            executeExecutionListeners(flowNode, ExecutionListener.EVENTNAME_START);
        } catch (BpmnError bpmnError) {
            ErrorPropagation.propagateError(bpmnError, execution);
            return;
        }
    }

    // ★ 检查是否存在已有的 MI root execution
    if (!hasMultiInstanceRootExecution(execution, flowNode)) {
        // 没有 → 创建全新的 MI root
        execution = createMultiInstanceRootExecution(execution);
    }

    // 执行 MultiInstanceActivityBehavior
    ActivityBehavior activityBehavior = (ActivityBehavior) flowNode.getBehavior();
    if (activityBehavior != null) {
        executeActivityBehavior(activityBehavior, flowNode);
    }
}

// 第 177-186 行
protected boolean hasMultiInstanceRootExecution(
        ExecutionEntity execution, FlowNode flowNode) {
    // 检查 execution 的父链中是否有相同 activityId 的 MI root
    ExecutionEntity currentExecution = execution.getParent();
    while (currentExecution != null) {
        if (currentExecution.isMultiInstanceRoot()
                && flowNode.getId().equals(currentExecution.getActivityId())) {
            return true;  // 找到了已有 MI root（活跃 MI 场景）
        }
        currentExecution = currentExecution.getParent();
    }
    return false;  // ★ 已完成 MI：旧 MI root 不存在 → 返回 false
}

// 第 188-198 行
protected ExecutionEntity createMultiInstanceRootExecution(ExecutionEntity execution) {
    ExecutionEntity parentExecution = execution.getParent();
    FlowElement flowElement = execution.getCurrentFlowElement();

    ExecutionEntityManager em = CommandContextUtil.getExecutionEntityManager();
    em.deleteRelatedDataForExecution(execution, null, false);
    em.delete(execution);                           // 删除当前的单例壳

    // 创建全新的 MI root execution
    ExecutionEntity miRoot = em.createChildExecution(parentExecution);
    miRoot.setCurrentFlowElement(flowElement);
    miRoot.setMultiInstanceRoot(true);
    miRoot.setActive(false);
    return miRoot;
}
```

**关键逻辑**:  
1. `hasMultiInstanceRootExecution` 沿父链搜索已有的 MI root —— 已完成 MI 的 root 已销毁 → 返回 `false`  
2. `createMultiInstanceRootExecution` 删除当前的单例壳 execution，创建全新的 MI root execution（`setMultiInstanceRoot(true)`）  
3. 然后 `executeActivityBehavior` 调用 `MultiInstanceActivityBehavior.execute()`

### 3.7 行为执行：MultiInstanceActivityBehavior.execute()

**类**: `org.flowable.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior`  
**Jar 内路径**: `org/flowable/engine/impl/bpmn/behavior/MultiInstanceActivityBehavior.java`

```java
// 第 119 行
protected String collectionElementIndexVariable = "loopCounter";

// 第 131-170 行
@Override
public void execute(DelegateExecution delegateExecution) {
    ExecutionEntity execution = (ExecutionEntity) delegateExecution;

    // ★ 核心判断：检查 loopCounter 变量
    if (getLocalLoopVariable(execution, getCollectionElementIndexVariable()) == null) {
        // == 分支 A：首次执行（全新 MI root，无任何循环变量）==
        int nrOfInstances = 0;

        // 处理变量聚合（如有）
        if (hasVariableAggregationDefinitions(delegateExecution)) {
            // ... 创建聚合变量 ...
        }

        try {
            // ★ 重新解析集合，创建所有子实例！
            nrOfInstances = createInstances(delegateExecution);
        } catch (BpmnError error) {
            ErrorPropagation.propagateError(error, execution);
        }

        // 0 个实例 → 清理 MI root 并跳过该节点
        if (nrOfInstances == 0) {
            cleanupMiRoot(execution);
        }

    } else {
        // == 分支 B：已有 loopCounter → 执行内部行为（创建 UserTask）==
        if (activity.isAsynchronous()) {
            CommandContextUtil.getActivityInstanceEntityManager()
                .recordActivityStart(execution);
        }
        innerActivityBehavior.execute(execution);
    }
}
```

**核心判断** — `getLocalLoopVariable(execution, "loopCounter") == null`:

- 全新 MI root（刚被 `createMultiInstanceRootExecution` 创建）→ 无任何局部变量 → `loopCounter` 不存在 → 走**分支 A**，调用 `createInstances()`
- 活跃 MI 的子执行回到 `execute()` → `loopCounter` 已设置（由 `ContinueMultiInstanceOperation` 设置）→ 走**分支 B**，创建 UserTask

**`cleanupMiRoot()` 方法**（第 242-260 行）：

```java
protected void cleanupMiRoot(DelegateExecution execution) {
    ExecutionEntity miRoot = (ExecutionEntity) getMultiInstanceRootExecution(execution);
    FlowElement flowElement = miRoot.getCurrentFlowElement();
    ExecutionEntity parentExecution = miRoot.getParent();

    ExecutionEntityManager em = CommandContextUtil.getExecutionEntityManager();
    em.deleteChildExecutions(miRoot, ...);
    em.deleteRelatedDataForExecution(miRoot, DELETE_REASON_END, false);
    em.delete(miRoot);

    // 创建 continuation execution 继续前进（跳过该节点）
    ExecutionEntity newExecution = em.createChildExecution(parentExecution);
    newExecution.setCurrentFlowElement(flowElement);
    super.leave(newExecution);
}
```

**关键风险**: 如果 `createInstances()` 返回 0（`assigneeList` 为空），`cleanupMiRoot()` 会让执行直接跳过该 MI 节点继续前进——这不是期望的回退行为。

### 3.8 实例创建：createInstances()（以 ParallelMultiInstanceBehavior 为例）

最终 `createInstances()` 由子类实现。以并行 MI 为例：

```
MultiInstanceActivityBehavior (抽象)
  ├── ParallelMultiInstanceBehavior   ← isSequential="false"
  └── SequentialMultiInstanceBehavior ← isSequential="true"
```

`createInstances()` 调用 `resolveNrOfInstances()` 解析 `collectionExpression`（例如 `${assigneeList}`）：

- 如果 `assigneeList = ["userA", "userB", "userC"]` → `nrOfInstances = 3`
- 创建 3 个子执行，每个调用 `executeOriginalBehavior()` → 创建对应的 UserTask

---

## 4. 快速引用表

| # | 类 | Jar 内路径 | 关键方法 | 行号 |
|---|-----|-----------|---------|------|
| 1 | `ChangeActivityStateBuilderImpl` | `org/flowable/engine/impl/runtime/` | `moveActivityIdTo()` | 80-86 |
| 2 | `ChangeActivityStateCmd` | `org/flowable/engine/impl/cmd/` | `execute()` | 32-38 |
| 3 | `AbstractDynamicStateManager` | `org/flowable/engine/impl/dynamic/` | `resolveActiveExecutions()` | 238-260 |
| 4 | `AbstractDynamicStateManager` | `org/flowable/engine/impl/dynamic/` | `doMoveExecutionState()` | 331-430 |
| 5 | `ContinueProcessOperation` | `org/flowable/engine/impl/agenda/` | `continueThroughFlowNode()` | 94-120 |
| 6 | `ContinueProcessOperation` | `org/flowable/engine/impl/agenda/` | `executeMultiInstanceSynchronous()` | 149-186 |
| 7 | `ContinueProcessOperation` | `org/flowable/engine/impl/agenda/` | `hasMultiInstanceRootExecution()` | 177-186 |
| 8 | `ContinueProcessOperation` | `org/flowable/engine/impl/agenda/` | `createMultiInstanceRootExecution()` | 188-198 |
| 9 | `MultiInstanceActivityBehavior` | `org/flowable/engine/impl/bpmn/behavior/` | `execute()` | 131-170 |
| 10 | `MultiInstanceActivityBehavior` | `org/flowable/engine/impl/bpmn/behavior/` | `cleanupMiRoot()` | 242-260 |
| 11 | `ExecutionEntityManagerImpl` | `org/flowable/engine/impl/persistence/entity/` | `deleteExecutionAndRelatedData()` | 554-580 |

---

## 5. 版本差异与风险警示

### 5.1 Flowable 版本行为差异

| 版本 | 行为 | 来源 |
|------|------|------|
| **6.7.x - 6.8.0** | MI 跳转正常（本文档验证） | 社区报告 + 源码分析 |
| **6.8.1 - 7.0.1** | **MI 跳转导致 ACT_RU_TASK 无任务，流程中断** | GitHub #3944 + CSDN 多篇博客 |
| **7.1+** | 未确认 | 需进一步测试 |

### 5.2 根因分析（6.8.1+ Bug）

社区分析指出，6.8.1+ 版本中 `AbstractDynamicStateManager.createEmbeddedSubProcessAndExecutions()` 方法的实现发生了变更，导致 MI 节点的子执行创建逻辑不正确。具体变更内容和行号因版本而异，但影响的是本文档第 3.4 节中步骤 D 的逻辑。

### 5.3 flowable-plus 项目的风险控制措施

1. **版本锁定**: `pom.xml` 中通过 `flowable-root` BOM 锁定 Flowable 6.8.0
2. **集成测试覆盖**: 应添加以下测试场景到 CI 矩阵：
   - 驳回/撤回/跳转到已完成 MI 节点（assigneeList 已预设）
   - 驳回到已完成 MI 节点（assigneeList 为空 → 降级重定向）
   - 跳转到已完成并行网关分支上的 MI 节点（应拦截）
3. **升级前置检查**: 若未来升级 Flowable 版本，必须在升级前回归测试本文档描述的所有行为路径

---

## 6. 与 ADR 的关系

- **ADR-0021**（会签节点回退采用运行时判断 + 原地重建策略）：本报告为其"核心发现"部分提供了源码级证据
- **ADR-0022**（会签建模双模式规范与 AssigneeResolver 扩展点）：定义了 `AssignerResolver` SPI，是原地重建路径的关键数据源
- **ADR-0003**（会签采用 Flowable 原生多实例）：本报告验证了原生多实例 API 在回退场景中的行为

---

## 7. 参考资料

| 资源 | 链接 |
|------|------|
| Flowable 6.8.0 官方 JavaDoc | `flowable-engine-6.8.0-sources.jar` |
| Flowable 3.16.0 JavaDoc (ChangeActivityStateBuilder) | https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/3.16.0/org/flowable/engine/runtime/ChangeActivityStateBuilder.html |
| 官方测试 ChangeStateForMultiInstanceTest | https://github.com/flowable/flowable-engine/tree/main/modules/flowable-engine/src/test/java/org/flowable/engine/test/api/runtime/changestate/ChangeStateForMultiInstanceTest.java |
| GitHub Issue #1674 | https://github.com/flowable/flowable-engine/issues/1674 |
| GitHub Issue #3944 | https://github.com/flowable/flowable-engine/issues/3944 |
| DeepWiki: 多实例活动 | https://deepwiki.com/flowable/flowable-engine/4.3-multi-instance-activities |
