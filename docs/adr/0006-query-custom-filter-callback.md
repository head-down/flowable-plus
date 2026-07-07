# 待办/已办查询自定义过滤采用 Consumer 回调模式

三期查询 API（`queryTodoTasks` / `queryDoneTasks`）的业务自定义搜索条件通过可选的 `Consumer<TaskQuery>` 回调注入，不提供 SQL JOIN 或内置业务表关联。

## 背景

待办/已办列表查询是工作流最高频的操作之一。除了标准过滤条件（userId、processDefinitionKey、时间范围等），各业务模块通常还需要按自己的业务字段筛选（如订单金额、部门名称、申请类型）。

## 决策

flowable-plus 在公开查询 API 上提供可选的 `Consumer<TaskQuery>` 回调参数，由调用方在回调中直接调用 Flowable 原生 `processVariableValueXXX` 系列方法追加过滤条件。不提供更高层的抽象（如自定义 SQL JOIN、中间表、Criteria 对象）。

## 考虑的方案

| 方案 | 结论 | 理由 |
|------|------|------|
| Copy-Paste SQL（参考 jw-zhyg-api）| 不采纳 | 要求用户建 `INSTANCE_BUSINESS` 中间表并复制 SQL 骨架，适用于应用框架，不适用于工具包 |
| Variable Map（`Map<String, Object>`）| 不采纳 | 只支持等于匹配，无法覆盖 like/gt/lt/in 等场景 |
| 后置关联（先查任务再关联业务表）| 不采纳 | 分页不可靠，过滤可能发生在分页之后 |
| `Consumer<TaskQuery>` 回调 | **采纳** | 完全利用 Flowable 原生 process variable 查询能力，分页不受影响，灵活且简单 |

## 后果

- 自定义过滤的前提是业务数据必须作为 process variables 存储在流程实例中。若数据在独立业务表，调用方需自行在应用层做后置过滤
- 回调暴露了 `TaskQuery` 类型，与 Phase 2 的"消除 Flowable 类型泄漏"方向不完全一致，但作为可选的扩展点，简单场景无需传递
