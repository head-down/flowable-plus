# 并行网关汇合节点驳回：直接拒绝而非强制回退

当 `rejectTask` 的目标节点位于并行网关汇合之后时，`findPreviousNodes` 会返回多个上一审批节点。此时无法确定应将任务驳回至哪一个节点——驳回任一分支都会导致其他分支的任务残留和 join 网关同步失效。

我们选择在此场景下直接抛出 `NoPreviousNodeException`（附带"当前节点位于并行网关汇合之后，无法驳回至单一上级节点"的错误信息），而非尝试强制回退。

## 考虑过的替代方案

- **驳回至所有上一节点**：产生多个待办任务且各分支状态不同步，join 网关计数机制被永久破坏。
- **驳回至一个节点 + 自动完成兄弟分支**：需要清除已执行分支的 execution 记录、手动补齐 join 网关计数，涉及非公开 API 和自定义 Flowable Command，且 Flowable 6.8.0 的已知 bug 会导致 join 网关永不触发。
- **基于 Saga 模式的分布式回滚**：设计复杂度和运行时开销远超当前需求，适合跨系统事务而非单引擎审批流。

## 后果

- `rejectTask` 仅支持串行审批拓扑（即上一节点唯一）。并行网关汇合后的任务如需驳回，推荐使用 `rejectTaskToInitiator` 直接退回发起人节点。
- 未来如需支持并行网关分支内部分驳回，可通过 `NodeFinder` 扩展节点类型判别 + 状态补偿机制，但不在当前切片范围内。

## 补充说明（2026-07-14）

`jumpToNode`（任意跳转）对此决策的例外处理：

`rejectTask` 拒绝并行网关汇合节点驳回的根本原因是**调用方未指定目标节点**——引擎无法在多个上游节点中自动选择一个。而 `jumpToNode` 由调用方**显式指定单一目标节点**（targetNodeId），不存在自动选择的问题。

因此 `jumpToNode` 允许从并行网关汇合节点跳转，不校验并行网关条件。风险由调用方自行承担——跳转到并行分支中的某个节点后，其他分支的历史状态可能破坏 join 网关的同步机制。此风险在接口文档中明确说明。
- 与多个 flowable/activiti 中国式审批生产方案的设计决策一致（均选择在并行网关处拒绝，而非强行回退）。

## 补充说明（2026-07-31）

### 非受控汇合场景：引入运行时历史数据辅助静态拓扑解析

BPMN 中存在一种与并行网关汇合**拓扑外观相似但语义不同**的场景——非受控汇合（Uncontrolled Merge）：

```
chairman ──────────┐
executive ─────────┤
integratedAdminDept┼→ handler → sealManager → end
subsidiaryManager ─┘
```

`handler` 有 4 条入线，source 均为 UserTask，中间无任何网关。`traceBackward` 遍历所有入线全量收录 → `prevNodes.size() = 4` → 触发误判为"并行网关汇合"。

**核心矛盾**：静态 BPMN 拓扑分析无法区分"多个上游节点同时到达（并行网关汇合）"与"多个上游节点互斥到达（非受控汇合）"。要做出正确判断，必须知道**在本次流程实例中，哪些上游节点实际执行过**。

### 决策

在 `findPreviousNodes` 中增加 `filterByHistory` 阶段：当静态遍历返回多个候选节点且 `processInstanceId` 非 null 时，通过 `HistoricActivityInstanceQuery` 对每个候选节点逐条执行 `count()` 查询，过滤出实际执行过的节点。

关键设计约束：
- **查询方式**：使用 `activityId + count()` 逐候选查询，避免 `.list()` 全量加载（OOM 隐患）。
- **安全网定位**：`filterByHistory` 放在 `findPreviousNodes` 内部而非 `rejectTask`/`withdrawTask` 调用方，使所有依赖 `findPreviousNodes` 的路径（驳回、撤回、权限校验）同时受益。
- **兜底策略**：历史匹配全部失败时抛出 `NoPreviousNodeException`，拒绝盲选——宁可安全拒绝，不可静默污染数据。

### 后果

- `findPreviousNodes` 不再仅是纯静态拓扑遍历，在候选节点 > 1 且 `processInstanceId` 非 null 时会触发数据库查询。
- 对于无历史记录的全新节点（如刚启动的流程），此增强逻辑不适用（`processInstanceId` 为 null 时跳过），行为保持不变。
- 并行网关汇合场景（多个分支均实际执行 → 多个候选均有历史记录 → 返回多个 → `rejectTask` 继续拦截）的正确性不受影响。
