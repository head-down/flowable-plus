# ADR-0018: 紧邻遍历使用 stopAtUserTask 参数复用现有遍历引擎

**日期**: 2026-07-25
**状态**: 已实现

## 上下文

NodeFinder 现有的正向遍历方法 `findAllReachableUserTasks` 和 `findNextUserTasks` 均通过 `traceForwardAll` 实现全量遍历——遍历从起点出发，穿越网关、子流程等中间节点后，收集沿途所有可达的 UserTask（遇到 UserTask 后继续穿越其 outgoing 探索下游）。

现在需要一个新的遍历变体：从任意节点出发，正向穿过网关/子流程入口后收集"紧邻"的第一个 UserTask 层级，不继续深入下级 UserTask。两种遍历的核心逻辑（网关穿越、条件表达式评估、子流程递归、CallActivity 递归）完全一致，唯一差异在于遇到 UserTask 后的行为——是否继续穿越其 outgoing。

## 决策

1. **`traceForwardAll` 增加 `stopAtUserTask` 布尔参数**：在私有遍历引擎方法签名中增加一个控制参数，而不是新增独立方法
2. **`stopAtUserTask=true` 时遇到 UserTask 收集后立即 return**：不遍历该 UserTask 的 outgoing flows，实现"紧邻"边界
3. **递归调用传播参数**：SubProcess、CallActivity 和 outgoing 遍历中的递归调用都传入相同的 `stopAtUserTask` 值，确保行为在嵌套结构中一致
4. **现有调用方传入 `false`**：`findAllReachableUserTasks` 和 `findNextUserTasks` 传入 `false`，保持行为不变（向后兼容）
5. **UserTaskTraversalFilter 仍然生效**：`stopAtUserTask` 的判断在 Filter 之后，即 Filter 跳过的节点正常触发停止逻辑

## 备选方案

- **新增独立方法 `traceForwardAdjacent` 复制遍历逻辑**：被否决——`traceForwardAll` 已近 70 行，复制会导致代码重复和维护负担
- **抽取 Visitor 模式**：当前仅两处行为差异（全量 vs 紧邻），引入额外抽象层次为时过早。若未来出现第三种遍历变体，再考虑重构

## 后果

- `traceForwardAll` 多一个 `boolean` 参数，6 处调用点（3 处外部 + 3 处内部递归）全部需要显式传参
- `findAdjacentUserTasks` 返回的是一层 UserTask，调用方若需全量遍历应使用 `findNextUserTasks`
- `getAdjacentTaskApprovers()` / `getAdjacentNodeApproversByProcessKey()` 返回的审批人列表跨节点不作去重。同一用户若出现在多个紧邻节点（如并行分支的各 UserTask），列表中会出现多次（每次携带对应 nodeId）。调用方应根据业务场景自行聚合（预览场景按 userId 去重，指派场景按 nodeId 分组）。同一节点内的审批人已由 `UserTaskApproverResolver` 按 assignee &gt; candidateUser &gt; candidateGroup 优先级去重。
- 该参数扩展了引擎的灵活性，未来若新增更多遍历策略变体，可能更自然地向策略模式演进
