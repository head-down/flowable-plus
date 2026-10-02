# 0042 无感等价对拍的实现形态 —— 参照装配 / 对拍字段 / 动态字段归一化

> **日期**：2026-09-26
> **来源**：探索期决议「AI 决策接入：实现方案定稿与开工闸门」的 **K2-001 无感等价对拍的实现形态：参照装配 / 对拍字段 / 动态字段归一化**；对应 kill switch 2 台账条目 **K2-001**（类型 `实现期必答项`，归属 I4）。
> **定位**：本文件是 **`E20`（`DecisionDisabledEquivalenceTest`）内部形态的唯一住所** —— 被测驱动面、参照装配构造、对拍字段清单、动态字段归一化规则、各面粒度、防空转形态、BPMN fixture 载体与覆盖面。
> **住所唯一**：`docs/impl/0042-verification-landings.md` 的 `E20` 行与 §9 改**指针**（该文件的落点身份与四落点义务**不变**）；ADR-0042 **正文零改动**（探索期决议 §9 已授权本方案展开 `E20` 内部形态）。
> **前置事实**（既有代码考古）：读侧入口三层与 VO 字段 · 事件与发布面 · `docs/impl/0042-module-and-build.md`（装配形态与构造缝）。
> **写作纪律**：按可公开标准书写 —— 不含真实下游项目名 / 公司名 / 人名。
> **依据订正（本方案）**：原方案引的「ADR-0042 第 12 节『守卫测试四落点』(c)」**不成立** —— ADR-0042 第 12 节是「人工覆盖与复核」；守卫测试四落实住 **ADR-0042 第 11 节第 6 条 (c)**（extension —— 全局关态跑既有 BPMN，与「extension 不参与」参照装配运行时对拍，`getApprovalHistory` 全量字段相等）。本文件的作用域为 (c) 条款的内部形态展开。

---

## §1 被测驱动面：三态与驱动入口

### 1.1 三态

| 态 | 构造 | 用途 |
|---|---|---|
| **关态**（`全局关`） | 机制组件**已构造并注册**，全局开关取**构造期定值 `false`** | 被测态之一 |
| **参照态**（`extension 不参与`） | 机制组件**根本不构造、不注册** | 对拍基准 |
| **开态**（正向对照） | 机制组件注册 + 开关 `true` + stub 决策目标 / 策略 / Provider / Transport | **防空转对照**（§7），不参与等值断言 |

### 1.2 驱动入口 = 框架受控入口（**不是**直接引擎 API）

那场「既有 BPMN」必须经**框架受控入口**驱动：手工构造 `ProcessLifecycleWorkflow` / `TaskExecutionWorkflow` / `CounterSignWorkflow`（写侧）+ `HistoryWorkflow`（读侧）+ `EventBus`（`DefaultEventPublisher` + 录制用 `ProcessEventListener`）。

**理由（防恒真）**：本机制的全部触发位点都住在框架 workflow 内部 —— 拉面的 `TaskCreatedEvent` 发射点在受控入口（`ProcessLifecycleWorkflow#startProcess`、`TaskExecutionWorkflow#executeRollback` 等），推面在位点服务入口。若直接调 `runtimeService` / `taskService`，`TaskCreatedEvent` 根本不发射、位点服务从不会被注入，机制**从未被触达** ⇒ 两态必然逐字段相等，断言恒真而无证据力。

**可行性依据**（一手核实，主仓 `70aa731`）：四个 workflow 类的构造依赖全是 core / 引擎类型，且只有 `ProcessLifecycleWorkflow` 带 Spring —— 仅 `startProcess` 一处 `@Transactional(rollbackFor = Exception.class)`（`ProcessLifecycleWorkflow.java:25,65-67`）；`TaskExecutionWorkflow` / `CounterSignWorkflow` / `HistoryWorkflow` **零 Spring 引用**。构造签名：

