# ADR-0031: 节点预览 API 收窄为三入口（8 方法 → 3 入口）

**日期**: 2026-08-11
**状态**: 已接受

## 上下文

`NodePreviewWorkflow`（经 `QueryOperations` / `FlowablePlus` 门面对外公开）长期暴露 8 个节点预览方法。分析表明 8 个方法并非 8 段独立逻辑，而是 **3 个正交维度的组合**：

| 维度 | 取值 |
|------|------|
| 锚点 | 流程定义（发起前静态预览，从 StartEvent 出发）/ 运行时任务（审批中，从当前任务出发） |
| 遍历深度 | 全遍历（完整审批链路）/ 紧邻遍历（仅第一个审批层级，ADR-0018） |
| 输出 VO | `NodeApproverVO`（节点分组）/ `NextTaskNodeVO`（节点 + 表单 + EndEvent）/ `ApproverInfoVO`（扁平审批人） |

由此产生的问题：

- **接口面 ≈ 实现**：8 个方法呈重复模板（查任务/查定义 → NodeFinder 遍历 → VO 映射），参数校验块重复 8 次，接口宽度几乎等于实现高度，深度不足。
- **调用方认知负担**：同一「下一步审批人」概念有 4 个入口变体（`getNextTaskApprovers` / `getAdjacentTaskApprovers` / `getNextNodeApproversByProcessKey` / `getAdjacentNodeApproversByProcessKey`），遍历深度被误建模为独立领域概念。
- **触发条件已主动触发**：2026-08-10 架构审查将本候选列为 Speculative 并暂缓，触发条件为「新增第 9 个预览入口或下游提出变更需求」。2026-08-11 评审决定不再等待触发条件，主动收窄。

**下游消费证据**：与 ADR-0028（无下游消费）不同，**确认有下游项目在使用这 8 个方法**。维护者决策：仍直接收窄，迁移由下游承担（见 README「v1.0.0 API 迁移」映射表）。

## 决策

1. **三入口替代八方法**（锚点 + 输出 VO 归组，遍历深度降格为枚举参数）：

   | 新入口 | 锚点 | 返回 | 合并的旧方法 |
   |--------|------|------|--------------|
   | `getNextNodeApprovers(processKey, TraversalMode mode[, variables])` | 流程定义 | `List<NodeApproverVO>` | `getNextNodeApproversByProcessKey` ×2、`getAdjacentNodeApproversByProcessKey` |
   | `getNextTaskNodes(taskId, TraversalMode mode)` | 运行时任务 | `List<NextTaskNodeVO>` | `getNextTaskNodes`、`getAdjacentTaskNodes` |
   | `getNextTaskApprovers(taskId, TraversalMode mode)` | 运行时任务 | `List<ApproverInfoVO>` | `getNextTaskApprovers` ×2、`getAdjacentTaskApprovers` |

2. **新增 `TraversalMode` 枚举**（`core/enums`）：`FULL`（全遍历）/ `ADJACENT`（紧邻遍历），语义源自 ADR-0018。遍历深度是渲染策略而非独立领域概念，故从方法名降格为参数。

3. **删除的能力**：
   - `getNextTaskApprovers(taskId, targetNodeId)` 的 **targetNodeId 过滤**：调用方按 `ApproverInfoVO.getNodeId()` 自行过滤即可，结果等价（过滤发生在扁平列表展开前后等价）。
   - `getNextTaskNodes` 的冗余 **processInstanceId 参数**：可从 `taskId` 唯一推出（`task.getProcessInstanceId()`），双 ID 存在传错对的静默风险，一并删除。
   - 命名去除 `ByProcessKey` 后缀：锚点已由签名参数表达。

4. **实现组织**：`NodePreviewWorkflow` 内部收敛为私有步骤——参数校验（×1）、`resolveActiveDefinition` / `resolveTask`、按模式分流的遍历步骤、三种 VO 映射、EndEvent 检查；接口面（3 方法 + 1 重载）小于实现高度，深层模块达成。

## 备选方案

- **两入口（被否决）**：定义锚点 + 任务锚点各 1 个，输出形态用泛型或选择器参数。否决理由：牺牲类型安全，接口「窄而模糊」，调用方需查文档才知道传什么。
- **Deprecated 过渡（被否决）**：新入口 + 旧方法标 `@Deprecated` 并存。否决理由：API 面反而更大（新旧并存双倍维护），且下游已明确接受直接迁移。
- **仅内部收敛不改接口（被否决）**：仿 Card C 抽取私有步骤。否决理由：接口面 8 入口的认知负担（4 个「下一步审批人」变体）不解决，Card G 的核心诉求就是接口面收窄。
- **保留 processInstanceId 参数（被否决）**：与现有签名一致减少迁移量。否决理由：双 ID 传错对的静默风险是已知缺陷，收窄窗口内应一并修正。

## 后果

- **正面**：
  - 接口面 8 入口 → 3 入口，调用方认知负担下降
  - 参数校验 / 遍历选择 / VO 映射逻辑各自收敛一处，局部性提升
  - 遍历深度从「4 个 adjacent 方法」降为「1 个枚举参数」，未来新增遍历策略只需加枚举值
- **负面 / 风险**：
  - **破坏性变更**：删除已发布库的全部 8 个旧公开方法签名，以 4 个新签名（3 语义入口 + 1 重载）替代，**确认存在下游消费**，下游需按 README 迁移映射表适配后升级。与 ADR-0028「无下游消费证据」的前提不同，本次是维护者主动决策的破坏性收敛
  - 版本保持 **1.0.0**（当前经私有/本地仓库发布，同版本覆盖可行）；**未来若发布 Maven Central，1.0.0 已被占用，必须升版本**，本 ADR 的破坏性变更届时需并入大版本说明
  - `getNextTaskNodes` 从「显式传 processInstanceId」改为「从 taskId 推出」：若调用方曾传与任务不一致的实例 ID，行为将修正为使用任务真实实例 ID
- **迁移**：README 新增「v1.0.0 API 迁移」小节，附 8 旧方法 → 3 新方法的映射表（含 targetNodeId → filter 写法）
