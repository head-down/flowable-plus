# 0042 P2 受控压力测试探针的实现形态 —— 两边同挂 BPMN / 四断言 / starter 集成测

> **日期**：2026-09-26
> **来源**：探索期决议「AI 决策接入：实现方案定稿与开工闸门」·「P2 受控压力测试探针的实现形态：两边同挂 BPMN / 四断言 / starter 集成测」；对应 **ADR-0042 第 13 节**「P2 受控压力测试探针」与 kill switch 2 执行面 `docs/research/kill-switch-2-acceptance-gate.md` §7。
> **定位**：本文件是 **starter 落点 `S5`（`DecisionTwoPathIsolationIntegrationTest`）内部形态的唯一住所** —— 三态三上下文的构造、参照态的边界、BPMN fixture、四断言与面④ 的具名断言形态与**冻结判定式**、对拍字段与归一化、防空转、已知限制。
> **住所唯一**：`docs/impl/0042-verification-landings.md` 的 `S5` 行与 §5.3 / §9 只留**指针**（该文件的落点身份与四落点义务不变）。**ADR-0042 正文零改动**；kill switch 2 台账**不新增行**（P2 不属类型闭集四项）；`CONTEXT.md` **零新增词条**（四断言与三态不引入新领域词）。
> **前置事实**（既有代码考古）：`AutoApprovalRule` 暴露面与调用位置 · `getApprovalHistory` 读侧 · `AbstractIntegrationTest` 与 BPMN 夹具惯例 · `TaskCreatedEvent` 发射点 · `docs/impl/0042-module-and-build.md`（装配形态 / 五个替换点 / 三层关闭位点）· `docs/impl/0042-equivalence-harness.md`（`E20` 的参照装配与归一化）。
> **写作纪律**：按可公开标准书写 —— 不含真实下游项目名 / 公司名 / 人名。

---

## §1 探针证什么

一句话：**两条路径共挂同一条 BPMN，跑完之后互不串味。**

ADR-0042 第 13 节「适用域（域界定）」把老路径（`AutoApprovalRule` 自动提交）**排除在 I1 适用域之外**，并要求它与本机制**可证伪地隔离**。本探针是该隔离的**受控压力测试**，四条断言（ADR 原文）：

1. 老路径行为与机制不存在时**逐字段等价**（五面可观测结局）；
2. 本机制侧**无任何自动提交痕迹**；
3. 从 `suggestedAction` 到流程推进**无框架通路**（结构性断言：该通路不存在）；
4. **默认关下探针不激活**。

**与 `E20` 的分工（不重复、不替代）**：`E20`（`DecisionDisabledEquivalenceTest`）在 extension 侧用 standalone 引擎证「关态 ≡ 参照态」的面 ① ② ③ ⑤，**无老路径、无 Spring 事务代理**（`…equivalence-harness.md` §5 / §9.2）。本探针的独有面 = **老路径共挂** + **面④ 事务语义**（探索期决议已把面④ 的载体推到此处）。

---

## §2 三个态与三个上下文

### 2.1 为什么必须三上下文

`@SpringBootTest` 的配置是**类级**的（`@SpringBootTest(properties = …)` 进入 `MergedContextConfiguration`），**不能**在同一 Spring 上下文里切三态。故三态 = **三个相互独立的上下文**。带 Spring 事务代理的真引擎上下文只有 `@SpringBootTest` 与 `SpringApplication` 两条路能建；**不做**「单上下文切属性」的变体。

### 2.2 构造形态（本方案定 shape）

| 态 | 构造 | 载哪条断言 |
|---|---|---|
| **参照** | **程序化 boot**（见下）：`spring.autoconfigure.exclude` 排除两个决策自动配置类 ⇒ 机制组件**不构造、不注册** | 断言 1 的基准 |
| **关态** | `@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)` + `@Import(SharedTestConfiguration.class)`；`flowable.plus.decision.enabled` **缺省即 `false`** | 断言 1 对拍 · **断言 4** · 面④ |
| **开态** | **程序化 boot**：`flowable.plus.decision.enabled=true` + 应用侧 stub（§2.3） | **断言 2** · **断言 3** · 断言 1 对拍 · 面④ |