- `ProcessLifecycleWorkflow(UserContext, TaskService, HistoryService, RuntimeService, IdentityService, NodeFinder, List<AutoApprovalRule>, EventBus)`
- `TaskExecutionWorkflow(UserContext, TaskService, HistoryService, RuntimeService, NodeFinder, MultiInstanceDetector, ExecutionTreeHelper, EventBus, ProcessEndDetector, CountersignRollbackStrategy)`
- `CounterSignWorkflow(UserContext, TaskService, HistoryService, RuntimeService, MultiInstanceDetector, NodeFinder, List<CounterSignCallback>, EventBus, ProcessEndDetector, CountersignRoundResolver)`
- `HistoryWorkflow(HistoryService, TaskService, BpmnModelCache, MultiInstanceDetector, IdentityResolver, ActionInferenceStrategy, CountersignRoundResolver)`

**依赖的无 Spring 可得性（已核实）**：`BpmnModelCache` / `NodeFinder` / `ExecutionTreeHelper` / `CountersignRollbackStrategy` 为接口（core 均有实现或可匿名）；`MultiInstanceDetector` / `CountersignRoundResolver` 为 core 具体类，构造只吃引擎服务；`DefaultActionInferenceStrategy` 隐式无参、无 Spring 依赖；`UserContext` 为 `@FunctionalInterface` 接口、core 无实现（lambda 即可）；`CounterSignCallback` 三方法全 `default` 空实现。

---

## §2 参照装配的构造

### 2.1 形态 = 同一测试内两个引擎实例

`ExtensionTestEngine`（探索期决议 §3.1）以**参数化 helper** 暴露两个构造：唯一差异是一个「机制组件是否参与」的布尔。关态与参照态**各自一个引擎实例**，两实例跑**同一份 BPMN + 同一段操作序列**。

不采用的两条路径：

- **参照态 = 机制组件注册但依赖传 `null` / 空实现** —— 这不是「不参与」，是「参与后坏掉」，否掉；
- **单引擎两跑** —— 参与与否是**引擎级构造期事实**，同一引擎改不了。

### 2.2 三条形态纪律

1. **两侧除机制组件注册与否外装配完全同构**（同一个 helper，唯一差异一个布尔），防「参照态顺手少了别的东西」；
2. **两侧同一份 BPMN**（含节点声明）—— 参照侧引擎把 `fp:*` 当未知扩展属性忽略即可；BPMN 若不同，则比的不是同一件事；
3. **参照态的 BPMN 声明必须合法** —— 否则会先撞靶子⑧ 的部署期阻断、跑不起来。

**引擎侧校验面的对等性由引擎自身保证（已核实，无需额外补）**：`ProcessEngineConfigurationImpl#initProcessValidator()` 在字段为 `null` 时用 `ProcessValidatorFactory` 补**默认实例**（源码 `ProcessEngineConfigurationImpl.java:2578-2588`），故 standalone 下**参照态也自带 26 个内置 `Validator`**；机制侧按 `docs/impl/0042-module-and-build.md` §2.5 是「自建默认实例 + `addValidatorSet` 叠加」。⇒ 两侧内置校验面天然同构，差异只剩本机制那一个 `ValidatorSet`。

### 2.3 「全局关」的置位形态 = 构造期定值

机制组件在**构造期**接收一个布尔（由 starter 从 `flowable.plus.decision.enabled` 读出后传入）。E20 关态传 `false`、参照态根本不构造。

**依据**：`CONTEXT.md`「启用 / 禁用」已把全局开关钉为**部署期配置状态**；运行期那一档是「运行暂停」（走 `DecisionRuntimeControl`，落 `E20` 之外的 `E17`，其 `#controlStateIsOwnedByApplication()` 钉状态归属）。`docs/impl/0042-module-and-build.md` §3 的「运行期门控」只排除「装配条件」这一语义（Bean 始终注册），**不要求可翻转**。故**不**给该开关开出运行期可变的口子。

**边界登记**：全局开关的**判定点**由探索期决议定（闸门链 stage 1 与位点服务入口），但其**注入形态**（extension 侧没有开关位：属性类与 `DecisionGuardrails` 都住 starter，且探索期决议 §2.4 附明写 extension 不得引用装配面配置）由本方案补白，已在探索期决议上留边界评论。

### 2.4 构造缝的承载：只吃 extension 可见类型

**纪律**：extension 侧组件的构造依赖**只取 extension 可见类型** —— 值（`enabled`、超时、退避、池尺寸）· `List<DecisionTarget>` / `List<DecisionPolicy>` · 执行器 · 写入器 · 位点服务；**starter 负责**读属性、收集去重、重复 key 的启动期 fail-fast，再把**收口后的结果**注入。

