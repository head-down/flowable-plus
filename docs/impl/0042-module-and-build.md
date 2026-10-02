# 0042 模块与构建的实现形态 —— 依赖账本 / 装配形态 / 关闭矩阵位点 / 零改动 CI

> **日期**：2026-09-26
> **来源**：探索期决议「AI 决策接入：实现方案定稿与开工闸门」的 **模块与构建的实现形态：依赖账本与 hutool 去留 / starter 条件装配 / 三层关闭矩阵 / 零改动 CI**
> **定位**：本文件是**面 7（模块与构建）的唯一住所** —— 依赖账本终表、包结构惯例、starter 装配形态与替换点、配置载体、引擎级装配（主闸 / 复核 / 注册表）、三层关闭矩阵的位点、读写两侧护栏常量、零改动 CI 的构造。
> **住所唯一**：断言形态与落点的**唯一住所**是 `docs/impl/0042-verification-landings.md`（本文件只给落点身份并**指针**引用）；命名约束的唯一住所是 `docs/impl/0042-naming-charter.md`。**ADR-0042 的正文修改只有两处**（尾部登记块逐条列明），其余一律**零改动**。
> **写作纪律**：按可公开标准书写 —— 不含真实下游项目名 / 公司名 / 人名；第三方厂商与产品名仅出现在命名宪章 §4.1 的禁词清单与 §4.5 判例留痕内。

---

## §1 依赖账本

### 1.1 三模块终表

| 模块 | 既有（本机制不动） | 本机制**新增** | 本机制**移除** |
|---|---|---|---|
| `flowable-plus-core` | `flowable-engine`（compile）· `spring-tx` · `hutool-all` · （test）`junit-jupiter` / `mockito-core` / `assertj-core` | **`com.fasterxml.jackson.core:jackson-databind`（显式声明，无版本 —— 由父 POM 的 `spring-boot-dependencies` BOM 管到 `2.13.5`）** · **`org.apache.commons:commons-lang3`（显式声明，无版本 —— 父 BOM 管到 `3.12.0`；见依据 5）** | — |
| `flowable-plus-extension` | `flowable-plus-core` | **`org.slf4j:slf4j-api`（无版本 —— 父 BOM 管；见依据 6）** · **`org.apache.httpcomponents:httpclient`**（无版本 —— 父 BOM 管到 `4.5.14`）· **`org.apache.commons:commons-lang3`（显式声明，无版本 —— 父 BOM 管到 `3.12.0`；见依据 7）** · **`com.fasterxml.jackson.core:jackson-databind`（显式声明，无版本 —— 父 BOM 管到 `2.13.5`；见依据 8）** · （test）`junit-jupiter` / `mockito-core` / `assertj-core` / `h2` | **`hutool-all`（声明了但零引用）** |
| `flowable-plus-spring-boot-starter` | `flowable-plus-core` · `spring-boot-starter` · `flowable-spring-boot-starter` · （optional）`spring-boot-configuration-processor` / `spring-security-core` / `spring-boot-actuator` · （test）`spring-boot-starter-actuator` / `-test` / `-jdbc` / `h2` / Testcontainers 一族 / 两个 JDBC 驱动 | **`io.github.flowable.plus:flowable-plus-extension`（`<optional>true</optional>`）** · **`io.micrometer:micrometer-core`（`<optional>true</optional>`，无版本 —— 父 BOM 管到 `1.9.17`）** | — |

**八条依据**（逐条可核；依据 1–5 为首版，依据 6–8 为后续实现期的账本订正）：

1. **core 显式声明 Jackson** —— core 读侧将首次直接 `import com.fasterxml`（解析证据行 JSON），而现状 core 源码零 Jackson 引用、Jackson 仅由 `flowable-engine → flowable-engine-common` **传递**。**直接使用即显式声明**是本方案立下的通用纪律：传递依赖是偶然的、上游换依赖即断，且会让中立性 ④ 的「直接依赖坐标清单 == 固定常量」断言（`DecisionNeutralityDependencyTest`）在语义上不完整。**版本仍由父 BOM 管，不新增 jar** ⇒ ADR-0042 §4「Jackson **零新增**」的表述**不因此失效**。
2. **extension 移除 `hutool-all`** —— 该模块 `src` 下仅 `package-info.java`，**声明了 hutool 却零引用**。extension 是 optional 依赖的**源头**（starter 以 optional 引它），把一个未使用的整包工具集留在它的依赖面上，正是 ADR-0029 判据 1（依赖隔离）要防的向下游传染。
3. **core 的 3 处 `StrUtil` 替换面判 out of scope** —— 属**主仓依赖账本清理**，与 AI 接入不在同一路径（Out of scope 已裁，探索期决议顺带发现）。故 `hutool-all` 在 core 保留；**替换面的事实坐标**（3 文件 / 3 import / 11 调用点，改动面为零成本文本替换）供另案引用。
4. **extension 手写判空，不引 `commons-lang3`** —— 机制对「空判定」的需求只有 `idempotencyKey` / `actionSummary` 两处的 `null 或去空白为空串`（ADR-0042 §9 第 9 条）。为一个谓词引入一个显式坐标不划算；extension 侧以**私有静态方法**承担（Java 8，两行）。`commons-lang3` 虽已在传递面上（`flowable-engine` 直接声明，compile），但**不转为显式声明**。
5. **core 显式声明 `commons-lang3`（2026-09-26 账本订正，实现期一手事实）** —— 首版账本按同上理由把 core 也判为「不转为显式声明」；实现探索期决议时该判据**被推翻**：`DecisionEvidenceComment` 的标记匹配是**字符串谓词**，按当时新立的个人规范（「字符串与集合的谓词判断必须优先用 Apache Commons 工具类，禁止裸调 JDK 方法」）应用 `StringUtils.startsWith/contains/substringAfter`，而不是手写 `!= null && startsWith` 与 `indexOf` 下标算术。**core 是主源码首次直接 `import org.apache.commons.lang3`**，故按依据 1 的同一纪律（**直接使用即显式声明**）**转为显式**。**版本仍由父 BOM 管（`3.12.0`），不新增 jar**（该坐标本就在 `flowable-engine` 传递面上）⇒ 依据 4 对 extension 的裁定**不受影响**（extension 的判空需求仍只有两处、仍手写），ADR-0042 §4 的底座选型表**不动**。**如实披露**：本条属**账本订正、不改任何决策**（机制底座、依赖方向、中立性 ④ 均未动）；且本方案**未新增任何 `hutool` 用法** —— 集合判空改由**流的结构**表达（`flatMap` 后零元素），方向与 ADR-0042 §4 的 hutool 退场一致；`Apache Commons Collections4` **不在 core classpath 上**，引入它等于新增 jar，故集合面本次不用第三方工具（记录以备后续面 7 复核）。

