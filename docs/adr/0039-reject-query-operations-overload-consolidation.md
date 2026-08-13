# ADR-0039: 否决 QueryOperations 重载收敛（架构审查 C6）

**日期**: 2026-08-13
**状态**: 已接受（否决）

## 上下文

2026-08-13 架构审查候选 C6：`QueryOperations`（`api` 包，13 方法）存在
3 组重载（报告称 5 组，实际 3 组）：

| 重载组 | 方法 |
|--------|------|
| 待办 | `queryTodoTasks(userId, query)` / `queryTodoTasks(userId, query, enhancer)` |
| 已办 | `queryDoneTasks(userId, query)` / `queryDoneTasks(userId, query, enhancer)` / `queryDoneTasksPrecise(userId, query)` |
| 节点预览 | `getNextNodeApprovers(key, mode)` / `getNextNodeApprovers(key, mode, variables)` |

报告建议将 enhancer、precise 开关、variables 折叠进查询/预览参数对象，
接口按语义收敛，消除重载。标注破坏性接口变更，强度 Speculative。

## 决策

**否决**。逐条核对后，报告 Problem 不成立、After 方案有硬伤、Wins 已被现状满足：

1. **Problem「接口宽度 = 实现宽度」不成立**：实现层（`TaskQueryModule`、
   `NodePreviewWorkflow`）的 2 参版全是 1 行委托——`queryTodoTasks(userId, query)`
   委托 3 参版（`TaskQueryModule.java:69`）、`getNextNodeApprovers(key, mode)`
   委托 3 参版（`NodePreviewWorkflow.java:82`）。实现宽度远小于接口宽度，
   无重复逻辑待消除。
2. **enhancer 并入 TaskQueryDTO 技术上不可行**：`enhancer` 是 `Consumer` 函数式
   类型，`TaskQueryDTO` 是纯数据请求体 DTO（`@Data`，HTTP 请求绑定）。
   Consumer 不可序列化 / 反序列化，`equals/hashCode/toString` 失效，
   函数式字段混入破坏 DTO 的数据契约。enhancer 必须在构造 Flowable 原生查询时
   作为方法参数传入，无法经 DTO 传递。
3. **precise 升为枚举开关是倒退**：`queryDoneTasks`（Phase 1 走 `involvedUser`
   标准 API，total 近似）与 `queryDoneTasksPrecise`（Phase 1 走 Native SQL，
   total 精确，且**不支持 enhancer**，见 Javadoc）实现路径完全不同。合并 +
   枚举开关会制造 `precise + enhancer` 非法组合——从编译期方法选择退化为
   运行时异常，方法内还要 if-else 分叉两套查询路径。
4. **Wins「新增过滤项不再增加方法」已被现状满足**：`Consumer<TaskQuery>`
   enhancer 正是 ADR-0030 有意收敛出的扩展点（从死接口 `TaskQueryEnhancer`
   收敛为 Consumer 单一形态），调用方新增过滤只需写 lambda，接口无需增方法。
5. **下游破坏面已量化**：核查 `jw-zhyg-api` 全库，仅
   `ProcessController.java:76/:93`（2 参版）与 `ApprovalPreviewService.java:68`
   （getNextNodeApprovers 3 参版）调用本接口——**被移除的 4 个重载零调用，
   破坏面为 0**。破坏面低只是消除减分项，不构成实施的充分理由。

与 C2 / C5 同构：重载是 Java 可选参数的标准表达，统一扩展点（Consumer enhancer）
已存在，此规模收窄属接口美学层面的过度抽象，非深模块缺失。

## 备选方案

- **仅收窄 `getNextNodeApprovers`（被否决）**：该组仅 2 个重载，3 参版是
  variables=null 的超集（`NodePreviewWorkflow.java:82` 一行委托）。为消除 1 个
  重载新建「预览请求对象」引入新 API 类型，得不偿失。
- **拆分请求 DTO 与内部查询对象（被否决）**：为容纳 Consumer 单独建不含
  函数式字段的请求 DTO + 内部查询对象，引入双对象映射成本，且实现层仍需
  Consumer 参数，不解决核心问题。

## 后果

- **正面**：公开门面保持稳定，下游 `jw-zhyg-api` 零迁移成本；重载作为 Java
  可选参数的标准表达保留，DTO 数据契约不被函数式字段污染。
- **负面 / 风险**：无。接口 13 方法中重载组是「可选增强」的显式表达，非缺陷。
- **重审条件**：`TaskQueryDTO` 需要承载的行为字段增多（如审批流类型、数据权限
  范围）导致重载参数再次膨胀，或 Flowable 原生 query 类型变化迫使重构查询入口。

## 交叉引用

- 架构审查 2026-08-13 候选 C6（Speculative）
- ADR-0030（删除死接口 TaskQueryEnhancer，回调收敛为 Consumer——重载的
  enhancer 参数即此决策产物）
- ADR-0010（FlowablePlus 门面纯聚合角色）
- ADR-0013（已办查询精确分页引入 Native SQL——precise 的实现依据）
- ADR-0037 / ADR-0038（同批否决项，过度抽象判据同源）