**依据 = 依赖方向本身**：`DecisionPipeline` 类型住 extension（命名宪章 §4.5），装配由 starter 做；而两个注册表是 **starter 包内可见**（`docs/impl/0042-module-and-build.md` §2.2 行 8、ADR-0042 第 6 节「注册表 internal」）。⇒ extension 组件**不可能**引用注册表类型，这条缝只能承载 extension 可见的东西。本方案把它写下来是**形态补白**（探索期决议定了注册表的职责、未定这条缝的承载），非新设计、非球门修订。已在探索期决议上留边界评论。

**E20 的取值纪律**：构造时传**测试自定的惰性值**（如 1s 超时），**不复制** `docs/impl/0042-module-and-build.md` §2.4 冻结的默认数值 —— 那些数值住 starter 的 `DecisionGuardrails`（包级可见、extension 不得引用），在 extension 测试里复制一份就是**第二个真相源**。

### 2.5 关态装配集（关态与参照态的唯一差异）

关态注册、参照态不注册的集合 = `docs/impl/0042-module-and-build.md` §2.2 逐 Bean 清单里**住 extension 的机制组件**（主闸 `DecisionNodeDeclarationValidator` · 拉管线监听 · `DecisionPipeline` · 装配器 / 入站加工 / 证据写入器 / 节点声明读取器 · `SuggestionSubmissionService`），加上 §2.4 的构造缝输入。starter 侧内件（注册表 / 专属池 / `ProcessEngineConfigurationConfigurer` / 启动期复核）在 extension 测试里**没有对应物** —— 主闸以「自建默认 `ProcessValidator` + 叠加 `ValidatorSet`」等价替换，其余按 §2.4 的缝以测试值 / 测试替身补。

**推面组件的存在性**：`SuggestionSubmissionService` 在关态**必须构造**（拉管线产出的提交也走位点服务），但「关态 `submit` 为 no-op 非失败」这条断言**不放进 E20** —— `S4` 已有 `#globalOffSubmissionIsANoOpNotAFailure()`，重复即两处真相。

---

## §3 对拍字段清单

### 3.1 比较面 = 全字段

对拍对象 = `List<ApprovalRecordVO>` 的**全字段**（13 个 + 本机制新增的 `decisionEvidences`）× 每条 `CountersignSubRecord` 的**全字段**（13 个）+ 列表**长度与顺序**。

**`decisionEvidences` 必须进清单**：它是本机制对读侧的**唯一**新增可见面，漏掉它等于绕开主角。关态下其期望是**恒空集合**（软回退），**不是 `null`**（与同 VO `operationComments` 的 `null` 惯例是有意分歧，见 ADR-0042 第 11 节第 4 条账本第 ③ 行）。

### 3.2 比较手段 = 归一化副本 + `equals`；守卫改为「防未归类」

两个 VO 均为 Lombok `@Data`（`ApprovalRecordVO.java:18-21`、`CountersignSubRecord.java:18-21`），`equals` / `hashCode` 由 Lombok **全字段生成**（非手写）。故：

- **比较手段** = 把两侧输出各做一份**归一化副本**，再 `equals` 整个列表。比较面 = 全字段是**结构事实**，漏字段在结构上不可能；
- **机械守卫的语义**（**订正**，本方案更正早前「防漏字段」的说法）= **防「新增字段未归类」**：断言「**归一化表 ∪ 内容表 == VO 字段集**」。`equals` 已保证新增**内容**字段自动纳入比较，唯一真风险是新增**动态**字段被错当内容字段 —— 而它的失败是**响的、不是静的**（测试直接红），守卫的作用是让归类意图显式、失败信息直指归类缺失。

**分类表**（判据来自 q4 决议）：

| 类 | 字段 |
|---|---|
| **动态**（按 §4 归一化） | `taskId`（父记录与子记录）· `startTime` · `endTime` · `duration` |
| **内容**（严格等值） | `nodeId` · `nodeName` · `action` · `comment` · `operationComment` · `operationComments` · `actorId` · `actorName` · `roundIndex` |

