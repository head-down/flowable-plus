# ADR-0024: 加签查重 fast fail 与查重口径

**日期**: 2026-08-10
**状态**: 已接受

## 上下文

GitHub issue #71（下游 jw-zhyg-api 手动测试反馈，需求文档
`docs/planning/countersign-duplicate-add-fast-fail-requirement.md`）指出
`CounterSignWorkflow.addCounterSigner()` 的去重存在两个维度缺口：

1. **维度一（活跃重复）**：`resolveCurrentAssignees` 只查当前活跃任务。全部重复时
   `newAssignees` 为空 → 直接 `return`，无 comment、无异常、无任何副作用，接口静默返回成功；
   部分重复时"跳过重复: xxx"仅追加进 ADD_SIGN comment，操作当下无感知。
2. **维度二（本轮已投票）**：已投过票的人任务已完成、不在活跃任务中，加签选到这样的人会被
   当作新增审批人，`addMultiInstanceExecution` 直接创建重复任务、产生重复待办。该查重仅在下游
   发起会签（`JwProcessService.initiateCountersign`）实现，加签路径缺失。

评审（grilling）进一步发现三个隐藏问题：

- **漏洞 A**：需求文档原方案的 `resolveVotedAssigneesInRound` 按 `csRoundIndex == roundIndex`
  严格匹配，但模式 B（固定会签预填 A/B/C，从未加签过）以及原始审批人（owner）的历史任务
  **没有 `csRoundIndex`**（打标只在 `addCounterSigner` 内发生）→ 无标历史任务永远匹配不上，
  模式 B 场景下"已投票重复加签"修不掉。
- **漏洞 B**：`csRoundIndex` 在折返后于执行周期内重新计数（ADR-0019/ADR-0020），上一周期已投票人
  的 `csRoundIndex` 可能与当前周期撞号，只按 `csRoundIndex` 匹配会**误拦跨周期复用**。
- **隐藏 bug**：名单内部自重复 `[A, A]` 时，现有循环只查"与活跃会签人重复"，两个 A 均不在
  `currentAssignees` → 会创建两个 assignee=A 的重复任务。

## 决策

### 1. "本轮已投票"判定口径：当前执行周期内 + csRoundIndex 匹配

已完成任务的归属判定为：**当前执行周期内**（`findCurrentCycleBoundary` 限定，边界为 null 时
不过滤、兼容老数据）的同节点已完成任务，且轮次匹配规则：

- `roundIndex > 0`：任务局部变量 `csRoundIndex == roundIndex`
- `roundIndex == 0`：`csRoundIndex` 缺失**或** `== 0`（隐式轮次 0）

**剔除被删除的任务**（实现期补充，2026-08-10）：Flowable 中 `removeCounterSigner` 减签调用
`deleteMultiInstanceExecution(executionId, false)` 也会在历史表留下 **finished** 记录（
`deleteReason = "deleted"`），被减签者**从未投票**，不应被当作"已投票"拦截（否则
"减签后再加签回"这一合法场景被误拦）。因此仅统计 `deleteReason == null`（正常投票完成）的任务。
区分依据：正常 `taskService.complete()` 完成后历史任务 `deleteReason` 为 null；
被删除/终止的任务 `deleteReason` 非 null。

周期限定修复漏洞 B（跨周期撞号误拦）；`roundIndex == 0` 的"无标视为命中"修复漏洞 A
（模式 B / 原始审批人无标历史任务可被正确拦截）。

### 2a. 部分重复 → 整体失败

名单中任意一个与当前活跃会签人重复 → 抛 `IllegalArgumentException`，不创建任何任务。
原子性：加签要么全部生效，要么报错让调用方去掉重复人后重提。

### 2b. 名单内自重复 → 整体失败

名单内自重复（如 `[A, A]`）→ 抛 `IllegalArgumentException`，不创建任何任务。

### 3. 并发竞态不纳入范围（已知限制）

两人同时加签同一人存在并发竞态（查重与创建之间无原子性）。**本次不纳入范围**——正确解法需
按 processInstanceId 加锁（ADR-0017 原则，仅在具体方法内精准处理），超出本次 issue 范围。
ADR-0017 的乐观锁重试原则同样不适用于本场景（Flowable 多实例创建无版本化冲突可依赖）。

### 4. 下游双保险保留

下游 `JwProcessService.initiateCountersign` 已有"本轮已投票"查重，**保留、本次不动下游**。
需提示下游口径差异风险：引擎层按"当前执行周期 + csRoundIndex"判定，允许跨轮次/跨周期复用；
下游简单口径（如按全局历史或按 assignee 去重）可能误拦引擎层已放行的复用场景。

### 5. 异常类型与零副作用顺序

异常类型 `IllegalArgumentException`（与减签 L246-249 一致）。

所有查重前置到副作用之前，失败时不写 initiator、不建任务、不写 comment：

1. 名单内自重复检测 → 抛异常
2. 空白过滤（现状保留）
3. 维度一：活跃重复检测，`skippedAssignees` 非空即抛异常（替换原静默 return）
4. 轮次计算 isNewRound / roundIndex
5. 维度二：`resolveVotedAssigneesInRound` 与 newAssignees 求交集，非空即抛异常
6. 通过全部查重后才执行 `trySetCounterSignInitiator` → 批量加签 → 打标 → comment

`trySetCounterSignInitiator` 后移的等价性已验证：首次加签（伪单例）场景下两种时序的
`isNewRound` 结果一致（均为 false、roundIndex=0），不影响折返/多轮判定。

## 备选方案

- **仅把"全部重复静默 return"改为抛异常，部分重复仍跳过继续新增**：被否决 ——
  部分成功部分跳过带来"到底加没加上"的困惑，与减签/发起会签的原子校验风格不一致。
- **按全局历史 + assignee 严格去重**：被否决 —— 会误拦跨轮次复用（新轮次重新选择人员应允许，
  与发起会签查重口径一致）与跨周期复用（漏洞 B）。
- **仅按 `csRoundIndex` 严格匹配、无周期限定**：被否决 —— 漏掉模式 B 无标历史任务（漏洞 A），
  且折返后跨周期撞号误拦（漏洞 B）。

## 后果

- **正面**：
  - 加签两层查重（活跃重复 + 本轮已投票）fast fail，操作当下即时可见错误，与减签/发起会签统一
  - 名单内自重复隐藏 bug（创建两个同 assignee 任务）一并修复
  - 模式 B 无标历史任务重复加签被正确拦截（漏洞 A）
  - 跨周期复用不被误拦（漏洞 B）
- **负面**：
  - 重复加签从"静默成功"变为"报错"，调用方（前端）需能展示错误提示；正常场景（前端已排除
    当前会签人/已投票人）不受影响
  - "跳过重复"文案从 comment 移除，审批历史不再出现该提示
  - `resolveVotedAssigneesInRound` 增加一次历史查询（含 `includeTaskLocalVariables`），
    加签路径多一次引擎 I/O
- **风险**：
  - 低：仅影响"传了重复/已投票名单"的调用，无重复的正常加签行为不变
  - 并发竞态为已知限制（决策 3），极端并发下仍可能创建重复任务
