# ADR-0034: 常规审批操作多实例拦截改运行时判定（伪单例放行）

**日期**: 2026-08-12
**状态**: 已接受

## 上下文

常规审批操作（`completeTask` / `rejectTask` / `rejectTaskToInitiator` / `withdrawTask` / `jumpToNode`）
的多实例拦截一直基于**模型静态判定**：`MultiInstanceDetector.isMultiInstance` 只看 BPMN 模型
是否配置了 `multiInstanceLoopCharacteristics`（`flowable-plus-core/.../model/MultiInstanceDetector.java`），
伪单例（会签节点 collection 只有 1 人，运行时只有 1 个活跃子任务）被误判为多实例而拦截。

而 `CounterSignWorkflow.isPseudoSingleton` 已是**运行时口径**（活跃任务数==1 且该节点全局历史任务数==1），
两处结论矛盾：同一伪单例任务，加签侧认为"未进入真多实例"，常规操作侧却将其拦死。

### 用户核心场景

- "财务/风控负责人"节点被建模为会签（multiInstance）节点，但运行时只有 1 个审批人（伪单例）。
- 该节点**无可用"提交/同意"路径**：`completeTask` 被拦（模型判定多实例）、`counterSign` 语义不符
  （会签要求多人投票），审批人只能走"返回"（库层 `jumpToNode`，业务层 jw-zhyg-api 命名为"返回"）。
- 业务侧无手段表达"这个会签节点实际只有 1 人，请按单实例处理"。

### 现状

- `completeTaskAsSingleton`（`f3924a5`，2026-08-06）曾试图以"跳过模型检测"放行伪单例 complete，
  但仅覆盖 complete、**零测试**、且把伪单例判断责任推给调用方。
- git 历史查证：`rejectTask` 的多实例拦截自 `98f68a2`（2026-07-03 会签落地）起就是模型判断，
  "伪单例可返回"的记忆在历史中无对应实现。

## 决策

### 1. 伪单例判据（运行时）

伪单例 = **活跃任务数 == 1 且该节点全局历史任务数 == 1**，与 `CounterSignWorkflow.isPseudoSingleton`
现口径完全一致。因此：

| 场景 | 活跃数 | 全局历史数 | 判定 |
|---|---|---|---|
| 模式 A 伪单例首次进入 | 1 | 1 | 伪单例 ✓ |
| 模式 A 首次加签 | 1 | 1 | 伪单例 ✓ |
| 加签/减签后回 1 人 | 1 | > 1 | 非伪单例 → 拦截 |
| 会签剩最后 1 人未投 | 1 | > 1 | 非伪单例 → 拦截 |
| 折返后新周期 1 人 | 1 | > 1（含上一周期） | 非伪单例 → 拦截 |

"会签剩最后 1 人未投"保持拦截：该场景仍需其余审批人投票，常规操作会破坏会签计数语义。

### 2. 判据落点：MultiInstanceDetector 统一

运行时判定收敛至 `MultiInstanceDetector`（构造注入 `TaskService` + `HistoryService`）：

- `isRuntimeMultiInstance(task)` = `isMultiInstance(task) && !isPseudoSingleton(task)`，
  供 `TaskValidation.validateNotMultiInstance`（常规操作拦截）使用；
- `isPseudoSingleton(task)` 从 `CounterSignWorkflow` 私有方法抽到公共判据，
  `CounterSignWorkflow` 改调它，消除口径漂移。

普通节点在模型判定处短路，运行时判定仅对模型多实例节点产生 2 次额外查询（活跃计数 + 历史计数），可接受。

### 3. 删除 completeTaskAsSingleton

其功能被 `completeTask` 的运行时判定自动覆盖。8/6 才加、无测试、项目无人使用，
删除符合 ADR-0028/0031 的 API 收敛方向。

### 4. 放行范围

全部 5 个常规操作：`completeTask` / `rejectTask` / `rejectTaskToInitiator` / `withdrawTask` / `jumpToNode`。

### 5. validateMultiInstance 保持模型判断

`counterSign` / 加签 / 减签 / 委派侧的 `validateMultiInstance` **保持模型判断不动**——
伪单例仍需可加签（模式 A 的初始态正是伪单例）。

### 6. 自动重定向策略无需改

`AutoRedirectCountersignRollbackStrategy` 已对**目标节点**做运行时判定（count ≤ 1 放行），
伪单例放行后 `executeRollback` 链路通。

## 兼容性

| 接入方形态 | 兼容情况 |
|-----------|---------|
| 调用方（5 个常规操作） | ⚠️ 行为变化：伪单例任务从"被拦截"变为"放行"，这是本 ADR 的目标 |
| 调用方（`completeTaskAsSingleton`） | ❌ 已删除。功能被 `completeTask` 自动覆盖，编译期报错可立即发现 |
| `MultiInstanceDetector` 直接构造者 | ⚠️ 构造签名变化（+TaskService+HistoryService），编译期报错 |
| 会签侧 | ✅ 行为不变：`isPseudoSingleton` 判据与旧实现完全一致 |

## 后果

- **正面**：
  - 伪单例会签节点获得可用"提交/同意/返回"路径，业务"返回"（jumpToNode）可正常执行；
  - 会签剩最后 1 人未投、加签/减签回 1 人等真多实例场景仍被拦截，会签计数语义不被破坏；
  - 常规操作与加签侧共用同一伪单例判据，消除口径漂移。
- **负面 / 风险**：
  - `MultiInstanceDetector` 构造签名变化波及自动配置、各 workflow 及直接 `new` 它的单元测试；
  - 从多实例 body 内部 `moveActivityIdTo` 移走唯一子任务时，Flowable 6.8.0 的 miBody/计数器状态
    清理依赖引擎行为，已通过集成测试验证"不残留孤立 miBody execution、流程可继续"
    （折返后任务干净重建；因全局历史数 > 1，折返后的节点走 counterSign 而非伪单例放行）；
  - 伪单例放行属行为变更，内测期无人使用，风险低。
