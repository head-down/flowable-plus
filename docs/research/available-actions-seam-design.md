# 可用动作集（Action Availability）接缝设计

> 性质：**设计提案，未采纳**。本文只定接口形态与接缝位置，是否进入实现待维护者评估；若采纳，须先补 ADR（新增公开 API + 可选策略回调）。
> 时间：2026-11-03
> 来源：`docs/research/approval-implementation-benchmark.md` §5.1 / §5.2
> 关联：ADR-0005、ADR-0007、ADR-0010、ADR-0011、ADR-0015、ADR-0017、ADR-0022、ADR-0032、ADR-0033、ADR-0035、ADR-0039、ADR-0042
> 术语：**module / interface / seam / adapter / depth / leverage / locality** 按 `codebase-design` 技能定义使用。

## 1. 问题

「当前用户在这个任务上能做哪些动作」是本仓**没有接口的事实**，它只以**隐式形式**散落在三条写路径的守卫链里：

- `TaskValidation`（`validateTaskExists` / `validateCurrentUserIsAssignee` / `validatePreviousNodeAssignee` / `validateNotMultiInstance` / `validateMultiInstance`）
- 各 Workflow 写方法各自选择的守卫组合
- extension 侧 `ComparableAction.isAvailableFor(action, modelMI, runtimeMI, initiatorDecisionTask)`（`ComparableAction.java:94`）——**包内可见**、只认 4 个动作、只判结构

后果是三处漂移：

1. **UI 层**：按钮渲染必须自己重推守卫，或在业务代码里硬编码节点/角色判断（参考项目 R 的做法是节点 JSON 配置，见对比文档 §5.1）。
2. **AI 层**：ADR-0042 的"建议准入"需要知道"人能不能做这个动作"，但只能拿到 4 个动作的结构判定，拿不到"这个人"的维度。
3. **一致性**：新增一个动作或调整一条守卫时，必须记得同步三处；没有任何机制保证同步。

> 一句话：**框架自己知道答案，但没把答案做成接口。**

## 2. 现状证据：守卫矩阵

写路径实际使用的守卫（逐方法读源码得出）：

| 动作 | 写入口 | 守卫链 |
|---|---|---|
| `AGREE` | `TaskExecutionWorkflow:78` `completeTask` | 任务存在且活动 + 非（真）多实例 + **claim 语义**（未认领或已认领给自己） |
| `REJECT` | `:109` `rejectTask` | 任务存在 + 受理人匹配 + 非（真）多实例（折返决策任务豁免） |
| `RETURN` | `:135` `rejectTaskToInitiator` | 同上（折返决策任务豁免） |
| `WITHDRAW` | `:167` `withdrawTask` | 任务存在 + 非（真）多实例 + **上一节点审批人**身份 |
| `TRANSFER` | `:196` `transferTask` | 任务存在 + 受理人匹配 |
| `jump`（**值域外**：动作词汇里无 `JUMP`，跳转以 `CommentType` 参数表达） | `:229` `jumpToNode` | 任务存在 + 受理人匹配 + 非（真）多实例 + 目标节点在历史集合内 |
| `COUNTER_SIGN_AGREE` / `COUNTER_SIGN_REJECT` | `CounterSignWorkflow:75` `counterSign` | 任务存在 + 受理人匹配 + **多实例** |
| `ADD_SIGN` | `:150` `addCounterSigner` | 任务存在 + 多实例 |
| `DELETE_SIGN` | `:327` `removeCounterSigner` | 任务存在 + 多实例 |
| `DELEGATE` | `:375` `delegateTask` | 任务存在 + 受理人匹配 + 多实例 |
| `RESOLVE_DELEGATE` | `:414` `resolveDelegate` | 任务存在 + **当前用户是委派人（`task.owner`）** |
| `INVALID` | `ProcessLifecycleWorkflow:99` `invalidateProcess` | 流程级（非任务级，**不进本接口的值域**） |

两个必须写进接口实现细节的事实：

