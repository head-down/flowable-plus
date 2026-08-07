# FlowablePlus 门面保持纯聚合角色

`FlowablePlus` 类作为查询/图模块的**查询门面**，仅做收敛注入点的聚合，不承担编排职责。编排能力应放在业务层，由调用方标准实现。

> **门面只聚合读操作（查询门面）。写操作（发起、同意、驳回、撤回、撤销、会签等）有意不进门面**，各操作族以独立接口（`ProcessLifecycleOperations`、`TaskExecutionOperations`、`CounterSignOperations`）作为 seam，调用方按需注入对应接口。详见下文「门面范围」。2026-08-07 架构审查确认此定位。

## 背景

2026-07-17 架构审查（`/improve-codebase-architecture`）发现 `FlowablePlus` 的 14 个 public 方法均为单行委托（return delegate.method(args)），属于纯聚合层，无编排深度。

审查结论：删除门面会导致调用方从注入 1 个 Bean 变为注入 5 个 Bean，且"哪个模块处理哪种查询"的认知负担从门面内部转移到每个调用方。门面在**注入收敛**上提供了足够的 leverage，161 行代码换来的注入简化是合理的。

## 决策

**保持 `FlowablePlus` 现有结构不变**，角色定位为：

- **聚合层（查询门面）**：收敛子模块的入口到一个注入点（`TaskQueryModule`、`ProcessQueryWorkflow`、`NodePreviewWorkflow`、`DiagramWorkflow`、`HistoryWorkflow`、`PersonnelWorkflow`，共 6 个）
- **不做编排**：各模块的调用互不依赖（如待办查询不依赖流程摘要查询），不存在天然编排点。编排职责属于业务层，不属于底层框架。

### 门面范围

`FlowablePlus` 只实现读操作接口（`QueryOperations`、`DiagramOperations`、`HistoryOperations`）。**写操作接口（`ProcessLifecycleOperations`、`TaskExecutionOperations`、`CounterSignOperations`）不进门面**，理由：

- 写操作族之间没有共享编排点——发起/同意/驳回与会签是彼此独立的操作面
- 各操作族独立接口让调用方能精确裁剪能力（只注入 `CounterSignOperations` 即限缩为会签操作）
- 写操作进同一个门面会无谓拓宽门面接口面，违背深层模块原则

写操作实现类（`ProcessLifecycleWorkflow`、`TaskExecutionWorkflow`、`CounterSignWorkflow`）直接实现各自接口，调用方注入接口而非门面。

## 后果

- 调用方只需注入 `FlowablePlus` 一个 Bean 即可使用所有查询/图能力
- 需要写操作（发起/同意/驳回/会签等）的调用方注入对应 `*Operations` 接口，**不要**期望在门面上找到写方法
- 当未来出现需要编排的场景（如一次调用需同时返回待办列表和流程摘要），编排逻辑应在业务层实现。可参考 `flowable-plus-biz-sample`（计划中的业务示例模块）中的 `TodoTaskBizService` 等标准实现
- 门面的 14 个委托方法新增零成本——新增子模块方法时，门面中加一行委托即可
- 如果未来子模块数量显著增长（>10 个），可重新评估是否需要按领域拆分为多个门面
