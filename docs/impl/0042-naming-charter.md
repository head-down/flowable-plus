# 0042 契约命名宪章 —— 面 1 其余四来源的可判定命名约束集

> **日期**：2026-09-25
> **来源**：探索期决议（**契约命名宪章：面 1 其余四来源的可判定命名约束集**）
> **定位**：本文件是**规则集，不是名字表** —— 给它一组候选标识符，它能判「合不合规」。具体名字归面 1 的四来源（节点声明 / 证据载体 / 幂等与重放 / 位点服务与提交模型）。
> **住所唯一**：本文件是命名约束的唯一住所。
> **前置事实**（主仓考古，`docs/research/`）：`R1` CommentType 全貌 · `R2` 事件惯例 · `R4` 审批轨迹读侧 · `R6` BPMN 节点声明与扩展属性惯例。
> **写作纪律**：按可公开标准书写 —— 不含真实下游项目名 / 公司名 / 人名；第三方厂商与产品名仅出现在 §4.1 的禁词清单内（与 ADR-0042 §4 先例一致）。

---

## §1 适用面与覆盖类别

### 1.1 适用面

- **本文的判定对象** = 面 1 四来源（节点声明 schema / 决策证据载体 / 幂等与重放 / 位点服务与提交模型）提出的候选标识符。
- **规则本身的适用面** = 面 1–7 新增的**公开**标识符。后续各面新增公开标识符时**直接引用本宪章**，不另立一套。
- 面 1–7 的**私有**实现细节（`private` 成员、包内内部类）**不受** §2.D 术语回指约束，**仍受** §2.B-T1 绝对禁词约束。

### 1.2 覆盖类别

**契约面标识符 —— 受本宪章全部规则**：

| # | 类别 | 例（形态，非规定名） |
|---|---|---|
| ① | Java 公开类型名（类 / 接口 / 枚举） | `…VO` / `…Event` / `…Policy` |
| ② | 公开常量名（含枚举取值） | `SCREAMING_SNAKE_CASE` |
| ③ | 公开字段名与方法名（含 VO 字段、getter） | `camelCase` |
| ④ | BPMN 扩展属性的**符号名、URI、属性名** | 见 §2.E |
| ⑤ | 配置属性 key | `flowable.plus.decision.*` |
| ⑥ | 常量**值**字面量（标记前缀、落库 `TYPE_` 串） | `[SYSTEM:DECISION_EVIDENCE]` |
| ⑦ | JSON 字段名（= **写侧** VO 字段名，二者同源；**读侧专属字段**不在键集内） | `camelCase` |

**降档受约束**：Java **包名与模块名**只受 §2.B-T1 绝对禁词；其组织惯例归面 7，不在本宪章。

### 1.3 越界声明（本宪章**不**覆盖）

| ADR-0042 §3「中立性核对清单」项 | 归属 |
|---|---|
| ② 取值域不绑 AI（`subjectType` / `chainStage` / `outcome`） | **证据载体** |
| ④ 依赖方向（extension / starter 依赖图上不得出现 AI 相关依赖） | 面 7 |
| ⑤ 装配（starter 条件装配不得装配任何 AI 组件） | 面 7 |

本宪章只承担 **①（类型与常量命名不绑 AI）** 与 **③（必填性不预设「必定经过模型调用」）** 的可判定化。

---

## §2 规则集

### §2.A 规则的效力层级与断言边界

**A.1 每条规则必须标注三类强制机制之一**：

| 强制机制 | 含义 | 判定者 |
|---|---|---|
| **机械可判** | 可写成断言，机器可判 | 守卫测试（落点见 §7） |
| **结构保证** | 类型 / 命名空间 / 装配层天然成立，违反即写不出来 | 编译器 / 装配层 |
| **文档纪律** | 人评审可判，无机器落点 | 评审者 |

**A.2 断言边界（与面 6「中立性五面断言」的分工）**：

- 本宪章**只给判据 + 输入契约**；**断言的形态与落点归面 6**（`验证落点的实现形态`），与面 6 其余验证落点一起定，避免两处各定一遍而漂移。
- 面 6 的断言**必须直接消费**本宪章 §2.B 的 T1/T2 清单与 §2.D 的受控词根表，**不得另立一套词表**。

**A.3 违规处置**：

- 判据不通过 ⇒ **该面 1 来源不得关闭**，只有两条出路：**改候选名**，或按 §5 走**偏离登记**。
- 本宪章**不**阻断开工（开工闸门是收口决议的事）。
- 本宪章**不跑实测**；它只写义务与落点。

### §2.B 决策源中立（ADR-0042 §3 ①③ 的可判定化）

#### B.1 T1 绝对禁词

**类别闭集四条**：① 厂商 / 公司名；② **具体**模型家族与型号名；③ **具体**商业产品名；④ 以上三者的**缩写、中文译名、变体拼写**。

- **禁止出现在任何公开标识符**：含字段名、方法名、包名、模块名、常量值字面量（§1.2 全类别）。
- **T1 / T2 分界条（可判）**：**能唯一指向某个厂商或某个具体产品的词归 T1**；**只表示能力类别、跨厂商通用**的词（`AI` / `Model` / `LLM` / `Agent`）归 T2。
- **强制机制**：机械可判（标识符切词后与 §4.1 清单做子串匹配）。
- 首版清单具名落在 **§4.1**；增删由主仓维护者裁，登记四项（标识符 / 处置 / 理由 / 日期）。

#### B.2 T2 域词限制

**域词集**：`AI` / `Model` / `LLM` / `Agent` / `智能` / `大模型`（及同义变体）。

- **禁**在**类型名与枚举名**（ADR-0042 §3 ① 的对象）。
- **许**在**字段名与方法名**，但须通过 **B.3 替换测试**。

#### B.3 替换测试（判定式）

判定式 = 看名字**绑的是「生产者的事实」还是「模型的实施手段」**：

| 情形 | 判定 | 例 |
|---|---|---|
| 描述 **provider 契约事实** | **通过** | `modelId` / `provider` / `rawOutput`（以及将来的 `temperature`、token 计数一类） |
| 描述**机制自身或结果来源** | **不通过** | `aiDecision*` / `aiSuggestion*` / `modelVerdict` 形态 |

**通过项的附加条件**：该字段必须**可空**（非 AI 决策源下取不到值）。项进 §4.4，并**注明「可空」** —— 可空是它通过替换测试的前提；丢掉这个条件，`modelId` 就从「provider 面事实」滑成「必经模型调用」。

#### B.4 T3 取值字面量豁免

`AI` 作为 `subjectType` 的**取值**（ADR-0042 §3 ②）不是「类型名 / 常量名的绑定」，**不在 T2 面内**。划清以免规则自相矛盾；取值域本体的中立性归探索期决议（证据载体）。

#### B.5 ③ 必填性不得预设「必定经过模型调用」

**B.5.1 反例判据（机械可判）**：

> 若存在一个**不经模型调用**的合法 `SUGGESTION_PRODUCED`（`SYSTEM` 直提 / `USER` 直提 / **经非模型端点的出站产出**），而某字段在该分支被标**必填** ⇒ **违规**。

> **（2026-09-25 显式化）** 括号内的列举由两例补至三例 —— 判定条件「**不经模型调用**」未变，只是把**本就在条件内、此前未被列举**的情形（`decisionTarget` 不限定模型端点，见 ADR-0042 第 3 节通用化总纲）补齐。来源：探索期决议（决策证据载体形态）；其直接后果是 ADR-0042 第 5 节矩阵 A 列 `modelId` 由「必填」改「**条件必填**」（条件 = Provider 缝从响应解析到模型标识）。**不构成球门修订**：判据、违规后果、承受面均未动。

**B.5.2 登记要求（文档纪律）**：必填 / 可空矩阵**逐格**写必填理由；理由**只能**引 `outcome` 分支或 `subjectType`，**不得**引「因为会调模型」。

**B.5.3 与 B.3 的联动**：§4.4 的通过项若在矩阵中被标必填 ⇒ 按 B.5.1 判违规。

**承受面**：本条的矩阵在证据载体的必填 / 可空决议内。

### §2.C 形近

#### C.1 形近的定义 = 词形屈折

**归一**：去掉分隔符（`_` / `-`）并统一小写后比较。

**判定**：两个公开标识符互为**单复数形**即**形近** —— 规则屈折三种：`+s`、`+es`、`y→ies`。

**不扩到一般编辑距离**：那会把 `decisionTarget` / `decisionPolicy` 这类**同前缀不同词根**误判为形近，反而不可判。

**已知未覆盖**：不可数 / 不规则复数（`data` / `info` / `indices`）**不判** —— 如实登记，不为此扩规则。

#### C.2 并置作用域（两级）

| 域 | 范围 | 判定 |
|---|---|---|
| **硬域** | 同一 BPMN 元素；同一 Java 类型 / 接口 / 枚举内 | **禁止**任何形近并置 |
| **软域** | 同包；同模块公开 API 面 | **新增**标识符不得与同域**既有**标识符形近 |

#### C.3 既有豁免

主仓既有形近对**冻结不动**（判例：`operationComment` / `operationComments`，`R4` 事实）。豁免的**后果**是：**不得再引入第三个形近变体**（如新增不得叫 `operationCommentList` / `operationCommentS`）。

#### C.4 具名判例

- `decisionDataSources` 为 ADR 冻结名；**永不**新增 `decisionSource`（同一 `userTask` 元素上即**硬域违规**）。

### §2.D 术语 ↔ 标识符

#### D.1 术语回指唯一来源

任何新增**公开类型 / 常量组 / 契约面字段**必须**可回指** `CONTEXT.md` 一个词条或 ADR-0042 一节；无词条者要么先立词条，要么在 §4 登记为「实现细节，不入术语表」。

**回指判据** = 候选标识符**去风格词尾**（`VO` / `Event` / `Type` / `Constants` / `Service`）后的词根，能在 §2.D 受控词根表中查到且**指向唯一词条**。

#### D.2 成对概念词根必须不同

被 ADR / `CONTEXT.md` 钉为「不得合并」的成对概念，其标识符**不得共用同一词根**的变体，**尤其不得靠单复数区分**（与 §2.C 合流）。

#### D.3 派生概念不得落成字段

契约定为**派生事实**的概念**不得**成为 POJO 字段 / 持久化槽位 / JSON 键；若须命名供读侧计算，词尾必须取**判定 / 计算**语义（`…Decision` / `resolve…`），**禁止** `…Status` / `…State`（会把它读成被持久化的属性）。

#### D.4 受控词根表（种表 + 登记程序）

本宪章定**种表**；其余词根由**首次落标识符的那一来源**登记 —— **先登记后使用**，未登记即违规。

**① ADR 已冻结（直接入表，不重定）**：

`DecisionEvidence`（`DecisionEvidenceVO` / `decisionEvidences` / `DECISION_EVIDENCE`）· `DecisionTarget`（`decisionTarget`）· `decisionDataSources` · `decisionEnabled` · `decisionPolicy` · `suggestedAction` · `actionSummary` · `inputSnapshot` · `rawOutput` · `rationaleFacts` · `rationaleNarrative` · `modelId` · `provider` · `chainStage` · `degraded` · `subjectType` · `subjectId` · `subjectName` · `subject` · `outcome`（三叶子态 `SUGGESTION_PRODUCED` / `NO_SUGGESTION_BY_POLICY` / `SUGGESTION_FAILED`）· `completeness` · `truncated` · `redacted` · `schemaVersion` · `failureKind` · `policyReason` · `TaskCreatedEvent` / `onTaskCreated` · `DecisionObserver`

**② 成对概念的互斥裁定（含无词根词条）**：

| 词条（`CONTEXT.md`） | 规定词根 | 说明 |
|---|---|---|
| 决策源 | **无词根** | 永不落标识符；含**永不**新增 `decisionSource` |
| 决策目标 | `DecisionTarget` | ADR-0042 第 7 节 冻结 |
| 撤回 / 作废 | `WITHDRAW` / `INVALID` | 沿用 core `CommentType` 取值，不另造词 |
| 否决 / 覆写 | **均无词根** | 采纳判定里同值不区分（`NOT_ACCEPTED`），不得各自造词 |
| 未产出 | **无词根** | 上位词，**不承载契约取值** ⇒ 不得成为枚举名；契约顶层只取叶子态 |

**③ 无词根清单**（派生 / 判定 / 边界项，**禁止**落标识符）：

`采纳判定`（若读侧必须命名，只许判定语义，见 D.3）· `决策活动` · `通过` · `离开本域` · `未激活`

> **边界（防误伤）**：`未激活` 是**复合派生状态**（来源 = 禁用 或 机制未装配），故无词根；但它的两个**来源与控制面**各自另有标识符，不因本清单被禁 —— 见 ④。
> 与 ADR-0042 §12 同向：**不新增** `late` 状态、**不新增** post-decision advice 类型。

**④ 待登记（有控制面 / 有承载类型，词根由首次落标识符的来源登记）**：

| 词条 | 需标识符的理由 | 登记来源 |
|---|---|---|
| `启用 / 禁用` | 部署期配置状态 ⇒ 配置属性 key | 面 7（模块与构建） |
| `运行暂停` | 运行期暂止的控制面 ⇒ 配置属性 key 或控制方法（其**留痕侧**已由 `policyReason = SUSPENDED` 承载，不另造） | 面 4（拉管线与出站缝） |
| `表态比较面` | 白名单留在 extension（ADR-0042 §2「已记的债」）⇒ 子集常量 | 位点服务与提交模型 |

> 本表是**待登记占位**，不是规定词根；各来源登记前不得使用相关标识符（§2.D.4 登记程序）。
>
> **（2026-09-26）三行均已登记** —— `启用 / 禁用` 见 §4.5 末「配置属性 key 词根（机制级）」行（登记来源 = **模块与构建**）；`运行暂停` 见 §4.5「运行暂停」行（拉管线与出站缝）；`表态比较面` 见 §4.5「表态比较面」行（位点服务与提交模型）。

**登记程序**：面 1–7 任一来源需要新词根 ⇒ 先在 §4 登记（词条 → 词根 → 允许的标识符形态）⇒ 再使用。

### §2.E 命名空间符号与 URI

**E.1 单一常量**：BPMN 扩展属性的**符号名（prefix）与 URI 必须成对定义在单一常量类**，读侧 / 写侧 / 建模侧共用同一常量。**禁止**任何一侧出现裸字面量。

**E.2 URI 唯一性判据（机械可判）**：不得等于 `http://flowable.org/bpmn` 或 `http://activiti.org/bpmn`（引擎保留命名空间，`R6` §4.1 引擎源码核实），且须含本机制的稳定标识路径段。

**E.3 属性名与命名空间**：

- **开独立命名空间**，不塞进 `flowable:` / `activiti:` 保留名。
- 属性名**小驼峰**（同主仓 `isStartTask` 与引擎原生属性的大小写惯例）。
- URI 的**最终字符串**归 `节点声明四属性与 BPMN schema` 定；本宪章只判「成对入常量 + 唯一性 + 不得裸字面量」。

**E.4 反例登记**：主仓 `SkipInitiatorNodeFilter` 读键用**裸前缀字面量** `"flowable"`，而引擎按 **URI** 存储元素级自定义属性；该路径是否命中**未核实**（`R6` §4.2/4.3 源码级差异）。**本机制不复制**，登记于 §4.3。

---

## §3 判定程序

给定一组候选标识符，按序执行；任一步判「不通过」即停在该步并出结论。

| 步 | 动作 | 规则 | 机制 |
|---|---|---|---|
| 1 | **归类**：按 §1.2 类别把候选分栏（含判定其是否只降档受约束） | §1.2 | — |
| 2 | **T1 绝对禁词扫描**（切词后与 §4.1 清单匹配） | §2.B.1 | 机械可判 |
| 3 | **T3 豁免判定**：是否为**取值字面量**？是 ⇒ 跳过步 4 | §2.B.4 | — |
| 4 | **T2 替换测试** + **必填性联动**（通过项若必填 ⇒ 违规） | §2.B.2 / B.3 / B.5.3 | 机械可判 |
| 5 | **形近硬域**（同 BPMN 元素 / 同 Java 类型内） | §2.C.2 | 机械可判 |
| 6 | **形近软域** + **既有豁免比对**（含「不得引入第三变体」） | §2.C.2 / C.3 | 机械可判 |
| 7 | **成对概念词根可分** | §2.D.2 | 文档纪律 |
| 8 | **派生概念不落字段**（含 `…Decision` / `resolve…` 词尾要求） | §2.D.3 | 结构保证 |
| 9 | **术语回指**（受控词根表命中且唯一） | §2.D.1 / D.4 | 文档纪律 |
| 10 | **命名空间与 URI 常量规则**（仅 ④ 类候选） | §2.E | 机械可判 |
| 11 | **冲突裁决**（多规则相抵时定胜负） | §5 | — |
| 12 | **出判定结果表**（表式见 §7.2） | §7.2 | — |

