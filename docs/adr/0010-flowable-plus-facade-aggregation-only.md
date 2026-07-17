# FlowablePlus 门面保持纯聚合角色

`FlowablePlus` 类作为 5 个查询/图模块的统一入口，仅做收敛注入点的聚合，不承担编排职责。编排能力应放在业务层，由调用方标准实现。

## 背景

2026-07-17 架构审查（`/improve-codebase-architecture`）发现 `FlowablePlus` 的 14 个 public 方法均为单行委托（return delegate.method(args)），属于纯聚合层，无编排深度。

审查结论：删除门面会导致调用方从注入 1 个 Bean 变为注入 5 个 Bean，且"哪个模块处理哪种查询"的认知负担从门面内部转移到每个调用方。门面在**注入收敛**上提供了足够的 leverage，161 行代码换来的注入简化是合理的。

## 决策

**保持 `FlowablePlus` 现有结构不变**，角色定位为：

- **聚合层**：收敛 5 个子模块的入口到一个注入点（`TaskQueryModule`、`ProcessQueryWorkflow`、`NodePreviewWorkflow`、`DiagramWorkflow`、`HistoryWorkflow`）
- **不做编排**：各模块的调用互不依赖（如待办查询不依赖流程摘要查询），不存在天然编排点。编排职责属于业务层，不属于底层框架。

## 后果

- 调用方只需注入 `FlowablePlus` 一个 Bean 即可使用所有查询/图能力
- 当未来出现需要编排的场景（如一次调用需同时返回待办列表和流程摘要），编排逻辑应在业务层实现。可参考 `flowable-plus-biz-sample`（计划中的业务示例模块）中的 `TodoTaskBizService` 等标准实现
- 门面的 14 个委托方法新增零成本——新增子模块方法时，门面中加一行委托即可
- 如果未来子模块数量显著增长（>10 个），可重新评估是否需要按领域拆分为多个门面
