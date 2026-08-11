# ADR-0025: CommentType 业务/操作分组解耦审批意见提取与操作注释识别

**日期**: 2026-08-10
**状态**: 已接受

## 上下文

GitHub issue #72（上游 jw-zhyg-api 反馈）指出：会签节点执行"加签"后，审批历史中操作者记录的
`comment` 字段显示的是 `加签审批人: 003162`（ADD_SIGN 操作注释文本），操作者本人填写的业务审批
意见被"覆盖"展示。

根因定位（与 issue #72 分析一致）：

- **写侧是追加，数据不丢**：`CounterSignWorkflow.addCounterSigner`（CounterSignWorkflow.java:250-255）
  与 `removeCounterSigner`（:304）通过 `taskService.addComment` 追加 ADD_SIGN / DELETE_SIGN 类型注释，
  不会覆盖已有业务意见 Comment。
- **读侧按"单槽位"取 comment**：`HistoryWorkflow.extractCommentText`（HistoryWorkflow.java:278-282）
  与 `ProcessQueryWorkflow.extractCommentText`（ProcessQueryWorkflow.java:431-434）对同一任务仅取一条。
- **取法不区分业务意见与操作注释**：`DefaultActionInferenceStrategy.findFirstBusinessComment`
  （DefaultActionInferenceStrategy.java:64-89）第二遍按时间倒序返回第一个可解析为 `CommentType` 的
  comment。ADD_SIGN / DELETE_SIGN 是合法 `CommentType`（CommentType.java:30, 33），且加签注释通常
  时间最新、排在倒序列表最前，于是顶掉业务意见。
- **`inferAction`（:32-42）与 comment 提取共用同一 `findFirstBusinessComment`**，action 同样被污染。

本质：ADR-0009 的"特征提取"策略假定"能解析为 CommentType 即业务意见"，但 ADD_SIGN / DELETE_SIGN
等**操作注释**虽属于 CommentType，却不携带审批人的业务投票语义。

## 决策

### 1. CommentType 划分为两组

- **业务意见组**（参与 `comment` 槽位竞争）：`AGREE`、`REJECT`、`RETURN`、`WITHDRAW`、`INVALID`、
  `COUNTER_SIGN_AGREE`、`COUNTER_SIGN_REJECT`、`AUTO_COMPLETE`、`INITIATE_COUNTERSIGN`
- **操作注释组**（不参与 `comment` 槽位竞争）：`ADD_SIGN`、`DELETE_SIGN`、`DELEGATE`、
  `RESOLVE_DELEGATE`、`TRANSFER`

`INITIATE_COUNTERSIGN` 保留在业务组且维持第一遍优先匹配（DefaultActionInferenceStrategy.java:68-75）
不变：它是发起会签的"开始"语义记录，comment 文本可能携带发起人意见；且当前 main 代码无写侧写入
该类型（仅历史数据/下游使用），改动超出本 issue 范围。

### 2. 解耦 comment 提取与 action 推断

`ActionInferenceStrategy` 接口新增 `findFirstOperationComment`；`findFirstBusinessComment` 第二遍扫描
**跳过操作注释组**，保证 `comment` 字段只返回真实业务意见。

### 3. action 推断优先级：业务意见 → 操作注释 → deleteReason → null

`inferAction` 按以下顺序：

1. 业务意见组 Comment → 映射对应 `ApprovalAction`（覆盖 `findFirstBusinessComment` 命中）
2. 操作注释组 Comment → 映射 `ADD_SIGN` / `DELETE_SIGN` / `TRANSFER`（`CommentTypeConverter` 对这三个有
   对应 `ApprovalAction`；DELEGATE / RESOLVE_DELEGATE 无映射则跳过）
3. DeleteReason 兜底（现状保留）
4. null（活跃节点）

据此：

- "仅加签、未投票"的活跃任务：无业务意见 → 操作注释生效 → action = `ADD_SIGN`（保留上游加签语义）；
- "既有业务投票又有 ADD_SIGN"的任务：action 取真实投票动作（如 `COUNTER_SIGN_AGREE`），加签信息
  由 operationComment 独立承载——避免"action=加签 + comment=同意"的展示割裂，且"先加签后投票"
  时序下与现状行为一致（时间倒序取到投票）。

### 4. VO 新增 operationComment 字段

`ApprovalRecordVO`、`CountersignSubRecord` 各新增 `operationComment` 字段
（操作注释 fullMessage，如 `加签审批人: 003162`），与 `comment` 语义解耦。纯新增字段，向后兼容。
（`ApprovalTraceVO` 已随 ADR-0028 删除，其角色并入 `ApprovalRecordVO`。）

`HistoryWorkflow.buildNormalRecord` / `buildSubRecord` 填充该字段。
（原 `ProcessQueryWorkflow.buildHistoricTraceVO` / `buildActiveTraceVO` 已随 ADR-0028 删除，历史轨迹统一由 `getApprovalHistory` 承担。）

## 备选方案

- **操作注释优先（issue 原始验收表：既有业务意见又有 ADD_SIGN 时 action = ADD_SIGN）**：被否决 ——
  任务记录的 action 应描述审批人对任务做了什么；投了同意却显示"加签"语义割裂；且"先加签后投票"
  时序会从现状的投票 action 回归为 ADD_SIGN。
- **钉钉式：为 ADD_SIGN / DELETE_SIGN 在审批历史时间线生成独立记录（actor=操作人，target=被加签人）**：
  被否决 —— 更贴近主流产品展示，但需在 HistoryWorkflow 插入合成记录、影响全局排序与会签归组，
  改动面与回归风险大，超出本次 bug 修复范围。operationComment 字段方案为保守过渡，满足
  "意见不被覆盖 + 加签可识别"。未来若上游需要钉钉式展示，可基于 operationComment 或独立记录演进。
- **维持现状（上游解析"加签审批人:"文本前缀）**：被否决 —— 正是本 bug 的根源，且文本前缀解析脆弱。
- **仅修 comment 槽位、不改 action 推断**：被否决 —— 不新增 `findFirstOperationComment` 时，
  "仅加签未投票"任务会因业务意见为空而降级为 null / AGREE，丢失加签语义。

## 后果

- **正面**：
  - `comment` 字段只返回真实业务意见，操作注释不再抢占槽位
  - 加签/减签信息通过 `operationComment` 独立返回，上游可去掉"加签审批人:"文本前缀解析
  - "仅加签未投票"任务 action 仍为 ADD_SIGN，加签语义不丢
  - 顺带修复 DELEGATE / RESOLVE_DELEGATE 等类型被 `findFirstBusinessComment` 误取后
    `CommentTypeConverter` 抛异常走 catch 兜底的含糊行为
- **负面 / 风险**：
  - `findFirstBusinessComment` 语义变化：第三方自定义 `ActionInferenceStrategy` 实现需同步
  - 上游 jw-zhyg-api 需同步适配（VO 增字段为向后兼容变更；`injectAddSignComment` 文本前缀解析
    改为基于 `action` + `operationComment`）——必须与本次改动同步排期
  - 老数据无需迁移：纯读侧修复，历史 ADD_SIGN comment 行读出来后正确分流