---

## §4 判例、清单与豁免登记表

### §4.1 T1 首版禁词清单（类别闭集，可增删，增删须登记）

| 类别 | 首版词条 |
|---|---|
| ① 厂商 / 公司名 | `OpenAI` · `Anthropic` · `Google` · `Meta` · `Microsoft` · `NVIDIA` · `Mistral` · `Cohere` · `Alibaba` · `Baidu` · `DeepSeek` |
| ② 具体模型家族 / 型号名 | `GPT` · `Claude` · `Gemini` · `Llama` · `Qwen` · `ERNIE` · `PaLM` |
| ③ 具体商业产品名 | `Bedrock` · `AzureOpenAI` · `VertexAI` · 百炼 · 文心 |
| ④ 缩写 / 中文译名 / 变体拼写 | 上列各项的中文译名与常见变体拼写（如「克劳德」一类）；**不含**跨厂商通用能力词（归 T2） |

> **登记格式**：`标识符 / 处置（入 T1 / 出 T1）/ 理由 / 日期`。清单只增不减为常规；「出 T1」须给理由。

**登记**：

| 标识符 | 处置 | 理由 | 日期 |
|---|---|---|---|
| `flowable` | **出 T1（明确不纳入）** | 本仓**自身命名根**（groupId `io.github.flowable.plus`、starter 配置前缀 `flowable.plus.*`、项目名 `flowable-plus`、及本机制 URI `http://flowable.plus/bpmn` 的路径段）；纳入 T1 将使全仓标识符整体违规。§2.B.1「能唯一指向某个厂商或具体产品的词归 T1」在此**让位于「本仓自称」** | 2026-09-25 |

### §4.2 冻结与禁令判例

| # | 判例 | 依据 | 结论 |
|---|---|---|---|
| 1 | `decisionDataSources`（原名 `decisionSources` → 改） | ADR-0042 第 3 节 命名钉死 | 冻结；原名不得回退 |
| 2 | `decisionSource` | ADR-0042 第 3 节 命名钉死 | **永不新增**（硬域形近违规） |
| 3 | `decisionTarget` | ADR-0042 第 7 节 | 冻结 |
| 4 | `DecisionEvidence` / `DECISION_EVIDENCE` / `decisionEvidences` | ADR-0042 第 5 节 | 冻结；命名取自领域语义、不绑 AI |
| 5 | `TaskCreatedEvent` / `onTaskCreated` | ADR-0042 第 9 节 | 冻结；覆盖契约写类型 javadoc + ADR，**不写进名字** |
| 6 | 集合字段用**复数**、单值用**单数** | `R4` 公开 API 事实 | 默认遵循；偏离须登记 |
| 7 | `VO` 后缀；`List<X>` 不用数组；`getXxx` | `R4` 公开 API 事实 | 默认遵循；偏离须登记 |
| 8 | 事件类名 = 过去式**语义事实名** + 域前缀（`Task*` / `Process*`）；回调 = `on` + 去 `Event` | `R2` 9/9 事实 | 默认遵循；偏离须登记 |
| 9 | `CommentType` 新取值 `SCREAMING_SNAKE_CASE`，语义取自词条 | `R1` 命名风格事实 | 冻结 |

### §4.3 形近豁免与反例

| # | 条目 | 判定 |
|---|---|---|
| 1 | 主仓既有 `operationComment` / `operationComments` | **豁免**（既有对冻结）；**不得再引入第三变体** |
| 2 | 主仓 `flowable:isStartTask`（往引擎保留命名空间塞自定义属性） | **不复制**命名空间做法（复制其小驼峰大小写惯例） |
| 3 | 主仓 `SkipInitiatorNodeFilter` 裸前缀字面量 `"flowable"` | **反例，不复制**（见 §2.E.4） |
| 4 | `CommentType` 单一 `switch` 带 `default`（新增取值编译通过、运行时抛异常） | `R1` 已知风险；新取值必须配穷举守卫 |
| 5 | `CommentType` 业务组为**隐式补集**、无显式常量 | `R1` 既有负债；本机制的新组**必须显式**（`EnumSet` + 单一来源），不复制 |

### §4.4 字段面允许项（T2 替换测试通过，须可空）

| 字段 | 通过理由 | 条件 |
|---|---|---|
| `modelId` | 描述 provider 契约事实 | **可空** |
| `provider` | 描述 provider 契约事实 | **可空** |
| `rawOutput` | 描述 provider 契约事实 | **可空** |

### §4.5 词根登记表（面 1–7 先登记后使用）