**「序号」的归属（澄清）**：`roundIndex` 是引擎写入的**内容**（会签轮次），两侧应同值 ⇒ **不归一化**；**列表下标**天然逐位置对齐 ⇒ 不归一化。把 `roundIndex` 当测试噪声会丢掉「会签轮次结构是否等价」的观测。

**`actorName` 严格等值的前提**：`IdentityResolver` 的默认实现在 **starter**（core 无默认实现），故 E20 自备**确定性 stub**；`actorName` 的等值由该 stub 承担。

---

## §4 动态字段归一化规则

**主策略（β）= 出现序规范化**；**下限（α）= 掩码**。四条细则：

1. **`null` / 非 `null` 形态必须一致** —— 掩码不得抹掉空值语义（如「当前节点 `endTime` 为 `null`」必须两侧同为 `null`）；
2. **列表长度一致、逐位置比较** —— 记录顺序必须等值：顺序正是机制最可能影响的东西（机制若多/少写一行，顺序与长度即变），掩码掉时间会连带丢掉它；
3. **`taskId` 按出现序重编号，父记录与子记录共用同一张编号表** —— 否则会签子记录的 `taskId` 与父记录对不上号，把等价的两次运行错判为不等；
4. **`startTime` / `endTime` 归一化为「出现序向量」**（保留序关系，不比绝对时刻）；**`duration` 归一化为「与参照侧同为 `null` / 同为非 `null`」**。

**依据**：两态是**两个引擎实例**，`taskId`（`DbIdGenerator` 生成）与时间戳必然不同；单纯掩码（α）会丢掉顺序这一**业务可观测**事实，而 `getApprovalHistory` 的呈现顺序正是靠 `startTime` 排序 + 稳定排序的 tie-break 得来的。

---

## §5 各面的对拍粒度（靶子⑦ 的对应关系）

**口径**：靶子⑦ 的落点**本来就是**「starter 集成测 + core 守卫测试」（ADR-0042 第 13 节），`E20` 只是第 11 节第 6 条 (c) 给**面②** 的 extension 侧落点。下表的展开是 `E20` 在 extension 现有条件下**顺带覆盖**的面。

| 面 | 定义（ADR-0042 第 11 节第 1 条） | E20 对拍对象 | 判据 |
|---|---|---|---|
| **①** | 运行时状态与结束态历史 | 活动实例集 / 历史实例的状态 · `deleteReason` · 结束态 / 历史变量集 / 历史活动与任务的**节点 id 序列** | 归一化后逐项等值，序列逐位置 |
| **②** | `getApprovalHistory` 返回值逐字段 | `List<ApprovalRecordVO>`（§3.1） | 归一化副本 `equals` |
| **③** | 所有写操作返回值与异常 | 三个写侧 workflow 的**受控入口**每次调用的「返回 VO 归一化形态 + 抛出的异常类型」（消息按动态归一化） | 逐次调用逐位置等值 |
| **④** | 事务语义 | **不在 E20 视域**（见下） | — |
| **⑤** | 既有 `ProcessEventListener` 回调序列 | 回调**方法名序列** + 每个事件的**内容字段**（`getEventTime()` / `taskId` 归动态） | 逐位置等值 |

**统一规则**：一面一条具名断言（§9），共用 §3/§4 的「动态 / 内容」二分与归一化规则 —— 不为各面另立第二套清单（探索期决议 §6.2 的 SSOT 纪律）。

**面④ 的归属**：框架唯一的 `@Transactional` 住在 core `ProcessLifecycleWorkflow#startProcess`，standalone 引擎**无 Spring 事务代理 ⇒ 注解惰性**，「事务语义」在 extension 侧**没有可观测面**。故面④ **不在 E20 视域**，落回靶子⑦ 的 starter 侧，边界已推到 **P2 受控压力测试探针**——按纪律只登记边界与指出新 frontier，**不代探索期决议拆断言**。**已落定（2026-09-26）**：面④ 的具名断言形态由探索期决议关闭时裁定，落 `S5`（`DecisionTwoPathIsolationIntegrationTest`）—— 可观测 = **老路径 fail-fast 的「整体回滚」**，**唯一住所 = `docs/impl/0042-two-path-isolation-probe.md` §5.4**（本行只留指针）。
（用「关态无机制活动 ⇒ 事务边界同」这种同义反复硬覆盖面④，**不予采用**：它不是实测。把 `E20` 整体搬去 starter 亦不采用：与 (c) 条款「住 extension」冲突，属落点修订。）