6. **extension 显式声明 `slf4j-api`（2026-09-27 账本订正，实现期一手事实）** —— 观测面的结构化日志是 ADR-0042 第 10 节「消费三层」的下限（**始终**写、不可弱化），`DecisionObservationEmitter` 是 extension 主源码**首次直接 `import org.slf4j`** 的类型。按依据 1 的同一纪律（**直接使用即显式声明**）转为显式；版本仍由父 BOM 管，不新增 jar。**如实披露**：本条属**账本订正、不改任何决策**（与探索期决议的依据 5 同型）；core 主源码既有 `DefaultEventPublisher` 等对 SLF4J 的直接使用**未转显式**，属主仓依赖账本清理的不同路径，本方案不代裁（同依据 3 的口径）。

7. **extension 显式声明 `commons-lang3`（2026-09-27 账本订正，实现期一手事实）** —— 依据 4 的裁定建立在「extension 对空判定的需求只有 `idempotencyKey` / `actionSummary` 两处、判据是两行手写」这一**前提**上；节点声明的实现期事实**推翻了该前提**：声明读取要做到「逗号切分保留空 token、两侧裁剪、判空、按枚举常量名原文匹配」，字符串谓词的数量与种类都超出「为一个谓词引一个坐标」的范围，而个人规范**禁止手写判空**（`"".equals(s)` / `s.length() == 0` / `str == null || str.isEmpty()` 一律违规）并**优先 Apache Commons `StringUtils`**。故 `DecisionNodeDeclarationReader` 成为 extension 主源码**首次直接 `import org.apache.commons.lang3`** 的类型，按依据 1 的同一纪律（**直接使用即显式声明**）转为显式。**版本仍由父 BOM 管（`3.12.0`），不新增 jar**（该坐标本就在 `flowable-engine` 的传递面上，core 已显式声明 ⇒ 与依据 5 同源）。**如实披露**：本条属**账本订正、不改任何决策**（机制底座、依赖方向、中立性 ④、ADR-0042 §4 的底座选型表均未动）；依据 4 的**结论口径随之收窄为**「extension **不引入** `Collections4` 一类新 jar，且集合面不为判空新增依赖（改由流的结构表达）」—— 其「为一个谓词不引坐标」的原始理由**不再覆盖字符串谓词**（该前提已被实现期事实推翻，非口径漂移）。

8. **extension 显式声明 `jackson-databind`（2026-09-27 账本订正，实现期一手事实）** —— ADR-0042 第 6 节的 clamp 计量口径是「**载荷序列化后的 UTF-8 字节数**」，而载荷同型 `DecisionPayload` 住 extension ⇒ `DecisionClamp` 成为 extension 主源码**首次直接 `import com.fasterxml.jackson.*`** 的类型（`ObjectMapper.writeValueAsString` 计量）。按依据 1 的同一纪律（**直接使用即显式声明**）转为显式；版本仍由父 BOM 管（`2.13.5`），**不新增 jar**（该坐标本就在 core → extension 的传递面上 —— core 已按依据 1 显式声明）。**如实披露**：本条属**账本订正、不改任何决策**（机制底座、依赖方向、中立性 ④、ADR-0042 §4「Jackson **零新增**」的表述均未动 —— 不新增 jar，只是把传递面上的直接使用**转正**）。

### 1.2 依赖方向纪律（中立性 ④ 的账本面）

- extension / starter 的依赖图上**不得出现任何 AI 相关依赖** —— 本机制全部新增项（Jackson / httpclient / micrometer / extension 自身）**均与 AI 无关**，④ 由 `DecisionNeutralityDependencyTest` 以「直接依赖坐标清单 == 固定常量 + 禁词库入口类负向存在性」机械承担。
- **不引入构建期强制**：`maven-enforcer-plugin` / `bannedDependencies` **不采用**。理由：主仓零 enforcer 先例（全仓检索无命中、CI 未调用任何收敛插件），其自然住所是**父 POM**（= 主仓依赖账本，与本机制不同路径）；引入它等于给一个只服务本机制的断言配一个全仓构建插件。§5 的测试形态在既有 CI 内生效且失败信息可读，「机械可判」的性质不缺。
- `micrometer-core` **必须 optional**（不向下游传染，守探索期决议的最小依赖纪律）。

### 1.3 包结构组织惯例（宪章 §1.2 降档归本方案）

| 模块 | 包 | 内容 |
|---|---|---|
| core | `io.github.flowable.plus.core.vo` | `DecisionEvidenceVO` · `DecisionRationaleFact`（探索期决议已定，本方案**确认**） |
| core | `io.github.flowable.plus.core.enums` | 七个取值枚举（`DecisionOutcome` / `DecisionPolicyReason` / `DecisionFailureKind` / `DecisionChainStage` / `DecisionSubjectType` / `DecisionCompleteness` / `DecisionRationaleFactKey`）· `DecisionEvidenceComment` · `DecisionContextSource`（探索期决议已定）· **本方案新增** `DecisionEvidenceReadGuard` · `DecisionEvidenceWriteGuard` |
| extension | **`io.github.flowable.plus.extension.decision`**（本方案新定） | 机制的全部公开契约与实现（20+ 类型） |
| starter | `io.github.flowable.plus.starter`（既有） | 既有 `FlowablePlusAutoConfiguration` 一族 + 本方案新增两个配置类 |

- **extension 取机制子包**（非根包）：该模块的定位是**储备位**（`package-info.java` 原文：预留、当前无功能内容），把第一个机制铺满根包会让第二个机制无处安放；子包也让中立性 ⑤ 的「无 extension ⇒ 空集」与 T1 扫描的类清单更可读。
- core 两个护栏常量类**与 `DecisionEvidenceComment` 同包** —— 证据面的取值与常量同处一包，不为两个类新开包（包名只受 T1，宪章 §1.2 已降档）。

