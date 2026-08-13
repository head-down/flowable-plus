# ADR-0037: 否决审批操作样板提取执行模板（架构审查 C2）

**日期**: 2026-08-13
**状态**: 已接受（否决）

## 上下文

2026-08-13 架构审查（`architecture-review-20260813-150021.html`）候选 C2：
审批操作（驳回、撤回、跳转等）的样板代码（权限校验 → 目标解析 → 意见记录 →
变更活动状态）存在重复，建议提取为统一「执行模板」，由模板固定骨架、各操作
注入差异步骤。

审查时点 `TaskExecutionOperations` 公开操作含：`completeTask`、`claimTask`、
`rejectTask`（含 `PreviousNodeResolutionStrategy` 变体）、`rejectTaskToInitiator`、
`withdrawTask`（含变体）、`transferTask`、`jumpToNode`、`getJumpableNodes`。

## 决策

**否决**。审批操作样板已由 `TaskExecutionWorkflow.executeRollback`
（`TaskExecutionWorkflow.java:381`）吸收为回退统一入口——驳回、撤回、跳转
三类操作共用该路径（目标解析 → 评论记录 → assigneeList 回写 → changeState），
样板并未散落多处。

剩余操作间差异是**业务语义**，不是可抽象的重复骨架：

- `withdrawTask`：上一节点审批人权限校验（与其余操作的身份校验不同）
- `transferTask`：无多实例（MI）校验
- `rejectTaskToInitiator`：走 detach 式回退（不回指定节点，直接回发起人）
- 各操作发布的事件（eventBus）各自独有

模板方法把「业务语义差异」强行归入「步骤插槽」，在此规模属过度抽象。
审查强度为 Speculative，未达值得重审的摩擦阈值。

## 备选方案

- **提取执行模板（被否决）**：见上文。骨架已存在（`executeRollback`），
  剩余差异是各操作独有的业务语义，模板化反而增加插槽抽象成本。
- **与 C1 一并实施（被否决）**：C1（会签轮次状态机收敛）与 C2 共享任务执行域，
  但 C1 已独立实施完成（PR #92），C2 无额外收益。

## 后果

- **正面**：避免为 5 类操作引入模板框架（新抽象 + 各操作适配器 + 测试改造），
  业务语义保持各操作自解释。
- **负面 / 风险**：无。未来若新增第 6、7 个同类操作且重复骨架再次出现，
  可重审本决策。
- **重审条件**：同一骨架被复制的调用点 ≥ 3 且差异步骤稳定为固定集合。

## 交叉引用

- 架构审查 2026-08-13 候选 C2（Speculative）
- ADR-0017（乐观锁冲突仅在具体方法内精准重试——同一「不抽通用机制」取向）
- ADR-0034（多实例拦截改运行时判定）
- PR #92（C1 会签轮次状态机收敛，同域先落地项）
