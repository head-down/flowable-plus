# ADR-0028: 审批轨迹收敛为单一入口（删除 getApprovalTrace）

**日期**: 2026-08-10
**状态**: 已接受

## 上下文

仓库中长期存在两个"审批轨迹"双胞胎模块，提供语义高度重叠的两套公开 API：

| 维度 | `HistoryWorkflow.getApprovalHistory` | `ProcessQueryWorkflow.getApprovalTrace` |
|------|-------------------------------------|----------------------------------------|
| 返回 VO | `List<ApprovalRecordVO>` | `List<ApprovalTraceVO>` |
| 构造粒度 | 活动级时间线（START 记录 + 贪心归组 + csRoundIndex 轮次切分） | 任务级构造，无 START、无轮次 |
| 出现顺序 | `995d50c`（较新） | `ae46ecb`（较早，phase-3 S4） |

重复证据：

- **重复四件套**：`HistoryWorkflow:253-311` 与 `ProcessQueryWorkflow:417-467`
  逐字重复 `groupCommentsByTaskId` / `extractCommentText` /
  `extractOperationCommentText` / `extractOperationCommentsText`。
- **常量双定义**：`CS_ROUND_INDEX_VAR` 在 `HistoryWorkflow`（私有）与
  `CounterSignWorkflow`（包级 static）各定义一份。
- **VO 近重复**：`ApprovalTraceVO` 与 `ApprovalRecordVO` + `CountersignSubRecord`
  字段高度重合。
- **README 双宣传**：功能特性与 API 一览表同时宣传两个入口。

**消费证据**：`git log` 确认 `getApprovalTrace` 于 `ae46ecb` 引入（早于
`getApprovalHistory` 的 `995d50c`）；全仓除测试外无调用方；与用户确认未在业务侧
使用过 `getApprovalTrace`，无下游消费证据。`getApprovalHistory` 覆盖了
`getApprovalTrace` 的全部能力超集（活动级时间线 ⊃ 任务级轨迹），且多出 START
记录与轮次语义。

## 决策

1. **单一入口**：`getApprovalHistory` 成为「审批轨迹」的唯一公开方法；
   删除 `getApprovalTrace` 与 `ApprovalTraceVO`（删除已发布库的公开 API 属不可逆
   决策，故本 ADR 需记录）。行为变化：`getApprovalTrace` 删除即无下游行为变化，
   历史轨迹统一由 `getApprovalHistory` 承载。
2. **时间线语义**：以活动级时间线为规范（START 记录 + 贪心归组 +
   csRoundIndex 轮次切分）。
3. **内核**：`HistoryWorkflow` 即审批轨迹内核，**不新增类**；javadoc 声明其
   规范内核地位。
4. **常量收敛**：删除 `HistoryWorkflow` 私有 `CS_ROUND_INDEX_VAR` 副本，统一引用
   `CounterSignWorkflow.CS_ROUND_INDEX_VAR`（同包 package-visible）。
5. **ProcessQueryWorkflow 瘦身**：删除 `getApprovalTrace` 及其全部私有构建逻辑，
   构造器移除 `MultiInstanceDetector` 与 `ActionInferenceStrategy` 两个依赖
   （两者只剩 `getApprovalTrace` 在用），新签名 `(RuntimeService, TaskService,
   HistoryService)`。核心推断模块 `ActionInferenceStrategy` 本身保留不动
   （ADR-0009 三级推断，仍被 `HistoryWorkflow` 消费）。
6. **测试策略**：`HistoryWorkflowTest`（32 用例）作为内核测试面保留；
   删除 `ProcessQueryOperationsTest` 中全部 trace 用例；集成测试
   `testApprovalTrace` 改调 `getApprovalHistory`。
7. **VO 字段**：本次不触碰 VO 字段（TraceEvent / NodeStatus 增强留待后续，
   落点转移至 `ApprovalRecordVO`）。

## 备选方案

- **双视图共享内核（被否决）**：保留 `getApprovalTrace` 作为薄包装，内部委托
  `getApprovalHistory` 后做 VO 转换。否决理由：保留两个公开入口与两个 VO 的
  API 面不变，仅合并实现，消除不了文档/认知/测试的双份维护成本；且任务级视图
  本身语义更弱（无 START、无轮次），保留价值低。
- **仅删 getApprovalTrace 保留 ApprovalTraceVO（被否决）**：无消费方时删除无
  关人员但保留无人使用的 VO 无意义，一并删除使清理彻底。

## 后果

- **正面**：
  - 消除双胞胎模块重复四件套与 VO 近重复，单一实现载体
  - `ProcessQueryWorkflow` 瘦身约一半代码量，构造器依赖减少两个
  - 公开 API 面收敛，README 不再双宣传
  - 后续 VO 增强（TraceEvent / NodeStatus）有唯一落点 `ApprovalRecordVO`
- **负面 / 风险**：
  - **破坏性变更**：删除已发布库的公开 API `getApprovalTrace` 与
    `ApprovalTraceVO`，调用方若仍引用需迁移至 `getApprovalHistory`；
    经确认当前无下游消费证据，风险可控
  - 历史 ADR（0025/0027）中对 `ApprovalTraceVO` / `getApprovalTrace` 的引用
    成为历史记录，仅部分补充交叉引用指向本 ADR