**程序化 boot 的形态**（**首例，如实披露**：主仓测试面**无先例**）：

```
new SpringApplicationBuilder(BpmnQueryIntegrationTestApplication.class)
      .sources(<应用侧 stub 配置>)        // 三态同款，见 §2.3
      .properties(<态专属属性>)            // 见上表
      .run()
```

- **生命周期**：`@BeforeAll` 起、`@AfterAll` 关（`ConfigurableApplicationContext#close`）；**不入** Spring TestContext 的上下文缓存。
- **数据源属性从被注入的 `Environment` 复制**（`spring.datasource.*`）—— `AbstractIntegrationTest#configureDataSource` 是 `@DynamicPropertySource`，只服务 TestContext 管理的上下文，程序化上下文必须自行承接（**三库矩阵下同一份复制逻辑**）。
- **三态共用一个库无害**：所有断言与查询按 `processInstanceId` / `businessKey` 作用域；`ApprovalRecordVO` 不含 `processDefinitionId`，同名流程定义的多版本部署不进对拍面。**不得**据此称「三态数据隔离」—— 不隔离，也不需要隔离。
- 三个上下文仍**相互独立**（本探针的硬约束）；「唯一差异」由构造代码**直接表达**，与 `…equivalence-harness.md` §2.2 纪律 1 同旨。
- 对拍**在同一测试方法内**完成 ⇒ **零类序依赖**：不引 `ClassOrderer`、不动 `junit-platform.properties`、单类运行（`-Dtest=DecisionTwoPathIsolationIntegrationTest`）不受影响。

### 2.3 三态**同款**的应用侧 stub（唯一差异纪律）

下列全部属**应用侧**（替换点与接线单元），**三态逐一同款注册**：

| stub | 作用 |
|---|---|
| `ProbeAutoApprovalRule` | 老路径规则：`evaluate` 返回固定意见串；带一个**测试侧开关**，`armFailure()` 后抛异常（承 §5.4 的 fail-fast 场景） |
| `DecisionTarget`（key = fixture 声明的 token） | 决策目标接线单元 |
| `DecisionPolicy`（key = fixture 声明的 token） | 出域策略 |
| `DecisionProvider`（stub） | 出站缝的**整体替换点**，返回固定建议、**不发起网络**（`E19` 的 stub 契约） |
| `DecisionRuntimeControl` / `DecisionCredentialResolver` | 取 extension 默认实现，不替换 |
| `UserContext` | 复用 `SharedTestConfiguration` 的同款实现 |

⇒ 三态的**唯一差异** = 两个决策自动配置类是否参与 + `enabled` 的取值。

**为什么 stub 不能只挂开态**：主闸（部署期校验）**不随全局开关消失**（ADR-0042 第 11 节第 3 条、`…module-and-build.md` §2.5）⇒ 关态下 fixture 的 `fp:decisionPolicy` / `fp:decisionTarget` 必须能解析到注册表里的 key，否则**部署当场阻断**、探针跑不起来。把 stub 归入应用侧、三态同款，正是这条约束的形态解。

### 2.4 落点身份

- 落点 = **S5**，**一个类**：`DecisionTwoPathIsolationIntegrationTest`（starter，`io.github.flowable.plus.starter`）。
- **不采用**「三顶层类」形态：顶层类的执行顺序**只能**由模块级 `junit-platform.properties` 的 `junit.jupiter.testclass.order.default` + `@Order` 强制（`@TestClassOrder` 只能排 `@Nested` 子类；`ClassOrderer` 标 `@API(EXPERIMENTAL, since = "5.8")`，一手核实 JUnit 5.8.2 源码），且单类运行会因基线缺席而红。
- **不采用**「单顶层类 + 三 `@Nested`」形态：`@Nested` 自有 `@SpringBootTest(properties=…)` 在默认 `INHERIT` 下产出独立上下文一节，**只有源码面结论、无运行时先例**（探索工作区零 `@Nested` 用例）⇒ 前置条件未满足。