1. **`completeTask` 没有 assignee 断言**，它用 `taskService.claim(taskId, userId)` 的引擎语义表达"未被认领或已归我"。可用性判定若写成 `assignee.equals(me)` 就会与写路径不一致（未认领的任务会被误判为不可用）。
2. **折返决策任务豁免**（ADR-0035）让 `REJECT` 在真多实例下也可用。这是"动作 × 结构"的交叉例外，天然属于 module 内部。

## 3. 设计约束

| 约束 | 来源 | 对本设计的要求 |
|---|---|---|
| 不允许裸透传 | ADR-0015 | 本接口带**权限校验 + 审批语义 + MI 判定**三重增值，满足准入门槛；实现不得只是转调 `TaskQuery` |
| 框架不内建 RBAC/部门模型 | ADR-0032 | 角色/岗位规则**只能以回调形式**由应用填；框架不持有角色表、不解释角色语义 |
| 框架必须自防越权 | ADR-0032 权限三层 | 结构守卫恒由框架先算，应用策略**只能收窄不能放宽** |
| 门面纯聚合、仅读 | ADR-0010 | 只读方法，可经 `FlowablePlus` 门面暴露；不得有副作用 |
| 接口不得变杂物袋 | ADR-0039 | 不往 `QueryOperations` 堆；动作可用性归动作域 |
| 可用动作的定义 | ADR-0042 §12 | **「某动作可用」的唯一定义 = core 对应写路径的守卫会放行**，逐动作对齐、零误拒、不拍档位 |
| 单一事实源 | ADR-0028 | 不得新增侧表/缓存作为第二真相 |

## 4. 接口设计（设计两次）

### 方案 A：逐动作布尔查询

```java
boolean canPerform(String taskId, ApprovalAction action);
```

- 优点：参数直白，单点测试容易。
- 缺点：UI 要渲染 12 个按钮就得 12 次调用 → N+1（每次都要读任务 + 读模型 + 判 MI）。**接口诱导调用方做错事**，是浅接口的典型症状。

### 方案 B：单方法返回可用集

```java
Set<ApprovalAction> getAvailableActions(String taskId);
```

- 优点：一次调用拿全量；surface 极小（1 方法 1 参数）。
- 缺点：UI 拿不到"为什么不可用"（禁用按钮需要 tooltip / 引导走会签）；AI 侧只需要 4 个动作，但必须拿全量再自己过滤。

### 方案 C：单方法返回值对象（选定）

```java
public interface TaskExecutionOperations {
    /** 读取单任务的可用动作集；只读、无副作用、不做任何写操作。 */
    ActionAvailability getActionAvailability(String taskId);
}
```

```java
public final class ActionAvailability {

    /** 可用动作集（不可修改；可能为空集，空集是合法态）。 */
    Set<ApprovalAction> getAvailable();

    /** 单动作判定（等价于 getAvailable().contains(action)，同源保证）。 */
    boolean isAvailable(ApprovalAction action);

    /** 不可用原因；动作可用时返回 null。 */
    UnavailableReason reasonOf(ApprovalAction action);
}
```

```java
public enum UnavailableReason {
    /** 任务不存在 / 已完成 / 流程已结束 */
    TASK_NOT_ACTIVE,
    /** 当前用户既非受理人、也未认领该任务 */
    NOT_ASSIGNEE,
    /** 真多实例节点：常规动作须走会签 */
    MULTI_INSTANCE,
    /** 非多实例节点：会签类动作不可用 */
    NOT_MULTI_INSTANCE,
    /** 撤回：当前用户不是上一节点审批人 */
    NOT_PREVIOUS_ASSIGNEE,
    /** 收回委派：当前用户不是该任务的委派人 */
    NOT_DELEGATOR,
    /** 应用策略回调否决（只收窄） */
    POLICY_DENIED
}
```

**深度评估**（12 个动作 × 5 类守卫事实 → 1 方法 + 1 值类型）：

- **Leverage**：UI 按钮、AI 建议准入、批量操作预检、后端接口自检四个消费者共用一份判定。
- **Locality**：守卫语义（含 claim 语义、折返豁免）集中一处；新增动作只改一处。
- **删除测试**：删除本模块 → 每个消费者重新推导守卫矩阵，且必须读懂 `TaskValidation` + MI 检测 + ADR-0035 豁免才写得对 → 复杂度会在 N 个调用点重现。**通过删除测试。**