---

## §2 starter 装配形态

### 2.1 装配切两片（本方案新定）

| 配置类 | 内容 | 条件 |
|---|---|---|
| `FlowablePlusDecisionAutoConfiguration` | 机制运行组件（§2.2 行 1–10） | **只**条件于 extension 存在 |
| `FlowablePlusDecisionValidationAutoConfiguration` | 主闸 validator 装配 + 启动期复核（§2.2 行 11–13） | **只**条件于 extension 存在 |

**为何必须切两片**：ADR-0042 §11 第 3 条要求「**部署期校验与总闸解耦：validator 始终生效**」，且该节明确「**全局启用开关的取值不进入校验判定**」（「开关关 + 声明非法」仍阻断）。故校验 / 复核侧**不可能**随全局开关消失。**推论**：「全局关 ⇒ 类不存在」在结构上不可能实现 ⇒ ADR-0042 §11 第 1 条的五面可观测结局等价（**不是**运行时零活动）是该机制「关闭后无感」的**唯一**实现路径。本方案据此裁定。

### 2.2 逐 Bean 装配清单与五个替换点

**「`@ConditionalOnMissingBean`」一栏是本方案的承重纪律**：

| # | Bean | 来源 / 构造依赖 | `@ConditionalOnMissingBean` |
|---|---|---|---|
| 1 | `DecisionRuntimeControl` | extension 默认实现（状态由**应用持有**的进程内内存态） | ✅ **替换点** |
| 2 | `DecisionCredentialResolver` | extension 默认实现 | ✅ **替换点** |
| 3 | `DecisionTransport` | extension `HttpDecisionTransport` | ✅ **替换点** |
| 4 | `DecisionProvider` | extension `DefaultDecisionProvider` | ✅ **替换点** |
| 5 | `DecisionMetricsRecorder` | starter `DefaultDecisionMetricsRecorder`（**包内可见**） | ✅ **替换点** |
| 6 | `SuggestionSubmissionService` | extension 实现（`DefaultSuggestionSubmissionService`）；构造依赖 = starter 已注册的 `MultiInstanceDetector` Bean + 引擎服务（`TaskService` / `RuntimeService`）+ `DecisionObservationEmitter` + 开关定值；**内部件（写入器 / 入站加工 / clamp）由实现自持、不进构造面**（**2026-09-27 由推面位点服务账本订正**：原写「+ 内部写入器」—— 写入器是被使用者、不是构造依赖；订正后仍**只取 extension 可见类型**） | ❌ |
| 7 | `DecisionContextAssembler` · `DecisionInboundProcessor` · `DecisionEvidenceWriter` · `DecisionNodeDeclarationReader` | extension 内部件 | ❌ |
| 8 | 决策目标注册表 · 出域策略注册表 | starter 内建；从 `@Autowired(required=false) List<DecisionTarget>` / `List<DecisionPolicy>` 收集；**重复 key ⇒ 启动期 fail-fast**（ADR-0042 §7）；注册表类型住**包内**（ADR-0030：禁无消费者的公开类型） | ❌ |
| 9 | 专属有界线程池 | starter 内建；`AbortPolicy`、尺寸取属性（§2.4）、`destroyMethod` 关闭；**不复用事件执行器**（其 `CallerRunsPolicy` 会使回调可能落在流程事务内） | ❌ |
| 10 | `DecisionPipeline` · `DecisionTaskCreatedListener` | 探索期决议明定为「starter 的装配对象」；**与 extension 组件之间的「构造缝」承载见 `docs/impl/0042-equivalence-harness.md` §2.3 / §2.4**（`K2-001 无感等价对拍的实现形态` 的一致性回填，**形态补白、不重开本方案**：extension 侧构造依赖**只取 extension 可见类型** —— 值 / `List<DecisionTarget>` / `List<DecisionPolicy>` / 执行器 / 写入器 / 位点服务；starter 负责读属性、收集去重、fail-fast 后注入；**`enabled` 同走此缝、取构造期定值**） | ❌ |
| 11 | `DecisionNodeDeclarationValidator` | extension 纯类（**无 Spring**） | ❌ |
| 12 | `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>`（非废弃父接口；实现住 starter 内建，§2.5） | starter 内建（§2.5） | ❌ |
| 13 | 启动期复核 `SmartInitializingSingleton` | starter 内建（§2.5） | ❌ |
| — | 应用级默认数据源集 `DecisionDefaultContextSources` | **应用提供**，框架不注册 | — |
| — | `DecisionObserver` 集合 | **应用提供**，`@Autowired(required=false) List<DecisionObserver>`（主仓 SPI 惯例） | — |

**两条纪律**：

- **替换点只开五处**（行 1–5）。内部件（行 6–13）**一律不加** `@ConditionalOnMissingBean`：没有替换需求的 Bean 开替换点会让中立性 ⑤ 的「集合恒等」在运行期失去意义，且与 ADR-0030 同旨。
- **D1（主闸实现分居两模块）**：`Validator` 实现（纯类、无 Spring）住 extension；`ProcessEngineConfigurationConfigurer`（要 Spring）住 starter —— 由探索期决议已定的「extension 侧零 Spring 依赖」**结构性逼出**（extension 当前 pom 无任何 Spring 坐标，实现不能靠 `@Component` 自注册）。
- **`DecisionDefaultContextSources` 的注入形态**：**单一可选依赖**（`@Autowired(required=false)`）；**缺失或空集一律按空集处理**，框架代码中**不得存在任何隐式全集兜底**（守卫测试钉死，ADR-0042 §7）；内在集合取 `Set<DecisionContextSource>`。

### 2.3 条件写法与注册清单