---

## §3 参照态的边界登记（防误读）

**本探针造的是「装配面」的机制不参与，不是「依赖面」的 extension 不存在。**

- **事实**：extension 是 starter 的**主依赖 optional**（`…module-and-build.md` §1.1）；Maven 的 `optional` 只影响**传递** ⇒ **extension 恒在 starter 的测试 classpath 上** ⇒「不引 extension」在本模块**结构上不可构造**。
- 故「机制不存在」在本探针内**落地为「两个决策自动配置类不参与」**，与 `E20` 参照态的定义（组件**根本不构造、不注册**）**同实测面**。
- 「依赖面移除」由 ADR-0042 第 11 节第 6 条 (d) 的落点承担（`S1` `DecisionAssemblyAbsenceTest`，`ApplicationContextRunner` + `FilteredClassLoader`）。**两条合起来**才是 §11 第 1 条的「机制不存在」。
- **不得**把 §2.2 的参照态读作「已验 extension 不存在」。
- 词面留痕：`spring.autoconfigure.exclude` 须以**两个决策自动配置类的全限定名**书写（一手核实 `spring-boot-autoconfigure-2.7.18` 的 `AutoConfigurationImportSelector#getExcludeAutoConfigurationsProperty`：读 `spring.autoconfigure.exclude`，且被排除者**必须是自动配置候选**，否则 `IllegalStateException`）。

---

## §4 BPMN fixture：两边同挂

### 4.1 形态 = 双节点，两条路径各有独立落点

```
testDecisionTwoPathIsolation
  └─ UserTask "firstApproval"          ← 老路径：AutoApprovalRule 在 startProcess 事务内自动提交
       └─ UserTask "declaredApproval"  ← 本机制：fp 四属性声明齐全
```

**为何不用「同节点双挂」**：老路径在 `startProcess` 事务内**同步完成**首任务，机制的拉面随后必然撞「**锚点失效**」写入槽位（任务已消失）⇒ 机制侧**产不出证据**，断言 2 的正向面失去被验对象、整条退化为恒真式。双节点让两条路径各有**可独立观测**的落点，仍满足「一个 BPMN 两边同挂」，且直接服务「证两条路径不串味」。

**老路径只碰 `firstApproval` 的结构保证**：`autoCompleteFirstTasks` **只在 `startProcess` 内**被调用（`ProcessLifecycleWorkflow`），故规则不会作用于后置节点。

### 4.2 载体 = classpath 资源（starter 惯例，与 extension 相反）

新建 `flowable-plus-spring-boot-starter/src/test/resources/bpmn/test-decision-two-path-isolation.bpmn20.xml`，经 `repositoryService.createDeployment().addClasspathResource(...)` 部署（与既有 9 个集成测同惯例）。

**不**走代码内构造 —— 那是 extension 的纪律（`…equivalence-harness.md` §6.1：extension **不建** `src/test/resources`，使 recorded 无栖身处）。starter 已有 `src/test/resources`（`application.yml` / `logback-test.xml` / `bpmn/`），classpath 资源即其既有形态。

### 4.3 声明必须合法（三态都会部署它）

四属性齐全、`decisionPolicy` 必填、token 取 `DecisionContextSource` 的**枚举常量名原文**、`decisionTarget` / `decisionPolicy` 指向 §2.3 已注册的 key。理由：关态 / 开态**有**主闸，非法声明会在部署期阻断 ⇒ 探针根本跑不起来（那是靶子⑧ 的行为，不在本探针的断言集内）。

---

## §5 断言 1 与面④

### 5.1 对拍面 = 五面（ADR-0042 第 11 节第 1 条）