| 词条 | 规定词根 | 允许的标识符形态 | 登记来源 |
|---|---|---|---|
| （见 §2.D.4 种表） | | | 本宪章 |
| 节点声明（`CONTEXT.md`） | `DecisionNodeDeclaration` | `DecisionNodeDeclaration`（**单一常量类**，并置 `NAMESPACE_URI` / `NAMESPACE_PREFIX` / `DECISION_ENABLED` / `DECISION_DATA_SOURCES` / `DECISION_TARGET` / `DECISION_POLICY`；值为 ADR-0042 第 7 节 冻结的 camelCase 属性名字面量） | 节点声明四属性与 BPMN schema |
| 有效数据源声明（`CONTEXT.md`） | `DecisionContextSource` | `DecisionContextSource`（闭集枚举；**成员集与各自语义**由「出域控制的实现形态」登记） | 节点声明四属性与 BPMN schema |
| 决策证据（`CONTEXT.md`） | `DecisionEvidence` | `DecisionEvidenceVO`（读侧值类型）· `decisionEvidences`（字段）· `DECISION_EVIDENCE`（`CommentType` 取值）· `DecisionEvidenceComment`（证据常量类，与 `CommentType` 同包；成员 `COMMENT_TYPE` / `MARKER_PREFIX` / `MARKER_SUFFIX` / `EVIDENCE_COMMENT_TYPES` / `marker()` / `hasMarker(String)` / `stripMarker(String)`） | 决策证据载体形态 |
| 依据事实（`CONTEXT.md`「决策证据」的「判定依据」面） | `rationaleFact` | `rationaleFacts`（字段，**列表**）· `DecisionRationaleFact`（单条值类型，`key` + `value`）· `DecisionRationaleFactKey`（**闭集枚举**，成员 = `BASIS_CODE` / `POLICY_RULE` / `MISSING_INPUT` / `SCORE` / `SOURCE_REF`；**扩展走框架发版**，生产者不得自定义键） | 决策证据载体形态 |
| 结局 / 政策原因 / 失败类别 / 链路阶段 / 主体 / 完整度（`CONTEXT.md`） | `outcome` · `policyReason` · `failureKind` · `chainStage` · `subjectType` · `completeness` | `DecisionOutcome`（`SUGGESTION_PRODUCED` / `NO_SUGGESTION_BY_POLICY` / `SUGGESTION_FAILED`）· `DecisionPolicyReason`（七值：`NO_SOURCE_DECLARED` / `POLICY_REJECTED` / `MODEL_DECLINED` / `CONTEXT_UNAVAILABLE` / `CREDENTIAL_UNAVAILABLE` / `SUSPENDED` / `OVERLOADED`；方法 `getDescription()` 返回各值的可自诊中文描述、作 C 列证据行的文本兜底依据 —— **2026-10-02 由主仓 #104 补**）· `DecisionFailureKind`（七值；**`INBOUND_PROCESSING_FAILED` 为唯一可在产出态出现者**，须在类型 javadoc 登记）· `DecisionChainStage`（`PRIMARY` / `FALLBACK` / `RULE`）· `DecisionSubjectType`（`AI` / `SYSTEM` / `USER`）· `DecisionCompleteness`（`FULL` / `PARTIAL` / `NO_PAYLOAD` / `RESTRICTED`） | 决策证据载体形态 |
| 直提自述位（`CONTEXT.md`「直提」） | `attestedDataSources` | `attestedDataSources`（**只许复数形态**；元素类型 = `DecisionContextSource`，其成员集由「出域控制的实现形态」登记） | 决策证据载体形态 |
| 方向限定词（标志字段） | ——（**限定词，非独立词根**） | `outboundRedacted` / `outboundTruncated` / `outboundCompleteness` / `inboundRedacted` / `inboundTruncated` / `inboundCompleteness`（词根 `redacted` / `truncated` / `completeness` 不变，`outbound` / `inbound` 为**允许的标识符形态**；不使用裸名形态） | 决策证据载体形态 |
| 幂等身份（`CONTEXT.md`） | `Idempotency` | `idempotencyKey`（**字段**；推面「建议提交模型」与证据 VO「判别 / 承载」组**同源同值**；只许单数形态） | 幂等身份与重放标注 |
| 重放（`CONTEXT.md`） | `Replay` | `resolveReplayOf(DecisionEvidenceVO)`（**方法**；读侧派生面，**永不落字段 / JSON 键 / 持久化槽位**，词尾取**判定 / 计算**语义 —— 见 §2.D.3） | 幂等身份与重放标注 |
| 记录时间（ADR-0042 §5「时间不进 JSON」，**本行新立**） | `recordedTime` | `recordedTime`（**字段**；`DecisionEvidenceVO` 的**读侧专属**字段 —— 写侧恒不填、**不在 JSON 载荷键集内**（写侧不产出该键）、由读侧投影逐行从评论行 `TIME_` 填充）。**类别 ⑦ 的例外**：JSON 键集 = **写侧**字段集，本字段不在其中。**与上「重放」行对照**：同属读侧事实，一个**落字段**（行事实）、一个**禁落字段**（派生判定）；本字段**不是判序输入**（判序输入 = 列表序，「未建序」由 `UnorderedDecisionEvidences` 承载）。**2026-09-29 由主仓 #103 落地补登记** | 主仓 #103（读侧记录时间） |
| 建议提交（`CONTEXT.md`） | `SuggestionSubmission` | `SuggestionSubmissionService`（**位点服务接口**，extension；**独立接口注入、不进 `FlowablePlus` 门面**）· `DefaultSuggestionSubmissionService`（**默认实现**，extension 公开；**2026-09-26 由收口决议补登记** —— 原仅登记接口，装配清单要求它是 Bean 而实现类名无出处）· `SuggestionSubmission`（**建议提交模型**，extension；扁平的调用方自述**写入契约**）· `submit(SuggestionSubmission)`（接口的**唯一公开方法**，返回 `void`）；**词根 `submit` 随本行登记**；**（2026-09-27 由推面位点服务补形态）** `DefaultSuggestionSubmissionService` 的**构造面只取 extension 可见类型** = core `MultiInstanceDetector` Bean + 引擎服务（`TaskService` / `RuntimeService`）+ `DecisionObservationEmitter` + 开关定值；内部件（写入器 / 入站加工 / clamp）**由实现自持、不进构造面**（详见本行归属来源下的实现期登记块） | 位点服务与建议提交模型签名 |
| 准入失败（`CONTEXT.md`，本文新立） | `SuggestionAdmission` | `SuggestionAdmissionException`（**单一 unchecked 异常**；**不继承** core `FlowablePlusException` 一族）· `SuggestionAdmissionReason`（**闭集枚举**，13 值：`TASK_ID_REQUIRED` / `IDEMPOTENCY_KEY_REQUIRED` / `ACTION_REQUIRED` / `ACTION_SUMMARY_REQUIRED` / `ACTION_NOT_COMPARABLE` / `ACTION_NOT_AVAILABLE_FOR_TASK` / `SUBJECT_TYPE_REQUIRED` / `PROVENANCE_GROUP_INCONSISTENT` / `MODEL_ID_NOT_ALLOWED_ON_DIRECT` / `INPUT_SNAPSHOT_NOT_ALLOWED_ON_DIRECT` / `BASIS_CODE_REQUIRED` / `RATIONALE_FACT_INVALID` / `ATTESTED_SOURCES_INVALID`）；**（2026-09-27 由推面位点服务补形态）** `SuggestionAdmissionException` 的载体形态 = **原因 + 锚点上下文**两个只读访问器（`getReason()` / `getTaskId()`；词根均已随本行与「幂等身份」行登记，**不新增词根**） | 位点服务与建议提交模型签名 |
| 表态比较面（`CONTEXT.md`） | `ComparableAction` | `ComparableAction`（**常量类**，extension；私有构造）—— 成员 `MEMBERS`（`EnumSet<ApprovalAction>`，**单一来源**；**公开面静态类型取 `Set`** —— 构造来源仍是 `EnumSet.of(...)`，取 `Set` 是为守住单一来源不被改写，与 `DECLARED_ATTRIBUTES` / `EVIDENCE_COMMENT_TYPES` 同形；**2026-09-27 由推面位点服务如实登记**）· `isComparable(ApprovalAction)`（判定方法）· **（2026-09-27 由推面位点服务补形态）** `isAvailableFor(ApprovalAction, boolean, boolean, boolean)`（**包内**判定方法 —— 可用动作三行映射的承载体，消费者只有位点服务，**不进公开面**） | 位点服务与建议提交模型签名 |
| 决策载荷（`CONTEXT.md`，本文新立；**出域面补字段集形态定稿**） | `DecisionPayload` | `DecisionPayload`（**分段定型外壳**，extension；四段 = `processVariables` / `taskVariables` / `taskMetadata` / `processInstanceMetadata`）· `TaskMetadata`（定型小对象；**出域面补字段集** = `taskId` / `taskName` / `nodeId` / `assignee` / `createTime`）· `ProcessInstanceMetadata`（定型小对象；**出域面补字段集** = `processInstanceId` / `processDefinitionKey` / `businessKey` / `startUserId` / `startTime`）；**未声明的段取 `null`、声明但空取空集合**。两个小对象的字段取**引擎字段的最小子集**、一律可空（除 `TaskMetadata.nodeId` 对应 `Task#getTaskDefinitionKey()` 外，其余与引擎**同名字段**逐一对应）；**不在元数据上开字段级枚举**（字段取舍归**策略的内容选择**），故字段集是**最小基线**、不是「声明面」 | 出域控制的实现形态（**字段集形态定稿**归出域面） |
| 出域策略（`CONTEXT.md`） | `DecisionPolicy` | `DecisionPolicy`（**策略接口**，extension；**不带方向词** —— 词条明文「双向服务」，回指唯一）· `key()`（**出域面补形态** —— ADR-0042 第 6 节「**策略与决策目标同型**（bean + 唯一 `key()` + 节点按 key 引用 + 部署期校验）」；`key()` 的形态已由「决策目标」行登记，本行沿用、不新增词根）· `applyOutbound(DecisionPayload)` · `applyInbound(String)`（**词根 `apply` 随本行登记**；入参 = 冻结字段 `rawOutput` 的形态，裸 `String`）· `DecisionOutboundResult`（`permitted` / `payload` / `record`）· `DecisionInboundResult`（`persistable` / `rawOutput` / `record`） | 出域控制的实现形态 |
| 加工记录（ADR-0042 §6 有词、`CONTEXT.md` 无条目 ⇒ 登记为**实现细节，不入术语表**） | `DecisionProcessingRecord` | `DecisionProcessingRecord`（**只承加工事实** `redacted` / `truncated`，**不承「说不」** —— 「说不」住结果类的显式结果位） | 出域控制的实现形态 |
| 有效数据源声明（`CONTEXT.md`，**成员集与各自语义**） | `DecisionContextSource`（沿用探索期决议行，本行补成员集） | 成员 4 值 `PROCESS_VARIABLES` / `TASK_VARIABLES` / `TASK_METADATA` / `PROCESS_INSTANCE_METADATA`（**承载单元级**）· 枚举字段 `dropPriority`（`int`；**越大越先丢**、留 10 间隔、互异且非空）· **BPMN token = 枚举常量名原文**（不引映射表）· **住所 = core** | 出域控制的实现形态 |
| 拉管线（`CONTEXT.md`，本文新立） | `Pipeline` | `DecisionPipeline`（extension；闸门链与各阶段执行体；**不带方向词** —— `Pull` / `Push` 不得成为第二套方向词） | 拉管线与出站缝的实现形态 |
| 到点信号订阅（实现细节） | ——（**不入术语表**） | `DecisionTaskCreatedListener`（extension；实现 core `ProcessEventListener.onTaskCreated`；**只做纯读门控 + 入队** —— 零引擎命令、零网络、零状态写入） | 拉管线与出站缝的实现形态 |
| 决策目标（ADR-0042 第 7 节 冻结词根，**本行补允许形态**） | `DecisionTarget` | `DecisionTarget`（**接口**，应用实现；`key()` + 接入信息） | 拉管线与出站缝的实现形态 |
| 出站提供方缝（ADR-0042 第 7 节「Provider 缝」，**可整体替换**） | `Provider` | `DecisionProvider`（接口）· `DefaultDecisionProvider`（默认实现）· `DecisionProviderRequest` · `DecisionProviderResponse` | 拉管线与出站缝的实现形态 |
| 出站传输缝（ADR-0042 第 7 节「Transport 缝」，**公开 SPI**） | `Transport` | `DecisionTransport`（接口）· `HttpDecisionTransport`（默认实现）· `DecisionTransportRequest` · `DecisionTransportResponse` | 拉管线与出站缝的实现形态 |
| 凭据材料（`CONTEXT.md`） | `Credential` | `DecisionCredentialResolver`（**单一解析 SPI**，按目标 key **实时**取值、不缓存、缺失合法）· `DecisionCredential`（**不透明类型**：无有效 `toString()`、无状态提取方法、不实现序列化契约） | 拉管线与出站缝的实现形态 |
| 运行暂停（`CONTEXT.md`；宪章 §2.D.4 ④ 待登记项由本文登记） | `RuntimeControl` | `DecisionRuntimeControl`（**接口**：`pause()` / `resume()` / `isPaused()`）；控制面取**控制方法**、**不取配置属性 key**；状态由**应用持有**（进程内内存态），框架不持久化、不带管理端点 | 拉管线与出站缝的实现形态 |
| 装配器与装配结果（ADR-0042 第 6 节/§7；探索期决议未具名） | `Assembly` | `DecisionContextAssembler` · `DecisionAssemblyResult`（字段 `emptyAssembly` + `payload` + **`droppedContextSources`（出域面补形态）**；**空装配是显式事实**，不得由「四段全 `null`」反推；`droppedContextSources` = 有效数据源集之外的来源，即**声明面最小化的基线计数**，**非标志**、不计入任何标志，只作可观测计数） | 拉管线与出站缝的实现形态（**代定**，已回写探索期决议；`droppedContextSources` 归出域面） |
| 出站响应契约的**显式不产出位** | ——（**字段**，非独立词根） | `declined`（模型主动不产出，**走过**一次出站调用；与 `suggestedAction` **必居其一**；两者皆缺或皆在 ⇒ `RESPONSE_UNPARSEABLE`）· **`policyReason`（本地短路，**未**发起出站调用；**2026-10-02 由主仓 #104 补** —— 与 `declined` / `suggestedAction` 三者必居其一，取值收窄为本地短路两值）** · 工厂 `localShortCircuit(DecisionPolicyReason)`（**2026-10-02 由主仓 #104 补**；**不新增词根** —— 同 `declined`，只是本地短路事实在响应契约上的入口；`policyReason` 词根已由「结局 / 政策原因…」行登记，不重复）** | 拉管线与出站缝的实现形态（本地短路通道归 **主仓 #104**） |
| 配置属性 key 词根 | `timeout` · `budget` · `attempts` · `backoff` · `executor` | `flowable.plus.decision.*` 下：`outbound-connect-timeout` / `outbound-read-timeout` / `total-budget` / `max-attempts` / `backoff.*` / `executor.*`；**`启用 / 禁用` 词根与全局开关 key 仍归面 7**（本行不登记） | 拉管线与出站缝的实现形态 |
| 实现细节（**不入术语表**，允许实现期使用、不参与公开命名表） | —— | `DecisionEvidenceWriter`（extension **内部**写入器，物质化两类未产出；**证据写入与提交模型补方法形态** —— 包内可见的 `materialize(DecisionEvidenceDraft)`（物质化一条证据：判列 → 逐格校验矩阵 → 推导方向标志与完整度）、`row(DecisionEvidenceVO)`（交整行「标记 + ASCII-safe JSON」，写入前施加尺寸护栏）、成员常量 `SCHEMA_VERSION` = 1）· **`DecisionEvidenceDraft`（证据写入与提交模型补形态；extension 包内，一次书写的显式事实载体 = 调用方自述子集 + 两个方向「到底有没有载荷」的显式事实 + 加工事实；字段集见同批披露①）** · `DecisionInboundProcessor`（extension **内部**，入站载荷卫生 + 硬上限 clamp；与直提共用 **clamp**、**不**共用策略；**出域面补方法形态** —— 包内可见的 `process(String)`（只施加 clamp：超限 ⇒ `null`），构造只持 `DecisionClamp`、**不持** `DecisionPolicy`）· `DecisionNodeDeclarationReader`（extension **内部**，运行期读节点声明；**节点声明与部署期校验补方法形态** —— 包内可见的 `mechanismAttributes(BaseElement)` / `mechanismExtensionElements(BaseElement)` / `declaredValue(BaseElement, String)` / `declaredKey(BaseElement, String)` / `parseEnabled(String)` / `splitDataSourceTokens(String)` / `parseDataSourceToken(String)`，读侧与校验侧共用同一份解析，不在两处各写一遍）· **`DecisionClamp`（出域面补形态；extension 包内，出域「整段丢弃」与入站「整串丢弃」共用同一 32 KiB 硬上限；成员 `MAX_PAYLOAD_BYTES` = 32,768 字节（两方向同一上限的唯一住所）、`clampOutbound(DecisionPayload)` / `clampInbound(String)`；「只许调低、不可放大」= 构造期 `Math.min(请求值, 硬上限)`）** · **`DecisionClampRejectedException`（出域面补形态；包内异常，丢到全空仍超时的兜底拒绝信号）** · **`DecisionContextSnapshot`（出域面补形态；extension 包内，单次引擎命令内读到的四份原始上下文）** | 拉管线与出站缝的实现形态（`DecisionClamp` / `DecisionClampRejectedException` / `DecisionContextSnapshot` 与 `DecisionInboundProcessor.process(String)` 归**出域面**；`DecisionEvidenceWriter` 与 `DecisionEvidenceDraft` 归**证据写入与提交模型**） |
| 决策观测事实（`CONTEXT.md`「可观测面」，本文新立） | `Observation` | `DecisionObservation`（**extension 公开类型**；瞬时、不落盘）· 字段 **16** 个（`taskId` / `nodeId` / `processInstanceId` / `outcome` / `failureKind` / `policyReason` / `severity` / `subjectType` / `modelId` / `chainStage` / `latencyMs` / `inputTokens` / `outputTokens` / `writeDegradedCause` / `admissionReason` / `droppedContextSources`） | 可观测性的实现形态 |
| 决策观测事实的消费 SPI（ADR-0042 第 10 节 冻结词根 `DecisionObserver`，**本行补允许形态**） | `DecisionObserver`（沿用） | `DecisionObserver`（**普通接口**，extension）· `onDecision(DecisionObservation)`（**唯一方法**；沿用 §4.2 判例 8「回调 = `on` + 去 `Event`」） | 可观测性的实现形态 |
| 严重度（ADR-0042 第 10 节 错误分类两轴之一） | `Severity` | `DecisionSeverity`（**公开枚举**：`ERROR` / `WARN` / `INFO`；由构造点固定填入，亦为日志级别映射源）· 字段 `severity` | 可观测性的实现形态 |
| 可观测信号（ADR-0042 第 10 节「可观测面」） | `Metrics` | `DecisionMetrics`（**单一常量类**，extension；**只出常量、不出类型**）—— 信号名 **9** 个（`FLOWABLE_PLUS_DECISION_FAILURE` / `…_NO_SUGGESTION_BY_POLICY` / `…_REPLAY` / `…_CONTEXT_SOURCE_DROPPED` / `…_LATENCY` / `…_TOKENS` / `…_TOKENS_USAGE_MISSING` / `…_WRITE_DEGRADED` / `…_SUBMISSION_REJECTED`；**名不带 `METRIC` / `COUNTER` 一类后缀**）· 维度键 **10** 个（常量名 `FAILURE_KIND` / `SEVERITY` / `NODE_ID` / `SUBJECT_TYPE` / `POLICY_REASON` / `DIRECTION` / `MODEL_ID` / `CAUSE` / `REASON` / `CONTEXT_SOURCE`，值 = 冻结名的小驼峰字面量）· 闭集值（`direction` ∈{`input`, `output`}；`cause` ∈{`anchor_lost`, `instance_ended`}；`reason` 与 `contextSource` 取**枚举常量名的小写蛇形**）· logger 名常量（单一来源，供下游按前缀配 appender / 级别）—— **（工程基座补形态）** 具名为 `LOGGER_NAME`（值 = `io.github.flowable.plus.extension.decision`）· 换算方法 `tagValue(Enum<?>)`（枚举常量名 → 小写蛇形，**单点换算**，`reason` / `contextSource` 共用同一换算、不在两处各写一遍）· 闭集值常量名 `DIRECTION_INPUT` / `DIRECTION_OUTPUT` / `CAUSE_ANCHOR_LOST` / `CAUSE_INSTANCE_ENDED` | 可观测性的实现形态 |
| 未物质化（`CONTEXT.md` 三处不得混） | `writeDegradedCause` / 复用 `SuggestionAdmissionReason` | `writeDegradedCause`（**字段**；`ANCHOR_LOST` / `INSTANCE_ENDED`）· `admissionReason`（**字段**；`SuggestionAdmissionReason`，未物质化的准入失败下只取 `IDEMPOTENCY_KEY_REQUIRED`） | 可观测性的实现形态 |
| 用量未知 / 用量不适用（`CONTEXT.md`「可观测面」，本文新立） | ——（**无词根**，由信号 `…tokens.usage.missing` 承载） | ——（**不落标识符**；`unknown` 标记值形态已废） | 可观测性的实现形态 |
| 配置属性 key 词根（机制级） | `enabled`（沿用主仓既有开关词根 `flowable.plus.enabled` / `flowable.plus.event.enabled`） | `flowable.plus.decision.enabled`（**全局启用开关**；宪章 §2.D.4 ④ 待登记项由本文登记；**默认 `false`**，**不参与装配条件**故无 `matchIfMissing`）；其余 11 键的词根已由「拉管线与出站缝」登记 | 模块与构建 |
| 机制配置载体 | `Decision`（**机制级载体**；回指 **ADR-0042 §7**「全局默认面与 fail-closed」，非 `CONTEXT.md` 词条） | `FlowablePlusDecisionProperties`（starter 公开属性类，`@ConfigurationProperties("flowable.plus.decision")`）· `ExecutorProperties` / `BackoffProperties`（**`public static` 嵌套类**；取 `…Properties` 词尾以避与 JDK `java.util.concurrent.Executor` 同名遮蔽） | 模块与构建 |
| 机制装配配置类 | ——（沿用主仓 `FlowablePlus*AutoConfiguration` 形态） | `FlowablePlusDecisionAutoConfiguration`（运行组件）· `FlowablePlusDecisionValidationAutoConfiguration`（主闸 validator + 启动期复核） | 模块与构建 |
| 节点声明校验器（沿用 §4.5「节点声明」行词根，**本行补允许形态**） | `DecisionNodeDeclaration`（沿用） | `DecisionNodeDeclarationValidator`（extension 公开类，**无 Spring**）· 成员 `VALIDATOR_SET_NAME`（值为字面量 `flowable-plus-decision`，会经 `ValidationError.validatorSetName` 出现在部署失败信息里）· `INVALID_DECLARATION_PROBLEM`（**节点声明与部署期校验补形态**；值为字面量 `flowable-plus-decision-node-declaration-invalid`，会经 `ValidationError.problem` 出现在部署失败信息里 —— 本机制全部声明违规共用一个问题码，逐条规则的区别落在描述里） | 模块与构建 |
| 应用级默认数据源集（`CONTEXT.md`「有效数据源声明」的应用级一侧，**本文新立**） | `DecisionDefaultContextSources` | `DecisionDefaultContextSources`（**定型值类型**，应用提供的单一可选 Bean；内在集合取 `Set<DecisionContextSource>`；**缺失或空集一律按空集**，禁隐式全集兜底） | 模块与构建 |
| 决策指标记录器（沿用 §4.5「可观测信号」行词根，**本行补允许形态**） | `Metrics`（沿用） | `DecisionMetricsRecorder`（extension **公开接口**，机制专属替换点）· `record(DecisionObservation)`（**唯一方法**；**工程基座补形态**）· 默认实现 **住包内、不住公开面**（不入 §1.2 类别 ①） | 模块与构建 |
| 证据读写护栏（沿用 §4.5「决策证据」行词根） | `DecisionEvidence`（沿用） | `DecisionEvidenceWriteGuard`（成员 `MAX_EVIDENCE_BYTES`）· `DecisionEvidenceReadGuard`（成员 `MAX_NESTING_DEPTH` / `MAX_PARSE_BYTES`）；均 core 具名常量、**非可配置** | 模块与构建 |
| 实现细节（**不入术语表**，允许实现期使用、不参与公开命名表） | —— | 指标默认实现 `DefaultDecisionMetricsRecorder`（starter **包内可见**）· 两个注册表类型（决策目标 / 出域策略，starter **包内**）· 框架硬上限常量类 `DecisionGuardrails`（starter **包级可见顶层类**；只承载十个**数值**键的硬上限、属性类字段初始化器**直接引用**其常量、「超上限 = `Math.min` 收口 ＋ 启动期 `WARN`」；**完整的住所与边界 B1–B4 见 `docs/impl/0042-module-and-build.md` §2.4 附**）· read / write 护栏的**私有静态判空**方法 | 模块与构建 |
| 任务新建事件（`CONTEXT.md` 无条目；回指 **ADR-0042 第 2 节定案 9** 的事件覆盖范围） | `TaskCreated`（**沿用** §2.D.4 ① 已冻结词根，**不新增词根**） | `TaskCreatedEvent`（事件类型，ADR 冻结）· `onTaskCreated`（回调，ADR 冻结）· **`taskCreated(...)`（`EventBus` 语义方法 —— 沿既有 9 方法同形「事件名去 `Event` 后小驼峰」，本行补该形态）** | core 四行残留与读侧投影的实现形态 |
| 实现细节（**不入术语表**，允许实现期使用、不参与公开命名表） | —— | 读侧证据行投影器 `DecisionEvidenceRowProjector` · 「新就绪」任务发射器 `NewlyReadyTaskEmitter`（均 core `…core.workflow` **包内可见**）· 重放判定共用实现 `DecisionReplayJudge`（core `…core.vo` **包内可见**；两个 VO 的 `resolveReplayOf` 共用同一实现，避免同一逻辑在两处各写一遍） | core 四行残留与读侧投影的实现形态 |
| 决策观测事实的三面分发点（回指 **ADR-0042 第 10 节**「单一构造点 / 消费三层」，非 `CONTEXT.md` 词条） | `Observation`（沿用） | `DecisionObservationEmitter`（**extension 公开类型**，单一构造点的出口）· `emit(DecisionObservation)`（**唯一公开方法**）· 包内可见的 `LOG_FORMAT`（固定字段名的日志格式串）与 `logArguments(DecisionObservation)`（日志面实参）—— **测试可见面，降档**（§1.1：包内实现细节只受 §2.B-T1） | 工程基座与观测一族 |
| 结局映射表（行集来源 = 拉管线与出站缝决议 §13；回指 **ADR-0042 第 10 节**「计错判据」） | `outcome`（沿用） | `DecisionOutcomeMapping`（**extension 公开枚举**；**键 = 产生点**，产出观测的 **20** 行 —— **2026-10-02 由主仓 #104 补本地短路两行，18 → 20**）· 成员 `ROW_COUNT`（= **20** 的**对账常量**，不随表长推导）· 行字段 `outcome` / `failureKind` / `policyReason` / `severity` / `retryable` / `evidenceRow` | 工程基座与观测一族（本地短路两行归 **主仓 #104**） |
| 写入期降级原因（回指 **ADR-0042 第 9 节第 10 条**「两个写入失败槽位」+ 字段 `writeDegradedCause`） | `writeDegradedCause`（沿用） | `WriteDegradedCause`（**extension 公开枚举**：`ANCHOR_LOST` / `INSTANCE_ENDED`；**不是结局**、不产生失败记录，只降级日志 + 独立计数） | 工程基座与观测一族 |

> **本文新增的登记与偏离**：登记 6 行（上表末 6 行），**§4.6 无新增偏离**。`DIRECT_BASIS`（及任何 `DIRECT_*` 反向词形态）作为候选**判不通过**：直提性是结构性事实，**永不落标识符**（ADR-0042 §3 派生 2 + 本宪章 §2.D.3）。