- **extension 缺席判定**：`@ConditionalOnClass(name = "io.github.flowable.plus.extension.decision.SuggestionSubmissionService")` —— 取**字符串形态**（沿用既有 Security 先例），**marker = 机制面向 starter 的唯一入口契约类型**（探索期决议已定其住所）。
- **装配粒度**：**独立配置类级**（沿用主仓 Actuator 先例）—— 机制是一整族 Bean + 属性类 + 引擎 configurer，方法级会把 `FlowablePlusAutoConfiguration` 撑成第二个巨型类。
- **`spring.factories` 登记**：在既有两条之后**追加两条**（`FlowablePlusDecisionAutoConfiguration` → `FlowablePlusDecisionValidationAutoConfiguration`）。
  **登记序只作清单卫生，不构成运行期顺序保证** —— 一手核实：`spring-boot-autoconfigure-2.7.18.jar` 内 `AutoConfigurationSorter#getInPriorityOrder(Collection<String>)` + `sortByAnnotation` + `doSortByAfterAnnotation` 存在，自动配置类经排序（注解优先、否则按类名），登记序不被保留。
- **不引 `@AutoConfigureBefore` / `@AutoConfigureAfter`**：本方案两个新类**判无真实先后**（两片互不依赖；复核靠 `afterSingletonsInstantiated` 天然居后；configurer 只要被注册即被收集）。日后若判有真先后，**以注解表达**并同步登记本清单。

### 2.4 配置载体

**单一属性类**：`FlowablePlusDecisionProperties`，`@ConfigurationProperties("flowable.plus.decision")`，在 `@EnableConfigurationProperties` 显式登记（主仓惯例，非 `@ConfigurationPropertiesScan`）。

**十二个键**（探索期决议冻结十一个 + 本方案补全局开关）：

| 键 | 默认值（Java 字段初始化器） | 语义 |
|---|---|---|
| `enabled` | **`false`** | **全局启用开关**（部署期配置状态） |
| `outbound-connect-timeout` | `5s` | 每次尝试的出站连接超时 |
| `outbound-read-timeout` | `30s` | 每次尝试的出站读取超时 |
| `total-budget` | `60s` | 整次决策总预算（含全部重试，绑定实例存活期） |
| `max-attempts` | `3` | 尝试次数上限 |
| `backoff.initial` | `500ms` | 退避初值 |
| `backoff.multiplier` | `2` | 退避倍数 |
| `backoff.max` | `5s` | 退避上限 |
| `executor.core-size` | `2` | 专属池核心线程数 |
| `executor.max-size` | `4` | 专属池最大线程数 |
| `executor.queue-capacity` | `20` | 专属池队列容量 |
| `executor.thread-name-prefix` | `flowable-plus-decision-` | 专属池线程名前缀 |

**形态三则**：

- **嵌套静态类**（`public static class ExecutorProperties` / `public static class BackoffProperties`）—— 探索期决议冻结的键形态是**点分族**（`executor.core-size`），**点分族本身已排除「拍平」**（拍平会产出 `executor-core-size`，与冻结 key 不符）。**如实登记**：主仓**无嵌套 `@ConfigurationProperties` 先例**（探索期决议均未观察到），此为首例。嵌套类取 `…Properties` 词尾而非裸 `Executor`（避与 JDK `java.util.concurrent.Executor` 同名遮蔽）。
- **全局开关不是条件装配语义** —— 因为它**不参与装配条件**（§3），故**不写 `matchIfMissing`**：它就是属性类的一个字段，初始化器 `= false`。**与主仓既有开关「默认 `true` + `matchIfMissing = true`」是有意分歧**，须一并登记。
- **「只许调低、不可放大」的锚** = 包内常量类 `DecisionGuardrails` —— 其**住所与边界**见 §2.4 附；构造时一律 `Math.min(可设值, 硬上限)`，硬上限的数值**等于上表冻结的默认值**（框架默认可调低、不可放大）。

### §2.4 附 `DecisionGuardrails` 的住所与边界

本条是对该常量类的**限定**（非新增决策）；原决议只写「包内常量类」，此处把住所与边界钉死，以免它长成第二个配置面或跨界承载。

- **住所**：包 = `io.github.flowable.plus.starter`，与 `FlowablePlusDecisionProperties` / 两个配置类**同包**；**包级可见顶层类**（`final` + 私有构造）；**不住公开面** ⇒ 只受命名宪章 §2.B-T1，§2.C / §2.D 对其**不适用**（宪章 §1.1）。消费者**只有 starter 的装配代码**（池 / 超时 / 退避 / 总预算的构造点）；**extension 不得引用**（extension 零 Spring、不得读装配面配置），core 更不得。
- **B1 只承载「方向 = 收紧」的硬上限**：恰好覆盖 `flowable.plus.decision.*` 的**十个数值键**（两个超时 / 总预算 / 尝试次数 / 退避三项 / 池三项）；**不含** `enabled`（布尔开关无「上限」语义）、**不含** `executor.thread-name-prefix`（字符串无上限）。
- **B2 单一数值承载位**：属性类的数值字段**初始化器直接引用**常量（`private long outboundConnectTimeout = DecisionGuardrails.OUTBOUND_CONNECT_TIMEOUT_MS;`），属性类**不重复写数字**。**已被接受的代价（如实登记）**：`spring-boot-configuration-processor` 编译期生成元数据时**可能解析不到**该跨类常量表达式 ⇒ 生成的 `spring-configuration-metadata.json` 里对应键**缺 `defaultValue`**（IDE 补全不显示默认值；**不影响绑定行为**）。
- **B3 不得跨界承载**：core 的读写护栏三常量（`DecisionEvidenceWriteGuard` / `DecisionEvidenceReadGuard`，§4）与 extension 的 **32 KiB 载荷 clamp**（探索期决议已登记）**都不住这里** —— 它们属**不同的事实面**（允许解析 / 允许写入 / 出域载荷），不与配置面共用常量类。
- **B4 无第二消费者**：常量不得成为任何公开 API 的默认值来源，只服务构造期收口。
- **超上限的行为 = 静默 clamp（`Math.min`）＋ 每个被收口的键一条启动期 `WARN`**（记原值与上限），**不 fail-fast**。依据：① 同族先例 —— ADR-0042 §10 / 探索期决议已裁「事件面关闭**不** fail-fast（启动期 `WARN` + 已知边界）」；② fail-fast 会给 ADR-0042 §11 第 1 条的「关闭后无感」加上**第二个显式例外**（全局关态下一条超限配置会让应用起不来，而机制**不存在**时不会有该失败；该节目前唯一的显式例外是「BPMN 写了本机制扩展属性且不合法」）。
- **机械落点**：`#guardrailsCoversExactlyTheNumericKeys()`（常量集恰等十个数值键）＋ `#guardrailsConstantsEqualPropertyFieldDefaults()`（反射对账：属性类每个数值字段的初始值 == 对应常量）＋ `#overLimitConfigIsClampedWithStartupWarn()`（收口行为与 `WARN`）→ 均落 **S4** 家族（`docs/impl/0042-verification-landings.md`）。