| 面 | 对拍对象 | 断言名形态 |
|---|---|---|
| ① | 活动 / 历史实例状态 · `deleteReason` · 历史变量集 · 历史活动与任务的**节点 id 序列** | `#legacyPathEqualsReferenceOnRuntimeAndHistoryState()` |
| ② | `List<ApprovalRecordVO>` 全字段 + 每条 `CountersignSubRecord` 全字段 + 列表长度与顺序 | `#legacyPathEqualsReferenceOnApprovalHistory()` |
| ③ | 三个写侧 workflow **受控入口**每次调用的「返回 VO 归一化形态 + 抛出的异常类型」 | `#legacyPathEqualsReferenceOnWriteOperations()` |
| ④ | **事务语义** —— 见 §5.4 | `#legacyPathFailFastRollsBackInEveryState()` |
| ⑤ | 既有 `ProcessEventListener` 回调**方法名序列** + 每个事件的内容字段 | `#legacyPathEqualsReferenceOnListenerCallbackSequence()` |

**两处态对**：断言 1 在「**关态 vs 参照**」与「**开态 vs 参照**」各做一次。

**开态的面②有一处必须写明的取值**：开态下机制**确实会**往 `decisionEvidences` 写一行（那是它的正常工作，不是串味）。故：

- 面② 的**全字段** `equals` 在「**关态 vs 参照**」做（两侧 `decisionEvidences` 皆为空集合）；
- 「**开态 vs 参照**」的对拍面 = **人工意见组侧的全部字段**（`decisionEvidences` **移出对拍面**）；该字段另由 §9 的正向断言承担（开态非空、参照 / 关态恒空集合）。

**不得**把「开态 `decisionEvidences` 非空」读作对拍失败，也不得为迎合对拍而把该字段掩掉。

### 5.2 驱动入口 = 框架受控入口（不是直接调引擎 API）

老路径只由 `FlowablePlus#startProcess`（⇒ `ProcessLifecycleWorkflow#startProcess`）驱动，其余操作走既有门面。理由与 `E20` §1.2 同：`TaskCreatedEvent` 的发射点在**受控入口**（`startProcess` 落在 `autoCompleteFirstTasks` **之后**），直接调 `runtimeService` / `taskService` 会让机制**从未被触达**，两态必然逐字段相等、断言恒真。

### 5.3 对拍字段与归一化 = **有意重复的第二处**（措辞写死）

> 本探针的「动态 / 内容」字段二分表与出现序归一化细则，在 starter 测试面**另有一份实现**，与 `docs/impl/0042-equivalence-harness.md` §3 / §4 的那一份**语义相同、代码不复用**。
> 原因 = **模块边界**：extension 的测试类型不进 starter 的测试 classpath（test scope 不传递），共享在结构上不可行。
>
> **这不构成两处真相**：唯一可能漂移的是那张字段名清单；两侧各有一条「归类守卫」断言（**同名** `#equivalenceClassificationCoversEveryVoField()`），**只查本地 VO 的字段集**是否被本地清单覆盖（extension 侧见 `…equivalence-harness.md` §3.2 / §8，starter 侧见本文件 §10）。漂移的失败是**响的**（守卫直接红），不是静的。
>
> 与 `…equivalence-harness.md` §2.4「不得复制 `DecisionGuardrails` 默认数值」的区别：那里禁复制是因为**真相源唯一**（数值只有一个权威住所）；这里的重复由**模块边界**逼出，且未引入第二个权威 —— 两份清单各自服务**本模块内**的守卫断言。

**二分表与细则照抄 `…equivalence-harness.md` §3.2 / §4 的语义**（动态 = `taskId` / `startTime` / `endTime` / `duration`；内容 = 其余全字段；主策略 = 出现序规范化、掩码为下限；四条细则原样）。**`decisionEvidences` 恒进清单**。

### 5.4 面④ 的具名形态（本探针唯一能交付这一格的地方）

**可观测 = 老路径 fail-fast 的「整体回滚」。** `E20` 测不到这一格的根因是 standalone 无 Spring 事务代理（`…equivalence-harness.md` §9.2），而本探针有真代理。