> **此后新增的登记与偏离（幂等身份与重放标注）**：登记 2 行（上表末 2 行）；**§4.6 无新增偏离**。两条**落选候选**（审计留痕，均判**不通过**、**不**登记豁免）：`submissionKey` —— 词根 `submission` 未登记，且「建议提交」模型的命名归「位点服务与建议提交模型」（撞 §2.D.1 回指 + §2.D.4 登记程序）；`decisionKey` —— 与既有 `Decision*` 词根同域，回指**不唯一**（会被读成「决策的键」而非「一次提交的身份」，撞 §2.C.2 软域 + §2.D.1）。

> **命名判例（随本文登记）**：原候选 `DecisionDataSource` 与 ADR 冻结的 BPMN 属性名 `decisionDataSources` 构成 `+s` 屈折 ⇒ §2.C.1 + §2.C.2 **软域形近**，判**不通过**；改名方向 = 插入**非屈折词干** `Context`，且词干直接取自 `CONTEXT.md`「有效数据源声明」正文的「上下文来源」。**不**登记豁免。

> **此后新增的登记与偏离（位点服务与建议提交模型签名）**：登记 3 行（上表末 3 行）；**§4.6 无新增偏离**。三条**判例留痕**：
>
> ① **常量名 `ACTIONS`** —— **规则上判通过**（§2.C.1 归一后与类型名 `ComparableAction` 不构成屈折对），处置 = **预防性改名 → `MEMBERS`**，理由 = 避免在词根周边形成单复数屈折噪声（主仓维护者裁量，**非规则命中**）。
>
> ② **枚举取值 `MODEL_ID_REQUIRED`** —— **命名本身不命中任何规则**，处置 = **撤销、不建**（**不**登记豁免）。理由：该取值的判据「**非直提 ∧ Provider 缝解析到模型标识**」中，「缝是否解析到」**只有 Provider 缝可知**；位点服务拿到 `modelId == null` 时无法区分「缝没解析到」与「缝漏填」，§2.D.3 又禁止为它加「是否解析到」的派生字段 ⇒ 该取值在位点服务侧**无触发点**。**语义义务保留**（「非直提且 Provider 缝解析到模型标识时应必填」）；其**守卫判定点**改登记于「拉管线与出站缝」。
>
> ③ **枚举取值 `ANCHOR_TASK_ID_REQUIRED` → `TASK_ID_REQUIRED`** —— **简化，非偏离**。理由：锚点字段名 `taskId` 已由 ADR-0042 §5 冻结，不以「锚点」词根另起重复前缀。

> **此后新增的登记与偏离（core 残留四行的实现形态）**：**§4.6 无新增偏离**；§4.5 的「决策证据」登记行内成员由 `COMMENT_TYPES` **改名 `EVIDENCE_COMMENT_TYPES`**。**判例留痕（订正）**：原候选 `COMMENT_TYPES` 与同一类型内的公开常量 `COMMENT_TYPE` 归一去分隔符、统一小写后构成 `+s` 屈折对 ⇒ §2.C.1 + §2.C.2 **硬域**违规；处置 = **改新增侧的名**（§5.1 第 3 条），**不**登记豁免。**订正披露**：`决策证据载体形态` 的七列判定表把该对判为「通过（§2.C.2 硬域）」系**误判** —— 该两成员**未**被 ADR 冻结（ADR-0042 第 5 节 只点名 `MARKER_PREFIX` / `MARKER_SUFFIX`（私有）与 `marker()` / `hasMarker(String)` / `stripMarker(String)`），故 §5.1 第 1 条的冻结保护**不适用**。改名依据 = 与既有 `OPERATION_COMMENT_TYPES` 平行（`evidencecommenttypes` 与 `commenttype` 归一后**不构成屈折对**）。

> **此后新增的登记与偏离（出域控制的实现形态）**：登记 **3 新行 + 1 行补成员集**（上表末 4 行）；**§4.6 无新增偏离**。**判例留痕（三条）**：
>
> ① **方向词唯一性（本次唯一被改名的判例）**：探索期决议已把方向限定词登记为 **`outbound` / `inbound`**（六个标志字段）。本文草拟结果类型时曾用 `Egress` / `Ingress` —— 判定为**同一概念的第二套方向词**，使「成对概念」既分立又各自另起一套，撞 §2.D.2 的立意 ⇒ 处置 = **全量改为 `outbound` / `inbound`**（`DecisionOutboundResult` / `DecisionInboundResult`），**不**登记豁免。
>
> ② **`DecisionPolicy`（类型）与 BPMN 属性名 `decisionPolicy` 归一后恒等** ⇒ **非屈折**（§2.C.1 只判 `+s` / `+es` / `y→ies`），**不构成** §2.C.2 硬域违规；且二者分属**不同硬域**（Java 类型 vs BPMN 元素属性）。依据同探索期决议判例「常量名与其值的字面量归一后同形，恒等而非屈折」。
>
> ③ **落选候选 `DecisionEgressPolicy` / `DecisionIngressPolicy`** —— **判不通过**：词条「出域策略」明文「**双向服务**」，带方向词会把它读成单向。处置 = **撤销、不建**（**不**登记豁免）。
>
> **枚举成员硬域自检**：四成员归一后 `processvariables` / `taskvariables` / `taskmetadata` / `processinstancemetadata` —— **无屈折对**（§2.C.1）⇒ §2.C.2 硬域干净。**跨类型同形不违规**：`taskMetadata`（载荷字段）与 `TASK_METADATA`（枚举成员）分属**不同类型** ⇒ 不在同一硬域。

> **此后新增的登记与偏离（拉管线与出站缝的实现形态）**：登记 **6 个类型词根**（`Pipeline` / `Provider` / `Transport` / `Credential` / `RuntimeControl` / `Assembly`）+ **5 个配置属性 key 词根**（`timeout` / `budget` / `attempts` / `backoff` / `executor`）+ **3 行实现细节**（不入术语表）+ `DecisionTarget` 一行**补允许形态**（上表末 11 行）；**§4.6 无新增偏离**。**判例留痕（三条）**：
>
> ① **落选候选 `sourceGroup`** —— **判不通过**：撞 §2.D.1「回指唯一」—— §2.D.4 ② 已把**决策源**钉为「**无词根**、永不落标识符」，`sourceGroup` 会被读成「**决策源**的分组」（`CONTEXT.md` 亦无「出处组」词条可回指）。处置 = **撤销、不建**（**不**登记豁免）；出处组三字段（`provider` / `chainStage` / `degraded`）在**出站响应契约**内**平铺**承载 —— 三名均已由 ADR 冻结，平铺可完全规避新增类型名与「组」这一层的词条代价。
>
> ② **`Pull` / `Push` 不作为方向词引入** —— 沿用探索期决议判例 ① 的教训（**同一概念不得有第二套方向词**）：拉管线执行体取名**不带方向词**（`DecisionPipeline`），拉 / 推的区分由词条承载、不落标识符。
>
> ③ **`declined` 判通过** —— 类别 ⑦（JSON 字段名；**出站响应契约**字段，非证据 VO 同源项）。不含 §2.B.2 的 T2 域词 ⇒ **不触发** §2.B.3 的「可空」附加条件；§2.B.5.1 的反例判据**不适用**（该字段只活在出站响应契约内，不属 ADR 第 5 节的必填 / 可空矩阵）。**不登记新词根**（`decline` 语义由既有 `policyReason` 取值 `MODEL_DECLINED` 承载，本字段只是该事实在响应契约上的**显式位**）。
>
> **此后新增的登记与偏离（主仓 #104，Provider 缝本地短路通道）**：登记 **3 处补形态**（「结局 / 政策原因…」行补两取值 + `getDescription()` 方法、「出站响应契约的显式不产出位」行补 `policyReason` 字段与 `localShortCircuit(...)` 工厂、「结局映射表」行 18 → 20）；**§4.6 无新增偏离**。判例留痕（两条）：
>
> ① **枚举方法 `getDescription()`** —— 只读返回该取值的中文说明、**不承载任何判定**（§2.D.3 禁的是「派生概念落字段」与方向词兼职，本方法两者都不沾）；**不登记新词根**，回指「政策原因」词条。类别 ⑦（JSON 字段名）不适用 —— 它不出现在任何载荷。
>
> ② **工厂 `localShortCircuit(DecisionPolicyReason)`** —— 回指唯一（「本地短路」是本次新立的**出站响应契约概念**，不撞既有词条）；**取值收窄**（只收本地短路两值）由工厂在构造期强制；「本地短路」**不落独立枚举 / 字段 / 词根**（不为单一取值族另起类型，与「决策源」无词根同理）。
>
> **硬域自检**：本次新增的 extension 公开标识符归一后两两比较 —— `decisionproviderrequest` / `decisionproviderresponse`、`decisiontransportrequest` / `decisiontransportresponse`、`decisioncredential` / `decisioncredentialresolver`、`decisionassemblyresult` / `decisioncontextassembler`、`decisionpipeline` / `decisionpolicy`（同前缀不同词根）**均不构成屈折对**（§2.C.1）；`DecisionTarget`（类型）与 BPMN 属性名 `decisionTarget` 归一后**恒等而非屈折**、且分属不同硬域（依据同探索期决议判例 ②）。**跨硬域同形不违规**：响应契约字段 `provider` / `chainStage` / `degraded` / `modelId` 复用证据 VO 冻结名，分属不同硬域。

> **此后新增的登记与偏离（可观测性的实现形态）**：登记 **6 行**（上表末 6 行）；**§4.6 无新增偏离**。**判例留痕（四条）**：
>
> ① **`DecisionObservation` 与 `DecisionObserver`** —— 归一后 `decisionobservation` / `decisionobserver` **非屈折**（§2.C.1 只判 `+s` / `+es` / `y→ies`；二者**不同词根**），**不构成** §2.C.2 硬域违规；依据同探索期决议判例 ② 与「同前缀不同词根」的既有口径。**登记为判例**，供日后在 `DecisionObserv*` 前缀上新增标识符时比对。
>
> ② **信号名的下划线 → 点分隔重规范化**（`decision.no_suggestion_by_policy` → `flowable.plus.decision.no.suggestion.by.policy`）—— 属**命名换算**（探索期决议已明写「最终字符串归实现期」的示例），**不登记偏离**，只作**判例留痕**：本机制信号名一律**全小写、点分隔**。
>
> ③ **`unknown` 标记值形态的废除** —— 探索期决议的「usage 缺失记 `unknown`」在本次落定中被**独立计数**（`…tokens.usage.missing`）取代；该字面量**永不**出现在信号名或维度值中（`CONTEXT.md`「用量未知」的 `_Avoid_` 已挡）。**不登记偏离**（属实现期形态定稿）。
>
> ④ **同族值域两风格并存（有意）** —— BPMN token 取**枚举常量名原文**（探索期决议定），指标 tag value 取**枚举常量名的小写蛇形**（本文定：`cause` / `reason` / `contextSource`）。二者分属**建模面 token** 与**观测面 tag value** 两个不同的硬域与消费者，**有意不统一**；登记以免日后被读成疏漏。
>
> **硬域自检（本文）**：新增公开标识符归一后两两比较 —— `decisionobservation` / `decisionobserver` / `decisionseverity` / `decisionmetrics` **均不构成屈折对**；常量类内 9 个信号名与 10 个维度键常量亦**无屈折对**；`CONTEXT_SOURCE`（维度键）与 `…_CONTEXT_SOURCE_DROPPED`（信号名）归一后为**包含而非屈折**，不违规。

> **此后新增的登记与偏离（验证落点的实现形态）**：**§4.6 无新增偏离**；本文不新增任何**公开**标识符（其产物 = 测试类与断言名，住所见 `docs/impl/0042-verification-landings.md`），故 §4.5 **不新增行**。**判例留痕（一条，依据 §1.1 推断，不改规则文本）**：
>
> **`src/test` 类型（测试类 / stub / fixture）不受 §2.C 形近与 §2.D 术语回指的约束，仍受 §2.B-T1 绝对禁词约束。** 依据 = §1.1 末句「面 1–7 的**私有**实现细节……**仍受** §2.B-T1」，测试类型属该类；而 §1.1 的适用面（§1.2 的 ①–⑦ 覆盖类别）以**公开面**为对象，故 §2.C / §2.D 对其不适用。**直接后果**：面 6 的标识符宇宙（`docs/impl/0042-verification-landings.md` §1.4）**须含 `src/test` 新增类型**，且 `#testTypesStillSubmitToT1()` 为该义务的机械落点。登记以免日后被读成疏漏，或反过来被误扩到「测试类也要过 §2.C 形近」。

> **此后新增的登记与偏离（模块与构建）**：登记 **8 行**（行号见上表末 8 行）；**§4.6 无新增偏离**。**判例留痕（四条）**：
>
> ① **`Micrometer` 不入 §4.1 T1 清单**（明示处置 + 理由）—— T1 判据是「**能唯一指向某个厂商或某个具体产品的词**」；在指标库语境下 `Micrometer` 确实唯一指向某个具体产品，**但** `micrometer` 本义是通用物理名词（测微计），入 T1 将连带禁掉一切含该词的合法标识符（含 JDK / 生态既有名），代价远大于收益。**处置 = 不入 T1，但钉一条纪律：本机制的默认实现一律取中立词**（`Default…` 形态），**不得**使用任何指标后端的产品词命名。判例依据 = §2.B.1 的「能唯一指向」是**语境相关**判据，此处以「通用名词优先」让位。
>
> ② **`Set<DecisionContextSource>` 的登记（非偏离）** —— §4.2 判例 6 / 7 的 `List<X>` 是**集合容器的惯例**，未规定「必须 `List`」；本处取 `Set` 是因为**重复的数据源声明无意义**（节点侧 `decisionDataSources` 的重复 token 已是**部署期阻断项**），且 `DecisionContextSource` 各成员带互异的 `dropPriority`。**不登记偏离**，登记为判例留痕以免日后被读成疏漏。
>
> ③ **`DecisionEvidenceReadGuard.MAX_PARSE_BYTES` 的命名订正**（一致性复核结论，非重开）—— 起草中曾拟 `MAX_PAYLOAD_BYTES`。**订正**为 `MAX_PARSE_BYTES`：`payload` 在语料中是 `DecisionPayload`（**决策载荷**）与「出域载荷」的**专名**（`CONTEXT.md` 有专条），用它指「证据行 JSON」会撞 §2.D.1「**回指唯一**」。订正后读侧两常量与写侧 `MAX_EVIDENCE_BYTES` 各指**不同的事实**（允许解析 vs 允许写入）。
>
> ④ **本文新增公开类型名的软域自检** —— `decisionnodedeclarationvalidator` / `decisionevidencereadguard` / `decisionevidencewriteguard` / `decisiondefaultcontextsources` / `decisionmetricsrecorder` / `flowableplusdecisionproperties` / `executorproperties` / `backoffproperties` / `flowableplusdecisionautoconfiguration` / `flowableplusdecisionvalidationautoconfiguration` 归一后两两比较**无屈折对**；`decisionmetricsrecorder` 与既有 `decisionmetrics` 归一后为**包含而非屈折**（依据同「可观测性的实现形态」判例 ①）；跨硬域同形不违规（`ExecutorProperties` 类型名 vs 配置 key 段 `executor`）。

> **此后新增的登记与偏离（八靶子具名击穿实验）**：**§4.5 不新增行、§4.6 无新增偏离** —— 本文新增的标识符**全部住 `extension/src/test`**（坐标常量 `DecisionBreachExperiments` 及其字段 `EXPERIMENTS` / `VARIANTS`、元守卫 `DecisionBreachExperimentsTest`），依上一条判例「`src/test` 类型不受 §2.C 形近与 §2.D 术语回指的约束，**仍受 §2.B-T1 绝对禁词**约束」，故**不登记词根行**。**判例留痕（两条）**：
>
> ① **对 Q14 第 4 项措辞的口径收窄（显式披露）** —— 起草时拟「§4.5 登记词根 `Breach` / `Variant`」；据上条判例，`src/test` 类型**不受 §2.D 回指约束**，登记词根会与判例冲突（把测试类型读进受控词根表）。**收窄为**：§4.5 不新增行，只作本判例留痕 + T1 自检。**这不是规则修订**，是既有判例的直接适用。
>
> ② **T1 自检（`src/test` 类型仍受）** —— `decisionbreachexperiments` / `experiments` / `variants` / `decisionbreachexperimentstest` 与 **6 个新增断言名**（`experimentsAreExactlyTheEightNamedTargets` / `everyExperimentNamesItsMainLanding` / `mainLandingsCoverAllFourInvariantsAndStayDistinct` / `variantsCarryTheirMotherExperimentName` / `experimentsCarryNoVerdictField` / `breachLandingSourcesAvoidPrivateStateAccess`）逐一切词，**无 §4.1 T1 禁词**（无厂商 / 具体型号 / 具体产品及其缩写译名）。**硬域自检**：`EXPERIMENTS` / `VARIANTS` 归一后 `experiments` / `variants` **非屈折**（§2.C.1 只判 `+s` / `+es` / `y→ies`；二者**不同词根**）。
>
> **判定结果表**（§7.2 表式）：本文的分类结论为「**全部候选均属测试类型 ⇒ §2.B-T1 适用、§2.C / §2.D 不适用 ⇒ 一律 `通过`**」，逐条判定表随**探索期决议**成文（同 §7.2「决议内附」的既有口径）。

