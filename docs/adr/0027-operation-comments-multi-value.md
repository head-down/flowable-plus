# ADR-0027: 操作注释多值化（operationComments 列表字段）

**日期**: 2026-08-10
**状态**: 已接受

## 上下文

GitHub issue #74 指出：会签多实例节点上，**同一任务（taskId）连续多次加签**后，
审批历史只保留最后一次加签的操作注释（"覆盖"而非"追加"）。

根因定位：

- **写侧无问题**：`CounterSignWorkflow.addCounterSigner`（CounterSignWorkflow.java:266）
  通过 `taskService.addComment` 追加 ADD_SIGN 注释，数据不丢。
- **读侧缺陷**：`HistoryWorkflow.extractOperationCommentText`（HistoryWorkflow.java:289-293）
  与 `ProcessQueryWorkflow.extractOperationCommentText`（ProcessQueryWorkflow.java:443-446）
  对同一任务只取一条；`DefaultActionInferenceStrategy.findFirstOperationComment`
  （DefaultActionInferenceStrategy.java:124-143）按时间倒序返回第一条（最新一条），
  前几次加签的操作注释被丢弃。

本质：ADR-0025 将 `operationComment` 设计为**单值槽位**（任务最后执行的操作），
未覆盖"同一任务可多次执行同类操作"（连续加签、委派-收回委派循环等）的展示场景。
ADR-0025 备选方案"钉钉式独立记录"曾被否决，本 issue 是其遗留场景。

## 决策

### 1. 修复方向：聚合展示，不做钉钉式独立记录

沿用 ADR-0025 已否决"钉钉式独立记录"的结论（需在 HistoryWorkflow 插入合成记录、
影响全局排序与会签归组，改动面与回归风险大），采用**方案一（聚合展示）**：
在既有记录上新增多值字段承载全部操作注释。

### 2. 数据结构：新增 List<String> operationComments，保留单值 operationComment

- 新增 `List<String> operationComments`：该任务的**全部**操作注释文本，
  按**时间正序**排列（最早在前）。
- 保留单值 `operationComment`：**最新一条**操作注释，语义 = 任务最后执行的操作，
  向后兼容既有调用方（如上游 jw-zhyg-api 的文本解析）。

### 3. 修复范围：HistoryWorkflow 与 ProcessQueryWorkflow 都修

`HistoryWorkflow`（审批历史 `getApprovalHistory`）与 `ProcessQueryWorkflow`
（流程跟踪 `getApprovalTrace`）**都填充** `operationComments`，避免两接口展示不一致。

### 4. 聚合类型：全部操作注释组，不做类型特判

`operationComments` 聚合 5 种操作注释类型（与 `OPERATION_COMMENT_TYPES` 一致）：
`ADD_SIGN` / `DELETE_SIGN` / `DELEGATE` / `RESOLVE_DELEGATE` / `TRANSFER`，
不做类型特判（不区分"加签类"与"委派类"），统一按时间正序返回全部。

### 5. 排序：时间正序（最早在前）

`groupCommentsByTaskIdDesc` 每组按时间倒序排列（供 `findFirst*` 取最新），
`findAllOperationComments` 需**反转成时间正序**返回，保证列表从最早操作开始展示。

### 6. 接口扩展：新增抽象方法 findAllOperationComments

`ActionInferenceStrategy` 新增**抽象方法**
`List<Comment> findAllOperationComments(List<Comment> taskComments)`，
返回时间正序的全部操作注释 Comment。

**不用 default 方法**：空实现（如返回空 List / null）会导致第三方自定义实现
"静默无操作注释"的隐性 bug——调用方无法区分"确实无操作注释"与"实现未适配"。
抽象方法强制第三方实现者显式适配，编译器即拦截。

### 7. 空值语义：无操作注释时 operationComments 为 null

无操作注释时 `operationComments` 返回 `null`（与单值 `operationComment` 字段同构，
同为空值语义，避免 `[]` / `null` 两种空形态并存）。

### 8. 上游适配独立跟踪（不在本 issue 交付）

上游 jw-zhyg-api（`ProcessController.toCountersignTask` / `injectAddSignComment`
前缀解析）的适配**独立跟踪**，不属于本 issue 交付。`operationComments` 为纯新增字段，
向后兼容，B 方案（上游未适配前）保证功能不退化——单值 `operationComment` 语义不变。

### 9. action 推断不变

action 推断**保持现状**（仍 `findFirstOperationComment` 取最新一条），
`operationComment` 值不变，既有测试不破。

## 备选方案

- **钉钉式独立记录（方案二）**：为每次加签在审批历史时间线生成独立记录。被否决 ——
  ADR-0025 已否决，本会话再次确认否决（无新论据推翻）。
- **`operationComment` 用 `\n` 聚合字符串**：文本解析脆弱，被否决 —— 改用 List 字段。
- **接口 default 方法（返回空 List）**：静默空结果风险，被否决 —— 改用抽象方法。
- **operationComments 空值时返回空 List**：被否决 —— 与单值字段 `null` 语义不一致，
  采用与 `operationComment` 同构的 `null` 语义。

## 后果

- **正面**：
  - 连续加签的操作注释不再"覆盖"丢失，`operationComments` 完整返回
  - 单值 `operationComment` 语义不变（任务最后执行的操作），向后兼容，上游未适配前不退化
  - `HistoryWorkflow` 与 `ProcessQueryWorkflow` 展示一致
  - 抽象方法强制第三方 `ActionInferenceStrategy` 实现显式适配，避免静默空结果
- **负面 / 风险**：
  - 第三方自定义 `ActionInferenceStrategy` 实现需同步新增 `findAllOperationComments`
    （编译期强制，不产生运行时静默失败）
  - `operationComments` 为纯新增字段，老数据无需迁移（纯读侧修复）
  - 上游 jw-zhyg-api 展示层如需利用多值，需另行排期适配（决策 8）