### 2.5 引擎级装配（主闸 / 复核 / 注册表）

**主闸 —— 叠加式注册，不得整体替换**（ADR-0042 §6「三档的形态定稿」一手事实：`setProcessValidator` 是**整体替换**语义，引擎装配期自动装 **26 个内置 `Validator`** ⇒ 直接替换会静默摧毁全部内置语义校验）：

```
ProcessValidatorFactory#createDefaultProcessValidator()   // 自建默认实例（返回接口 ProcessValidator）
  ProcessValidatorImpl impl = (ProcessValidatorImpl) defaultValidator;   // 下转型：addValidatorSet 只住实现类
  ValidatorSet set = new ValidatorSet(DecisionNodeDeclarationValidator.VALIDATOR_SET_NAME);
  set.addValidator(decisionNodeDeclarationValidator);                    // ValidatorSet#addValidator 返回 void
  impl.addValidatorSet(set);                                             // ProcessValidatorImpl#addValidatorSet 返回 void
  configuration.setProcessValidator(defaultValidator);                    // 最后整体赋值
```

- 路径 = `org.flowable.spring.boot.EngineConfigurationConfigurer<org.flowable.spring.SpringProcessEngineConfiguration>`（`configure(SpringProcessEngineConfiguration)`，回调发生在引擎 `buildProcessEngine()` **之前** —— `initProcessValidator()` 只在字段为 `null` 时补默认值）。
- **形态订正（2026-09-26，收口决议一致性复核，如实披露）**：本条原写作链式伪码 `addValidatorSet(...).addValidator(...)`，**不可落地** —— 一手核实（`flowable-process-validation-6.8.0.jar`）：`ProcessValidatorFactory#createDefaultProcessValidator()` 返回**接口** `org.flowable.validation.ProcessValidator`（只有 `validate` / `getValidatorSets`），`addValidatorSet` 只住在 `org.flowable.validation.ProcessValidatorImpl`，且 **`ProcessValidatorImpl#addValidatorSet` 与 `ValidatorSet#addValidator` 均返回 `void`**（两处都不可链式）⇒ 可达形态 = **下转型 + 两处分行调用**。**包名同步订正**：`Validator` / `ValidatorSet` / `ValidatorImpl` / `ProcessLevelValidator` 的实际包是 **`org.flowable.validation.validator`**（不是 `org.flowable.validation`）。**该订正不改任何决策**（叠加式注册、`ValidatorSet` 具名、不随开关消失三条均不动）。
- **配置器接口的形态订正（2026-09-26，同上）**：原写 `org.flowable.spring.boot.ProcessEngineConfigurationConfigurer` —— 该接口在 6.8.0 带 **`@Deprecated`**（`javap -v` 实证 `Deprecated: true`）。改用其**非废弃父接口** `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>`：一手核实收集点 `BaseEngineConfigurationWithConfigurers<T>` 的字段类型**就是** `List<EngineConfigurationConfigurer<T>>`，且 standalone（`ProcessEngineServicesAutoConfiguration$StandaloneEngineConfiguration`）与 app（`ProcessEngineAutoConfiguration$ProcessEngineAppConfiguration`）两条装配路径**都继承它** ⇒ 换父接口**零行为差异**、零编译告警。
- **`ValidatorSet` 具名 `flowable-plus-decision`**，常量 = `DecisionNodeDeclarationValidator.VALIDATOR_SET_NAME`（生产者自带自己的组名，单一来源）。该字面量会经 `ValidationError.validatorSetName` 出现在**部署失败信息**里 ⇒ 属契约面可见字面量（宪章 §1.2 ⑥）。
- **问题码 = `DecisionNodeDeclarationValidator.INVALID_DECLARATION_PROBLEM`（2026-09-27 由节点声明与部署期校验补形态）**：`addError` 契约要求一个 `problem`，本机制不占用引擎 `Problems` 词表，自带一个消费者 = 下游运维 / 工具（按它机械识别「本机制阻断的部署」）；本机制全部声明违规**共用一个**问题码，逐条规则的区别落在描述里（描述必带现场值）。该字面量经 `ValidationError.problem` 出现在部署失败信息里 ⇒ 与组名同属契约面可见字面量（登记位 = 命名宪章 §4.5「节点声明校验器」行）。
- **不受全局开关门控**（ADR-0042 §11 第 3 条）。

**启动期复核**：`SmartInitializingSingleton#afterSingletonsInstantiated`（上下文 refresh 之内、Bean 齐备之后）⇒ 不一致时**刷新失败、应用不启动**（用 `ApplicationRunner` / `ApplicationReadyEvent` 会先起后炸，留下半启动上下文）。判据沿用 ADR-0042 §11 第 3 条的「**唯一显式例外**」：*出现本机制扩展属性 ∧ 不满足校验规则* ⇒ 未声明的遗留定义不被误伤。
**如实披露（运维须知）**：移除一个仍被引用的策略 Bean ⇒ **应用起不来**（ADR-0042 §6 已披露）。

**复核所需两个注册表不得随全局开关消失**（否则「开关关 + 声明合法」会因注册表缺席被误判为漂移，反撞「关闭后无感」）。故 §2.2 行 8 不加任何开关条件。

**最后防御**（运行期阻断式 ⇒ `failureKind = INTERNAL_ERROR`）住在运行组件内，**不需要**独立 Bean。

### 2.6 指标装配