> **此后新增的登记与偏离（收口：方案成文 + 开工闸门）**：§4.5 的「建议提交」登记行**补 1 个允许形态**（`DefaultSuggestionSubmissionService`，默认实现，extension 公开）；**§4.6 无新增偏离**。依据 = 装配清单要求位点服务是 Bean（`docs/impl/0042-module-and-build.md` §2.2 行 6），而原登记**只有接口** —— 主仓纪律「先登记后使用」，故由收口决议**补登记后使用**。**判例留痕（一条）**：
>
> ① **`DefaultSuggestionSubmissionService`（候选，通过）** —— 与 `SuggestionSubmissionService` 归一后 `defaultsuggestionsubmissionservice` / `suggestionsubmissionservice` **非屈折**（§2.C.1 只判 `+s` / `+es` / `y→ies`，加前缀不属）；T1 切词**无禁词**。形态与既有默认实现族同形（`DefaultDecisionProvider` / `HttpDecisionTransport` / `DefaultDecisionMetricsRecorder`）。**落选候选**：`SuggestionSubmissionServiceImpl` —— 判**不通过**：与同族默认实现的命名形态不一致（该族一律取 `Default…` 前缀），且 `Impl` 后缀在本机制语料内无先例。处置 = **撤销、不建**（**不**登记豁免）。
>
> **判定结果表**（§7.2 表式）：见本文件 §7.2。

> **此后新增的登记与偏离（core 四行残留与读侧投影的实现形态）**：§4.5 **补 1 个允许形态 + 新增 1 行实现细节**；**§4.6 无新增偏离**。逐条：
>
> ① **`EventBus.taskCreated(PlusTask, Date)`（形态补登记，非新词根）** —— §2.D.4 ① 已冻结 `TaskCreatedEvent` / `onTaskCreated`；`EventBus` 的语义方法名沿**既有 9 方法同形**（事件名去 `Event` 后小驼峰：`taskCompleted` / `processStarted` …）⇒ `taskCreated` 是同一词根的**方法形态**，本行只把它补进登记，**非新增词根、非改名**。
>
> ② **三个 core 包内实现细节（不入术语表、不登记词根）** —— 读侧证据行投影器、「新就绪」任务发射器、重放判定共用实现；均住 `io.github.flowable.plus.core.{workflow,vo}`、**包内可见**；依 §1.1 只受 §2.B-T1，§2.C / §2.D 对其**不适用**。登记以免日后被读成疏漏。
>
> **判例留痕（一条）**：本文新增的**测试类型**（`TaskCreatedEventContractTest` / `HistoryWorkflowEvidenceReadTest`）与**源码扫描支撑类的可见性放宽**（`SourceScanSupport` 及其 `Hit` 由包内改公开，供跨包读侧守卫复用同一份防空转扫描）均属 §1.1 判例「`src/test` 类型不受 §2.C / §2.D、**仍受 §2.B-T1**」的直接适用，**不登记词根行**。
>
> **T1 自检**：`taskcreated` / `decisionevidencerowprojector` / `newlyreadytaskemitter` / `decisionreplayjudge` / `sourcescansupport` 及本文全部新增断言名（`C1` / `C2` / `C4` / `C7` 四类，清单见 `docs/impl/0042-verification-landings.md` §2 相应行的断言名形态）逐一切词，**无 §4.1 T1 禁词**（无厂商 / 具体型号 / 具体产品及其缩写译名）。
>
> **判定结果表**（§7.2 表式）：
>
> | 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
> |---|---|---|---|---|---|---|
> | core 四行残留与读侧投影 | `TaskCreatedEvent` · `onTaskCreated` | ① ③ | §2.B.1 · §2.D.4 ①（**ADR 已冻结**） | 通过 | 机械可判 | `C2`（`#shouldCarryContractFields()` 等）；本文落地，**不改名** |
> | core 四行残留与读侧投影 | `taskCreated`（`EventBus` 语义方法） | ③ | §2.B.1 · §4.2 判例 8（语义方法名与回调同形）· §2.D.1（回指 `TaskCreatedEvent`） | 通过 | 机械可判 | `C1`（`#allSemanticMethodsShouldNoopWithoutPublisher()` 穷举补一行） |
> | core 四行残留与读侧投影 | `decisionEvidences`（两 VO 字段） | ③ ⑦ | §2.B.1 · §2.C.1（与 `operationComments` 非屈折）· §2.D.4 ①（词根已登记） | 通过 | 机械可判 | `C7`；字段名与 JSON 键同源（无 JSON 输出面，仅读侧投影） |
> | core 四行残留与读侧投影 | `resolveReplayOf(DecisionEvidenceVO)` | ③ | §2.B.1 · §2.D.3（判定语义词尾，**不落字段**）· §4.5「重放」行 | 通过 | 机械可判 | `C7`（`#resolveReplayOfReturnsOriginalOrNull()` / `#distinctKeysAreNeverJudgedAsReplay()`） |
> | core 四行残留与读侧投影 | `DecisionEvidenceRowProjector` · `NewlyReadyTaskEmitter` · `DecisionReplayJudge` | ①（**包内**） | §1.1（包内实现细节**仅** §2.B.1） | 通过（降档） | 机械可判（T1） | 不入公开命名表；T1 自检见上 |
> | core 四行残留与读侧投影 | `SourceScanSupport` · `SourceScanSupport.Hit`（可见性放宽） | 测试支撑（§1.1 判例） | 仅 §2.B.1 | 通过（降档） | 机械可判（T1） | 供 `C7` 的 G7 扫描与 `C3` / `C5` 共用 |
>
> **落选候选（审计留痕）**：无 —— 本文未产生被判「不通过」的候选标识符，故**不建**豁免、**不登记**偏离。

> **此后新增的登记与偏离（工程基座与观测一族）**：§4.5 **登记 3 新行 + 2 行补形态**（上表末 3 行；「可观测信号」行补具名 logger 常量 / 换算方法 / 闭集值常量名，「决策指标记录器」行补 `record(DecisionObservation)`）；**§4.6 无新增偏离**。逐条：
>
> ① **`SuggestionAdmissionReason`（值域先行落地，不新增登记行）** —— 观测事实的 `admissionReason` 字段以本枚举为类型，故本文先落**值域**（十三值闭集；词根行已由「位点服务与建议提交模型签名」登记），异常与位点服务仍归该来源。**不做**任何下位形态占位（不改类型为 `String` / 裸串）。
>
> ② **`WriteDegradedCause`（新公开类型）** —— 宪章「未物质化」行原只登记**字段与取值**（`ANCHOR_LOST` / `INSTANCE_ENDED`），未登记类型名；实现期按「值域是机制契约、下游要按它分流」立公开类型，本行补登记。
>
> ③ **`DecisionOutcomeMapping`（新公开类型）** —— 「结局映射表」原以探索期决议承载、无代码住所；本文把它落成公开枚举（**键 = 产生点**、`ROW_COUNT` 为**对账常量**），使「计错判据 = `failureKind != null`」与「按政策未产出绝不入错误率」有可判承载位。
>
> ④ **`DecisionObservationEmitter`（新公开类型）** —— 「单一构造点 / 消费三层」原只登记了三个**消费者**（日志 / `DecisionObserver` / 指标），未登记**出口**；本文落出口类型与其唯一公开方法 `emit(DecisionObservation)`。包内可见的 `LOG_FORMAT` / `logArguments(DecisionObservation)` 是**测试可见面**（观测面禁载的运行期可判形态），依 §1.1 降档、不进公开命名表。
>
> ⑤ **`DecisionMetrics` 的三个补形态** —— 具名 logger 常量 `LOGGER_NAME`（原行只写「logger 名常量」未具名）；换算方法 `tagValue(Enum<?>)`（原行只写「取枚举常量名的小写蛇形」，本文收成**单点换算**，避免下游各写一遍）；闭集值常量名四个（原行只给取值、未具名）。
>
> **判例留痕（五条）**：
>
> ① **`DecisionOutcomeMapping` 与 `DecisionOutcome`**（core 枚举）归一后 `decisionoutcomemapping` / `decisionoutcome` 为**包含而非屈折**（§2.C.1 只判 `+s` / `+es` / `y→ies`），依据同既有「`decisionmetricsrecorder` 与 `decisionmetrics`」判例；二者分属**不同模块与不同硬域**（结局映射表 vs 结局闭集）。
>
> ② **`WriteDegradedCause`（类型）与字段 `writeDegradedCause`** 归一后**恒等而非屈折**，依据同 §4.5「`DecisionPolicy`（类型）与 BPMN 属性名 `decisionPolicy`」判例；二者分属不同硬域（Java 类型 vs 字段名）。
>
> ③ **`DecisionObservationEmitter` 与 `DecisionObservation` / `DecisionObserver`** 归一后**非屈折**（加后缀不属三种规则屈折），依据同「可观测性的实现形态」判例 ①。
>
> ④ **跨类型同形（有意）** —— 行常量名与其取值来源枚举的常量名存在同形：行 `INBOUND_PROCESSING_FAILED` 与 core `DecisionFailureKind.INBOUND_PROCESSING_FAILED`、行 `ANCHOR_LOST` / `INSTANCE_ENDED` 与 `WriteDegradedCause` 同值。依既有「跨硬域同形不违规」判例（不同 Java 类型）**不构成违规**；**有意同词**（同一事实的「产生点键」与「落位值」），登记以免日后被读成疏漏。
>
> ⑤ **闭集值常量名取「限定词 + 取值段」**（`DIRECTION_INPUT` / `CAUSE_ANCHOR_LOST` …）—— 沿用 §4.5「方向限定词」行既有做法；与维度键常量 `DIRECTION` / `CAUSE` 归一后为**包含而非屈折**。
>
> **硬域自检（本文）**：新增公开标识符归一后两两比较 —— `decisionobservationemitter` / `decisionoutcomemapping` / `writedegradedcause` 与既有 `decisionobservation` / `decisionobserver` / `decisionseverity` / `decisionmetrics` / `decisionmetricsrecorder` / `suggestionadmissionreason` **均不构成屈折对**；`DecisionOutcomeMapping` 的 18 个行常量彼此**不构成屈折对**；`DecisionMetrics` 内 9 信号名 / 10 维度键 / 4 闭集值常量彼此**不构成屈折对**（`…_TOKENS` 与 `…_TOKENS_USAGE_MISSING`、`CAUSE` 与 `CAUSE_ANCHOR_LOST` 为**包含而非屈折**）。
>
> **T1 自检**：`decisionobservationemitter` / `emit` / `logformat` / `logarguments` / `decisionoutcomemapping` / `rowcount` / `writedegradedcause` / `anchorlost` / `instanceended` / `loggername` / `tagvalue` / `directioninput` / `directionoutput` / `causeanchorlost` / `causeinstanceended` / `record` / `suggestionadmissionreason` 及其 13 个成员，逐一切词后**无 §4.1 T1 禁词**（无厂商 / 具体型号 / 具体产品及其缩写译名）。
>
> **判定结果表**（§7.2 表式）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 工程基座与观测一族 | `DecisionObservationEmitter`（+ `emit(DecisionObservation)`） | ① ③ | §2.B.1 · §2.C.1（与 `DecisionObservation` / `DecisionObserver` 非屈折）· §2.D.1（回指 ADR-0042 第 10 节） | 通过 | 机械可判（T1） | `E18` 的 `#observerFailureIsIsolatedAndNeverThrows()` / `#payloadSentinelNeverLeaksIntoLogOrTags()` |
| 工程基座与观测一族 | `DecisionOutcomeMapping`（+ `ROW_COUNT` + 20 行常量 + 6 行字段） | ① ② | §2.B.1 · §2.C.1（与 `DecisionOutcome` 包含非屈折）· §2.D.1（回指 ADR-0042 第 10 节） | 通过 | 机械可判 | `E14` 的三条断言 |
| 工程基座与观测一族 | `WriteDegradedCause`（+ `ANCHOR_LOST` / `INSTANCE_ENDED`） | ① ② | §2.B.1 · §2.C.1（与字段 `writeDegradedCause` 恒等非屈折）· §2.D.1（回指 ADR-0042 第 9 节第 10 条） | 通过 | 机械可判（T1） | `E18` 的 `#fieldSetEqualsDeclaredSixteenWithAllowedTypes()` |
| 工程基座与观测一族 | `LOGGER_NAME` · `tagValue(Enum<?>)` · `DIRECTION_INPUT` / `DIRECTION_OUTPUT` / `CAUSE_ANCHOR_LOST` / `CAUSE_INSTANCE_ENDED` | ② ③ | §2.B.1 · §2.C.1（类型内无屈折对）· §2.D.1（词根行沿用） | 通过 | 机械可判 | `E18` 的 `#signalNamesAndValuesArePrefixedFormattedAndT1Clean()` / `#closedSetValuesAreLowerSnakeOfEnumNames()` |
| 工程基座与观测一族 | `DecisionMetricsRecorder.record(DecisionObservation)` | ③ | §2.B.1 · §2.D.1（词根行沿用） | 通过 | 机械可判（T1） | `E18` 的 `#observerFailureIsIsolatedAndNeverThrows()` |
| 工程基座与观测一族 | `SuggestionAdmissionReason`（值域落地，13 值） | ② | §2.B.1 · §2.D.4（**已由位点服务登记**） | 通过（沿用） | 机械可判 | `E18` 的 `#closedSetValuesAreLowerSnakeOfEnumNames()` |
| 工程基座与观测一族 | `ExtensionTestEngine` · `DecisionFixtures`（测试专用类型） | 测试类型（§1.1 判例） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | 不登记词根行（依「`src/test` 类型」判例）；T1 自检见上 |
| 工程基座与观测一族 | `LOG_FORMAT` · `logArguments(DecisionObservation)`（包内可见） | 降档（§1.1） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | `E18` 的 `#payloadSentinelNeverLeaksIntoLogOrTags()` |
| 工程基座与观测一族 | 落选候选 | —— | —— | —— | —— | **无** —— 本文未产生被判「不通过」的候选标识符，故**不建**豁免、**不登记**偏离 |


