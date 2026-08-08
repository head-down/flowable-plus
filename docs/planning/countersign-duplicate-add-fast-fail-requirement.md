# 需求：重复加签应 fast fail 抛异常，而非静默跳过

**提交方**: jw-zhyg-api（下游接入方）
**日期**: 2026-08-08
**类型**: 功能变更请求（行为修正）
**优先级**: 中（不阻塞加签功能可用，但影响下游手动测试 C3 验收与用户操作反馈）
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

- **全部重复**：`newAssignees` 为空 → 方法直接 `return`，不创建任务、不写 comment，**静默无响应**。
- **部分重复**：新增部分正常执行，仅在 ADD_SIGN comment 中追加"跳过重复: xxx"，需翻审批历史才可见。

## 二、问题描述

下游 jw-zhyg-api 手动测试（ruoyi-flow-engine issue #47，C3"重复加签跳过"）发现：

1. **全部重复**时调用 `/activiti/process/addCounterSigner` 接口返回成功，但实际什么都没做，前端无任何提示——用户以为加签成功，实际名单里的人早已在会签中。
2. **部分重复**时的"跳过重复"提示只存在于审批历史 comment 中，操作当下无感知。
3. 与既有模式不一致：`removeCounterSigner`（减签）遇"已投票/剩余不足"直接抛 `IllegalArgumentException`；`initiateCountersign`（发起会签）遇"本轮已投票"直接抛异常（下游 `ServiceException`）。唯独加签走"静默跳过"。

期望：**重复加签直接抛异常（fast fail）**，前端立即收到明确错误提示，与减签/发起会签的校验风格统一。

## 三、变更需求

### 3.1 目标

`addCounterSigner()` 遇重复审批人时抛 `IllegalArgumentException`，不再静默跳过，也不再把"跳过重复"写进 comment。

### 3.2 改动点

**文件**: `flowable-plus-core/src/main/java/io/github/flowable/plus/core/workflow/CounterSignWorkflow.java`
**方法**: `addCounterSigner(PlusTask task)`（约第 138-228 行）

变更逻辑（拆分循环之后）：

```java
// 现状：
if (newAssignees.isEmpty()) {
    return;
}

// 期望：名单中任意一个与当前活跃会签人重复 → 整体失败
if (!skippedAssignees.isEmpty()) {
    throw new IllegalArgumentException(
            "审批人 " + String.join(", ", skippedAssignees) + " 已在本轮会签中，无法重复加签");
}
```

同时移除 comment 组装中的"跳过重复"追加逻辑（`commentMsg.append("，跳过重复: ...")`），仅保留"加签审批人: xxx"。

### 3.3 涉及范围

- `addCounterSigner()` —— 本次变更核心
- `initiateCountersign`（下游 `JwProcessService`）内部也调用 `addCounterSigner`：发起会签前已有"本轮已投票"查重，且发起场景 assignees 不含发起人自己，与当前活跃任务（仅发起人）不会重复，基本不受影响。若受影响，下游侧同步调整调用顺序（先校验后写 comment）。

## 四、设计决策（需 flowable-plus 评审确认）

**部分重复时是整体失败，还是跳过重复项继续新增？**

- **推荐：名单中任意一个重复 → 整体失败**（原子性）。加签要么全部生效，要么报错让调用方去掉重复人后重提，行为可预期，避免"部分成功部分被跳过"带来的困惑。
- 备选：部分重复时仍执行新增部分、仅跳过重复项（保持现状的宽容语义，仅把"全部重复"由静默 return 改为抛异常）。若选择此方案，"跳过重复"comment 保留与否需另行确认。

## 五、验收标准

1. 全部重复（名单均为当前活跃会签人）→ 抛 `IllegalArgumentException`，不创建任务，无 comment 写入
2. 部分重复（推荐方案下）→ 整体抛 `IllegalArgumentException`，不创建任何任务
3. 无重复 → 正常加签（回归，含多轮加签、`csRoundIndex` 打标不变）
4. 单元测试 `CounterSignWorkflowTest.testAddCounterSignerSkipsDuplicate`（约第 473-499 行）同步更新：从"验证不调用 addMultiInstanceExecution"改为"验证抛 `IllegalArgumentException`"
5. 加签/减签/发起会签的异常校验风格统一（均为调用方即时可见的错误）

## 六、影响与风险

| 项 | 说明 |
|----|------|
| 语义变化 | 重复加签从"静默成功"变为"报错"，调用方（前端）需能展示错误提示；若前端选人列表已排除当前会签人，则正常场景不受影响 |
| 兼容性 | 仅影响"传了重复名单"的调用，无重复的正常加签行为不变 |
| 测试 | 需更新 `testAddCounterSignerSkipsDuplicate`，并新增"部分重复整体失败"用例（若采用推荐方案） |
| comment | "跳过重复"文案从 comment 移除，审批历史不再出现该提示 |
