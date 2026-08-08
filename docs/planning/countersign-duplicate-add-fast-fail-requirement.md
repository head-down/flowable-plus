# 需求：加签查重补全——重复/已投票加签应 fast fail 抛异常

**提交方**: jw-zhyg-api（下游接入方）
**日期**: 2026-08-08
**类型**: 功能变更请求（行为修正）
**优先级**: 中（不阻塞加签功能可用，但影响下游手动测试 C3/C6 验收与用户操作反馈）
**状态**: 🆕 待评审（已提 issue，见 head-down/flowable-plus #71）

---

## 一、背景

flowable-plus 的 `CounterSignWorkflow.addCounterSigner()` 在加签时对名单做去重：

```java
List<String> newAssignees = new ArrayList<>();
List<String> skippedAssignees = new ArrayList<>();
for (String assignee : assignees) {
    if (StrUtil.isBlank(assignee)) {
        continue;
    }
    if (currentAssignees.contains(assignee)) {
        skippedAssignees.add(assignee);   // 重复（已在当前活跃会签人中）
    } else {
        newAssignees.add(assignee);
    }
}

if (newAssignees.isEmpty()) {
    return;   // ← 全部重复时直接返回，无 comment、无异常、无任何提示
}
```

现状去重存在**两个维度缺口**：

- **维度一（活跃重复）**：`currentAssignees` 来自 `resolveCurrentAssignees`，只查**当前活跃任务**（未投票的审批人）。全部重复时直接 `return` 静默无响应；部分重复时"跳过重复"仅写入审批历史 comment，操作当下无感知。
- **维度二（已投票重复）**：已投过票的人任务已完成，**不在活跃任务列表中**，加签选到这样的人会被当作新增审批人，`runtimeService.addMultiInstanceExecution` 直接创建重复任务，产生重复待办。该"本轮已投票"查重只在发起会签（下游侧 `JwProcessService.initiateCountersign`）实现，**加签路径缺失**。

## 二、问题描述

下游 jw-zhyg-api 手动测试（ruoyi-flow-engine issue #47，C3"重复加签跳过"、C6"加签选人查重"）发现：

1. **全部重复**时调用 `/activiti/process/addCounterSigner` 接口返回成功，但实际什么都没做，前端无任何提示——用户以为加签成功，实际名单里的人早已在会签中。
2. **部分重复**时的"跳过重复"提示只存在于审批历史 comment 中，操作当下无感知。
3. **已投过票的人可被重复加签**：加签名单含本轮已投过票的人 → 被当作新增审批人创建任务，该审批人收到重复待办。发起会签有"本轮已投票"查重（下游 `ServiceException`），加签路径缺失。
4. 与既有模式不一致：`removeCounterSigner`（减签）遇"已投票/剩余不足"直接抛 `IllegalArgumentException`；`initiateCountersign`（发起会签）遇"本轮已投票"直接抛异常。唯独加签走"静默跳过/直接创建"。

期望：**加签两层查重（活跃重复 + 本轮已投票）均直接抛异常（fast fail）**，前端立即收到明确错误提示，与减签/发起会签的校验风格统一。

## 三、变更需求

### 3.1 目标

`addCounterSigner()` 对名单做两层查重，命中任一即抛 `IllegalArgumentException`（fast fail）：

1. 与**当前活跃会签人**重复（未投票者）
2. 与本轮（`csRoundIndex` 匹配）**已投过票**的审批人重复

不再静默跳过，也不再把"跳过重复"写进 comment。

### 3.2 改动点

**文件**: `flowable-plus-core/src/main/java/io/github/flowable/plus/core/workflow/CounterSignWorkflow.java`
**方法**: `addCounterSigner(PlusTask task)`（约第 138-228 行）

变更逻辑：