> **此后新增的登记与偏离（节点声明与部署期校验）**：§4.5 **2 行补形态**（「节点声明校验器」行补 `INVALID_DECLARATION_PROBLEM`；「拉管线与出站缝」的「实现细节」行补 `DecisionNodeDeclarationReader` 的七个方法形态）；**§4.6 无新增偏离**。逐条：
>
> ① **`DecisionNodeDeclaration` 的六个成员**（命名空间 URI / 前缀 + 四个属性名常量）—— 由「节点声明四属性与 BPMN schema」登记，本文**原样落地**：**不新增登记行、不改名、不改值**。
>
> ② **`INVALID_DECLARATION_PROBLEM`（新公开常量）** —— 「模块与构建」只登记了 `VALIDATOR_SET_NAME`（`ValidatorSet` 组名），**未登记问题码**；实现期按「下游要按它机械识别本机制阻断的部署」立该常量，本行补形态。值 = 组名前缀 + 机制语义段，与组名同族；该字面量经 `ValidationError.problem` 出现在部署失败信息里，属契约面可见字面量（§1.2 ⑥）。
>
> ③ **`DecisionNodeDeclarationReader` 的七个包内方法** —— 依 §1.1 降档（包内实现细节只受 §2.B-T1），**不入公开命名表**；本行只登记形态，以免日后被读成疏漏。
>
> **判例留痕（三条）**：
>
> ① **属性名「禁裸字面量」的判据 = 带引号形态，不是裸子串** —— 属性名是普通英文词组，出现在**标识符片段**里（如构造参数名 `decisionTargetKeys`）**不是**另一份字面量来源；只有源码 / 注释里另行拼出的**字符串直接量**才是「裸字面量」。命名空间无此歧义（不可能成为标识符片段），按原样匹配。**不改 §2.E.1 的规则文本**，只把判据的可操作形态钉死（`E2` 的 `#attributesAndUriAreDefinedInSingleConstantClass()` 的机械面）。
>
> ② **校验器构造缝 = 两个 `Set<String>`（不新增公开类型）** —— 「引用不存在的 key」需要注册面 key 集，而注册表是 starter **包内**类型、extension 不得引用；故 key 集由**构造期注入**（starter 收口去重后传入）。**空集合法**：含义 = 应用未注册任何目标 / 策略，此时任何引用都阻断（与 fail-closed 同向）；`null` 非法（与空集含义不同）。
>
> ③ **数据源声明的整值空白 = 显式空集，与空 token 分列** —— `decisionDataSources=""`（含纯空白）判**零 token**（合法的一态：运行期恒「未声明数据源」）；`",x"` / `"x,"` 一类**逗号造成的空位**才是阻断项。依据 = ADR-0042 第 6 节的三态（位缺失 / 显式空集 / 有值）；空白容忍只授予 token 两侧，未授予空位。
>
> **硬域自检（本文）**：本文新增的公开标识符只有 `INVALID_DECLARATION_PROBLEM` 一个 —— 与 `VALIDATOR_SET_NAME` 归一去分隔符、统一小写后 `invaliddeclarationproblem` / `validatorsetname` **非屈折**；与既有 `DecisionNodeDeclaration` / `DecisionNodeDeclarationValidator` / `DecisionNodeDeclarationReader` 亦**无屈折对**。包内七个方法名（`mechanismattributes` / `mechanismextensionelements` / `declaredvalue` / `declaredkey` / `parseenabled` / `splitdatasourcetokens` / `parsedatasourcetoken`）两两比较**无屈折对**。
>
> **T1 自检**：`invaliddeclarationproblem` / `decisionnodedeclaration` / `decisionnodedeclarationvalidator` / `decisionnodedeclarationreader` / `decisiontargetkeys` / `decisionpolicykeys` / `mechanismattributes` / `mechanismextensionelements` / `declaredvalue` / `declaredkey` / `parseenabled` / `splitdatasourcetokens` / `parsedatasourcetoken` 及其字面量取值（`flowable-plus-decision-node-declaration-invalid`）与 `E2` 的 5 个、`E3` 的 15 个断言名（清单见 `docs/impl/0042-verification-landings.md` §3.2 的 `E2` / `E3` 行与本文件的判定结果表）逐一切词，**无 §4.1 T1 禁词**（无厂商 / 具体型号 / 具体产品及其缩写译名）。
>
> **判定结果表**（§7.2 表式）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 节点声明与部署期校验 | `DecisionNodeDeclaration`（六成员：`NAMESPACE_URI` / `NAMESPACE_PREFIX` / 四属性名常量） | ② ④ | §2.B-T1（`flowable` 已**出 T1**）· §2.C.2 硬域（类型内无屈折对）· §2.D.4（词根行已登记）· §2.E.1 | 通过（沿用） | 机械可判 | `E2` 的 `#attributesAndUriAreDefinedInSingleConstantClass()` / `#uriIsNotAnEngineReservedNamespace()` / `#attributeNamesAreCamelCase()` |
| 节点声明与部署期校验 | `INVALID_DECLARATION_PROBLEM`（+ 值字面量） | ② ⑥ | §2.B.1 · §2.C.2 硬域（类型内无屈折对）· §2.D.1（词根行沿用） | 通过 | 机械可判（T1） | `E3` 的全部阻断断言（共 11 条：失败信息须含该问题码 —— 该判据住共用的阻断断言辅助，不逐条重写） |
| 节点声明与部署期校验 | `DecisionNodeDeclarationValidator`（+ 构造参数 `decisionTargetKeys` / `decisionPolicyKeys`） | ① ③ | §2.B-T1 · §2.C.2 硬域（与 `DecisionNodeDeclaration` 含后缀非屈折）· §2.D.1（词根行已登记） | 通过 | 机械可判 | `E3`（部署期阻断与不阻断）；`E1` 的 `#typeNameFreeOfDomainWords()` |
| 节点声明与部署期校验 | `DecisionNodeDeclarationReader` 及其七个包内方法 | 降档（§1.1） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | `E2` 的 `#tokenParsingAcceptsEnumNameVerbatim()` / `#enabledParsingAcceptsOnlyLowercaseLiterals()` |
| 节点声明与部署期校验 | `DecisionNodeDeclarationTest` · `DecisionNodeDeclarationValidatorTest`（测试类型） | 测试类型（§1.1 判例） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | 不登记词根行（依「`src/test` 类型」判例）；T1 自检见上 |
| 节点声明与部署期校验 | 落选候选 | —— | —— | —— | —— | **无** —— 本文未产生被判「不通过」的候选标识符，故**不建**豁免、**不登记**偏离 |


> **此后新增的登记与偏离（出域面：装配器 / 策略 / clamp / 入站加工）**：§4.5 **1 行新增实现细节**（出域面）+ **3 行补形态**（「决策载荷」行补两个小对象的字段集、「装配器与装配结果」行补 `droppedContextSources`、「拉管线与出站缝」的「实现细节」行补 `DecisionClamp` 一族与 `DecisionInboundProcessor.process(String)`）；**§4.6 无新增偏离**。逐条：
>
> ① **`DecisionClamp`（新公开面之外的实现细节）** —— 「出域控制的实现形态」只写「框架硬上限 clamp」而**未具名类型**，而落点表已把 E6 钉为 `DecisionClampTest`、G2 / G3a 的守卫也需读该常量 ⇒ 实现期按 §1.1 立**包内类型**（只受 §2.B-T1），补形态以免日后被读成疏漏。**两方向同一上限**：出域「整段丢弃（`dropPriority` 降序）」与入站「整串丢弃（⇒ `null`）」共用同一 `MAX_PAYLOAD_BYTES` = 32,768 字节，是「两方向 clamp 上限之和」这一 G3a 事实的**唯一数字权威**。**只许调低、不可放大** = 构造期 `Math.min(请求值, 硬上限)` —— 以 API 形态把「不可放大」变成**机械可判**（E6 的 `#clampNeverRaisesAppConfiguredLimit()`），v1 **不暴露应用侧配置口**（管线传硬上限本身）。**段内永不动刀** = 只置段为 `null`，绝不截断段内字符串。
>
> ② **两个元数据小对象的字段集（形态定稿）** —— ADR-0042 第 6 节只点名 `businessKey` 与发起人属流程实例元数据、并明写「**不在可枚举的元数据上开字段级枚举**」（字段取舍归策略的内容选择），**未给字段集**。实现期取**引擎同名字段的最小子集**（`TaskMetadata` 五字段 / `ProcessInstanceMetadata` 五字段，一律可空），作为**最小基线**登记。**如实披露**：字段集是**最小可选面**而非声明面 —— 策略可再作内容选择；日后要增字段属**扩展**（加字段不改语义），不是本文的球门修订。
>
> ③ **`DecisionAssemblyResult.droppedContextSources`（补字段形态）** —— 「可观测性的实现形态」已把它定为观测事实的第十六个字段（「装配器丢弃的来源」），但**未见诸装配结果类型**。实现期按「声明面最小化的基线计数」落成 `DecisionAssemblyResult` 的字段 = **有效数据源集之外的来源**（反映**建模漏配**）；**非标志**、不计入任何标志（与 clamp 丢弃分列）。依探索期决议的裁定：装配器丢不计标志、clamp 丢计 `truncated`（对偶勿混）。
>
> ④ **`DecisionInboundProcessor.process(String)` 与 `DecisionPolicy.applyInbound` 的缺省透传** —— 「拉管线与出站缝」已定入站加工「只共用 clamp、不共用策略」，本文把它落成**结构保证**：`DecisionInboundProcessor` 构造只持 `DecisionClamp`、**不持** `DecisionPolicy`（E6 的 `#outboundAndDirectShareClampButNotPolicy()` 以反射 + 行为双证）。`applyInbound` 的**缺省实现 = 透传**（ADR-0042 第 6 节「入站方向 = 落盘前加工（可选，默认透传）」）：有内容 ⇒ 可落盘且原样返回，无内容 ⇒ 不可落盘（避免 `persistable = true` 与「无内容」自相矛盾的非法态）。
>
> ⑤ **`DecisionPolicy.key()`（补形态）** —— ADR-0042 第 6 节明写「**策略与决策目标同型**（bean + 唯一 `key()` + 节点按 key 引用 + 部署期校验）」，而本行原只登记了两个**方向**方法；「模块与构建」§2.2 行 8 的策略注册表（`List<DecisionPolicy>` 收集、**重复 key ⇒ 启动期 fail-fast**）与已落地的节点声明校验器（其构造入参 `Set<String> decisionPolicyKeys`）都要求它存在 ⇒ 实现期按 ADR 补齐。**`key()` 的词根形态已由「决策目标」行登记**（`DecisionTarget`：`key()` + 接入信息），本行**沿用、不新增词根、不造第二形态**。**诚实性上限**：框架只承诺「节点按 key 单值引用 ∧ 注册表按 key 去重」，不承诺 key 的语义（那是应用的命名自由）。
>
> **判定结果表**（§7.2 表式）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 出域面 | `DecisionPayload`（+ 四段字段 `processVariables` / `taskVariables` / `taskMetadata` / `processInstanceMetadata`）· `TaskMetadata`（+ 五字段）· `ProcessInstanceMetadata`（+ 五字段） | ① ③ | §2.B-T1 · §2.C.2 硬域（归一后无屈折对）· §2.D.1（词根行已登记） | 通过（形态落地） | 机械可判 | `E5` 的 `#absentSegmentSerializesAsNullAndDeclaredEmptyAsEmptyCollection()` / `#segmentsAreExactlyFourTypedShells()` |
| 出域面 | `DecisionPolicy`（+ `key()` / `applyOutbound(DecisionPayload)` / `applyInbound(String)`）· `DecisionOutboundResult`（+ `permitted` / `payload` / `record`）· `DecisionInboundResult`（+ `persistable` / `rawOutput` / `record`）· `DecisionProcessingRecord`（+ `redacted` / `truncated`） | ① ③ | §2.B-T1 · §2.C.2 硬域 · §2.D.1（`key()` 词根沿用「决策目标」行）/ §2.D.2（方向词唯一 = `outbound` / `inbound`）· §2.D.4 | 通过（形态落地） | 机械可判 | `E7` 的 `#permittedTrueImpliesNonEmptyPayload()` / `#persistableTrueImpliesNonEmptyContent()` / `#processingRecordCarriesFactsOnlyNotVerdict()` |
| 出域面 | `DecisionContextAssembler` · `DecisionAssemblyResult`（+ `emptyAssembly` / `payload` / `droppedContextSources`）· `DecisionDefaultContextSources` | ① ③ | §2.B-T1 · §2.C.2 软域（对 `DecisionContextSource` 无屈折）· §2.D.1 / §2.D.3（`emptyAssembly` 是显式事实、非派生状态） | 通过（形态落地） | 机械可判 | `E4` 的全部八条断言 |
| 出域面 | `DecisionClamp`（+ `MAX_PAYLOAD_BYTES` / `clampOutbound(DecisionPayload)` / `clampInbound(String)`）· `DecisionClampRejectedException` · `DecisionContextSnapshot` | 降档（§1.1） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | `E6` 的 `#clampNeverRaisesAppConfiguredLimit()` / `#dropsSegmentsInDescendingDropPriority()` / `#fallsBackToRejectionWhenAllSegmentsDropped()` / `#neverSplitsWithinSegment()` / `#outboundAndDirectShareClampButNotPolicy()` / `#readGuardIsNotTheEgressClampConstant()` / `#writeGuardCoversBothDirectionClampUpperBounds()` |
| 出域面 | `DecisionInboundProcessor.process(String)`（包内） | 降档（§1.1） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | `E6` 的 `#outboundAndDirectShareClampButNotPolicy()` |
| 出域面 | `DecisionPayloadTest` · `DecisionContextAssemblerTest` · `DecisionClampTest` · `DecisionPolicyTest`（测试类型） | 测试类型（§1.1 判例） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | 不登记词根行（依「`src/test` 类型」判例）；T1 自检见下 |
| 出域面 | 落选候选 | —— | —— | —— | —— | **无** —— 本文未产生被判「不通过」的候选标识符，故**不建**豁免、**不登记**偏离 |

> **硬域自检（本文）**：新增公开 / 包内标识符归一后两两比较 —— `decisionpayload` / `taskmetadata` / `processinstancemetadata` / `decisionpolicy` / `decisionoutboundresult` / `decisioninboundresult` / `decisionprocessingrecord` / `decisioncontextassembler` / `decisionassemblyresult` / `decisiondefaultcontextsources` / `decisionclamp` / `decisionclamprejectedexception` / `decisioncontextsnapshot` / `clampoutbound` / `clampinbound` / `maxpayloadbytes` / `key` / `process` 与既有 `decisioncontextsource` / `decisionnodedeclarationreader` 一族 **均不构成屈折对**（`key` 沿用「决策目标」行既有形态，非本文新增）；`process`（方法）与 `processvariables` / `processinstancemetadata`（字段）分属不同硬域，且归一后为**包含而非屈折**；四段字段 `processvariables` / `taskvariables` / `taskmetadata` / `processinstancemetadata` 归一后**无屈折对**；`TaskMetadata` / `ProcessInstanceMetadata` 的字段名与同类型内既有字段**无屈折对**。
>
> **T1 自检**：`decisionpayload` / `taskmetadata` / `processinstancemetadata` / `decisionpolicy` / `applyoutbound` / `applyinbound` / `decisionoutboundresult` / `decisioninboundresult` / `decisionprocessingrecord` / `permitted` / `persistable` / `redacted` / `truncated` / `decisioncontextassembler` / `decisionassemblyresult` / `emptyassembly` / `droppedcontextsources` / `decisiondefaultcontextsources` / `decisionclamp` / `maxpayloadbytes` / `clampoutbound` / `clampinbound` / `decisionclamprejectedexception` / `decisioncontextsnapshot` / `key` / `process` / `taskid` / `taskname` / `nodeid` / `assignee` / `createtime` / `processinstanceid` / `processdefinitionkey` / `businesskey` / `startuserid` / `starttime`，逐一切词后**无 §4.1 T1 禁词**（无厂商 / 具体型号 / 具体产品及其缩写译名）。

