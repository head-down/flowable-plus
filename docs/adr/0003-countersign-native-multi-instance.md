# ADR-0003：会签采用 Flowable 原生多实例实现

## 状态

已接受

## 日期

2026-07-03

## 背景

二期规划聚焦多实例/多人审批能力，会签（所有审批人同意才通过）为最高优先级。需要决定会签的底层实现方式。

## 选项

### 选项 A：自定义表实现（参考 jw-zhyg-api）

在 Flowable 引擎外部，通过 `oa_countersign` + `oa_countersign_task` 两张自定义表管理会签任务生命周期。

- **优点**：动态选人灵活、多轮次原生支持、加签/委派随心所欲
- **缺点**：需要用户创建自定义表、需要拦截 Flowable 原生行为、事务一致性需自行管理、与引擎紧耦合

### 选项 B：Flowable 原生多实例（`multiInstanceLoopCharacteristics`）

利用 BPMN 原生 `multiInstanceLoopCharacteristics` + `RuntimeService.addMultiInstanceExecution()` / `deleteMultiInstanceExecution()` 实现会签。

- **优点**：零额外表、与 Phase 1 API 自然兼容、引擎保证事务一致性、加签减签有原生 API
- **缺点**：多轮次需应用层配合，本质上是 BPMN 层面概念而非业务层面

## 决策

**选择选项 B（Flowable 原生多实例）**。

### 理由

1. **项目定位**：flowable-plus 是"贴近引擎的增强工具包"，而非"开箱即用的业务审批系统"。不应强制用户创建自定义数据库表。
2. **架构一致性**：Phase 1 的所有操作（同意/驳回/撤回/撤销）均通过 Flowable 原生 API 实现，会签应延续此模式。
3. **加签减签有原生支持**：`RuntimeService.addMultiInstanceExecution()` 和 `deleteMultiInstanceExecution()` 覆盖了 P2 的加签减签需求。
4. **灵活性通过 SPI 补偿**：多轮次、业务回调等原生不足的能力通过 SPI/事件机制留给应用层扩展（类似 `UserContext` 模式）。

## 后果

- **正面**：会签实现轻量、无侵入、与现有 API 一致
- **负面**：多轮次会签（同一节点多次发起）无法直接在引擎层面表达，需在 API 层包装
- **风险**：`completionCondition` 表达式编写需要用户理解 Flowable 多实例变量模型，flowable-plus 需提供辅助工具简化