- **依赖**：`io.micrometer:micrometer-core`，starter 主代码 `<optional>true</optional>`（版本父 BOM 管，`1.9.17`）；**测试零新增** —— 既有 test-scope `spring-boot-starter-actuator` 已传递 `micrometer-core`（一手核实：`spring-boot-starter-actuator-2.7.18.pom` 含 `micrometer-core`；而 `spring-boot-actuator` 自身**不**传递它，只依赖 `spring-boot`）。
- **替换点类型 = 机制专属**：接口 `DecisionMetricsRecorder`（extension，公开）＋ 默认实现 `DefaultDecisionMetricsRecorder`（starter，**包内可见 ⇒ 不进公开类别 ①**，只受 T1）。**默认实现一律取中立词**，**不得**取能唯一指向具体产品的词（见命名宪章 §4.5 判例留痕）。
- **装配落点 = 运行组件配置类的 `@Bean` 方法级**，条件 `@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")` + `@ConditionalOnMissingBean`。**不新增配置属性类**（`flowable.plus.decision.*` 的冻结键里没有任何指标项，新增等于造第二处真相）；`spring.factories` 不新增条目。
- **无 micrometer 时缺省 = 无 Bean**（缺省即无指标消费者；管线以可选依赖接收，日志与回调不受影响）—— ADR-0042 §10 原文即「**有 Micrometer 才注册指标**」。**不设 no-op 常驻 Bean**：那会引入同型双 Bean 的注册序问题，而 §2.3 已定不引排序注解。
- **字节码安全纪律**（可机械验收）：运行组件配置类的**方法签名不得出现 `MeterRegistry`**（用 `ObjectProvider<MeterRegistry>` 取，实现类另置）—— 否则无 micrometer 时反射读方法签名会 `NoClassDefFoundError`。
- **不得在启动期急切创建指标名**：默认实现**懒注册**（首次观测时才向 `MeterRegistry` 取 meter），`@Bean` 方法体内**不得**预建 9 个信号。验收 = 上下文启动后、未发生任何决策时，本机制前缀的 meter 数为 **0**。

---

## §3 三层关闭矩阵的位点

| 层 | 载体 | 关闭判定点 | 关闭的结局 |
|---|---|---|---|
| **全局启用开关** | `flowable.plus.decision.enabled`（属性字段，默认 `false`） | **运行期读**：拉面 = 闸门链 **stage 1**（回调内纯读门控）；推面 = 位点服务入口 | 拉面与推面**均不触发、不留记录、非失败** |
| **节点声明** | BPMN `fp:decisionEnabled` | 拉面 = stage 2（纯读门控）；出域 = 装配器读 `decisionDataSources` | **只**门控拉面与出域；未声明节点**仍可被外部直提并留痕** |
| **推面** | 无节点级开关 | — | 只能靠全局关 |

**三条纪律**：

1. **全局开关是运行期门控，不是装配条件**。依据：探索期决议已把闸门链 stage 1 定为「**回调内纯读门控（全局开关 → 节点声明）**」—— 若 listener 被条件装配掉，该 stage 就是死代码；且 §2.1 已证结构等价不可能（validator 必须始终生效）。故 **extension 存在时机制 Bean 一律注册**，开关只在上述两个判定点被读。
2. **全局关时推面提交 = no-op 且非失败**：`SuggestionSubmissionService` Bean **常驻**（应用仍可注入），`submit(...)` 直接返回 —— **不落记录、不抛 `SuggestionAdmissionException`**。依据：`SuggestionAdmissionReason` 的 13 值闭集里**没有**「全局关」这一项（探索期决议冻结），故不得新造；「非失败」是 ADR-0042 §11 第 2 条表格的原文结论。
3. **「节点未声明」≠「该节点零决策痕迹」**（ADR-0042 §11 第 2 条防误读句），且三层**不得混**：`未激活`（禁用 / 未装配）/ `关闭`（曾激活后关掉，含全局关与节点关）/ `未声明`。

**「不引 extension ⇒ 零 Bean、零行为变化」是结构保证**（接口与实现全住 extension，类不存在即写不出来）—— 由 `S2` 的**空集**断言承担，不由运行期判断承担。

---

## §4 读写两侧护栏常量（成对，core）

R3-Q1 / R3-Q2 裁定：**写入侧设上界、写入前校验、超限拒绝写入**，使「写得进、读不出」**机械关闭**。

| 侧 | 常量类（core `…core.enums`） | 常量 | 语义 |
|---|---|---|---|
| 写入 | `DecisionEvidenceWriteGuard` | `MAX_EVIDENCE_BYTES` | **单条证据行的最大允许尺寸**（= 标记 + JSON 整行）。**派生自**「两方向 clamp 上限之和 ＋ 固定结构开销余量」—— 单一真相源仍是最初的 32 KiB，**不引入第二个独立数字**。写入前校验 |
| 读侧 | `DecisionEvidenceReadGuard` | `MAX_NESTING_DEPTH` | 读侧解析护栏：嵌套深度上限 |
| 读侧 | `DecisionEvidenceReadGuard` | `MAX_PARSE_BYTES` | 读侧解析护栏：大小上限 |

- **超限处置**：写入侧超限 ⇒ **拒绝写入** ⇒ 归 `SUGGESTION_FAILED` + `failureKind = INTERNAL_ERROR`。**零新增枚举值、零新增槽位、零新增 `writeDegradedCause` 取值**。依据 = ADR-0042 §6 的**完全同型**先例：clamp 丢到全空仍超 ⇒ 拒绝出域 ⇒ `SUGGESTION_FAILED` / `INTERNAL_ERROR`。
  - **落点落地（2026-09-27，证据写入与提交模型）**：行为面断言 = `E9` 的 `#writeGuardRefusesOversizedRowWithoutNewEnumValue()`（超限 ⇒ 拒写该行、改交 D 列的 `INTERNAL_ERROR` 行、被拒内容不落任何内容体、且五个闭集的规模一律未动）。**判据与形态未动**；写入器交**整行文本**、持久化由调用方完成（一手事实：引擎 `AddCommentCmd` 收单个 `message` 参数，`FULL_MSG_` 存全文、`MESSAGE_` 由引擎自行折叠截断）。
- **两侧不同源**（不同类、不同名、不同义），守「一对不同的事实不得共用一个承载位」。
- **命名订正（一致性复核结论，非重开）**：起草时曾拟读侧常量名为 `MAX_PAYLOAD_BYTES`；已订正为 **`MAX_PARSE_BYTES`**。理由：`payload` 在语料中是 `DecisionPayload`（**决策载荷**）与「出域载荷」的**专名**（`CONTEXT.md` 有专条），用它指「证据行 JSON」会撞命名宪章 §2.D.1「**回指唯一**」。订正后两侧名字各指**不同的事实**（允许写入 vs 允许解析），符合「一个承载位只承载一类事实」。