> **此后新增的登记与偏离（证据写入与提交模型：提交模型 / 证据写入器）**：§4.5 **1 行补形态**（「拉管线与出站缝」的「实现细节」行补 `DecisionEvidenceWriter` 的方法形态与新增包内类型 `DecisionEvidenceDraft`）+ **同行的归属格补「两类型归证据写入与提交模型」**；**§4.6 无新增偏离**。逐条：
>
> ① **`DecisionEvidenceWriter` 的方法形态（补形态）** —— 「决策证据载体形态」只给它一句「extension **内部**写入器，物质化两类未产出」，而落点表已把 E9 钉为 `DecisionEvidenceWriterTest` ⇒ 实现期补包内可见的 `materialize(DecisionEvidenceDraft)`（物质化一条证据：判列 → 逐格校验矩阵 → 推导方向标志与完整度）与 `row(DecisionEvidenceVO)`（交**整行文本** = 标记 + ASCII-safe JSON，写入前施加尺寸护栏），以及成员常量 `SCHEMA_VERSION` = 1。**`materialize` 是四列共用的唯一物质化入口** —— 判列由「`outcome` × 出处组是否为空」这一**结构性事实**承担，不由调用方自述；**`row` 只产一行文本、不持久化** —— 一手事实：引擎 `AddCommentCmd` 收单个 `message` 参数，`FULL_MSG_` 存全文、`MESSAGE_` 由引擎自行折叠截断，故写侧只交一行。**超限拒写 ⇒ 物质化为 D 列行**（`SUGGESTION_FAILED` / `INTERNAL_ERROR`），**零新增枚举值 / 槽位 / 写入降级取值**。
>
> ② **`DecisionEvidenceDraft`（新增包内类型，只受 §2.B-T1）** —— 「决策证据载体形态」只给了「写入契约 = `SuggestionSubmission`」，而**四列里只有 B 列（直提）是「调用方自述」**：A 列的出处组与 `modelId`、C 列的 `policyReason`、D 列的 `failureKind`、以及两方向「到底有没有载荷」的显式事实都由框架侧产生 ⇒ 实现期立一个**显式事实载体**，字段集 = 自述子集 + 判别三格（`outcome` / `policyReason` / `failureKind`）+ **两个方向的显式事实与加工事实**（`outboundPayloadPresent` / `outboundRedacted` / `outboundTruncated` / `outboundClampDropped` / `inboundPayloadPresent` / `inboundRestricted` / `inboundRedacted` / `inboundTruncated`）+ 转换点 `of(SuggestionSubmission)`。**它不承载** `schemaVersion` / 方向标志六项 / `completeness` / 标记（写入器恒填）。**如实披露**：本类型是**实现形态、非新契约面**（契约面仍是 `SuggestionSubmission` 与 core 的 `DecisionEvidenceVO`）；两个方向的显式事实取「不枚举路径」口径，与 ADR-0042 第 6 节的 `hasOutboundPayload` / `hasInboundPayload` **同义不同形** —— 它们住包内实现细节，不入术语表。
>
> **判定结果表**（§7.2 表式）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 证据写入与提交模型 | `SuggestionSubmission`（+ 十五字段）· `submit(SuggestionSubmission)` 的入参形态 | ① ③ | §2.B-T1 · §2.C.2（与 `SuggestionSubmissionService` 归一后非屈折）· §2.D.1 / §2.D.4（词根行已登记） | 通过（形态落地） | 机械可判 | `E8` 的全部四条断言（含自述位三态）；`E1` 的 `#typeNameFreeOfDomainWords()` |
| 证据写入与提交模型 | `DecisionEvidenceWriter`（+ `materialize(DecisionEvidenceDraft)` / `row(DecisionEvidenceVO)` / `SCHEMA_VERSION`）· `DecisionEvidenceDraft`（+ 字段集与 `of(SuggestionSubmission)`）· 私有嵌套 `Column`（四值 `OUTBOUND` / `DIRECT` / `POLICY` / `FAILURE`） | 降档（§1.1） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | `E9` 的全部八条断言 |
| 证据写入与提交模型 | `DecisionEvidenceSubmissionTest` · `DecisionEvidenceWriterTest`（测试类型） | 测试类型（§1.1 判例） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | 不登记词根行（依「`src/test` 类型」判例）；T1 自检见下 |
| 证据写入与提交模型 | 落选候选 | —— | —— | —— | —— | **无** —— 本文未产生被判「不通过」的候选标识符，故**不建**豁免、**不登记**偏离 |

> **硬域自检（本文）**：`suggestionsubmission`（沿用登记行，非本文新增）与 `decisionevidencewriter` / `decisionevidencedraft` / `materialize` / `row` / `schemaversion` / `outboundpayloadpresent` / `outboundredacted` / `outboundtruncated` / `outboundclampdropped` / `inboundpayloadpresent` / `inboundrestricted` / `inboundredacted` / `inboundtruncated` 归一后两两比较 —— 与既有 `decisionevidencevo` / `decisionevidencecomment` / `decisionevidencewriteguard` / `decisionevidencereadguard` / `decisionevidencerowprojector` **均不构成屈折对**（`DecisionEvidenceDraft` 与 `DecisionEvidenceWriter` 是同词根前缀差，不属 §2.C.1 的 `+s` / `+es` / `y→ies`）；`outbound…` 与 `inbound…` 两组**互不构成屈折对**（前缀不同）；`outboundpayloadpresent` 与 `inboundpayloadpresent` 同理；`materialize` / `present` / `restricted` 与既有 `redacted` / `truncated` / `completeness` 分属不同硬域；私有嵌套 `Column` 的四值与 `DecisionOutcome` / `DecisionPolicyReason` 的取值**分属不同硬域**（前者是产出路径的内部判列标签）。
>
> **T1 自检**：`suggestionsubmission` / `decisionevidencewriter` / `decisionevidencedraft` / `materialize` / `row` / `schemaversion` / `of` / `outboundpayloadpresent` / `outboundredacted` / `outboundtruncated` / `outboundclampdropped` / `inboundpayloadpresent` / `inboundrestricted` / `inboundredacted` / `inboundtruncated` / `column` / `outbound` / `direct` / `policy` / `failure` / `decisionevidencesubmissiontest` / `decisionevidencewritertest`，逐一切词后**无 §4.1 T1 禁词**（无厂商 / 具体型号 / 具体产品及其缩写译名）。

> **此后新增的登记与偏离（推面位点服务与表态比较面）**：§4.5 **3 行补形态**（「建议提交」行补 `DefaultSuggestionSubmissionService` 的构造面形态；「准入失败」行补 `SuggestionAdmissionException` 的载体形态；「表态比较面」行补包内判定方法 `isAvailableFor(...)`）；**§4.6 无新增偏离**。逐条：
>
> ① **`DefaultSuggestionSubmissionService` 的构造面（补形态）** —— 收口决议只登记了类名，未登记它的构造依赖形态。实现期按 `docs/impl/0042-module-and-build.md` §2.2 行 6 / 行 10 与 `docs/impl/0042-equivalence-harness.md` §2.4 的「构造依赖**只取 extension 可见类型**」纪律定：五项 = core `MultiInstanceDetector` Bean、引擎服务（`TaskService` / `RuntimeService`）、观测分发点 `DecisionObservationEmitter`、全局开关定值（`boolean`，构造期定值）。**内部件由实现自持**：`DecisionEvidenceWriter` / `DecisionInboundProcessor` / `DecisionClamp` 全住包内、**不进构造面**（这是对 §2.2 行 6「内部写入器」措辞的**账本订正** —— 写入器是被使用者、不是构造依赖，已同提交登记于 `0042-module-and-build.md`）。**零新增公开类型、零新增公开方法**。
>
> ② **`SuggestionAdmissionException` 的载体形态（补形态）** —— 「位点服务与建议提交模型签名」定「异常自带 `taskId` 上下文」，实现期落成两个**只读访问器**：`getReason()`（闭集十三值）与 `getTaskId()`（可空 —— 缺锚点正是原因之一）；**不新增词根**（`reason` 是已登记维度键、`taskId` 是已登记字段）。
>
> ③ **`ComparableAction.isAvailableFor(...)`（新增包内判定方法，只受 §2.B-T1）** —— 「表态比较面」行原只登记 `MEMBERS` 与 `isComparable`，而可用动作三行映射（探索期决议 §一.4）必须有个承载体：实现期把它落在**同一常量类的包内方法**上（消费者只有位点服务），**公开面维持两个成员不变**（主仓既有判例：「实现细节」的面取包内可见，如 `DecisionEvidenceWriter` 与 `DecisionObservationEmitter#logArguments`）。它只做映射、不触引擎 ⇒ 真值表可逐格对拍。
>
> ④ **`MEMBERS` 的公开面静态类型取 `Set`（一处形态如实登记，非名称变更）** —— 注册形态写的是 `EnumSet<ApprovalAction>`；`Collections.unmodifiableSet(EnumSet.of(...))` 的静态类型只能是 `Set`，而取 `Set` 是**为守住「单一来源」**（裸 `EnumSet` 公开成员可被调用方 `clear()`/`add()`，单一来源即成空文）。构造来源仍是 `EnumSet.of(...)`，**值域、名称与判据一字未动**；与 core `DecisionEvidenceComment.EVIDENCE_COMMENT_TYPES`、extension `DecisionNodeDeclarationValidator.DECLARED_ATTRIBUTES` 同形（仓内公开集合常量的一贯做法）。
>
> **判定结果表**（§7.2 表式）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 推面位点服务与表态比较面 | `DefaultSuggestionSubmissionService`（+ 五项构造面形态） | ①（沿收口决议登记行补形态） | §2.B.1 · §2.C.2 软域（对 `SuggestionSubmissionService` **加前缀，非屈折**）· §2.D.1 | 通过（形态落地） | 机械可判（T1） | `E10` 的全部断言（清单见 `verification-landings` §3.2 的 `E10` 行）；`E1` 的 `#typeNameFreeOfDomainWords()` |
| 推面位点服务与表态比较面 | `SuggestionSubmissionService` · `submit(SuggestionSubmission)` · `SuggestionSubmission` | ① ③（沿「位点服务与建议提交模型签名」登记行落地） | §2.B-T1 · §2.C.2（与 `SuggestionSubmissionService` 归一后非屈折）· §2.D.1 / §2.D.4 | 通过（沿登记行落地） | 机械可判 | `E10` 的全部断言；`E1` 的 `#typeNameFreeOfDomainWords()` / `#methodNamesFreeOfDomainWords()` |
| 推面位点服务与表态比较面 | `SuggestionAdmissionException`（+ `getReason()` / `getTaskId()`） | ①（沿「准入失败」登记行补形态） | §2.B-T1 · §2.C.2 · §2.D.1 / §2.D.4 | 通过（沿登记行落地） | 机械可判 | `E10` 的 `#rejectingCaseCarriesItsOwnReason()` / `#eachRuleHasAReachableConstructionPoint()` |
| 推面位点服务与表态比较面 | `SuggestionAdmissionReason`（十三值沿登记行） | ②（沿「准入失败」登记行） | §2.B-T1 · §2.C.2 硬域（同枚举内无屈折对）· §2.D.1 | 通过（沿登记行落地） | 机械可判 | `E10` 的 `#eachRuleHasAReachableConstructionPoint()`（键集恰等于 13 值集） |
| 推面位点服务与表态比较面 | `ComparableAction` · `MEMBERS` · `isComparable(ApprovalAction)` | ① ② ③（沿「表态比较面」登记行落地） | §2.B-T1 · §2.C.2 硬域（与类型名归一后无屈折对）· §2.D.1 | 通过（沿登记行落地） | 机械可判 | `E11` 的 `#membersIsExplicitAndExactlyFour()` / `#isComparableIsSameSourcedAsMembersContains()` |
| 推面位点服务与表态比较面 | `isAvailableFor(ApprovalAction, boolean, boolean, boolean)`（**包内**） | 降档（§1.1；判据仅 §2.B.1 T1） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | `E11` 的 `#truthTableMatchesCoreGuardsCellByCell()` / `#mirrorDoesNotDriftFromCoreTaskValidation()` |
| 推面位点服务与表态比较面 | `SuggestionAdmissionContractTest` · `ComparableActionAvailabilityTest`（测试类型） | 测试类型（§1.1 判例） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | 不登记词根行（依「`src/test` 类型」判例）；T1 自检见下 |
| 推面位点服务与表态比较面 | 落选候选 | —— | —— | —— | —— | **无** —— 本文未产生被判「不通过」的候选标识符，故**不建**豁免、**不登记**偏离；可用动作映射**未**启用公开面（探索期决议 §一.4 被否的两个档位口径属**判据选型**、非命名候选） |

> **硬域自检（本文）**：`defaultsuggestionsubmissionservice` / `suggestionsubmissionservice` / `suggestionsubmission` / `suggestionadmissionexception` / `suggestionadmissionreason` / `comparableaction` / `members` / `iscomparable` / `isavailablefor` / `getreason` / `gettaskid` 归一后两两比较 —— 与既有 `decisionobservation` / `decisionobserver` / `decisionseverity` / `writedegradedcause` **不构成屈折对**；`DefaultSuggestionSubmissionService` 与 `SuggestionSubmissionService` 为**加前缀**（不属 §2.C.1 的 `+s` / `+es` / `y→ies`）；`iscomparable` 与 `isavailablefor`、`getreason` 与 `reason`（维度键常量）、`gettaskid` 与 `taskId`（字段）均为**包含而非屈折**；三个布尔入参不落常量名，不产生屈折对。
>
> **T1 自检**：`defaultsuggestionsubmissionservice` / `suggestionsubmissionservice` / `submit` / `suggestionadmissionexception` / `suggestionadmissionreason` / `comparableaction` / `members` / `iscomparable` / `isavailablefor` / `getreason` / `gettaskid` / `suggestionadmissioncontracttest` / `comparableactionavailabilitytest`，逐一切词后**无 §4.1 T1 禁词**（无厂商 / 具体型号 / 具体产品及其缩写译名）。

> **此后新增的登记与偏离（拉管线与出站缝：管线 / 出站缝 / 凭据 / 运行暂停）**：§4.5 **5 行补形态**（「运行暂停」行补公开默认实现 `DefaultDecisionRuntimeControl`；「决策目标」行补接入信息的默认方言形态 `url()`；「凭据材料」行补 `DecisionCredential.of(String)` 与 `resolve(String)`；「出站提供方缝」行补 `DecisionProviderResponse` 的字段集与 `DecisionTarget` 的消费面；「出站传输缝」行补两个请求 / 响应类型的字段集与未取得响应的约定值）；**§4.6 无新增偏离**。逐条：
>
> ① **`DefaultDecisionRuntimeControl`（新增公开类型，补「运行暂停」行）** —— 该行只登记了接口与三方法，而装配清单（`module-and-build` §2.2 行 1）要求它是 `@ConditionalOnMissingBean` 的默认 Bean ⇒ 实现类必须有名字。按「默认实现族」形态取 `DefaultDecisionRuntimeControl`（与 `DefaultDecisionProvider` / `DefaultSuggestionSubmissionService` 同形）；状态 = 单个 `AtomicBoolean` 字段 `paused`（进程内内存态、不持久化、无管理端点、不读 Spring Environment —— E17 三断言钉死）。
>
> ② **`DecisionTarget.url()`（补「决策目标」行）** —— 登记行写「`key()` + 接入信息」而未定形态；默认 Provider 需要一个可读的出站地址，故接入信息的**默认方言形态**落成 `url()`（替换 Provider 缝后可被忽略 —— 接入信息内聚在 Bean 内部的口径不变）。
>
> ③ **`DecisionCredential.of(String)` 与 `DecisionCredentialResolver.resolve(String)`（补「凭据材料」行）** —— 不透明类型须有**唯一公开构造入口**（应用实现的解析 SPI 要能产它），落成静态工厂 `of(String)`；解析方法名取 `resolve`（沿用已登记判定词根，不新增词根）。**包内转交点如实登记**：`material()` 为包内可见，消费者只有同包的 `HttpDecisionTransport`（请求装饰）；「无状态提取方法」的判据面 = **公开成员**（E16 的扫描面 = `getMethods()` + 公开字段），包内转交点不构成公开提取面 —— 这是 ADR 第 7 节「请求装饰载体原样转交 Transport 缝」的落地形态，非约束放宽。
>
> ④ **`DecisionProviderResponse` 的字段集（补「出站提供方缝」行）** —— 决议 §5.3 的响应体字段全部落地（`declined` 取可空 `Boolean` 以承载「缺席 / false / true」三态），另补三类缝字段：`inputTokens` / `outputTokens`（观测面的用量只有缝可得；**响应体平铺**，缺失不猜）、`failureKind`（失败通道：HTTP 状态 → 失败类别的映射住缝内）、`rawOutput`（响应体原文裸串，即入站加工入参）。**互斥性的读法**：判据只看 `declined = true`；`declined = false` 是中性位、可与 `suggestedAction` 并见（否则「总是发 declined」的方言全违约）—— E15 把这条读法钉成可判事实。
>
> ⑤ **`DecisionTransportRequest` / `DecisionTransportResponse` 的字段集（补「出站传输缝」行）** —— 请求 = `url` + `body`（字节）+ `credential`（不透明对象原样转交）；响应 = `status` + `body`（字节，**只报状态与字节**）。**未取得响应的约定值** `STATUS_UNREACHABLE = 0`：SPI 可见面上 I/O 故障与超时不可分，由 Provider 缝统一映射为 `OUTBOUND_TIMEOUT` —— 如实登记为默认方言的既定口径。
>
> **判定结果表**（§7.2 表式）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 拉管线与出站缝 | `DecisionPipeline` · `DecisionTaskCreatedListener` · `pull(...)` | ①（沿「拉管线」与「到点信号订阅」登记行落地） | §2.B-T1 · §2.C · §2.D.1 / §2.D.4 | 通过（沿登记行落地） | 结构保证 + 机械可判 | `E13` 的八条具名断言；`E1` 的 T1 扫描 |
| 拉管线与出站缝 | `DecisionTarget`（+ `url()`）· `DecisionProvider` / `DefaultDecisionProvider` · `DecisionProviderRequest` / `DecisionProviderResponse`（+ 字段集） | ①（沿「决策目标」与「出站提供方缝」登记行补形态） | §2.B-T1 · §2.C.1 · §2.D.1 | 通过（补形态落地） | 机械可判 | `E15` 的六条具名断言 |
| 拉管线与出站缝 | `DecisionTransport` · `HttpDecisionTransport` · `DecisionTransportRequest` / `DecisionTransportResponse`（+ 字段集与 `STATUS_UNREACHABLE`） | ①（沿「出站传输缝」登记行补形态） | §2.B-T1 · §2.C.1 | 通过（补形态落地） | 机械可判 | `E19` 的 `#defaultProviderAcceptsInjectedTransport()` |
| 拉管线与出站缝 | `DecisionCredential`（+ `of(String)` / 包内 `material()`）· `DecisionCredentialResolver`（+ `resolve(String)`） | ①（沿「凭据材料」登记行补形态） | §2.B-T1 · §2.C · §2.D.1 | 通过（补形态落地） | 结构保证（三约束） | `E16` 的四条具名断言 |
| 拉管线与出站缝 | `DecisionRuntimeControl` · `pause()` / `resume()` / `isPaused()` · `DefaultDecisionRuntimeControl`（**新增公开类型**） | ①（接口沿「运行暂停」登记行；实现为新增，补形态） | §2.B-T1 · §2.C.2 软域（对 `DecisionRuntimeControl` **加前缀，非屈折**）· §2.D.3（`isPaused` 取判定语义，无 `…Status` / `…State` 词尾） | 通过（形态落地） | 机械可判 + 结构保证 | `E17` 的三条具名断言 |
| 拉管线与出站缝 | `StubDecisionTransport`（+ 两条 fixture 常量）· `DecisionPipelineTest` / `DecisionProviderContractTest` / `DecisionCredentialTest` / `DecisionRuntimeControlTest` / `DecisionStubContractTest`（测试类型） | §3.1 测试基座 / 测试类型（§1.1 判例） | **仅** §2.B.1 | 通过（降档） | 机械可判（T1） | `StubDecisionTransport` 依 §3.1 行落地；测试类型不登记词根行；T1 自检见下 |
| 拉管线与出站缝 | 落选候选 | —— | —— | —— | —— | **无** —— 本文未产生被判「不通过」的候选标识符（决议 §十四 的两条落选 `sourceGroup` / 带方向词形态在决议期已撤销，本文未再产生新候选），故**不建**豁免、**不登记**偏离 |