**注入**：`ProbeAutoApprovalRule#armFailure()` ⇒ `evaluate` 抛异常。

**判定式（冻结）**：

```
设 K  = fixture 流程 key；bk = 本次运行新造的 businessKey
执行：flowablePlus.startProcess(K, bk, vars)
断言（三态各一次、结论相同）：
  ① 抛出：调用抛异常，且异常类型三态一致
  ② 零残留（整体回滚）：
       runtimeService.createProcessInstanceQuery()
           .processInstanceBusinessKey(bk).count() == 0
     且 historyService 侧同 businessKey 无历史实例
  ③ 无自动提交残留：该 businessKey 下不存在 AutoApprovalRule 产生的 AUTO_COMPLETE 评论

禁止：探针测试类自身标 @Transactional
  —— 会与 startProcess 的事务语义混淆，使这一面白测。
```

---

## §6 断言 2 —— 判定式（冻结）

**核查面**（来自「采纳判定的人的一侧」的边界）：引擎**公开 API** `TaskService#getProcessInstanceComments` → `Comment#getType()`；**归因不经读侧契约面**。已一手核实：`flowable-engine-6.8.0-sources.jar` 中 `org.flowable.engine.TaskService` 具 `getProcessInstanceComments(String)` / `(String, String)`，`org.flowable.engine.task.Comment` 具 `getId` / `getUserId` / `getTime` / `getTaskId` / `getProcessInstanceId` / `getType` / `getFullMessage`。

```
符号：
  P           = 本次运行的流程实例 id
  T2          = 声明节点（带 fp 四属性）在本次运行中产生的**任务 id**
  C(P, type)  = taskService.getProcessInstanceComments(P) 中 getType() 等于 type 的评论集合
                （type 为枚举名原文串：本机制侧 "DECISION_EVIDENCE"，老路径侧 "AUTO_COMPLETE"）
  E(T2, type) = { c ∈ C(P, type) : c.getTaskId() 等于 T2 }

断言 2 成立 ⇔ 下列三条同时成立：
  ① 正向（防空转）：E(T2, "DECISION_EVIDENCE") 非空
  ② 负向          ：E(T2, "AUTO_COMPLETE") 为空
  ③ 锚点仍在      ：taskService.createTaskQuery().taskId(T2).active().count() == 1

前置同步：机制侧为**异步**（专属池），① 须以**有界等待**收敛、超时即失败并输出观测面信号；
         不得用固定 sleep 代替有界等待。（等待常量取**测试自定值**，不复制 DecisionGuardrails 默认数值。）

禁止写法（防回归）：
  · 不得写成 C(P, "AUTO_COMPLETE") 为空 —— 老路径本就会写一条，该写法是**错误判据**（会把断言 1 的对象错算进断言 2）；
  · 不得以读侧契约面（getApprovalHistory / ApprovalRecordVO）作判据 —— 归因不经读侧；
  · 不得以 FULL_MSG_ 前缀文本作判据 —— 判据面是 TYPE_ 列（getType()），文本标记只作证据行的识别补充。
```

**断言名**：`#mechanismSideLeavesNoAutoSubmitTrace()`。

---

## §7 断言 3 —— 判定式（冻结）

```
S        = ComparableAction.MEMBERS（表态比较面的全部取值，恰四值；与 E11 的「同源等价」断言一致）
T2       = 声明节点上的活跃任务 id
Snapshot = ( 活跃任务集 { taskId → nodeId }，
             审批轨迹中**人工意见组**（业务意见 + 操作注释组）的评论集合
             —— 逐条取 (getTaskId(), getType(), getFullMessage()) )

逐 s ∈ S 执行：
  经机制**唯一公开写入口**提交一条建议：
      suggestedAction = s ＋ **本次新造的幂等身份**（四轮各不同键，不同键即不同决策）
  提交后：
    ① 留痕成立    ：E(T2, "DECISION_EVIDENCE") 的**基数**恰比提交前增 1（有界等待收敛）
    ② 流程状态不变：Snapshot 与提交前逐项相等（活跃任务集、人工意见组逐条相等）
    ③ 无自动提交  ：E(T2, "AUTO_COMPLETE") 为空

判据面 = 机制公开写入口（submit(...) 返 void、入参不承载推进语义）；遍历取值 = 该面能达到的最强形式。

不在本断言内做的：「extension 不引用 AutoApprovalRule / starter 不装配桥接 Bean」的**静态断言**
  —— 反射覆盖不到方法体引用，源码 / 字节码扫描不在既有工具面 ⇒ 降为**文档纪律**（按
  `docs/impl/0042-verification-landings.md` §6.2 三分法登记），靶子 → 落点的归属裁定**留待探索期决议**。
```