**不属本方案的第三层**：**节点级关闭**归 `S4` 的三层关闭矩阵（`#globalOffGatesPullAndEgressOnly()` 一族）；本方案只承**「全局关」与「extension 不参与」两态**。

---

## §6 BPMN fixture：载体与覆盖面

### 6.1 载体 = Java 常量 / 代码内构造

fixture BPMN 以 **Java 常量形态**（`DecisionFixtures`，探索期决议 §3.1）在代码内构造 `BpmnModel`，经 `createDeployment().addBpmnModel(...)` 部署。**不得破例引用主仓 `src/test/resources` 下的既有 BPMN 文件**，**不建** extension 的 `src/test/resources`。

**依据**：探索期决议 §6.2 结构保证第 4 条「**`src/test/resources` 不建** ⇒ recorded fixture 无栖身处」。⇒ 所谓「既有 BPMN」在 extension 测试里只能是**自建的等价 BPMN**（一手事实：`flowable-plus-core/src/test/resources` 下**只有 `logback-test.xml`、无任何 BPMN**；BPMN 文件全部住 starter 的 `src/test/resources/bpmn/`）。**不得把本条读作「复用主仓既有 fixture」。**

### 6.2 覆盖面

固定操作序列覆盖三条路径：

1. **会签节点** —— 触及 `countersignRecords` / `roundIndex` / 会签父记录的空值形态；
2. **回退路径（驳回 / 撤回 / 跳转）** —— 触及 `action` 三级兜底推断，以及「驳回 / 撤回 / 跳转三链共用一处 `executeRollback`」；
3. **作废 / 删除实例**（`ProcessLifecycleWorkflow#invalidateProcess`）—— 触及 `ApprovalAction.INVALID` 与 `ProcessInvalidatedEvent`，并且是面①「历史实例状态 · `deleteReason`」这一格**唯一**会出现非常规值的路径（不纳入则该格恒为常规值，等于没测）。

**不纳入**：加签 / 减签 / 委派（`ADD_SIGN` / `DELETE_SIGN` / `TRANSFER` / `DELEGATE`）—— 它们触及的操作注释组分支在两侧代码完全相同，机制只在「有无证据行」上留下足迹，边际证据弱，却要额外拖进 `List<CounterSignCallback>` 与 `CountersignRollbackStrategy` 的构造面；`ApprovalAction` 13 值穷举属零散契约测，E20 的靶心是**同一序列下两态等价**。

### 6.3 硬约束：fixture 至少含一个**已声明节点**

至少一个 `UserTask` 写 `fp:decisionEnabled="true"` 且四属性齐全（`decisionEnabled` / `decisionDataSources` / `decisionTarget` / `decisionPolicy`，`decisionPolicy` 必填）。**理由**：节点若全部未声明，拉面在两侧都不会触发 —— 又回到 §1.2 的恒真陷阱：机制没有机会动，「无感」就没有被证明。

---

## §7 防空转：两条，缺一不可

| # | 形态 | 证明什么 |
|---|---|---|
| **甲（正向对照）** | **第三态 = 开态**：机制组件注册 + 开关 `true` + stub 决策目标 / 策略 / Provider / Transport（复用探索期决议 §3.1 的 `StubDecisionTransport` + `DecisionFixtures`），断言机制活动**非零**（`decisionEvidences` 非空、有出站调用记录） | 「机制**能**产生活动」⇒ 关态 ≡ 参照态 不是恒真式 |
| **乙（装配集恒等）** | 断言**关态装配集 == 参照态装配集 ∪ 机制组件集**（集合恒等惯用法，同 `S2` 的 `#automaticallyConfiguredMechanismBeansEqualDeclaredSet()`） | 「两侧的唯一差异**确实**是机制组件」⇒ 防「唯一差异被误实现成零差异」 |

**甲、乙的关系**：乙证「组件确实注册了」、甲证「注册了且能产出」；任一单独都不足以排除恒真，故两条并立。