> **硬域自检（本文）**：`decisionpipeline` / `decisiontaskcreatedlistener` / `pull` / `decisiontarget` / `url` / `decisionprovider` / `defaultdecisionprovider` / `decisionproviderrequest` / `decisionproviderresponse` / `declined` / `suggestedaction` / `actionsummary` / `rationalefacts` / `rationalenarrative` / `modelid` / `provider` / `chainstage` / `degraded` / `rawoutput` / `inputtokens` / `outputtokens` / `failurekind` / `decisiontransport` / `httpdecisiontransport` / `decisiontransportrequest` / `decisiontransportresponse` / `statusunreachable` / `decisioncredential` / `of` / `material` / `decisioncredentialresolver` / `resolve` / `decisionruntimecontrol` / `defaultdecisionruntimecontrol` / `paused` / `pause` / `resume` / `ispaused` 归一后两两比较 —— 与既有 `decisionevidence…` 一族 / `decisionobservation` / `decisionmetrics` / `decisionoutcomemapping` **均不构成屈折对**（`DefaultDecisionRuntimeControl` 与 `DecisionRuntimeControl` 是**加前缀**；`material` 与 `modelid` 分属不同硬域且非屈折；`pause` / `paused` 与 `payloadpresent` 一类无词形关联；`provider` 字段与 `decisionprovider` / `defaultdecisionprovider` 类型名归一后**同形同物**——出处字段就是该缝的标识，回指唯一）。
>
> **T1 自检**：上述全部标识符逐一切词后**无 §4.1 T1 禁词**（`Http` = 能力类别非 T1；无厂商 / 具体型号 / 具体产品及其缩写译名；`stub` 为测试基座既有词）。

> **此后新增的登记与偏离（验证收口：击穿实验坐标 / 等价对拍 / 命名守卫 / 读序）**：§4.5 **新增 1 行**（「未建序」承载位）；**§4.6 无新增偏离**。逐条：
>
> ① **`UnorderedDecisionEvidences`（新增公开类型，core `…core.vo`）** —— E12 的「`ID_` 不可解析 ⇒ 整锚点不判（拒绝标注）」的实现期载体裁定（探索期决议的边界评论推入本文）。**裁定依据（一手论证）**：判定面（`DecisionReplayJudge`）只吃 `List<DecisionEvidenceVO>`，而证据 VO 不携带任何时序 / 序号元数据 ⇒ 判定面**无法从列表内容识别**「次序可信度」——任何「靠列表序 / 位置」的通道在几何上不可分（升序合法标注与降序错位标注的位置结构同构）；VO 字段被读侧硬清单第 8 项封死；包私类型跨 `core.workflow`（生产者）→ `core.vo`（判定面）不可见。故「未建序」事实必须由**列表自身的运行时类型**承载 ⇒ 新增一个公开的不可修改 `List` 具体类：内容完整（证据行照常投影）、序未建立；两个 VO 取值器对它**原样透传**（不再二次包装，避免掩盖类型身份），判定面识别即整锚点返回 `null`。**ADR-0042 第 9 节第 7 条判据零改动**（本文裁定的只是「写在哪」）。`equals` / `hashCode` 沿 `AbstractList` 按内容计算，不影响按内容比较的对拍面。
>
> ② **`src/test` 类型（本文五件：`DecisionBreachExperiments` / `DecisionBreachExperimentsTest` / `DecisionEvidenceReadOrderTest` / `DecisionDisabledEquivalenceTest` / `NamingCharterComplianceTest`）** —— 依既有判例不登记词根行，仅受 §2.B-T1。
>
> **判例留痕（两条）**：
>
> ① **E1 标识符宇宙的模块边界** —— core 测试树的新增类型不在 extension 类路径上（core 不发布 test-jar），反射不可达；E1 的「命中类名集合 == 固定清单」反射守卫只覆盖**本模块可见宇宙**（core 主源 + extension 主源 + extension 测试树），core 测试树类型以**名录 T1 扫描**承担（见 `0042-verification-landings.md` §3.2 的 E1 登记块）。登记以免日后被读成「宇宙漏扫」。
>
> ② **T2 字段面的扫描对象 = 实例字段** —— §2.B.3 的「必填性联动」以**可空性**为前提，只对实例字段有意义；常量名（如维度键 `MODEL_ID`）不承载可空性，其名录已由 §4.5「可观测信号」行登记。E1 的 T2 字段扫描跳过 `static final`。**不是规则修订**，是判据可操作化的边界钉死。
>
> **硬域自检（本文）**：`unordereddecisionevidences` 与 `decisionevidences`（字段）/ `DecisionEvidenceVO` / `DecisionEvidenceRowProjector` / `DecisionReplayJudge` 归一后为**包含而非屈折**（§2.C.1 只判 `+s` / `+es` / `y→ies`）；`experiments` / `variants` 非屈折；本文全部新增标识符两两比较**无屈折对**。
>
> **T1 自检**：`unordereddecisionevidences` / `decisionbreachexperiments` / `decisionevidencereadordertest` / `decisiondisabledequivalencetest` / `namingchartercompliancetest` 及全部新增断言名与字段名，逐一切词后**无 §4.1 T1 禁词**。
>
> **判定结果表**（§7.2 表式）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 验证收口 | `UnorderedDecisionEvidences` | ①（新增公开类型；承载位实现细节，不入术语表） | §2.B-T1 · §2.C.1（与 `decisionevidence…` 一族包含而非屈折）· §2.D.3 不适用（非派生事实，是承载位；派生面 `resolveReplayOf` 形态未动） | 通过（形态落地） | 机械可判（`E12` 三条断言钉住其行为） | `E12` 的 `#nonNumericIdMakesWholeAnchorUndecidable()` / `#noReplayMarkingWhenUndecidable()` / `#degradesWhenAssumptionIsBroken()`；`E1` 的 T1 / 屈折扫描 |
| 验证收口 | `DecisionBreachExperiments`（+ `EXPERIMENTS` / `VARIANTS` + 两行坐标类型）· 5 个测试类型 | 测试类型 / 测试专用常量（§1.1 判例） | **仅** §2.B-T1 | 通过（降档） | 机械可判（T1） | `E21` 六条具名断言；`E1` 十条具名断言；不登记词根行 |
| 验证收口 | 落选候选 | —— | —— | —— | —— | **无** —— 本文未产生被判「不通过」的候选标识符，故**不建**豁免、**不登记**偏离 |

> **此后新增的登记与偏离（主仓 #103 读侧记录时间落地）**：§4.5 **新增 1 行**（「记录时间」）+ **§1.2 类别 ⑦ 补限定**（JSON 键集 = **写侧** VO 字段集）；**§4.6 无新增偏离**。逐条：
>
> ① **新增行 `recordedTime`** —— 归「决策证据」族的**读侧承载**字段，其本体（ADR-0042 §5「时间不进 JSON —— 由评论行 `TIME_` 列填充」）**早已冻结**，本行只登记**名字与住所**：写侧恒不填、**不在 JSON 载荷键集内**，由读侧投影逐行从评论行 `TIME_` 填充。
> ② **类别 ⑦ 的唯一精化**：原写「JSON 字段名（= VO 字段名，二者同源）」—— 本字段**在 VO 上、但不在载荷键集内**，故 ⑦ 精化为「JSON 字段名 ≡ **写侧** VO 字段名」。**该精化不新增豁免、不改 ⑦ 的判据方向**（仍禁借注解改名与漏键），只把键集的对照面钉到**写侧**；主仓机械面 = `C6` 的两条断言（原 `#jsonKeysEqualFieldNames()` 随之改名 `#jsonKeysEqualWriteSideFieldNames()`，见 `0042-verification-landings.md` §2）。
> ③ **与「重放」行的对照留痕**：两条相邻的读侧事实**方向相反** —— `resolveReplayOf` 是**派生判定**（§2.D.3 禁其落字段 / JSON 键），`recordedTime` 是**行事实**（**允许**落字段，且**同样**不进 JSON 键）。两条的共同点只有一句：**都不是判序输入** —— 判序输入是**列表序**，「该锚点是否建序」由 `UnorderedDecisionEvidences` 承载。
> ④ **名字选型的三条对质（留痕）**：取 `recordedTime`（**记录时刻** = 评论行 `TIME_`）—— 不取 `createTime`（出域载荷 `TaskMetadata` 已占位、语义是**任务创建**，别物）；不取 `eventTime`（core 事件契约既有方法名 `getEventTime()` 已占位）；不取 `…At` 后缀（主仓无此形态）。字段类型取 `java.util.Date`（与同族读侧 VO 的 `startTime` / `endTime` 同型，下游原样透传零适配），属**主仓个人规范的具名偏离** —— 该偏离随字段 javadoc 登记在**主仓**，不占本宪章 §4.6。
>
> **硬域自检（本文）**：本批新增标识符只有 `recordedTime` 一个 —— 与 `decisionevidences`（字段）/ `DecisionEvidenceVO` / `UnorderedDecisionEvidences` 归一后**为包含而非屈折**；与 `startTime` / `endTime`（同族读侧 VO 的既有字段，非本机制标识符）**词根不同、非屈折**；本批**无第二个新增标识符**。
>
> **T1 自检**：`recordedtime` 逐字切词后**无 §4.1 T1 禁词**。
>
> **判定结果表**（§7.2 表式；本批**非 §7 四来源之一**，表式照用、**不作关闭依据**）：

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|
| 主仓 #103 | `recordedTime` | ③（公开字段名）· ⑦（**写侧**键集；本字段不在键集内） | §2.B.1 · §2.B-T1 · §2.C.1（与既有字段无屈折对）· §2.D.3 **不适用**（非派生事实） | 通过（形态落地） | 机械可判 | `C6` 的 `#matrixCoversEveryFieldOfVO()`（矩阵含其「读侧专属」格）与 `#jsonKeysEqualWriteSideFieldNames()`（载荷键集不含它）；`C7` / `E12` 的时间断言（见 `0042-verification-landings.md`） |

### §4.6 偏离登记表（宪章 §5.2 四项）
| 标识符 | 规则 | 理由 | 裁决人 |
|---|---|---|---|
| `http://flowable.plus/bpmn`（URI） | §2.E.2「且须含**本机制**的稳定标识路径段」 | 本机制的 URI 采用**框架级单命名空间**：稳定路径段 `flowable.plus/bpmn` 标识的是**框架的 BPMN 扩展面**，非机制级段 —— 属性名已带 `decision` 词根（机制级 URI 会重复），且框架只占一个扩展命名空间。采用**登记**而非宽松重读 §2.E.2 | 主仓维护者（2026-09-25 裁定） |

---

## §5 冲突裁决与偏离登记

### 5.1 优先级（自高至低）

1. **ADR-0042 已冻结的名字与既有标识符** —— 不得因本宪章改名（改名 = 球门修订或破坏性变更，须走 ADR 受限重开）。
2. **中立性**（§2.B T1 / T2）
3. **术语可分（§2.D.2）+ 形近（§2.C）** —— 撞则改**新增**的那一侧，既有侧不动。
4. **主仓惯例**（§4.2 第 6–9 条）—— 默认遵循；偏离可登记，不阻断。

### 5.2 偏离登记

一切偏离写入 §4 对应表并附**四项**：标识符 / 规则 / 理由 / 裁决人（偏离类登记的住所 = **§4.6**）。**未登记即视为违规**。

### 5.3 规则间不构成冲突的情形

- **T3 取值字面量**（§2.B.4）与 T2 不冲突：前者不在后者的适用对象内，是**划界**而非**豁免**。
- **包名 / 模块名**只受 T1：§1.2 已降档，故其余规则对它们**不适用**，不构成冲突。

---

## §6 与 ADR-0042 / `CONTEXT.md` 的关系与越界声明

- **与 ADR-0042**：本宪章**不修改** ADR 的任何条款，只把 §3「中立性核对清单」①③ 与 §3「命名约束（钉死）」落成可判形式。ADR 冻结的名字在本宪章内**不可动**（§5.1 第 1 条）。
- **与 `CONTEXT.md`**：`CONTEXT.md` 是术语的**唯一语义来源**；本宪章 §2.D.4 是术语到**词根**的唯一映射表。二者分工：`CONTEXT.md` 管「这个词什么意思」，本宪章管「这个词落到标识符时长什么样」。
- **越界清单**（详见 §1.3）：ADR-0042 第 3 节 ② 归 **探索期决议（证据载体）**；④⑤ 归**面 7**；断言形态与落点归**面 6**。
- **承载面的点名**（本宪章只给判据，承受方另有其来源）：§2.B.5 必填性矩阵 → **探索期决议（证据载体）**；§2.D.3 派生概念读侧命名 → **探索期决议（证据载体）**；§2.D.4 ④ 待登记词根 → **模块与构建**（`启用 / 禁用`）、**拉管线与出站缝**（`运行暂停`）、**位点服务与提交模型**（`表态比较面`）；§2.E URI 最终字符串 → **节点声明四属性与 BPMN schema**；§2.A.2 断言形态与落点 → **面 6**。

---

## §7 关闭条件（面 1 四来源的义务）

面 1 的四来源（节点声明 / 证据载体 / 幂等与重放 / 位点服务与提交模型）**各自**须交出以下三条，方为「受本宪章约束」，**缺一不得关闭**：

### 7.1 三条义务

1. **回填判定结果表** —— 决议内附 §7.2 表式的逐条判定表（候选标识符 × 各规则 → 三态），使「合规」是可复核的**产物**而非口头声明。
2. **具名断言落点** —— 凡命中「机械可判」类规则的候选，决议须给出**断言落点**（测试类 + 断言名形态）；无落点即不得关闭。
3. **偏离登记** —— 任何偏离写入 §4 并附理由；未登记即视为违规。

### 7.2 判定结果表（统一模板，一来源一份）

**主键 = 「来源 × 候选标识符」**；**一个标识符一行，不合并**。

| 来源 | 候选标识符 | 类别 | 命中规则 | 判定 | 强制机制 | 断言落点 / 处置 |
|---|---|---|---|---|---|---|

- **类别**：§1.2 的 ①–⑦；
- **命中规则**：§2.A–E 的规则编号（一格可多命中）；
- **判定**：`通过` / `不通过` / `已登记豁免`（**三态，不含「待议」** —— 悬置会让该来源无法关闭）；
- **强制机制**：`机械可判` / `结构保证` / `文档纪律`；
- **断言落点 / 处置**：`机械可判` 列**必填**；`不通过` 与 `已登记豁免` 填理由与 §4 登记位。