**断言名**：`#submissionNeverAdvancesProcessState()`。

---

## §8 断言 4 —— 判定式（冻结）+ 与 S4 的切分

```
关态下，同一条两边同挂 BPMN 跑完整序列后：
  ① 零证据：C(P, "DECISION_EVIDENCE") 为空
  ② 零出站：stub DecisionProvider 的调用计数 == 0
  ③ 老路径照常：E(firstApproval, "AUTO_COMPLETE") 恰 1 条，
               且流程已推进 —— 活跃任务集 == { declaredApproval }
```

**断言名**：`#probeStaysInactiveUnderDefaultOff()`。

**与 `S4`（`DecisionClosureMatrixIntegrationTest`）的切分**（显式登记，避免第二处真相）：`S4` 证「**装配面与门控位点**」（合成上下文、无真实 BPMN、无 stub Provider）；本断言证「**场景面无活动**」。「**零出站调用**」这一格在 `S4` **结构上不可达**（`S4` 没有 `DecisionProvider` 替身）—— 而它正是 I4 的承重格。两处不构成第二处真相。

---

## §9 防空转：两条，缺一不可

| # | 形态 | 证明什么 | 断言名 |
|---|---|---|---|
| **甲** | **开态正向**：开态下 `E(T2, "DECISION_EVIDENCE")` 非空 ∧ stub `DecisionProvider` 调用计数 ≥ 1 | 「机制**能**产生活动」 | `#mechanismProducesEvidenceUnderEnabledState()` |
| **乙** | **装配差异恒等**：参照态 `BeanDefinition` 名集 ⊆ 关态；且「关态 \ 参照」== **机制组件集**；且关态与开态的 `BeanDefinition` 名集**恒等**（唯一差异只在属性） | 「唯一差异**确实**是机制组件」 | `#stateContextsDifferExactlyByMechanismComponents()` |

**甲、乙的关系**：乙证「组件确实注册了」、甲证「注册了且能产出」；任一单独都不足以排除恒真（同 `E20` §7）。

---

## §10 断言名清单与落点回填

落点类 = `S5` `DecisionTwoPathIsolationIntegrationTest`（starter，`extends AbstractIntegrationTest` + `@SpringBootTest` 载关态 + 程序化 boot 另两态）。

| 断言名 | 承 | 态 |
|---|---|---|
| `#legacyPathEqualsReferenceOnRuntimeAndHistoryState()` | 断言 1 · 面① | 关态 vs 参照 · 开态 vs 参照 |
| `#legacyPathEqualsReferenceOnApprovalHistory()` | 断言 1 · 面② | 同上（开态移出 `decisionEvidences`） |
| `#legacyPathEqualsReferenceOnWriteOperations()` | 断言 1 · 面③ | 同上 |
| `#legacyPathFailFastRollsBackInEveryState()` | 断言 1 · **面④** | 三态各一次 |
| `#legacyPathEqualsReferenceOnListenerCallbackSequence()` | 断言 1 · 面⑤ | 关态 vs 参照 · 开态 vs 参照 |
| `#mechanismSideLeavesNoAutoSubmitTrace()` | **断言 2** | 开态 |
| `#submissionNeverAdvancesProcessState()` | **断言 3** | 开态 |
| `#probeStaysInactiveUnderDefaultOff()` | **断言 4** | 关态 |
| `#mechanismProducesEvidenceUnderEnabledState()` | §9 甲 | 开态 |
| `#stateContextsDifferExactlyByMechanismComponents()` | §9 乙 | 三态 |
| `#equivalenceClassificationCoversEveryVoField()` | §5.3 归类守卫（与 `E20` **同名**的第二处实例） | — |