**G1–G7 机械验收约束**（形态 / 常量名 / 纪律在本方案钉死；**具体数值入探索期决议的「实现期默认数值」清单**）：

| # | 约束 | 验收形态 | 落点 |
|---|---|---|---|
| **G1** | 三常量**成对**存在、具名、值 > 0 | 反射断言 | core `C9` / `C10`（`DecisionEvidenceReadGuardTest` / `DecisionEvidenceWriteGuardTest`） |
| **G2** | 读侧两常量**不得与出域 clamp 的 32 KiB 同源**（不同常量类） | `getDeclaringClass()` 不同 | **extension 的 clamp 守卫**（读侧常量住 core、出域 clamp 常量住 extension，**只有同时可见两者的模块才可判**） |
| **G3a** | `MAX_EVIDENCE_BYTES` ≥ 两方向 clamp 上限之和（**必要下界**） | 数值不等式 | **extension 的 clamp 守卫**（同上：跨模块读两侧常量） |
| **G3b** | **`MAX_PARSE_BYTES` ≥ `MAX_EVIDENCE_BYTES`** —— 写出侧上界一经成立即机械关闭「写得进、读不出」 | 数值不等式 | core `C9`（两侧常量都住 core） |
| **G4** | `MAX_NESTING_DEPTH` ≥ 最深合法 fixture 的**实测**嵌套深度 | 深度上限 ≥ **最深合法 fixture 实测深度**（Java 常量形态、**不引金样本文件**） | core `C9`（fixture 住 core 测试树，见下「落点订正」） |
| **G5** | 三阈值**不出现在配置面** | 属性类字段集**恒等** §2.4 的 12 键 | starter（`S4` 家族） |
| **G6** | 超限 ⇒ **只跳过该条证据投影 + 一条可观测信号**，审批轨迹行**不消失** | 行为断言 | 并入探索期决议的 `C7` 家族 |
| **G7** | 护栏**在解析之前**生效（不得先整体解析再判大小） | 受限源码扫描（沿用 `C5` 既有手法） | **读侧投影实现所在来源**（见下「落点订正」：本表首落来源不含解析代码，落此处恒真） |

> **落点订正（2026-09-26，实现期一手事实，如实披露）**：本表首版的「落点」列把 G1–G4 与 G7 一并写在 core 守卫测试里，**在依赖方向下不可落地** —— ① G2 / G3a 要读 **extension 侧**的出域 clamp 常量，而 **core 不得依赖 extension**（模块单向）；② G4 原指定的 `DecisionFixtures` 是 **extension 的测试基座**，core 测试树不可见；③ G7 要扫的是**读侧解析实现**，该实现不在本表首落的来源内（该来源交付物明确「无读侧投影改动」），落此处只会得到**恒真**的扫描。
>
> **订正只改「测在哪」，不改「测什么」**：G2 / G3a / G4 / G7 的**判据与验收形态一字未动**（仍是 `getDeclaringClass()` 不同、仍是数值不等式、仍是「≥ 最深合法 fixture 实测深度」、仍是受限源码扫描）。G4 的 fixture 改取 **core 测试树内的 Java 常量**（结构仍不落资源文件 ⇒ recorded 无栖身处，仍合「不引金样本」）；G7 随**读侧投影**的落点走。**不触发受限重开、不改任何决策**（本表不是 ADR 条款）。（订正落点同批见 `docs/impl/0042-verification-landings.md` §2 的 `C9` / `C10` 行。）
>
> **G7 落点落地（2026-09-27，core 读侧投影）**：G7 已随读侧投影实现落进 **`C7` `HistoryWorkflowEvidenceReadTest`**（断言名 `#guardAppliedBeforeParsing()`），上表 G7 行的「落点」列随之指向 `C7`。**判据与验收形态未动**（仍是「护栏在解析之前生效、不得先整体解析再判大小」的受限源码扫描）。

---

## §5 零改动 CI 的构造

- **矩阵轴零改动**：既有 `os ∈ {ubuntu-latest, windows-latest}` × `java = 8` × `db ∈ {h2}` + `include` 追加 ubuntu 的 `mysql` / `postgresql`，共 4 个 job；**工作流文件 diff 为空**。
- **extension 真引擎基座固定 H2、不读 `flowable.test.db`**（`ExtensionTestEngine`，standalone in-mem）⇒ 四个 job 行为一致，**不产生分叉、不新增轴**。
- **测试类收录**：全部以 `Test` / `IntegrationTest` 结尾 ⇒ 落 surefire **默认 includes**（主仓未观察 `maven-failsafe-plugin`，两类均由 surefire 执行）⇒ **POM 的 surefire 配置零改动**。机械面 = `#allNewTestClassesMatchSurefireIncludes()`（`E1`）。
- **extension 测试树首次建立**：`src/test` + 四个 test-scope 依赖（§1.1）；**`src/test/resources` 不建**（「recorded 不进 v1」的结构保证 —— recorded fixture 无栖身处），**不得**在 POM 里配 `testResources`。
- **验收句不变**：无外网、无真实凭据，三库下 `mvn clean verify` 全绿（ADR-0042 §7）。

> **过渡触发器（2026-09-26 加，如实披露 —— 实现批次的工作分支也要跑 CI）**：主仓 `ci.yml` 的 `push` 原只认 `master`，而**转正 PR 尚未到期**（转正是最后一片），特性分支推送因此**不触发**任何 CI ⇒ 实现批次失去云端验证面。故本批给 `push.branches` **加一条过渡项 `feat/ai-decision-*`**：**矩阵轴与 job 体一字未动**（仍是 §5 首条的 4 个 job），只多一个触发条件。
>
> **恢复条件（钉在转正那一片）**：转正时必须把 `push.branches` **恢复为仅 `master`**，以恢复首条「**工作流文件 diff 为空**」的可验收性质；未恢复即转正 ⇒ §5 首条的验收性质不成立（属**未闭环的过渡改动**）。此边界已推入转正。

---

## §6 命名判定结果表（宪章 §7.2 表式，一来源一份）