## 5. 接缝与适配器

```
        getActionAvailability(taskId)          ← 唯一外部接缝
                    │
        ┌───────────┴────────────┐
        │  结构面（框架恒算）      │  ← 内置适配器：TaskValidation + MI 检测 + 折返豁免 + claim 语义
        └───────────┬────────────┘
                    │ 交集（只收窄）
        ┌───────────┴────────────┐
        │  策略面（应用可选）      │  ← ActionAvailabilityPolicy SPI（可选 Bean）
        └────────────────────────┘
```

```java
/** 应用侧可选策略：只能收窄框架已判定的可用集，不得放宽。 */
public interface ActionAvailabilityPolicy {
    /**
     * @param taskId   任务标识
     * @param available 框架结构面已判定的可用集（只读）
     * @return 收窄后的可用集；必须为 available 的子集
     */
    Set<ApprovalAction> narrow(String taskId, Set<ApprovalAction> available);
}
```

**接缝纪律**：

- 策略面是**回调而非模型**（ADR-0032）：框架不定义角色、岗位、部门，只定义"再过滤一次"的位置。
- 两个适配器才成立（内置结构面 + 应用策略面），符合"一个适配器只是假想接缝"。
- **fail-closed**：策略回调抛异常 ⇒ 该任务视为不可用（或按实现约定降级为结构面并打 WARN，二选一须在 ADR 里定死，不得沉默 fail-open）。
- 防放宽：收到非子集返回值 ⇒ 与 `available` 求交，并记 WARN（不静默信任）。

## 6. 与 ADR-0042 的关系（消除第二真相）

当前 `ComparableAction.isAvailableFor` 是包内判定，注释明确"不进公开面"。本设计**不引入第二个判定实现**，而是：

1. core 新增本 module，作为唯一判定。
2. extension 的 `ComparableAction` 退化为**投影**：`MEMBERS`（4 个可比较动作）不变，`isAvailableFor` 改为从 `ActionAvailability` 取子集。
3. `ComparableActionAvailabilityTest`（镜像守卫测试）继续存在，断言"4 个可比较动作 ⊆ 可用集 ⇔ 写路径放行"。

这样 ADR-0042 的"可用动作 = 守卫放行"定义从**写在注释里的约定**变成**可执行的不变量**，且不触碰四行残留账本。

## 7. 测试策略（接口即测试面）

只通过 `getActionAvailability` 断言，不测内部方法（不测 past the interface）。

**核心不变量（镜像守卫测试）**：对值域内每个动作，在同一夹具上执行写路径：

| 写路径结果 | 断言 |
|---|---|
| 成功 | 动作 ∈ 可用集 |
| 抛权限/状态类异常 | 动作 ∉ 可用集，且 `reasonOf` 非 null |
| 抛其他异常（如引擎内部错误） | 不参与断言（不得据此反推可用性） |

**必需场景（6 类）**：

1. 非受理人访问他人已认领任务 → 仅 `TASK_NOT_ACTIVE` / `NOT_ASSIGNEE` 类原因。
2. **未认领**任务 → `AGREE` 可用（验证 claim 语义，防止退化成 assignee 相等判定）。
3. 真多实例节点 → 常规动作不可用、会签动作可用（`MULTI_INSTANCE`）。
4. **伪单例**（运行时仅剩 1 个活跃子任务、该节点历史任务数 == 1）→ 常规动作可用（ADR-0034）。
5. **折返发起人决策任务** → `REJECT` 可用（ADR-0035 豁免）。
6. 流程已结束 / 任务已完成 → 空集 + `TASK_NOT_ACTIVE`。

## 8. 兼容与迁移

- 新增接口方法属**源码级破坏**（任何自行实现 `TaskExecutionOperations` 的下游需补实现）。本项目当前无外部使用者，可接受，但须在 CHANGELOG 记 breaking。
- 实现落在 `TaskExecutionWorkflow`（与守卫同处，保证 locality），门面按 ADR-0010 自动聚合。
- 写路径**行为零变化**：本 module 只读、不参与任何写操作的放行决策；写路径仍各自校验（防止"读到的可用性在写之前失效"这类 TOCTOU 被误当成授权）。