```java
// ① 拆分循环（现状保留）：区分 newAssignees / skippedAssignees

// ② 维度一：活跃重复拦截（替换"全部重复时 return"）
if (!skippedAssignees.isEmpty()) {
    throw new IllegalArgumentException(
            "审批人 " + String.join(", ", skippedAssignees) + " 已在本轮会签中，无法重复加签");
}

// ③ 轮次计算（现状保留）：isNewRound / roundIndex（约第 180-191 行）

// ④ 维度二：本轮已投过票拦截（新增，依赖 roundIndex，放在批量加签之前）
List<String> votedAssigneesInRound = resolveVotedAssigneesInRound(
        processInstanceId, activityId, roundIndex);
List<String> alreadyVoted = newAssignees.stream()
        .filter(votedAssigneesInRound::contains)
        .collect(Collectors.toList());
if (!alreadyVoted.isEmpty()) {
    throw new IllegalArgumentException(
            "审批人 " + String.join(", ", alreadyVoted) + " 已在本轮投过票，无法重复加签");
}

// ⑤ 批量加签（现状保留，约第 194-198 行）
```

同时：
- 移除 comment 组装中的"跳过重复"追加逻辑（`commentMsg.append("，跳过重复: ...")`），仅保留"加签审批人: xxx"。
- 新增私有方法 `resolveVotedAssigneesInRound(processInstanceId, activityId, roundIndex)`：查询同节点已完成历史任务（`finished` + `includeTaskLocalVariables`），提取 `csRoundIndex` 任务局部变量，返回与 `roundIndex` 匹配的已投票审批人集合。判定口径与 `initiateCountersign` 的下游查重一致（参考 `JwProcessService` 现有实现）。

### 3.3 涉及范围

- `addCounterSigner()` —— 本次变更核心（活跃重复 + 本轮已投票 两层查重）
- 新增 `resolveVotedAssigneesInRound` 辅助方法
- `initiateCountersign`（下游 `JwProcessService`）：已有"本轮已投票"查重（抛 `ServiceException`），本次不改下游逻辑。引擎层实现后，下游查重保留作双保险或移除由评审决定。
- 相关单元测试：`CounterSignWorkflowTest.testAddCounterSignerSkipsDuplicate`（约第 473-499 行）需更新，并新增"本轮已投票被拦截"用例。

## 四、设计决策（需 flowable-plus 评审确认）

**决策一：部分重复时是整体失败，还是跳过重复项继续新增？**

- **推荐：名单中任意一个重复 → 整体失败**（原子性）。加签要么全部生效，要么报错让调用方去掉重复人后重提，行为可预期，避免"部分成功部分被跳过"带来的困惑。
- 备选：部分重复时仍执行新增部分、仅跳过重复项（保持现状的宽容语义，仅把"全部重复"由静默 return 改为抛异常）。若选择此方案，"跳过重复"comment 保留与否需另行确认。

**决策二："本轮已投票"的判定口径**

- 查历史任务按 `csRoundIndex` 匹配"本轮"，而非所有历史轮次。跨轮次加签选**上一轮**投过票的人应允许（新轮次重新选择，与发起会签查重口径一致）。
- 判定时机在轮次计算之后（依赖 `roundIndex`），批量加签之前，避免部分创建。

## 五、验收标准

1. 全部重复（名单均为当前活跃会签人）→ 抛 `IllegalArgumentException`，不创建任务，无 comment 写入
2. 部分重复（推荐方案下）→ 整体抛 `IllegalArgumentException`，不创建任何任务
3. 加签名单含本轮已投过票的人 → 抛 `IllegalArgumentException`，不创建任务
4. 跨轮次加签选上一轮已投过票的人 → 正常通过（本轮未投票即可加签）
5. 无重复 → 正常加签（回归，含多轮加签、`csRoundIndex` 打标不变）
6. 单元测试：`testAddCounterSignerSkipsDuplicate` 更新为断言抛异常；新增"本轮已投票被拦截"用例
7. 加签/减签/发起会签的异常校验风格统一（均为调用方即时可见的错误）

## 六、影响与风险

| 项 | 说明 |
|----|------|
| 语义变化 | 重复加签从"静默成功"变为"报错"，调用方（前端）需能展示错误提示；若前端选人列表已排除当前会签人/已投票人，则正常场景不受影响 |
| 兼容性 | 仅影响"传了重复/已投票名单"的调用，无重复的正常加签行为不变 |
| 查重口径 | 按 `csRoundIndex` 匹配本轮，跨轮复用人员不被误拦（与发起会签查重口径一致） |
| 测试 | 需更新 `testAddCounterSignerSkipsDuplicate`，新增"部分重复整体失败""本轮已投票被拦截""跨轮复用通过"用例 |
| comment | "跳过重复"文案从 comment 移除，审批历史不再出现该提示 |