**主键 = 「来源 × 候选标识符」**；一个标识符一行，不合并。归一后两两比较的**硬域 / 软域自检**列于表后。

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 模块与构建 | `flowable.plus.decision.enabled` | ⑤ 配置 key | §2.B.1 · §2.C.2 硬域 · §2.D.4 | 通过 | 机械可判 | `E1` · `S4` |
| 模块与构建 | `outbound-connect-timeout` · `outbound-read-timeout` · `total-budget` · `max-attempts` | ⑤ | 同上 | 通过 | 机械可判 | `E1` · `S4` |
| 模块与构建 | `backoff.initial` · `backoff.multiplier` · `backoff.max` | ⑤ | 同上 | 通过 | 机械可判 | `E1` · `S4` |
| 模块与构建 | `executor.core-size` · `executor.max-size` · `executor.queue-capacity` · `executor.thread-name-prefix` | ⑤ | 同上 | 通过 | 机械可判 | `E1` · `S4` |
| 模块与构建 | `FlowablePlusDecisionProperties` | ① | §2.B.1 · §2.C.2 软域 · §2.D.1（**回指 ADR-0042 §7** 全局默认面与 fail-closed，非 `CONTEXT.md` 词条） | 通过 | 文档纪律 + 机械可判（T1） | `E1` |
| 模块与构建 | `ExecutorProperties` · `BackoffProperties`（`public static` 嵌套类） | ① | §2.B.1 · §2.C.2 硬域（外层类内） · §2.D.1 | 通过 | 机械可判 | `E1` |
| 模块与构建 | `FlowablePlusDecisionAutoConfiguration` | ① | §2.B.1 · §2.C.2 软域（对既有 `FlowablePlusAutoConfiguration` / `FlowablePlusHealthContributorAutoConfiguration` 无屈折） | 通过 | 机械可判 | `S2` · `E1` |
| 模块与构建 | `FlowablePlusDecisionValidationAutoConfiguration` | ① | 同上 | 通过 | 机械可判 | `S2` · `E1` |
| 模块与构建 | `DecisionNodeDeclarationValidator` | ① | §2.B.1 · §2.D.1（词根 `DecisionNodeDeclaration` 已登记） | 通过 | 机械可判 | `E3` · `E1` |
| 模块与构建 | `VALIDATOR_SET_NAME`（成员形态） | ② | §2.B.1 · §2.C.2 硬域 | 通过 | 机械可判 | `E3` · `E1` |
| 模块与构建 | `flowable-plus-decision`（`ValidatorSet` 名字面量） | ⑥ | §2.B.1（`flowable` 已登记**出 T1**） · §2.C.2 | 通过 | 机械可判 | `E3` |
| 模块与构建 | `DecisionDefaultContextSources` | ① | §2.B.1 · §2.C.2 软域（对 `DecisionContextSource` 无屈折） · §2.D.1（§4.5 新登记行） | 通过 | 机械可判 | `E4` · `E1` |
| 模块与构建 | `DecisionMetricsRecorder` | ① | §2.B.1 · §2.C.2 硬域（对 `DecisionMetrics` **包含而非屈折**） · §2.D.1（词根 `Metrics` 已登记，补成员形态） | 通过 | 机械可判 | `S2` · `E1` |
| 模块与构建 | `DecisionEvidenceReadGuard` · `DecisionEvidenceWriteGuard` | ① | §2.B.1 · §2.D.1（词根 `DecisionEvidence` 已登记） | 通过 | 机械可判 | G1–G7 |
| 模块与构建 | `MAX_NESTING_DEPTH` · `MAX_PARSE_BYTES` · `MAX_EVIDENCE_BYTES` | ② | §2.B.1 · §2.C.2 硬域（各自类型内） | 通过 | 机械可判 | G1–G7 |
| 模块与构建 | `io.github.flowable.plus.extension.decision`（包名） | 降档（§1.2） | **仅** §2.B.1 | 通过 | 机械可判（T1） | `E1` |
| 模块与构建 | `DefaultDecisionMetricsRecorder` · 两个注册表类型 · `DecisionGuardrails`（住所与边界见 §2.4 附）· extension 的私有静态判空方法 | 私有 / 包内实现细节（§1.1） | **仅** §2.B.1 · §2.C / §2.D 不适用 | 通过（降档） | 机械可判（T1） | `E1` 的 `#testTypesStillSubmitToT1()` 同族扫描（T1）；`DecisionGuardrails` 另落 **S4** 三条（B1 覆盖 / B2 对账 / 超限收口） |

**硬域 / 软域自检**（归一后，去分隔符、统一小写）：

- 12 个配置 key 两两比较 —— `enabled` / `outboundconnecttimeout` / `outboundreadtimeout` / `totalbudget` / `maxattempts` / `backoffinitial` / `backoffmultiplier` / `backoffmax` / `executorcoresize` / `executormaxsize` / `executorqueuecapacity` / `executorthreadnameprefix` —— **无屈折对**。
- 本方案新增类型名两两比较 —— `flowableplusdecisionproperties` / `executorproperties` / `backoffproperties` / `flowableplusdecisionautoconfiguration` / `flowableplusdecisionvalidationautoconfiguration` / `decisionnodedeclarationvalidator` / `decisiondefaultcontextsources` / `decisionmetricsrecorder` / `decisionevidencereadguard` / `decisionevidencewriteguard` —— **无屈折对**；`decisionmetricsrecorder` 与既有 `decisionmetrics` 归一后为**包含而非屈折**（依据同探索期决议判例 `CONTEXT_SOURCE` / `…_CONTEXT_SOURCE_DROPPED`）。
- 跨硬域同形不违规：`ExecutorProperties`（Java 类型）与配置 key 段 `executor` 分属不同硬域。

---

## §7 复现免责声明

本文件是**实现形态与账本的记录**，不是可执行的构建产物。文中的 bean 清单、条件形态、常量与数值**均为骨架**：属性类 / 配置类 / 注册表 / 常量类的**实现体、精确数值与断言体**属实现期产物（数值清单归探索期决议）。任何据此复现的尝试，须自行搭建被测工程 —— 本文档**不随附可运行工程**，也不对未实现阶段的复现结果作承诺。**本方案不跑实测**：实测是 ADR-0042 第 13 节的**撤销条件**，属执行段。