**回填**：`docs/impl/0042-verification-landings.md` §4 新增 `S5` 行、§5.3 新增推入项、§9 的 **P2** 行改**指针**；本文件为唯一住所（**不摘抄**）。断言名受命名宪章 §2.B-T1 约束，实现期由 `E1` 的 `#methodNamesFreeOfDomainWords()` 机械承担。

---

## §11 已知限制与如实披露

1. **程序化 boot 是模块内首例** —— 主仓 / 探索工作区测试面均**无先例**；其自身可靠性无既有先例可援。三库矩阵下每类多起两个 Spring 上下文，成本如实登记。
2. **数据源属性的承接** —— `AbstractIntegrationTest#configureDataSource` 只服务 TestContext 管理的上下文；程序化上下文须复制 `spring.datasource.*`（读被注入的 `Environment`）。三库矩阵下同一逻辑，但该逻辑本身**无先例**。
3. **面⑤ 的保真度待实测** —— 机制写证据行走 `TaskService#addComment`；须实测确认它**不**触发框架 `ProcessEventListener` 的任何回调。若实测触发，则面⑤ 的对拍面须显式写进「机制带来的既有回调」并另立说明 —— **按 `…equivalence-harness.md` 的先例如实登记，不静默掩掉**。
4. **三态共用一个库** —— 见 §2.2；对拍面已按 pid / bk 作用域设计。**不得**称「三态数据隔离」。
5. **参照态无主闸** —— fixture 的声明合法性在参照态**不被校验**；其合法性由关态 / 开态的部署兜住（§4.3）。
6. **断言 3 不含静态「无类型引用」断言** —— 见 §7；该条降为文档纪律，靶子 → 落点的归属裁定归**探索期决议**。
7. **本文件不跑实测** —— 实测（含八靶子的具名击穿实验）是 ADR-0042 第 13 节的**撤销条件**，属执行段。

---

## §11.1 实现期披露（2026-09-27 落地）

> 落点 = 主仓 `feat/ai-decision-starter` `20a7426`（自 `d2110b1` 追加）。以下为实现期一手事实与判读裁定，**判据与冻结判定式零改动**；逐条与判定式的对应关系在探索期决议中对账。

