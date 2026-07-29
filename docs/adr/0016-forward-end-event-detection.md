# ADR-0016: 正向 EndEvent 终止检测作为独立 NodeFinder 方法

**日期**: 2026-07-29
**状态**: 已实现

## 上下文

`traceForwardAll()` 仅收集 UserTask 节点。当当前节点的唯一后续节点是 EndEvent 时，
`findAdjacentUserTasks` 和 `findNextUserTasks` 返回空列表。下游调用方无法区分
"无下游审批节点（流程将结束）"与"未知原因导致的空列表"这两种情况。

## 决策

1. **NodeFinder 的 UserTask-only 收集设计不动** — 其核心职责是审批节点遍历，保持不变
2. **新增独立方法 `findForwardEndEvents`** — 与现有 API 风格统一（`findAllReachableUserTasks`、
   `findNextUserTasks`、`findAdjacentUserTasks`），返回 `List<String>` 而非 `boolean`，
   为未来扩展预留信息空间
3. **新建专用遍历引擎 `traverseForEndEvent`** — 不复用 `traceForwardAll`，因为遍历语义差异大：
   `traceForwardAll` 收集 UserTask，`traverseForEndEvent` 检测是否无 UserTask 且到达 EndEvent
4. **NextTaskNodeVO 增加 `end` 字段** — 最小侵入性的 VO 变更
5. **消费逻辑在 NodePreviewWorkflow 层** — `getAdjacentTaskNodes` 和 `getNextTaskNodes`
   在 NodeFinder 返回空列表时，调用 `findForwardEndEvents` 检测终止状态

## 备选方案

- **在 `traceForwardAll` 中收集 EndEvent**：被否决 — 污染 UserTask 收集逻辑，破坏单一职责
- **返回复合类型 `EndReachabilityResult`**：被否决 — 当前仅三种状态（终止/不终止/无法判定），
  前两种可通过 `List<String>` 空/非空区分，"无法判定"与"不终止"前端行为一致（均不显示结束提示），
  无需显式区分。若未来需要区分，再抽象
- **消费者侧直接做 BPMN 模型判断**：被否决 — 遍历逻辑泄漏到 Workflow 层，破坏封装

## 边界语义

| 场景 | 行为 |
|------|------|
| UserTask | 返回 false（路径上有审批节点） |
| EndEvent（流程级） | 收集 ID → 返回 true |
| EndEvent（子流程/CallActivity 内部） | 不收集 → 返回 true → 继续走父流程 outgoing |
| 排他网关（有 vars，无匹配无 default） | 不判定（返回空） |
| 并行/包容网关 | 所有分支必须全部到达 EndEvent 才终止 |
| 回环 | visited 防无限循环；UserTask 在 visited 之前检查，防止漏判 |
| Terminate/Error/CancelEndEvent | 均视为流程终止 |
| ServiceTask / 中间事件 | 继续穿越；无 outgoing → 返回 true（不阻断） |
| 边界事件 | **当前不处理**，若边界事件导向 UserTask 可能漏判 |
| 模型断裂（无 outgoing 且非 EndEvent） | 不视为终止，不阻断遍历 |

## 后果

- NodeFinder 接口新增 1 个方法，DefaultNodeFinder 新增 1 个公共方法 + 4 个私有方法
- NextTaskNodeVO 新增 `end` 字段（默认 false），向后兼容所有现有调用
- NodePreviewWorkflow 的 `getAdjacentTaskNodes` 和 `getNextTaskNodes` 新增 EndEvent 检测分支
- 边界事件漏判风险已识别并文档化，当前不处理（预留）
- 新增 14 个测试用例（11 个 NodeFinder + 3 个 NodePreviewWorkflow）