**附带（近零成本）**：录制 core 的 `TaskCreatedEvent` 计数，证「任务就绪确实发生过」。它**单独不够**（事件在两侧都照发，机制监听器收没收到它证不了），只作甲的辅助。

**本方案落库时的一致性复核补充（如实披露）**：甲来自本方案决议；**乙是从已定决议的合取推出的补充** —— §2.2 纪律 1「除机制组件注册与否外装配完全同构」∧ §1.2/§7 的「防恒真」要求，合起来即乙。它**不是新球门**：不触及 I1–I4、不改任何不变量适用域、不扩台账类型闭集，只是把已定两条的执行面断言写出来。若日后认为乙多余，删它需同时说明「唯一差异」由何保证。

---

## §8 断言名清单与落点回填

落点类 = `E20` `DecisionDisabledEquivalenceTest`（extension，真引擎基座 `ExtensionTestEngine`）。断言名形态：

| 断言名形态 | 承 |
|---|---|
| `#globalOffEqualsMechanismAbsentFieldByField()` | 面②（探索期决议已给的主名，保留） |
| `#globalOffEqualsMechanismAbsentOnRuntimeAndHistoryState()` | 面① |
| `#globalOffEqualsMechanismAbsentOnWriteOperations()` | 面③ |
| `#globalOffEqualsMechanismAbsentOnListenerCallbackSequence()` | 面⑤ |
| `#mechanismActiveProducesNonZeroActivity()` | §7 甲（开态正向对照） |
| `#globalOffAssemblySetEqualsReferencePlusMechanismComponents()` | §7 乙（装配集恒等） |
| `#equivalenceClassificationCoversEveryVoField()` | §3.2（「防未归类」守卫） |

**回填**：`docs/impl/0042-verification-landings.md` 的 `E20` 行与 §9 改**指针**指向本文件（唯一住所不变、不摘抄）。断言名受命名宪章 §2.B-T1 约束，实现期由 `E1` 的 `#methodNamesFreeOfDomainWords()` 机械承担；若出现 T1 禁词候选，按命名宪章 §4.6 偏离登记。

---

## §9 已知限制与如实披露

1. **主仓首个 standalone 真引擎基座** —— 一手核实：core / starter 现有测试一律 Mockito mock service + 手工 `new workflow`，**未观察到** `createStandaloneInMemProcessEngineConfiguration` 或任何真实引擎构造。`ExtensionTestEngine` 是**首例**，其自身可靠性无既有先例可援。
2. **standalone 下 `@Transactional` 惰性** —— `startProcess` 的事务注解在无 Spring 代理时不生效 ⇒ 面③ 测到的是**非事务语义**下的行为（例如老路径 `AutoApprovalRule` fail-fast 的「整体回滚」不可观测）。两态**同样**缺该代理，故等值结论不受影响，但该面的**保真度**低于生产。这与面④ 不在视域是**同一根因**，随面④ 一并说明。
3. **core 残留四行两侧都在** —— 证据 VO 类型 / `decisionEvidences` 字段 / `CommentType` 新值 / 读侧排除均住 core，extension 侧构造不出「去掉」；两态差异**只在机制组件参与与否**，这与 ADR-0042 第 11 节第 4 条的残留账本一致。
4. **引擎 API 引用**：`createStandaloneInMemProcessEngineConfiguration()` 存在（`ProcessEngineConfiguration.java:198`，返回 `ProcessEngineConfiguration`）；部署方法 `addBpmnModel(...)` / `addString(...)` 属 **`DeploymentBuilder`**，**不在** `RepositoryService` 上。
5. **`IdentityResolver` 的默认实现在 starter** —— core 无默认实现；E20 自备确定性 stub，`actorName` 的等值由它承担。

---

## §10 复现免责声明

本文件是**内部形态与判据的记录**，不是可执行的测试套件。文中的三态构造、字段清单与归一化规则为**骨架**：断言体、常量取值与 fixture 的 BPMN 结构属实现期产物。任何据此复现的尝试，须自行搭建被测工程 —— 本文档**不随附可运行工程**，也不对未实现阶段的复现结果作承诺。**本方案不跑实测**：实测（含八靶子的具名击穿实验）是 ADR-0042 第 13 节的**撤销条件**，属执行段。