1. **声明节点为伪单例多实例**（模型级会签 + 单元素集合）：§7 的冻结判定式要求逐 `ComparableAction.MEMBERS` 四值经位点服务提交且各增 1 条证据，而准入按 `ComparableAction#isAvailableFor` 校验动作可用性——普通节点拒会签两值、真多实例拒 AGREE / REJECT，**伪单例是四值同时可用的唯一形态**（§7「遍历取值 = 该面能达到的最强形式」的唯一可满足读法）。§4.1 的双节点形态不变，老路径结构保证不变（`autoCompleteFirstTasks` 只作用于 startProcess 时刻的活跃任务快照）。
2. **fixture 载体改放 `bpmn-decision-probe/`**（§4.2 字面为 `bpmn/`）：`bpmn/` 是 Flowable auto-deploy 的默认前缀，带 `fp:` 声明的 fixture 会在其它测试类的空注册表上下文被部署期校验阻断；`bpmn-decision/` 则是 S4 开态上下文的 auto-deploy 前缀（实测同样阻断）。探针不需要 auto-deploy，故改放探针专属独立前缀、由各态上下文显式部署——§4.2 的「classpath 资源 + `addClasspathResource` 显式部署」语义不变。
3. **参照态排除落到装配边界 BFPP（初始化器形态）**：集成测试应用类对 starter 包做了 `@ComponentScan`，两个决策自动配置类经**扫描路径**注册——`spring.autoconfigure.exclude` 只过滤自动配置导入路径，对扫描路径无效（实测：排除后 `decisionPipeline` 仍被构造并撞双 `DecisionProvider` 候选）；且静态嵌套 `@TestConfiguration` 会被同一扫描收进**所有**上下文（实测：把机制组件从被测态也移除了）。故参照态保留属性排除（§3 词面留痕）之外，以仅挂参照态的 `ApplicationContextInitializer` + `addBeanFactoryPostProcessor` 在单例实例化之前按 `S2` 已断言的机制 Bean 名闭集移除机制组件定义。§9 乙的对照面相应为 **17 名**（减 `decisionProvider`）。
4. **探针桩不带 stereotype**：带 stereotype 的测试配置类会被扫描收进每一个上下文（`@TestComponent` 不豁免），`ProbeAutoApprovalRule` 是行为性 stub，漏进其它测试类会让老路径在不该触发的测试里自动提交（实测打红 `ProcessLifecycleIntegrationTest` 与 `S4`）。故桩类无任何注解、仅经 `@Import` / `.sources()` 显式注册（lite 配置类形态）。
5. **Provider 桩的响应体与 `rawOutput` 同源一致**：管线入站加工 `policy.applyInbound(response.getRawOutput())` 以响应体原文为入参——桩只给解析字段、不给 `rawOutput` 时写入器以出处组半填拒绝（实测：归 `INTERNAL_ERROR`、证据行不落）。桩以 `@Primary` 压过扫描路径注册的默认实现（`@ConditionalOnMissingBean` 求值时点随装载路径在桩注册前后摆动）。
6. **恢复 `DbIdGenerator`**（三态同款引擎配置器）：Flowable Spring Boot 路径的默认 `IdGenerator` 产出 **UUID 串**，而历史时间确定性化与读侧「同毫秒按数值 `ID_` 兜底」（D4）都建立在数值 `ID_` 上。
7. **历史时间确定性化 = 两表合并按全局数值 ID 共享序数改写**（§5.1 面①②的前置；E20 同手法、代码不复用）：同毫秒并列在引擎侧无任何可用顺序（D4），且记录面跨表取时间（start 活动行 vs 任务实例行）——两表独立改写会让跨表时间不可比（实测），须两表合并按全局 ID 序共享同一序数序列；改写带命中数守卫（静默零命中即红）。
8. **§9 乙的对拍面可操作化（两处具名剔除）**：① `DataSourceJmxConfiguration`（含 Hikari 内嵌）挂在 `@ConditionalOnSingleCandidate` 上随 TestContext / SpringApplication 两种装载路径解析序摆动；② `decisionProvider` 替换点 Bean 名的在场性同样随解析序翻转（实测：单类运行缺席、全量运行在场），其被替换语义由行为面钉死（管线注入单候选 `@Primary` 桩、调用计数进断言）。两者均与机制装配面无关、双侧同剔；机制组件集（17 名）不受影响。
9. **面④③的操作化**：「该 businessKey 下不存在 AUTO_COMPLETE 评论」——回滚后无 pid 可指，落地为「②零运行时 + 零历史实例（评论行必挂实例与任务，结构性承载）+ 态内两次成功 run 的 AUTO_COMPLETE 计数收口为恰各 1 条」。
10. **三态共用一个库**（§2.2 预告的落地）：程序化 boot 的数据源属性从被注入的 `Environment` 复制（命令行参数形态传递，默认属性优先级低于 application.yml 不能承载态专属差异），三库矩阵下同一份复制逻辑。

---

## §12 复现免责声明

本文件是**内部形态与判据的记录**，不是可执行的测试套件。文中的三态构造、fixture 结构、冻结判定式与归一化规则为**骨架**：断言体、常量取值与 BPMN 的 XML 属实现期产物。任何据此复现的尝试，须自行搭建被测工程 —— 探索工作区**不留构建根**，也不对未实现阶段的复现结果作承诺。
