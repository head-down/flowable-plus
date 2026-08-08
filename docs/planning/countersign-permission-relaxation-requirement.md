# 需求：模式A（偶发会签）加签权限放宽为"会签发起人 OR 当前活跃审批人"

**提交方**: jw-zhyg-api（下游接入方）
**日期**: 2026-08-08
**类型**: 功能变更请求
**优先级**: 高（阻塞下游加签功能正常使用）
**状态**: ✅ 已实现（2026-08-08，见 issue #70）

---

## 实现决策记录

- **减签同步放宽**：`validateCounterSignPermission` 为加签/减签共享方法，无法对单一操作差异化放行，因此减签随加签一并放宽为"会签发起人 OR 当前节点活跃审批人"。jw-zhyg-api 业务层已禁用减签（ADR-0004），引擎层放宽不构成实际风险。
- 实现：`CounterSignWorkflow.validateCounterSignPermission()` 模式A分支新增活跃审批人旁路。
- 测试：反转原"非发起人被拒"用例为"活跃审批人通过"，新增"非活跃审批人被拒"用例，全部通过。

---

## 一、背景

flowable-plus 的 `CounterSignWorkflow.validateCounterSignPermission()` 实现了双模式加签/减签权限校验（ADR-0022）：

| 模式 | 判定依据 | 加签/减签权限 |
|------|---------|--------------|
| 模式A（偶发会签/伪单例） | 流程变量 `countersignInitiator_<taskDefKey>` 已设置 | **仅会签发起人** |
| 模式B（固定会签） | 变量未设置 | 任意活跃审批人 |

该模型在下游 jw-zhyg-api 实际使用中出现功能死锁，**加签功能无法正常使用**。

## 二、问题描述

### 2.1 场景复现

jw-zhyg-api 的会签流程采用模式A：

1. 流程进入会签节点，初始仅 1 名发起人 admin（伪单例）
2. admin 通过 `/initiateCountersign` 发起会签，加签 B、C、D
3. `initiateCountersign` 内部对 admin 的子任务执行 `taskService.complete()`（发起人不投票，见 jw-zhyg-api ADR-0003），**admin 的待办消失**
4. B、C、D 获得待办，进入会签投票

### 2.2 死锁表现

| 角色 | 是否有加签入口 | 是否有加签权限 | 结果 |
|------|--------------|--------------|------|
| 发起人 admin | ❌ 待办已消失，进不了详情页 | ✅ 是发起人 | **无入口** |
| 参与人 B/C/D | ✅ 待办详情页有"加签"按钮（前端对活跃审批人可见） | ❌ 不是发起人 | **报错：`用户 000798 不是会签发起人 admin，无权操作`** |

前端入口与后端权限校验各管一截，中间断开了：

- 前端"加签"按钮对**所有活跃审批人**可见（`isCanApproval` 仅按活跃审批人过滤，不区分发起人）
- 后端 `validateCounterSignPermission()` 在模式A下**只认会签发起人**

结果是：发起人有权限但无入口，参与人有入口但无权限，加签功能实际不可用。

## 三、变更需求

### 3.1 目标

将模式A的加签/减签权限从"仅会签发起人"放宽为"**会签发起人 OR 当前节点活跃审批人**"，与模式B及主流平台（钉钉/飞书均为"当前审批人可加签"）保持一致。

### 3.2 改动点

**文件**: `flowable-plus-core/src/main/java/io/github/flowable/plus/core/workflow/CounterSignWorkflow.java`
**方法**: `validateCounterSignPermission(PlusTask task)`（约第 608-634 行）

变更逻辑：

```java
// 现状（模式A分支）：
if (countersignInitiator != null) {
    if (currentUserId.equals(countersignInitiator)) {
        return;
    }
    throw new PermissionDeniedException(
            "用户 " + currentUserId + " 不是会签发起人 " + countersignInitiator + "，无权操作");
}

// 期望：
if (countersignInitiator != null) {
    if (currentUserId.equals(countersignInitiator)) {
        return;
    }
    // 新增：当前节点活跃审批人也可加签
    List<String> currentAssignees = resolveCurrentAssignees(task);
    if (currentAssignees.contains(currentUserId)) {
        return;
    }
    throw new PermissionDeniedException(
            "用户 " + currentUserId + " 不是会签发起人 " + countersignInitiator
                    + " 或当前节点活跃审批人，无权操作");
}
```

模式B分支保持不变。

### 3.3 涉及范围

- `addCounterSigner()`（加签）—— **必须放宽**
- `removeCounterSigner()`（减签）—— `validateCounterSignPermission` 为两者共享方法，逻辑上会一并放宽。但注意 jw-zhyg-api 业务层已禁用减签（ADR-0004），仅引擎层保留能力。**是否同步放宽减签由 flowable-plus 决策**，可在评审时确认。

## 四、验收标准

1. 模式A：会签发起人加签 → 通过（回归）
2. 模式A：被加签的活跃审批人（非发起人）加签 → **通过**（本次变更核心）
3. 模式A：非活跃审批人（不在当前节点活跃任务中）加签 → 拒绝
4. 模式B：活跃审批人加签 → 通过（回归）
5. 多轮折返后：模式A原发起人仍可加签（`countersignInitiator_<key>` 保持不变）→ 通过
6. 新增/更新单元测试，覆盖上述场景

## 五、影响与风险

| 项 | 说明 |
|----|------|
| 语义变化 | 模式A从"发起人集中控制"变为"参与人也可加签"，与主流平台一致。**发起会签**（`/initiateCountersign`，需完成发起人子任务）入口不受影响——前端 `canInitiateCountersign` 判断 `initiator == task.assignee`，仍仅发起人可见 |
| 权限收窄面 | 非活跃审批人仍被拒绝，无安全回退 |
| 文档同步 | 需更新 `docs/research/countersign-permission-model-research.md` 的模式A权限结论（该文档当前明确"被加签的人不应再越权加签他人"） |
| 减签 | 业务层已禁用，引擎层是否同步放宽待决策 |