> 重要非目标：本接口**不是授权令牌**。它回答"此刻看起来能不能做"，不保证写入一定成功（任务可能被并发抢走）。这一条必须写进 Javadoc，否则会被误用成鉴权。

## 9. 未决问题

| # | 问题 | 现状与倾向 |
|---|---|---|
| Q1 | 值域用 `ApprovalAction` 还是新枚举 | `ApprovalAction` **缺 `DELEGATE` / `RESOLVE_DELEGATE` / `JUMP`**（`CommentType` 有委派两项、跳转以参数表达）。倾向：先补这三个值（小 ADR + 更新 `ComparableAction` 镜像守卫），再开本接口，避免「委派/跳转按钮拿不到可用性」的残缺 |
| Q2 | 放 `TaskExecutionOperations` 还是新接口 | 倾向 `TaskExecutionOperations`（就近守卫，locality）；若下游实现面顾虑大，退化为单方法新接口 |
| Q3 | 策略回调异常的处理 | 未定：fail-closed（该任务全部不可用）vs 降级结构面 + WARN。须在 ADR 里定死，不得留"看情况" |
| Q4 | 是否经 `FlowablePlus` 门面暴露 | 倾向暴露（纯读，符合 ADR-0010）；门面已聚合 `TaskExecutionOperations` |
| Q5 | 批量形态（多个 taskId） | v1 不做；批量审批（对比文档 §3.5）落地时再评估，避免提前抽象 |

## 附录 A：被否方案

| 方案 | 否决理由 |
|---|---|
| 逐动作布尔查询（方案 A） | 诱导 N+1，接口设计本身引导调用方做错事 |
| 只返回布尔 + 无原因 | UI 无法解释禁用原因；AI 侧无法区分"结构不允许"与"权限不足" |
| 在建表/缓存里存可用动作 | 违反 ADR-0028 单一事实源；状态一变即过期，且引入失效逻辑 |
| 框架内建角色/岗位白名单（参考项目 R 的节点 JSON 配置） | 违反 ADR-0032（不内建 RBAC 模型）；改为策略回调 |
| 暴露 `isAvailableFor` 的四个布尔参数 | 泄漏实现细节；调用方需自行收集 `modelMI / runtimeMI / initiatorDecisionTask`，等于把守卫搬出去 |

## 附录 B：动作 → 守卫 → 原因 映射

| 动作 | 结构守卫（内置） | 主要不可用原因 |
|---|---|---|
| `AGREE` | 任务活动 + 非真 MI + claim 未冲突 | `MULTI_INSTANCE` / `NOT_ASSIGNEE` |
| `REJECT` | 任务活动 + 受理人 + 非真 MI（折返豁免） | `MULTI_INSTANCE` / `NOT_ASSIGNEE` |
| `RETURN` | 同 `REJECT` | 同上 |
| `WITHDRAW` | 任务活动 + 非真 MI + 上一节点审批人 | `NOT_PREVIOUS_ASSIGNEE` |
| `TRANSFER` | 任务活动 + 受理人 | `NOT_ASSIGNEE` |
| `jump` | 任务活动 + 受理人 + 非真 MI + 目标在历史集合 | `MULTI_INSTANCE` / `NOT_ASSIGNEE` |
| `COUNTER_SIGN_AGREE` / `COUNTER_SIGN_REJECT` | 任务活动 + 受理人 + 多实例 | `NOT_MULTI_INSTANCE` / `NOT_ASSIGNEE` |
| `ADD_SIGN` / `DELETE_SIGN` | 任务活动 + 多实例 | `NOT_MULTI_INSTANCE` |
| `DELEGATE` | 任务活动 + 受理人 + 多实例 | `NOT_MULTI_INSTANCE` / `NOT_ASSIGNEE` |
| `RESOLVE_DELEGATE` | 任务活动 + 当前用户 == `task.owner` | `NOT_DELEGATOR`（建议补此原因值） |
| `INVALID` | 流程级，**不在值域** | — |
