# 0042 验证落点纲领表 —— 断言形态、落点与强制机制

> **日期**：2026-09-26
> **来源**：探索期决议（**验证落点的实现形态：守卫测试四落点 / 中立性五面断言 / stub 契约与 Transport 缝 / 漂移守卫**）
> **定位**：本文件是**验证落点的唯一住所** —— 承 ADR-0042 第 11 节第 6 条的**四落点义务**与中立性核对清单 ①–⑤，并收口面 1–5 各来源推入的**机械可判项断言内容**。给它一张落点表，它能判「该测什么、测在哪、算不算数」。
> **住所唯一**：本文件为唯一住所；ADR-0042 侧**零改动** —— 第 11 节第 6 条保持抽象的落点义务，具体类名住本文件。
> **本件只定落点与形态，不写断言体、不跑实测** —— 断言常量与默认数值属实现期产物（见 §8）。
> **2026-09-26 由探索期决议增补**（八靶子具名击穿实验的落点与断言形态）：§3.1 增测试基座常量 `DecisionBreachExperiments`、§3.2 增 `E21`（类计数 20 → 21）、§5.4 增面 6 的推入项、§9 边界表「八靶子」行改「已落定」。**其余一律零改动** —— `E3` / `E11` / `E13` 的断言与行文未动。
> **前置事实**：`docs/impl/0042-naming-charter.md`（命名约束唯一住所）。
> **写作纪律**：按可公开标准书写 —— 不含真实下游项目名 / 公司名 / 人名；第三方厂商与产品名仅出现在命名宪章 §4.1 的禁词清单内。

---

## §1 三条切分规则

落点表的三条组织规则先立在这里，§2–§4 的三张表是它们的逐条应用。

### 1.1 模块切分规则（按「断言对象是否依赖引擎的运行时行为 / 生成物」）

| 断言对象 | 落点模块 | 理由 / 判例 |
|---|---|---|
| 纯值 / 契约 / 枚举 / 命名 / 反射；**只验调用契约**（`verify` 调用次数与参数） | **原模块**（core 的住 core） | 不需引擎；core 的「纯单测、零 DB、零 Spring」测试分层是既有事实，不破 |
| 依赖引擎的**运行时行为或生成物**：真 `ID_` 序列、SQL 排序、部署期校验结果、引擎 API 抛异常 | **extension**（真引擎基座，见 §3.1） | 只有真引擎能产出这些事实；mock 会让断言退化为「测测试」 |
| Spring 装配 / 关闭矩阵 / 三库 | **starter** | starter 已有 `ApplicationContextRunner` 与 `AbstractIntegrationTest` 两套先例 |

**判例**：探索期决议的「装配器 ↔ 管线一次 `executeCommand`」虽涉及引擎，但断言对象是**调用次数**（`verify` 契约）⇒ 住 extension 单测、**不**需要真引擎跑通整条链。

### 1.2 组织规则（按被测对象，一个类）

- **一个被测类型 / 契约一个测试类**；同一类型被多来源推入时**并在一个类**，来源 → 类映射见 §5。
- 命名沿用主仓既有事实：**纯单测 `*Test`**、**真引擎 `*IntegrationTest`**（两类都落在 surefire 默认 includes 内，零 pom 配置）。
- 本文件的类名是**落点身份**：跨来源引用一律写「类名 + 断言名形态」，**不另立编号面**。

### 1.3 取证手段规则（反射跨模块、源码不跨模块）

| 手段 | 落点 | 理由 |
|---|---|---|
| **反射式**（T1 切词扫描、五个统一命名断言、闭集 / 字段集断言） | **单点住 extension** | extension 依赖 core ⇒ 反射可覆盖双方新增标识符；单点避免同一套词表在两处各写一遍而漂移（命名宪章 §2.A.2 的立意） |
| **源码式**（标记构造来源唯一：扫精确字面量的单一命中） | **住被测物所在模块**（`DecisionEvidenceComment` 在 core ⇒ 守卫住 core） | surefire 工作目录 = 模块 basedir，`src/main/java/…` 才可靠；跨模块相对路径脆 |

### 1.4 标识符宇宙的边界（命名宪章 §1.1 的推论）

- 宇宙 = **本机制新增 / 改动的类型**（固定类清单，非「扫整个包根」），含**常量值字面量**（标记、9 个信号名、10 个维度键、BPMN 属性名与 URI、配置 key）。
- **含 `src/test` 新增类型**（stub / fixture / 测试类名）—— 依据命名宪章 §1.1「私有实现细节**仍受** §2.B-T1 绝对禁词约束」；已在宪章 §4.5 补判例留痕（见 §6.3）。
- **防空转 = 三重保证**：`#identifierUniverseIsNotEmpty()`（规模下限）＋「反射命中的类名集合 == 固定清单」＋「访问源文件数 ≥ N」。三者去掉任一条，反射失效或改名后测试会**永绿**。

---

## §2 core 落点

**共 10 个**：新建 8 个 / 改既有 2 个（C9 / C10 由「模块与构建」推入，见 §5.3）。core 保持**纯单测**（JUnit 5 + Mockito + AssertJ），**不引 DB、不引 Spring、不自举引擎**。

| # | 落点类（模块） | 承哪些推入项 | 关键断言名形态 | 强制机制 |
|---|---|---|---|---|
| C1 | `EventBusTest` **（改既有）** | ADR-0042 第 11 节第 6 条 (a) 前半：`EventBus` 无 publisher 短路；**补 `taskCreated`** 进既有语义方法穷举 | `#shouldBeDisabledWithoutPublisher()`（既有）/ `#allSemanticMethodsShouldNoopWithoutPublisher()`（**补 `taskCreated` 一行**） | 机械可判 |
| C2 | `TaskCreatedEventContractTest` | ADR-0042 第 11 节第 6 条 (a) 后半：`TaskCreatedEvent` 无订阅者零副作用（**按第 4 条定义**：无状态变异 ∧ 无对外发射本机制语义事件）；字段契约（`taskId` / `processInstanceId` / `taskName` / `nodeId` / `assignee`（可空）+ `getEventTime()` ← `createTime`） | `#shouldCarryContractFields()` / `#shouldNotMutateStateWithoutSubscriber()` / `#shouldNotEmitMechanismSemanticEventsWithoutSubscriber()` | 机械可判（三条负向）＋**文档纪律**（「放行内部只读探活」的基线写入类 javadoc，引 ADR-0042 第 11 节第 4 条；**不得**写成「core 运行时零新增活动」） |
| C3 | `CommentTypeExhaustivenessTest` | ADR-0042 第 11 节第 6 条 (b) + 第 5 节读侧硬清单第 1–2 行：新值读侧排除；**靶心 = `CommentTypeConverter.toApprovalAction` 的 `default`**（该 `switch` 带 `default`，不加 `case` 则编译通过、运行时抛异常）；证据组常量 `EVIDENCE_COMMENT_TYPES` **显式 + 单一来源**；「三组」= 两显式 + 业务意见组**隐式补集** | `#everyCommentTypeIsMappedOrInKnownUnmappedSet()` / `#decisionEvidenceHasExplicitCase()` / `#evidenceGroupIsExplicitEnumSetSingleSourced()` / `#businessGroupStaysImplicitComplement()` | 机械可判 |
| C4 | `DefaultActionInferenceStrategyTest` **（改既有）** | ADR-0042 第 11 节第 4 条账本第 ④ 行 + 第 5 节读侧硬清单第 3–5 行：三处推断 = **一处实改 + 两处结构性已排除** | `#evidenceRowsShouldNotOccupyBusinessCommentSlot()`（实改点：`findFirstBusinessComment` 第二遍加证据组排除）/ `#operationCommentInclusiveFilterStructurallyExcludesEvidence()`（`findFirstOperationComment` / `findAllOperationComments` 经包含式过滤，**代码不动、由断言钉死**） | 机械可判 |
| C5 | `DecisionEvidenceMarkerTest` | ADR-0042 第 5 节「标记与 `TYPE_` 必须同源」+ 「标记构造来源唯一」（**源码式**，Q7 (a)+(b)） | `#markerConstructedFromSingleSource()`（受限源码扫描：命中文件数 == 1 ∧ 命中处 == `MARKER_PREFIX`，附「访问源文件数 ≥ N」防空转）/ `#markerIsPrivateAndDerivedFromCommentTypeName()` / `#stripMarkerRoundTripsWithJsonBody()` | 机械可判（源码扫描）＋**结构保证**（`MARKER_PREFIX` / `MARKER_SUFFIX` 私有 + 派生自 `CommentType.DECISION_EVIDENCE.name()`） |
| C6 | `DecisionEvidenceVOContractTest` | 「必填 / 可空矩阵必须写死」的 **core 侧**（Q18(a)）：矩阵表**覆盖字段集 == VO 字段集**（含读侧专属字段 `recordedTime` 的「读侧专属」格）；类别 ⑦ JSON 字段名与**写侧** VO 字段名**同源**（读侧专属字段不在载荷键集内）；**证据面闭集穷举**（`DecisionOutcome` 3 / `DecisionPolicyReason` 7 / `DecisionFailureKind` 7 / `DecisionChainStage` 3 / `DecisionSubjectType` 3 / `DecisionCompleteness` 4 / `DecisionRationaleFactKey` 5）—— 即**中立性 ②「取值域不绑 AI」**的落点 | `#matrixCoversEveryFieldOfVO()`（**形态未动**） / `#jsonKeysEqualWriteSideFieldNames()`（**2026-09-29 由主仓 #103 落地改名**，原名 `#jsonKeysEqualFieldNames()`；对照面由「VO 字段集」改为「矩阵中非读侧专属的字段」） / `#closedValueDomainsAreExhaustiveAndNotBoundToAi()` | 机械可判（反射 + 三态穷举） |
| C7 | `HistoryWorkflowEvidenceReadTest` | 「重放判定面」`resolveReplayOf`（原行 ⇒ `null`；重放 ⇒ 返回原行；**不同键互不判为同一次决策**）；读侧**容错条款**（判别式不可解析 / 载荷损坏 ⇒ 只跳过该条有效证据投影、**审批轨迹行不消失**；次要枚举取值未知 ⇒ **字段级降级 `null`**；`schemaVersion` 高于已知最大值 ⇒ 尽力读、**不做版本门禁**）；挂载层级（会签挂子记录、父 VO 恒空）/ `decisionEvidences` **恒返回空集合**（软回退） | `#resolveReplayOfReturnsOriginalOrNull()` / `#distinctKeysAreNeverJudgedAsReplay()` / `#corruptedRowSkipsProjectionOnly()` / `#unknownOutcomeSkipsProjectionOnly()` / `#unknownSecondaryEnumDegradesToNullOnly()` / `#higherSchemaVersionIsStillProjected()` / `#countersignRowsAttachToSubRecordOnly()` / `#decisionEvidencesDefaultsToEmptyCollection()` / `#overLimitRowSkipsProjectionButKeepsHistoryRow()`（**G6**，由「模块与构建」推入：写入侧上界与读侧护栏超限时**只跳过该条证据投影**、审批轨迹行不消失） / `#guardAppliedBeforeParsing()`（**G7**，由探索期决议的边界推入：`MAX_PARSE_BYTES` / `MAX_NESTING_DEPTH` 的检查先于任何 JSON 解析，受限源码扫描） / `#eachEvidenceCarriesItsOwnCommentRowTime()`（**2026-09-29 由主仓 #103 落地补**：多行证据各自带出所属评论行的 `TIME_` —— 两行载荷逐字相同 ⇒ 时间只可能来自各自那一条评论行） / `#unorderedAnchorStillCarriesRowTime()`（同批：「未建序」分支照常带时间 —— **序不可判 ≠ 时间缺失**） | 机械可判（纯值 + mock 读侧输入） |
| C8 | `DecisionContextSourceTest` | 「枚举成员与 `dropPriority`」：四成员穷举、`dropPriority` **互异且非空**、**无屈折对** | `#membersAreExactlyFour()` / `#dropPrioritiesAreDistinctAndNonZero()` / `#membersHaveNoInflectionPairs()` | 机械可判 |
| C9 | `DecisionEvidenceReadGuardTest` | 「模块与构建」的**读侧护栏约束 G1 读侧 / G3b / G4**：两常量成对且为正；**`MAX_PARSE_BYTES ≥ MAX_EVIDENCE_BYTES`**（写出侧上界一经成立即机械关闭「写得进、读不出」）；**深度上限 ≥ 最深合法 fixture 实测深度**（Java 常量 fixture，**不引金样本**） | `#readGuardConstantsArePairedAndPositive()` / `#parseBytesCoverTheWriteGuardUpperBound()` / `#nestingDepthCoversDeepestFixture()` | 机械可判（反射 + 数值不等式） |
| C10 | `DecisionEvidenceWriteGuardTest` | 「模块与构建」的**写入侧上界约束 G1 写侧**：`MAX_EVIDENCE_BYTES` 具名且为正 | `#writeGuardConstantIsNamedAndPositive()` | 机械可判（反射） |

> **落点订正（2026-09-26，实现期一手事实，如实披露）**：`C9` / `C10` 原承的 **G2 / G3a / G7** 已**改落**（**判据与验收形态一字未动**，改动仅为「测在哪」）：**G2**（读侧常量与出域 clamp 不同源）与 **G3a**（写入上界 ≥ 两方向 clamp 上限之和）要读 **extension 侧**的出域 clamp 常量，而 **core 不得依赖 extension** ⇒ 改落 **extension 的 clamp 守卫**；**G7**（护栏在解析之前生效）扫的是**读侧解析实现**，该实现不在本表首落的来源内（其交付物明确「无读侧投影改动」），落此只会**恒真** ⇒ 改成随**读侧投影**的实现落点走。C9 的 **G4** fixture 由 `DecisionFixtures`（extension 测试基座，core 不可见）改为 **core 测试树内的 Java 常量**，**结构仍不落资源文件** ⇒ 「不引金样本」不变。**断言名清单同步收缩**（原列的 `#readGuardIsNotTheEgressClampConstant()` / `#guardAppliedBeforeParsing()` / `#writeGuardCoversBothDirectionClampUpperBounds()` 随之移出）。**不触发受限重开**：本表不是 ADR 条款，且 G1–G7 的**约束集、判据、机械面**均未动。同批订正见 `docs/impl/0042-module-and-build.md` §4。

> **G7 落点落地登记（2026-09-27，core 读侧投影）**：上面那条订正里的 `#guardAppliedBeforeParsing()` **已随读侧投影实现落进 C7 家族**（本表 §2 的 `C7` 行），**判据、验收形态、受限源码扫描手法（精确字面量 + 命中文件数 + 访问源文件数下限防空转）均未动**。**本件的会话裁定**：该断言**并入 C7 家族**、**不另立落点**（§2 的 core 落点仍为 10 个）。**同批一并登记两条 C7 断言名的显式化**（本表首版只列了「载荷损坏」一支与「次要枚举降级」一支，而 ADR-0042 第 5 节容错条款的另两支在同一次实现中必须可判）：`#unknownOutcomeSkipsProjectionOnly()`（**判别式**不可解析 ⇒ 只跳过该条，与「载荷损坏」分列）与 `#higherSchemaVersionIsStillProjected()`（`schemaVersion` 更高 ⇒ 尽力读、不做版本门禁）。**两支的判据本就在 ADR 条内，本批只补断言名与覆盖，不改任何判据。**

> **C5 的 `N`、C 系列任何规模下限常量**归实现期默认数值（见 §8）。
> **不在 C7 的**：读侧顺序与 tie-break 的**真引擎**部分 —— 按 §1.1 归 extension（E12）。

---

## §3 extension 落点

**共 21 个类（全部新建）**，外加 §3.1 的测试基座。

### §3.1 测试基座（**测试专用类型，非测试类**）

| 载体 | 形态 | 依据 |
|---|---|---|
| `ExtensionTestEngine` | **standalone in-mem 引擎，固定 H2**；**不读 `flowable.test.db`**、不引 Spring、不引 Testcontainers | ADR-0042 第 11 节第 6 条 (c) 要求「跑既有 BPMN」；固定 H2 使 extension 在既有矩阵的**四个 job 上行为一致**（§7） |
| `StubDecisionTransport` | 默认 Provider 的**可注入 Transport 缝**实现；固定 fixture（Java 常量） | ADR-0042 第 7 节「Transport 缝是公开 SPI」；「stub 是契约」 |
| `DecisionFixtures` | **Java 常量**形态的固定 fixture（含 `SYSTEM` / `USER` 两条直提桩的载荷） | ADR-0042 第 11 节第 6 条 (c)「**不引入金样本文件**」；固定 fixture 不落资源文件 ⇒ **recorded 无栖身处** |
| `DecisionBreachExperiments` | **击穿实验的坐标常量**（母实验 `EXPERIMENTS` 恰八项 / 变体 `VARIANTS` 可空）—— **两个并列承载位，不得合并**（禁 `isVariant` 布尔、禁以「母实验名为空」兼职区分） | **由探索期决议推入**：八项具名击穿实验的具名形态与准入条件 ⑥ 的机械面；内部形态唯一住所 = `docs/impl/0042-kill-switch-experiments.md` §4.2 |
| （不建）`src/test/resources` | extension 测试树**不建资源目录** | 「recorded 不进 v1」的**结构保证**（§6.2） |

### §3.2 落点表

| # | 落点类 | 承哪些推入项 | 关键断言名形态 | 强制机制 |
|---|---|---|---|---|
| E1 | `NamingCharterComplianceTest` | 命名宪章 §2.B（T1 切词 / T2 替换测试与必填性联动 / T3 豁免）/ §2.C（形近硬域·软域、不得引入第三变体）/ §2.D（回指唯一、成对词根、派生不落字段）/ §2.E（URI 唯一性、成对入常量、禁裸字面量）；**并**收口各探索期决议各自的「命名类」推入项 | `#typeNameFreeOfDomainWords()` / `#methodNamesFreeOfDomainWords()` / `#constantNamesNotSingularPluralWithinType()` / `#fieldNamesNotSingularPluralWithinType()` / `#t3ExemptLiteralsAreEnumMembers()` / `#signalNamesPrefixedAndFormatted()` / `#namespaceUriIsUniqueAndPairedWithPrefix()` / `#identifierUniverseIsNotEmpty()` / `#testTypesStillSubmitToT1()` / `#allNewTestClassesMatchSurefireIncludes()` | 机械可判（反射 + 受限源码扫描）＋**结构保证**（SSOT 常量类） |
| E2 | `DecisionNodeDeclarationTest` | 四属性名与 URI **成对收口**单一常量类、URI 唯一性（≠ 两个引擎保留命名空间）、属性名小驼峰；「令牌解析」的**解析**部分（token = 枚举常量名原文、大小写敏感、仅两侧空白容忍） | `#attributesAndUriAreDefinedInSingleConstantClass()` / `#uriIsNotAnEngineReservedNamespace()` / `#attributeNamesAreCamelCase()` / `#tokenParsingAcceptsEnumNameVerbatim()` | 机械可判 |
| E3 | `DecisionNodeDeclarationValidatorTest` | 部署期阻断（**presence-based**、本机制 URI 下未知属性名 / 未知扩展元素 / 重复 token / 空 token 一律阻断、`decisionPolicy` 必填）；令牌阻断部分 | `#blocksOnUnknownAttributeUnderMechanismUri()` / `#blocksOnUnknownExtensionElement()` / `#blocksOnDuplicateOrBlankToken()` / `#blocksOnMissingDecisionPolicy()` / `#doesNotBlockWhenDeclarationAbsent()` / `#doesNotBlockWhenGlobalSwitchOffButDeclarationLegal()` | 机械可判（真引擎部署） |
| E4 | `DecisionContextAssemblerTest` | 有效数据源集 fallback 链（节点级 › 应用级 › 框架级 ∅）、**缺席取应用默认 / 显式空集恒 `NO_SOURCE_DECLARED`**、`decisionDataSources` **只被装配器读一次**、零 token 判定在装配器内（**不得由四段全 `null` 反推**）；「空装配是显式事实」+ 单 `executeCommand` 一致快照 | `#effectiveSourcesFollowNodeThenAppThenFrameworkChain()` / `#explicitEmptySetAlwaysYieldsNoSourceDeclared()` / `#absentDeclarationTakesAppDefault()` / `#emptyAssemblyIsExplicitNotInferredFromNullSegments()` / `#zeroTokenDecisionHappensInsideAssembler()` / `#readsDataSourcesExactlyOnce()` / `#snapshotUsesSingleExecuteCommand()` / `#absentOrEmptyDefaultSourcesBehaveAsEmptySet()`（由「模块与构建」推入：应用级默认数据源集**缺失或空集一律按空集**、禁隐式全集兜底） | 机械可判 |
| E5 | `DecisionPayloadTest` | 载荷三态（**未声明段 = `null`** 与**声明但空 = 空集合**两态可区分）、四段定型外壳 | `#absentSegmentSerializesAsNullAndDeclaredEmptyAsEmptyCollection()` / `#segmentsAreExactlyFourTypedShells()` | 机械可判 |
| E6 | `DecisionClampTest` | clamp：整段丢弃序 = `dropPriority` **降序**、**不可放大**、丢到全空仍超 ⇒ 兜底拒绝、**段内永不动刀**、单位 UTF-8 字节 / 硬上限 32 KiB / v1 无应用侧调参口；入站 clamp 与直提**共用 clamp、不共用策略** | `#clampNeverRaisesAppConfiguredLimit()` / `#dropsSegmentsInDescendingDropPriority()` / `#fallsBackToRejectionWhenAllSegmentsDropped()` / `#neverSplitsWithinSegment()` / `#outboundAndDirectShareClampButNotPolicy()`；**（2026-09-27 由出域面补）** `C9` 移出的 `#readGuardIsNotTheEgressClampConstant()` / `#writeGuardCoversBothDirectionClampUpperBounds()` 落本落点类（G2 / G3a；判据与形态一字未动） | 机械可判 |
| E7 | `DecisionPolicyTest` | 结果位互锁（`permitted` / `persistable` 为 `true` ⇒ 对应内容位**必非空**）、双向服务（`applyOutbound(DecisionPayload)` / `applyInbound(String)`）、`DecisionProcessingRecord` **只承加工事实**（「说不」住结果位） | `#permittedTrueImpliesNonEmptyPayload()` / `#persistableTrueImpliesNonEmptyContent()` / `#processingRecordCarriesFactsOnlyNotVerdict()` | 机械可判 |
| E8 | `DecisionEvidenceSubmissionTest` | **ADR-0042 第 11 节第 6 条 (c) 的证据面主落点 = Q18(a) 的主落点**：**四列矩阵逐格**（A 经出站调用 / B 直提 / C 按政策未产出 / D 失败）＋ 「直提双射」（出处组三字段全 `null` ⇔ 直提） | `#producesExactlyFourPathColumns()` / `#matrixCellHoldsForEveryProducedColumn()`（逐格表驱动）/ `#directSubmissionBijectionHolds()`；**（2026-09-27 由证据写入与提交模型补）** `#attestedDataSourcesThreeStatesAreDistinguishable()`（承接探索期决议的边界推入：自述位 `null` **省略键** / `[]` / 有值三态可区分，且省略只落该字段） | 机械可判 |
| E9 | `DecisionEvidenceWriterTest` | 标志**互锁四联**（每方向 `PARTIAL ⇔ (redacted ∨ truncated)`；`(FULL ∨ NO_PAYLOAD) ⇒ ¬redacted ∧ ¬truncated`；`NO_PAYLOAD ⇔ 载荷字段空 ∧ ¬∧¬`；`FULL / PARTIAL ⇒ 载荷非空`）+ `failureKind` **双向守卫三条** + 标志推导（**状态驱动两显式事实**、出域三值 / 入站四值、**出域取不到 `RESTRICTED`**、装配器丢不计标志 vs clamp 丢计 `truncated`）+ 两类未产出物质化 + **入站两降级先分类型** | `#flagInterlockHoldsPerDirection()` / `#inboundFailureKindExceptionIsBidirectional()` / `#flagsAreDerivedFromTwoExplicitFacts()` / `#outboundNeverTakesRestricted()` / `#inboundExceptionIsAnErrorButPolicyRejectionIsNot()` / `#writerMaterializesBothNonProducedOutcomes()`；**（2026-09-27 由证据写入与提交模型补）** `#writeGuardRefusesOversizedRowWithoutNewEnumValue()`（模块与构建 §4 的写入侧护栏行为面：超限拒写归 D 列 `INTERNAL_ERROR`，且闭集规模一律未动）与 `#subjectIsCarriedInJsonNotUserId()`（主体只走 JSON 三字段，证据面无任何 userId 类键 / 字段） | 机械可判 |
| E10 | `SuggestionAdmissionContractTest` | 准入规则**逐条 11 条**（正例通过 / 反例抛 `SuggestionAdmissionException` 且 `reason` 取对应值）+ 原因枚举**恰好 13 值** + **每取值须有可达构造点**（Q15(a)：键集相等）+ `null` 与去空白空串**同判**（`idempotencyKey` / `actionSummary` 两处）+ 缺身份 = **唯一不物质化**的准入失败 | `#eachRuleHasAReachableConstructionPoint()`（键集恰等于 13 值集）/ `#rejectingCaseCarriesItsOwnReason()` / `#nullAndBlankAreJudgedIdentical()` / `#missingIdentityIsTheOnlyUnmaterializedRejection()`；**（2026-09-27 由推面位点服务补）** `#positiveCasesPassPerRule()`（11 条规则的正例逐条通过）/ `#producedRowKeepsTheProvenanceBijection()` / `#oversizedInboundPayloadIsDeclaredAsInboundProcessingFailed()` / `#submitUnderGlobalOffIsInert()` / `#unknownAnchorDegradesWithoutRowOrFailure()` / `#writeFailureTakesOneOfTheTwoDegradedCauses()` / `#writerDefenseYieldsInternalErrorAndRethrows()` / `#rejectionWhoseRowCannotLandDegradesInsteadOfFailing()` | 机械可判 |
| E11 | `ComparableActionAvailabilityTest` | 可用动作三行映射**真值表逐格**（`isMultiInstance` / `isRuntimeMultiInstance` / `isInitiatorDecisionTask` 三布尔组合 × 四动作）+ **镜像漂移守卫**（镜像来源 = core 三个守卫 `TaskValidation.validateMultiInstance` / `validateNotMultiInstance(…, false)` / `validateNotMultiInstance(…, true)`）+ `ComparableAction.MEMBERS` **显式且恰好四值** + `isComparable` 与 `MEMBERS.contains` **同源等价** + `AUTO_COMPLETE` 不可达（它属 `CommentType` 侧） | `#truthTableMatchesCoreGuardsCellByCell()` / `#mirrorDoesNotDriftFromCoreTaskValidation()` / `#membersIsExplicitAndExactlyFour()` / `#isComparableIsSameSourcedAsMembersContains()` / `#autoCompleteIsNotAnApprovalAction()` | 机械可判（mock 引擎对象对拍真 core 守卫） |
| E12 | `DecisionEvidenceReadOrderTest` | 读侧顺序与 tie-break（锚点内 `TIME_` **升序**、引擎实为**降序**须重排；同毫秒按**数值 `ID_`** 升序；`ID_` 非数值 ⇒ **整锚点不判** + 信号；不可判时**无任何重放标注**）+ **引擎假设守卫**（默认 `DbIdGenerator` 下后写 ⇒ 数值 `ID_` 更大；假设被击穿 ⇒ **降级路径须触发** —— 守的是**假设**，不是引擎实现）+ 探索期决议的 `D4` 漂移守卫 | `#readsReSortToTimeAscendingWithinAnchor()` / `#sameMillisecondBreaksTieByNumericId()` / `#nonNumericIdMakesWholeAnchorUndecidable()` / `#noReplayMarkingWhenUndecidable()` / `#dbIdGeneratorMonotonicAssumptionHolds()` / `#degradesWhenAssumptionIsBroken()` / `#doesNotAssumeEngineAscendingOrder()`；**（2026-09-29 由主仓 #103 落地补，七条具名一字未动）** `#readsReSortToTimeAscendingWithinAnchor()` 内补两行断言：两条证据各自带出的记录时间 == 各自评论行的 `TIME_`（载荷里没有时间 ⇒ 只能来自行本身） | 机械可判（真引擎） |
| E13 | `DecisionPipelineTest` | **闸门链次序**（Q14(b)：`InOrder` 逐阶段 + **回调内零引擎命令 / 零网络 / 零状态写入**）+ 空装配短路 + 运行暂停（`NO_SUGGESTION_BY_POLICY` / `SUSPENDED`）+ 发起前锚点可见性检查（有界等待超时 ⇒ **不触发、无记录、不新增第四态**）+ 专属有界池（我满 ⇒ **日志 + 独立计数、不落证据行**）+ `catch (Exception)` **绝不 rethrow** + **事件面关闭不 fail-fast**（启动期 `WARN`） | `#stageOrderMatchesDeclaredChain()` / `#callbackPerformsNoEngineCommandNoNetworkNoStateWrite()` / `#emptyAssemblyShortCircuitsPipeline()` / `#pauseYieldsPolicyReasonSuspended()` / `#anchorInvisibilityDoesNotTriggerAndLeavesNoRecord()` / `#poolSaturationLeavesNoEvidenceRowButLogsAndCounts()` / `#neverRethrowsOnAnyException()` / `#closedEventChannelDoesNotFailFast()` / `#observationAttributionFollowsOutboundDispatch()`（**2026-10-02 由主仓 #105 补**：观测归因 / 链路按「**是否实际发起过主链路出站调用**」这一事实填值 —— 产出 / 模型主动不产出带 `modelId` + `chainStage`；出站失败 `chainStage = PRIMARY` 且 `modelId` 留空；未出站两者的失败恒空）/ `#localShortCircuitFallsToPolicyColumnWithoutProvenance()` 与 `#failureProvenanceFollowsOutboundDispatch()`（**2026-10-02 由主仓 #104 补**：Provider 缝本地短路落 C 列且**无出处痕迹**〔出处组 / `modelId` 空、不计错误、依据键按缺上下文取 `MISSING_INPUT` / 缺凭据取 `POLICY_RULE`〕；失败行的证据面出处组按「**确已出站**」填 —— 已出站整组填、未出站整组空，**与观测面同一判据**） / `#refusedDraftFallsToMinimalRowThatKeepsChainStage()`（**2026-10-02 补**：前述填值口径补落一条支路 —— 被写入器拒绝的草稿改落最小化 `INTERNAL_ERROR` 替代行后，观测的 `chainStage` **承继被拒草稿**，故替代证据行与观测行恒同值） | 机械可判 |
| E14 | `DecisionOutcomeMappingTest` | **结局映射表 20 行逐行落位**（承重交付物；**2026-10-02 由主仓 #104 补本地短路两行，18 → 20**）+ 「**计错判据 = `failureKind != null`**」 | `#everyMappingRowIsReachable()`（行数 == 20 常量）/ `#failureOnlyCountsErrorsWhenFailureKindNotNull()` / `#noSuggestionByPolicyNeverCarriesFailureKind()` | 机械可判 |
| E15 | `DecisionProviderContractTest` | 出站响应契约**互斥性**（`declined` 与 `suggestedAction` **必居其一**；皆缺或皆在 ⇒ `RESPONSE_UNPARSEABLE`；`chainStage` 缺失 ⇒ 同判）+ **幂等键不上线**（请求体不含任何身份字段）+ **跨硬域同源**（响应契约字段 `provider` / `chainStage` / `degraded` / `modelId` 与证据 VO 同名字段**两端同名同源**）+ 默认方言四段**无信封** + **凭据从不是载荷的一部分** | `#declinedAndSuggestedActionAreMutuallyExclusive()` / `#unparseableJudgementMatchesAllThreeViolations()` / `#requestBodyCarriesNoIdentityField()` / `#responseFieldNamesMatchEvidenceVOExactly()` / `#payloadCarriesNoEnvelope()` / `#credentialIsNeverPartOfPayload()` / `#localShortCircuitFactoryRejectsNonLocalReasons()`（**2026-10-02 由主仓 #104 补**：本地短路工厂只收两值，框架侧原因与 `MODEL_DECLINED` 在构造期即拒） | 机械可判 |
| E16 | `DecisionCredentialTest` | **不透明类型三约束**（**无有效 `toString()`** / **无状态提取方法** / **不实现序列化契约**）+ 解析侧任何失败**折叠进** `OUTBOUND_CREDENTIAL_INVALID`（与 Transport 的 401 / 403 同一枚举） | `#hasNoMeaningfulToString()` / `#exposesNoStateExtractionMethod()` / `#doesNotImplementSerializationContract()` / `#resolverFailureCollapsesIntoCredentialInvalid()` | 机械可判（反射三约束）＋ 机械可判（折叠行为） |
| E17 | `DecisionRuntimeControlTest` | 运行暂停控制面（`pause()` / `resume()` / `isPaused()`；状态**由应用持有**、框架不持久化、不带管理端点、不读 Spring Environment）+ 命名宪章 §2.D.3 词尾禁（**不得**出现 `…Status` / `…State`） | `#controlStateIsOwnedByApplication()` / `#pauseAndResumeAreIdempotentAndQueryable()` / `#noStatusOrStateSuffixedMembers()` | 机械可判 ＋ **结构保证**（状态归属） |
| E18 | `DecisionObservationContractTest` | **全部八组**：观测面常量契约（9 信号名 + 10 维度键：**名与值两侧** T1、值须匹配 `flowable.plus.decision.` 前缀 / 全小写点分隔 / 不带 `METRIC` · `COUNTER` 后缀、常量名 SCREAMING_SNAKE）/ 硬域无屈折对 / 闭集值（`direction` ∈{`input`,`output`}；`cause` ∈{`anchor_lost`,`instance_ended`}；`reason` · `contextSource` 取枚举常量名小写蛇形）/ 观测事实 **16** 字段 + 必填可空矩阵（`outcome` **可空**、`severity` 必填）/ **消费隔离**（`DecisionObserver` 抛异常 ⇒ 日志与指标**均不受影响**、不上抛、结局与流程状态不变）/ **观测面禁载**（Q13(a)：字段集恰等 16 常量 ∧ 类型白名单 ∧ 不含证据 VO 字段名 ∧ **载荷哨兵不出现于日志与 tag value**） | `#signalNamesAndValuesArePrefixedFormattedAndT1Clean()` / `#dimensionKeysHaveNoInflectionPairs()` / `#closedSetValuesAreLowerSnakeOfEnumNames()` / `#fieldSetEqualsDeclaredSixteenWithAllowedTypes()` / `#severityIsRequiredAndOutcomeNullable()` / `#observerFailureIsIsolatedAndNeverThrows()` / `#noEvidenceCredentialOrIdentityFieldAppears()` / `#payloadSentinelNeverLeaksIntoLogOrTags()` | 机械可判（含一条运行期哨兵断言） |
| E19 | `DecisionStubContractTest` | 探索期决议第 3 项（**stub 是契约、recorded 不进 v1**；默认 Provider 暴露**可注入 Transport 缝** + 固定 fixture）+ 「stub 是契约」 + 「v1 可达性」（**测试内**须提供最小 `SYSTEM` 直提生产者桩，**且同时覆盖 `USER`**）+ ADR-0042 第 7 节 | `#defaultProviderAcceptsInjectedTransport()` / `#fixturesAreInertJavaConstantsNotRecordedFiles()` / `#systemDirectStubProducesSubjectTypeSystem()` / `#userDirectStubProducesSubjectTypeUser()` / `#noRecordedFixtureResourceExists()` | 机械可判 ＋ **结构保证**（recorded 不进 v1） |
| E20 | `DecisionDisabledEquivalenceTest` | ADR-0042 第 11 节第 6 条 **(c)**：全局关态跑既有 BPMN，与「机制不参与」**参照装配**运行时对拍（`getApprovalHistory` 全量字段相等）—— 参照态 = **不注册本机制组件**。**内部形态（三态 / 参照装配 / 对拍字段 / 归一化 / 各面粒度 / 防空转 / fixture 覆盖面）见 `docs/impl/0042-equivalence-harness.md`（唯一住所）** | `#globalOffEqualsMechanismAbsentFieldByField()`（面②）+ 面①③⑤ 各一条 + 开态正向对照 + 装配集恒等 + 归类守卫（共 7 条，清单见上述文件 §8） | 机械可判（真引擎） |
| E21 | `DecisionBreachExperimentsTest` | **击穿实验的元守卫**（**由探索期决议推入**）：母实验规模与名逐字对账（准入条件 ⑥）+ 变体行母实验名在册 + **常量不含结论字段**（「扩实验集不扩结论集」的机械面）+ 击穿实验承载文件的**受限源码扫描**（准入条件 ①：无撬私有状态调用、无非公开包 import，附「访问源文件数 ≥ 8」防空转）。**内部形态唯一住所 = `docs/impl/0042-kill-switch-experiments.md`（§3 / §4.2）** | `#experimentsAreExactlyTheEightNamedTargets()` / `#everyExperimentNamesItsMainLanding()` / `#mainLandingsCoverAllFourInvariantsAndStayDistinct()` / `#variantsCarryTheirMotherExperimentName()` / `#experimentsCarryNoVerdictField()` / `#breachLandingSourcesAvoidPrivateStateAccess()` | 机械可判（反射 + 受限源码扫描） |

> **E1 的 `#fieldNamesNotSingularPluralWithinType()` 是唯一调用点** —— core VO 与 extension 各类型的字段名屈折检查都在此触发（§1.3）。
> **E20 的内部形态**（参照装配如何构造 / 对拍字段清单 / 动态字段归一化 / 三态与防空转 / fixture 覆盖面）**归 K2-001，已落定并成文于 `docs/impl/0042-equivalence-harness.md`**（唯一住所；本文件的 `E20` 行与本节只留指针，不摘抄）。
> **E3 / E11 的击穿实验**（靶子⑧ / 靶子⑥）归 **探索期决议**，本件只给机制侧断言的落点。
> **E21 的计数守卫形态**：`#experimentsAreExactlyTheEightNamedTargets()` 只对 `EXPERIMENTS` 断言「恰八」；**不得**出现「常量表总行数 == 8」一类断言（合法变体会将其打红）。

> **E12 的实现期一手事实（2026-09-27，core 读侧投影，如实披露）**：ADR-0042 第 9 节第 7 条要求 `ID_` 不可解析时「该锚点整体不判原 / 重放」，而**该「不判」在 core 侧没有可用的承载位** —— `resolveReplayOf` 是派生判定，§2.D.3 禁其为派生事实落字段，`ApprovalRecordVO` / `CountersignSubRecord` 的证据面又只许各加一个 `decisionEvidences`。故 core 读侧投影的**现行实现**取「锚点内 `TIME_` 升序 + `ID_` 可解析时按数值兜底」，**非数值 `ID_` 只落一条 `WARN`、不建立同毫秒次序**（详见 `DecisionEvidenceRowProjector` 的类型 javadoc）。**`#nonNumericIdMakesWholeAnchorUndecidable()` / `#noReplayMarkingWhenUndecidable()` 的机械面（含「不判」的载体选型）归本行裁定** —— 本件**不私设字段、不代裁**。

> **E14 的行集读法与「每行可达」的机械形态（2026-09-27，extension 工程基座与观测一族，如实披露）**：探索期决议 §13 的表共 **21 行**（`0a` / `0b` / `0c` + 编号 1–18）。本表的 **20 行** = **产出观测的全部结局行** —— 成功路径 **1 行**（决议表未单列；其落位由 ADR-0042 第 10 节与探索期决议的对齐表给出）+ 决议表**编号 2–18 的 17 行** + **Provider 缝本地短路 2 行**（`CONTEXT_UNAVAILABLE` / `CREDENTIAL_UNAVAILABLE`，**2026-10-02 由主仓 #104 补** —— 产生点落 Provider 缝，是决议表立法时尚不存在的通道，故**不在**编号 2–18 内）；**不触发**的四类（未激活 / 节点未声明 / 事件面关闭 / 无活锚点，即 `0a` / `0b` / `0c` + 编号 1）产出**零观测**、**不占表行**（与 ADR-0042 第 10 节「不触发的四类产出零观测」同向，亦不新增第四叶子态）。**「每行可达」的机械形态** = **该行的落位能构造出一条观测事实**（行数对账 `ROW_COUNT == 20` ∧ 逐行可落成观测）；「该行的产生点在机制内可到达」由决议表具名保证、属管线侧实现。**判据与验收形态未动**（仍是「逐行落位」+「行数 == `ROW_COUNT` 常量」，计数随本地短路两行同步）。**勘误一并披露（决议表行 7 措辞）**：探索期决议 §13 行 7 的「装配器异常 / **入站** clamp 兜底拒绝」中「入站」为笔误 —— 「入站 clamp 超限」已由行 14 承载（产出态 + `INBOUND_PROCESSING_FAILED`），行 7 的「兜底拒绝（丢到全空仍超限）」按探索期决议的边界推入只能是**出域** clamp；本件的实现与断言按「出域」读，**不两说并存**。

> **E2 / E3 的断言集与四条实现期口径（2026-09-27，节点声明与部署期校验，如实披露）**：**（一）E3 的断言集 = 本表 `E3` 行的六条具名断言（逐字）+ 九条分支断言** —— 六条命名只覆盖部分规则（「非 `UserTask` 承载」「启用开关取值非法」「写了其它属性却缺启用开关」「未知 token」「key 不在注册面」「启用态缺目标」「嵌套子流程内的非法声明」**七条阻断分支**与「显式禁用」「启用但无有效数据源」**两条合法态**在六条命名中无对应项），而 ADR-0042 第 11 节第 3 条要求阻断条件与三条穷举的**对账完备**，只靠四条阻断命名断言无法承载完备性 ⇒ 补齐为逐分支断言。**六条具名形态一字未动**，补齐项全部落同一落点类；**逐分支对账表住 `docs/impl/0042-kill-switch-experiments.md` §5.1（对账表本体在 §5.1.1）**（靶子⑧ 判定表达的唯一住所）。**（二）E2 增一条解析断言** `#enabledParsingAcceptsOnlyLowercaseLiterals()` —— 读取器的「只认小写字面量」规则若无此断言便只剩 E3 的部署期后果，**解析口径本身**无可判面。**（三）四条实现期口径**：① **「禁裸字面量」的判据取带引号形态**（属性名是普通英文词组，出现在标识符片段里不是另一份字面量来源；命名空间按原样匹配）—— 判据的可操作形态钉死在命名宪章 §4.5 判例留痕 ①，**§2.E.1 规则文本未动**；② **校验器的 key 集由构造期注入**（两个 `Set<String>`，starter 收口后传入；空集合法、`null` 非法）—— 注册表是 starter 包内类型，extension 不得引用，故不新增公开类型；③ **数据源声明的整值空白 = 显式空集**（零 token，合法），与 `",x"` / `"x,"` 的空 token（阻断项）分列；④ **启用开关不做两侧空白容忍**（`" true "` 判非法）—— ADR-0042 第 7 节只给数据源 token 授予「空白容忍」，未授予即不推定（宁可当场地响，不静默降级成「未声明」）。**四条都不改任何判据与验收形态**：E3 仍是「非法 ⇒ 部署期阻断（非 warning + 零痕迹）/ 合法 ⇒ 部署成功」，E2 仍是「常量成对 + 唯一性 + 小驼峰 + token 解析」。

> **一条对个人规范的有意偏离（同批登记）**：token 按<b>枚举常量名原文</b>匹配，即调用 `Enum#name()` —— ADR-0042 第 7 节把 token 冻结为「枚举常量名原文且**不引映射表**」，故给该枚举加「序列化属性」等于给该契约另造一份映射，与 ADR 相反 ⇒ 此处以**仓库文档化决策优先**（判据与代价写在 `DecisionNodeDeclarationReader#parseDataSourceToken` 的 javadoc：常量改名即改 BPMN 契约，属破坏性变更）。遍历一律取 `EnumSet.allOf(...)` 而非 `values()`。
>
> **E3 的「零痕迹」机械面（同批落地）**：断言「失败部署不落库」= 阻断前后 `createDeploymentQuery().count()` 相等 —— 这条把「非 warning 才阻断、且阻断发生在任何持久化之前」变成可判事实，**不新增落点**（同属 `E3` 家族）。

> **E8 / E9 的断言集与八条实现期口径（2026-09-27，证据写入与提交模型，如实披露）**：**（一）E8 的断言集 = 本表 `E8` 行的三条具名断言（逐字）+ 一条承接探索期决议边界推入的断言** `#attestedDataSourcesThreeStatesAreDistinguishable()` —— 探索期决议只落字段形状、未加序列化注解，而「`null` 省略该键」的动作方是**证据行的序列化**（写入侧）⇒ 该条归本件，**判据未动**（仍是 ADR-0042 第 5 节第 4 条的三态可区分）。**（二）E9 的断言集 = 本表 `E9` 行的六条具名断言（逐字）+ 两条补齐断言**：`#writeGuardRefusesOversizedRowWithoutNewEnumValue()`（「超限拒绝写入且零新增枚举值」原只在落点表的承推入项里，**无正面行为断言**）与 `#subjectIsCarriedInJsonNotUserId()`（「`ACT_HI_COMMENT.USER_ID_` ≠ 真实产出主体」原先只有 javadoc 义务，无判据）。**六条具名形态一字未动**，补齐项全部落同一落点类。**（三）自述位省略键的落点**：取**字段级注解**（core `DecisionEvidenceVO.attestedDataSources` 上的 `@JsonInclude(NON_NULL)`），**不**用写入侧映射器的全局「省略 null 键」—— 后者会顺手牵动其它字段，而矩阵里多处「必须 null」的格依赖键仍在；E8 的 `#attestedDataSourcesThreeStatesAreDistinguishable()` 以「另一处必须 null 的格仍以 JSON `null` 在场」把这条边界一并钉住。**（四）C 列 `inputSnapshot` 的读法（一处必须写明）**：矩阵对 C 列的 `inputSnapshot` 与 `rawOutput` 两格写的是**必须 null**，而标志推导（ADR-0042 第 6 节）以「该方向有没有载荷」为输入 ⇒ 本件按**矩阵的书面格**读：C 列两方向的载荷事实皆为「无载荷」（`MODEL_DECLINED` 那次出站调用由**出处组与 `modelId`** 记录，不由载荷字段记录；ADR-0042 第 5 节第 2 条「凡与出站调用相关的格一律二分」的口径按**表内已二分的那两行**（`modelId` 与出处组）落实）。判据与表格**一字未动**，本条只是把两处书面表述的读法钉死，**不触发受限重开**。**（五）互锁第三联在 `RESTRICTED` 下的读法**：`NO_PAYLOAD ⇔ 载荷字段空 ∧ ¬redacted ∧ ¬truncated` 的**反向支**（载荷空 ∧ 未加工 ⇒ `NO_PAYLOAD`）只在**三值域内**成立 —— `RESTRICTED` 的定义性特征正是「**有**载荷但按政策不可落盘」（载荷字段恒空而完整度非 `NO_PAYLOAD`），故 E9 的 `#flagInterlockHoldsPerDirection()` 与 `#inboundExceptionIsAnErrorButPolicyRejectionIsNot()` 把这支写成「无加工 ⇒ `FULL` / `NO_PAYLOAD` / `RESTRICTED` 三者之一」，**「无实际载荷」仍由 `NO_PAYLOAD` 单一承担**（不被布尔兼职、也不被 `FULL` 兼职）。**（六）E9 的文档纪律住所**（§6.3 第 3 条）：逐格必填理由的**机器住所**仍是 core 的 `DecisionEvidenceVOContractTest`（唯一，防两处真相），E9 的类 javadoc 以「三处来源 → 各列必填格」的**人评审形态**承接 E9 侧的登记义务，不复制逐格表。**（七）两条守卫的补齐（实现期自审发现，非规范新增）**：① **直提列取不到 `RESTRICTED`** —— ADR-0042 第 6 节写「入站方向按提交方是否交载荷取 `FULL` / `PARTIAL` / `NO_PAYLOAD`（**取不到 `RESTRICTED`** —— 直提的入站加工**不经策略**）」，而原先只在**样本层**断言（「样本里没出现」是**真空断言**：草稿置位即可产出该值）⇒ 改为**列级构造期守卫**（直提列声明「政策性不可落盘」即非法态），断言落 `E8` 的 `#directSubmissionBijectionHolds()` 与 `E9` 的 `#outboundNeverTakesRestricted()`；② **入站两降级不得同时声明** —— 「先分类型」的正面含义是「声明位上互斥」（同时声明政策性不可落盘与入站加工失败 ⇒ 非法态），断言落 `E9` 的 `#inboundExceptionIsAnErrorButPolicyRejectionIsNot()`。**（八）A 列 `modelId` 条件必填 = 本层面不真置（如实登记）**：矩阵该格的条件（Provider 缝是否从响应解析到模型标识）只在**出站缝**可得，写入器既不能置真也不能置假 ⇒ **不设该判据、不新增弱断言**，判定点归拉管线与出站缝；「矩阵任何一格都写不出来」这句话在**这一格**上不成立，特此写明（E9 类 javadoc 同步登记）。

> **E10 / E11 的断言集与六条实现期口径（2026-09-27，推面位点服务与表态比较面，如实披露）**：**（一）断言集**：E10 = 本表 `E10` 行的四条具名断言（逐字）+ **八条补齐的分支断言**（正例逐条通过 / 直提与拉面的双射双向成立 / 入站超限归产出态例外值 / 全局关为惰性 no-op / 两个写入失败槽位分别可判 / 最后防御归 `INTERNAL_ERROR` 并上抛 / **准入失败的行落不下时只降级、不报失败结局**）；**四条具名形态一字未动**。E11 = 本表 `E11` 行的五条具名断言（逐字），**无补齐项**（真值表 32 格 = 八布尔组合 × 四动作，另加「镜像 ⇔ 真 core 守卫」逐格对拍）。**（二）「不物质化」的读法必须写明**：本表 `E10` 行的「缺身份 = **唯一不物质化**」立句早于 ADR-0042 第 8 节第 4 条的 **2026-09-26 订正**（例外子句由「一项」扩为**三项前置** `taskId` ∧ `idempotencyKey` ∧ `subjectType`）。本件按**订正**读：不物质化 ⇔ 缺三项前置之一 —— 断言名逐字保留，其「唯一」读作「不物质化集恰为该三项」。**判据未改**，只把旧立句的读法钉死。**（三）一处同族未同步陈述的登记与归口**：ADR-0042 第 10 节与命名宪章 §4.5「未物质化」行均写「`admissionReason` 在未物质化的准入失败下**只取 `IDEMPOTENCY_KEY_REQUIRED`**」，同为订正前的措辞 —— 订正后不物质化集为三项前置，故观测面的 `admissionReason` 须承载**实际命中的那一项**。本件按订正实现（承载实际原因）；该措辞的同步**不由本件改**（改它即动条文强度），归**发布路径 / 收口**的同一次条文清洗。**（四）失败行的出处组携带规则**：只在出处组**完整**（三者同非 null）时原样搬入（矩阵 D 列出处组为 `nullable`）—— 直提失败行全 null、拉面失败行必带 `provider`，ADR-0042 第 8 节第 4 条 (iv) 的双射在失败行上成立；**半填**的出处组（正是规则 ⑧ 拒绝的形态）既不是直提也不是拉面，无法构成合法双射见证 ⇒ 落 null，本次拒绝的权威信号是同步异常与观测面的闭集原因。**（五）位点服务发观测的范围（探索期决议边界推入）**：准入拒绝（物质化 / 未物质化两种形态）与**两个写入失败槽位**各发一条 ERROR 观测（`severity` 与 `DecisionOutcomeMapping` 对账：`SITE_ADMISSION_REJECTED` 与未物质化三行皆 `ERROR`）；**成功路径的观测不在此**（其 `latencyMs` / `inputTokens` / `outputTokens` 只有管线可得）⇒ 拉面准入拒绝的观测由位点服务产、管线**只接住不重复产出**（与 ADR-0042 第 8 节第 4 条 (i)「只接住、不再重复落行」同向）。**（六）「主闸放行、写入器拒绝」的两类防御路径可达且如实登记**：core 矩阵 A 列要求**依据面非空**（`rationaleFacts` / `rationaleNarrative`），而 11 条准入规则**不含此项**（规则集冻结）；同理「非直提列带自述位」也不在规则集内。两形态均由探索期决议边界推入的**最后防御**承接：归 `INTERNAL_ERROR`（最小化失败行 + ERROR 观测）并**上抛**（不静默）—— **零新增枚举值、零新增准入规则、零新增落点类**。**（七）「已物质化」的判据落在结果面、不落在前置面（实现期自审订正）**：报 `SUGGESTION_FAILED` 结局的前提是**失败行真的落了** —— 前置带齐而**锚点读取即落空**或**写入失败**时，走既有「锚点失效 / 实例已结束」槽位（不成行、`failureKind` 与 `admissionReason` 皆 null，**不产生** `SUGGESTION_FAILED`、不计入错误率），与探索期决议边界评语第 4 点「任务不存在 / 实例已结束 ⇒ 走既有「写入期降级」槽位」严格对齐；本件以 `#rejectionWhoseRowCannotLandDegradesInsteadOfFailing()` 把这条钉成可判事实（两种入口各一格）。**（八）`MEMBERS` 的公开面静态类型取 `Set<ApprovalAction>`（如实登记一处形态偏离）**：注册形态写的是 `EnumSet<ApprovalAction>`，而 `Collections.unmodifiableSet(EnumSet.of(...))` 的静态类型只能是 `Set` —— 取 `Set` 是为**守住「单一来源」**（裸 `EnumSet` 公开成员可被调用方 `clear()`，单一来源即成空文；core 的 `DecisionEvidenceComment.EVIDENCE_COMMENT_TYPES` 与 extension 的 `DECLARED_ATTRIBUTES` 同形）。构造来源仍是 `EnumSet.of(...)`，**值域与判据一字未动**；已同提交补登记于命名宪章 §4.5 的「表态比较面」行。

> **E13 / E15 / E16 / E17 / E19 的断言集与八条实现期口径（2026-09-27，拉管线与出站缝，如实披露）**：**（一）断言集**：E13 = 本表 `E13` 行的八条具名断言（逐字）+ 六条分支补齐断言（同步重试复用同一次推导的载荷与键 / 不可重试不重试且失败行出处组完整 / 节点未声明·显式禁用·全局关三类回调即短路 / 启用字面量非法显式降级 / 入站两降级先分类型 / 被拒草稿改落最小化替代行时观测承继被拒草稿的链路阶段〔2026-10-02 补〕）；E15 = 六条具名（逐字）；E16 = 四条具名（逐字）；E17 = 三条具名（逐字）；E19 = 五条具名（逐字）。**具名形态一字未动**。
> **（二）回调内的节点声明读取 = 「声明面索引」（`nodeId → UserTask 元素`，装配面预热）**：回调被冻结为「零引擎命令 / 零网络 / 零状态写入」，而 `RepositoryService#getBpmnModel` 一类取证需要引擎命令 / I/O ⇒ 按决议 §一的**补充登记**取「预热」形态：监听器构造面收一张**索引表**（纯 JDK `Map`，无新公开类型），回调内只做表查找；**索引未命中 = 未声明，fail-closed**（冷启动未命中 = 登记过的已知边界）。索引的构建 / 预热与「`nodeId` 跨流程定义碰撞」的处置归装配面（starter，探索期决议），已在该来源留边界评论。
> **（三）Provider 缝的字段级形态补白**：`DecisionTarget` 的「接入信息」落成 `url()`（默认方言的出站地址；替换 Provider 缝后可忽略）；`DecisionProviderResponse` 补 `inputTokens` / `outputTokens`（观测面的用量只有缝可得；响应体**平铺**、缺失不猜）与 `failureKind`（失败通道）与 `rawOutput`（响应体原文裸串）；`DecisionTransportResponse.STATUS_UNREACHABLE = 0` —— SPI 的可见面只有「状态与字节」，I/O 故障与超时在缝内**同判**为 `OUTBOUND_TIMEOUT`，如实登记为默认方言的既定口径。
> **（四）互斥性的读法**：判据只看 `declined = true`；`declined = false` 是「未主动不产出」的**中性位**、可与 `suggestedAction` 并见 —— 否则任何「总是发 `declined`」的方言都会被判违规。E15 的 `#declinedAndSuggestedActionAreMutuallyExclusive()` 把这条读法钉成可判事实（正反各一格）。
> **（五）凭据三约束的判据面**：「无状态提取方法」判为**公开面约束**（E16 的扫描面 = 公开方法 + 公开字段）；默认 Transport 与凭据同包，请求装饰经**包内转交点** `material()` 完成 —— 这是 ADR 第 7 节「请求装饰载体原样转交 Transport 缝」的落地形态，非约束放宽（已登记于命名宪章 §4.5 的本件补形态块）。
> **（六）产出路径的一处实现期口径（如实披露）**：`SuggestionSubmission` 不承载「加工记录（redacted / truncated）」与「入站政策性不可落盘」两项事实，而写侧矩阵要求它们。故**产出且入站可落盘**的普通路径走位点服务（探索期决议边界 3「拉面与推面共用同一个 submit」），**入站政策性不可落盘（`RESTRICTED`）**的产出路径由管线直接物质化（否则该信号会被折叠成 `NO_PAYLOAD`，与 ADR 第 6 节「入站两类降级先分类型」相冲）。**代价如实登记**：走位点服务的 A 列行不携带加工两位（公开提交模型无此字段）—— 与探索期决议边界 6.1「加工事实由管线声明」存在张力，归**发布路径 / 收口**的条文清洗裁量，不在本件私改落定形态。
> **（七）重试的观测口径**：整次决策**只落一行证据、只产一条观测**（决议 §十三 行 8 / 9 / 12 的「整次决策一行，值取最后一次尝试」），中间尝试只落结构化日志 —— 不逐次计入错误指标；可重试集合取 `DecisionOutcomeMapping.isRetryable()`（不在缝内另立取值，兑现探索期决议边界评论的「失败分类与映射表行一致」）。
> **（八）拉面失败行的出处组与 `MODEL_DECLINED` 行的依据面**：失败行在**确已发起主链路出站调用**时**整组**填入出处组（`provider` / `chainStage = PRIMARY` / `degraded = false`，与探索期决议的 fixture 先例一致），**从未出站**的失败（装配失败 / 策略拒绝 / clamp 拒绝 / 目标不可解析 / 未归口兜底）整组为空；`MODEL_DECLINED` 行在响应未给依据时由框架按规则记录一条 `POLICY_RULE` 依据 + 文本兜底（写侧 C 列必填的兜底，非占位 payload）。锚点不可见（含可见性等待超时）与池满 ⇒ 不触发 / 不落证据行，与决议 §十三 行 1 / 行 3 一致。**（2026-10-02 由主仓 #104 收敛）** 原读法「失败行在决策目标 key **可解析**时整组填入，与 ADR 第 8 节第 4 条 (iv)『拉面失败行必带 provider』一致」**作废** —— 该泛化把**出站前**的失败也填了出处组，与第 5 节 C 列「未出站 ⇒ 出处组必须 null」及观测面「是否已出站」两把尺子相冲；收敛后证据面与观测面**同一判据**（ADR 第 8 节第 4 条 (iv) 已同步收窄措辞，头部登记已取代此前的「有意不同」）。判据与断言**一字未改**，只改填入条件。
> **（九）两处收窄读法的补登记（审查对账补齐）**：① **「幂等键不上线」的判据面** —— 请求体顶层即载荷四段（无信封、无顶层身份字段、无幂等键），而四段定型外壳里**已声明的 `taskMetadata` 段本就合法携带 `taskId`**（E5 的「声明但空 / 有值」三态），故「请求体不含任何身份字段」按字面不可满足；E15 的 `#requestBodyCarriesNoIdentityField()` 取**收窄读法**：「**未在有效数据源声明内**的身份字段一律不出现」（断言取不含 `TASK_METADATA` 的声明面 + `idempotencyKey` 无条件缺席）。② **`RESPONSE_UNPARSEABLE` 的第四处判定点** —— 决议给的三处违约（皆在 / 皆缺 / `chainStage` 缺失）之外，默认 Provider 把「**产出时建议面缺一**（`actionSummary` / `rationaleFacts` / `rationaleNarrative` 任一缺失）」也归 `RESPONSE_UNPARSEABLE`（可重试）：该三格是写侧矩阵的产出列必填格，前移到方言面判定可让「可重试」的语义落到真正的契约违规上，而不是等位点服务的准入异常（不可重试）来兜。两处均判据未改、只是把读法钉死。

> **E12 / E20 / E21 / E1 的断言集与实现期口径（2026-09-27，extension 验证收口，如实披露）**：**（一）断言集**：E12 = 七条具名（逐字，全绿，真引擎 + 真 `ID_` 序列）；E20 = 七条具名（逐字，全绿，清单见 `docs/impl/0042-equivalence-harness.md` §8）；E21 = 六条具名（逐字，全绿）；E1 = 十条具名（逐字，全绿）。**具名形态一字未动**。
> **（二）E12 的「整锚点不判（拒绝标注）」载体裁定（承接探索期决议边界评论的推入项）**：载体 = **新增 core 公开类型 `UnorderedDecisionEvidences`**（core `…core.vo`，不可修改 `List` 具体类）—— 裁定的一手论证：判定面只吃 `List<DecisionEvidenceVO>`，证据 VO 无时序 / 序号元数据，判定面**无法从列表内容识别**次序可信度（升序合法标注与降序错位标注的位置结构同构，任何「靠列表序」的通道几何上不可分）；VO 字段被读侧硬清单第 8 项封死；包私类型跨 `core.workflow`（生产者）→ `core.vo`（判定面）不可见。故「未建序」由**列表运行时类型**承载：投影器在锚点不可判时以它交出（内容完整、不建序、`WARN` 照落），两个 VO 取值器**原样透传**，判定面识别即整锚点拒绝标注。**ADR-0042 第 9 节第 7 条判据零改动**；登记与 T1 / 屈折自检见命名宪章 §4.5 的本件块。**「落日志与指标」的读法**：core 侧可承载的一半 = 投影器 `WARN`（日志）；指标面无本机制信号（九信号闭集未动、core 无指标面），该半句在 v1 的可承载面止于日志 —— 如实披露，不预立断言。
> **（三）core 读侧投影器的一处缺陷订正（实现期一手事实）**：原实现取 `NumberUtils.createLong(ID_)` 并在注释里声称「解析失败返回 `null`」—— 该说法有误，`createLong` 失败时**抛 `NumberFormatException`**（`NumberUtils` 无「失败返 null」的 `Long` 入口），恰好击穿「不可解析 ⇒ 不建立次序」判据；默认 `DbIdGenerator` 下 `ID_` 恒数值，缺陷从未显形，本件真引擎测试（换用 `IdGenerator`）将其挖出。订正为显式 try / catch（失败返 `null`），**判据本身不变**。
> **（四）E12 / E20 的 fixture 直连库改写**：两处 fixture 经 JDBC 直连内存库改写历史行状态（`ACT_HI_COMMENT.TIME_` 构造「同一毫秒 / 指定先后」；`ACT_HI_ACTINST` / `ACT_HI_TASKINST` 的 `START_TIME_` / `END_TIME_` 按**数值 `ID_` 升序（= 写入序）**指定确定全序）—— 引擎对「同毫秒并列」无客观先后、且并列序在两台引擎的库上不保证一致（真引擎实测），不钉死则对拍面顺序不稳。这是 **fixture 构造**（对抗动作本身走引擎公开位点），与本机制读侧 tie-break 的既有哲学同向；绝对值不进任何断言。
> **（五）E20 的面⑤读法**：`getEventTime()` 按「归动态」口径**整体退出对拍面**（毫秒边界在任意一次运行都会摆动，事件序已由序列位置承载）；**连续同名运行**（一次操作内的多实例拆分、相邻三次投票的同名回调）为并列组，组内按内容位（`assignee`）排序 —— 同毫秒并列无客观先后，内容等值面才是契约面。回调的其余内容字段（`comment` / `nodeId` 等）的等值由面①②③的效果面对拍间接承载，不逐事件展开 —— 如实登记覆盖边界。
> **（六）E21 的落点模块位与扫描范围**：靶子①③的主落点在 starter 侧（`S5`，尚未落地）—— 坐标常量带**模块位**（extension / starter），元守卫对「本模块落点反射可命中」与「跨模块落点在本模块不可见」**双向判真**；其准入①面由 starter 侧守卫承接（`S5` 落点关闭时登记）。源码受限扫描访问 **8 份**源文件（本模块六份主落点 + 坐标常量 + 守卫自身），达「≥ 8」下限；守卫自击中的自指问题以字面量**拼接**构造消解（守卫源文件在被扫之列）。计数守卫形态照 §4.2：只对 `EXPERIMENTS` 断言「恰八」，无「总行数 == 8」一类断言。
> **（七）E1 的两条口径**：① **宇宙的模块边界** —— 反射命中集合只覆盖本模块可见宇宙（core 主源 + extension 主源 + extension 测试树，规模 40 / 200 下限实测远超）；core 测试树类型不在 extension 类路径上（core 不发布 test-jar），以**名录 T1 扫描**承担（判例留痕见命名宪章 §4.5 本件块）。② **裸字面量的扫描域 = extension 主源**（测试树的 BPMN fixture 字符串合法绑定命名空间，不在契约面上）。
> **（八）「重复到达照记不抑制」的读法（对 §5.2 薄点的补登记）**：E12 的真引擎 fixture 以同键两行 / 三行断言**读侧不抑制**（两行都在 `decisionEvidences`、判据面只标注不丢弃）—— 这是**读侧**事实；§5.2 披露的薄点是**写侧**「重复到达仍然落行」（E8 四列穷举承载），本件**未**为其新增断言，处置不变。

---

## §4 starter 落点

**共 5 个类（全部新建）**。starter 承载「Spring 装配面」的断言；`S5` 是唯一的**真引擎 + 真 Spring 事务代理**落点。

| # | 落点类 | 承哪些推入项 | 关键断言名形态 | 强制机制 |
|---|---|---|---|---|
| S1 | `DecisionAssemblyAbsenceTest` | ADR-0042 第 11 节第 6 条 **(d)**：classpath 无 extension 时上下文正常启动、决策装配缺席；四行残留**无数据时零副作用** | `#contextStartsWithoutExtensionOnClasspath()` / `#noMechanismBeanIsPresent()` / `#coreResidualsRemainInert()` | 机械可判（`ApplicationContextRunner` + `FilteredClassLoader`） |
| S2 | `NeutralityAssemblyTest` | **中立性 ⑤（装配）** —— ADR-0042 第 3 节 称为**承重**面（把「AI 只是众多生产者之一」从文档承诺变成装配事实）；同时是 §6.2「不引 extension ⇒ 零 Bean」的机械面 | `#automaticallyConfiguredMechanismBeansEqualDeclaredSet()` / `#mechanismBeanSetIsEmptyWithoutExtension()` / `#noAutowiredMechanismBeanClassNameCarriesDomainWords()` / `#noDecisionMetersAreRegisteredBeforeFirstObservation()`（由「模块与构建」推入：**不得在启动期急切创建指标名** —— 未发生任何决策时本机制前缀的 meter 数为 0） | 机械可判 |
| S3 | `DecisionNeutralityDependencyTest` | **中立性 ④（依赖方向）** | `#extensionDirectDependenciesEqualDeclaredSet()` / `#starterDirectDependenciesEqualDeclaredSet()` / `#bannedVendorEntryClassesAreAbsent()`（覆盖传递依赖） | 机械可判 |
| S4 | `DecisionClosureMatrixIntegrationTest` | ADR-0042 第 11 节第 2 条**三层关闭矩阵**的 Spring 装配侧（全局开关 ＞ 节点声明（**只**门控拉面与出域）＞ 推面（无节点开关））+ 第 1 条「关闭后无感」≠「运行时零活动」 | `#nodeOffGatesPullAndEgressOnly()` / `#pushSideHasNoNodeSwitch()` / `#undeclaredNodeIsNotEquivalentToZeroEvidence()` / `#globalOffLeavesExistingBehaviourUnchanged()` / `#globalOffSubmissionIsANoOpNotAFailure()`（由「模块与构建」推入：全局关时推面 `submit(...)` **不落记录、不抛准入异常**） / `#guardThresholdsAreNotConfigurable()`（**G5**：属性类字段集**恒等**十二键，三个护栏阈值不出现在配置面） / `#mechanismBeansRemainRegisteredWhenGlobalSwitchIsOff()`（由「模块与构建」推入：**全局开关是运行期门控而非装配条件**，且 validator / 复核装配**不随开关消失**） / `#guardrailsCoversExactlyTheNumericKeys()`（**B1**：`DecisionGuardrails` 常量集**恰等**十个数值键，不含 `enabled` 与前缀） / `#guardrailsConstantsEqualPropertyFieldDefaults()`（**B2**：反射对账 —— 属性类每个数值字段的初始值 == 对应常量，防两处漂移） / `#overLimitConfigIsClampedWithStartupWarn()`（**超上限 = `Math.min` 收口 ＋ 一条启动期 `WARN`，不 fail-fast**） | 机械可判 |

| S5 | `DecisionTwoPathIsolationIntegrationTest` | **P2 受控压力测试探针**（ADR-0042 第 13 节）：两边同挂 BPMN（老路径 `AutoApprovalRule` + 本机制节点声明）的四断言 ① 老路径逐字段等价 ② 本机制侧无自动提交痕迹 ③ 从 `suggestedAction` 到流程推进无框架通路 ④ 默认关下不激活；**并承 ADR-0042 第 11 节第 1 条的「面④ 事务语义」**（`E20` 因 standalone 无 Spring 事务代理交付不了这一面）。**内部形态（三态三上下文 / 参照态边界 / fixture / 冻结判定式 / 归一化第二处 / 防空转）见 `docs/impl/0042-two-path-isolation-probe.md`（唯一住所）** | 11 条，清单见上述文件 §10 | 机械可判（`@SpringBootTest` 载关态 + 程序化 boot 另两态，真引擎 + 真事务代理） |

> **S5 的形态（P2 探针）**：**一个类、三个相互独立的上下文**（参照 = 程序化 boot + `spring.autoconfigure.exclude`；关态 = `@SpringBootTest`；开态 = 程序化 boot + `enabled=true`），对拍住在**同一测试方法内** ⇒ **零类序依赖**（不引 `ClassOrderer`、不动 `junit-platform.properties`、单类运行不受影响）。**否决**「三顶层类」（顶层类序只能由模块级配置参数强制、`ClassOrderer` 为实验性 API、单类运行即因基线缺席而红）与「三 `@Nested`」（`@Nested` 自有 `@SpringBootTest(properties)` 产出独立上下文一节无运行时先例）。详见 `docs/impl/0042-two-path-isolation-probe.md` §2.1–§2.4。
> **S2 的判据形态（Q5(a)）**：唯一能把「不装配 AI 组件」变成**闭集事实**的形式 —— **集合同等**（有 extension 时恒等于显式常量集）＋ **空集**（无 extension 时）＋ 集合内类名过 T1 / T2 扫描。**否定句清单（`doesNotHaveBean` 逐个点名）不采用** —— 它只能防住想到的，漏一个即静默通过。
> **S3 的形式（Q6(a)）**：测试内断言**直接依赖坐标清单 == 固定常量**（surefire 工作目录 = 模块 basedir，读 `pom.xml` 可靠）＋ **禁词库入口类**的负向存在性断言。**是否升级为构建期强制**（`maven-enforcer-plugin` / `bannedDependencies`）归 **探索期决议**（本件登记为备选，不代它决定）。

---

## §5 来源 → 类映射表

### §5.1 面 1 四来源的「具名断言落点」回填（命名宪章 §7.1 第 2 条）

四来源关闭时均把该项**推入本件**，此处逐来源回填，使命名宪章的关闭条件成为**可复核产物**。

| 来源 | 机械可判候选 | 断言落点 |
|---|---|---|
| 节点声明四属性与 BPMN schema | 四属性名 / URI 成对与唯一性、属性名大小写、BPMN token 形态 | **E2** `DecisionNodeDeclarationTest` |
| 决策证据载体形态 | 标记前缀单一来源；rationaleFacts 键集；结局 / 完整度 / 失败类别闭集；`attestedDataSources` 三态 | **C5** `DecisionEvidenceMarkerTest` · **C6** `DecisionEvidenceVOContractTest` · **E8** `DecisionEvidenceSubmissionTest` · **E9** `DecisionEvidenceWriterTest` · **E1** `NamingCharterComplianceTest` |
| 幂等身份与重放标注 | `idempotencyKey` 字段名屈折；`resolveReplayOf` 形近并置；重放派生面 | **E1** `NamingCharterComplianceTest` · **C7** `HistoryWorkflowEvidenceReadTest` · **E12** `DecisionEvidenceReadOrderTest` |
| 位点服务与建议提交模型 | 三类名；准入原因枚举闭集；表态比较面常量；可用动作映射 | **E1** `NamingCharterComplianceTest` · **E10** `SuggestionAdmissionContractTest` · **E11** `ComparableActionAvailabilityTest` |

### §5.2 面 2–5 各来源的推入项

| 来源 | 推入项 | 断言落点 |
|---|---|---|
| core 残留四行 | (a) 守卫内容；(b) 断言内容；三处推断排除；落点类名与写法 | **C1** · **C2** · **C3** · **C4** · **S1** |
| 出域控制 | 八组机械可判（枚举 / 令牌 / 载荷三态 / 结果位互锁 / 标志推导 / clamp / 主闸叠加 / 命名） | **C8** · **E2** · **E3** · **E4** · **E5** · **E6** · **E7** · **E9** · **E1** |
| 拉管线与出站缝 | 六组（T1 / 非屈折 / 恒等非屈折与跨硬域同形 / 互斥性 / 结构保证 / 非命名六项） | **E1** · **E13** · **E14** · **E15** · **E16** · **E17** · **E4** |
| 可观测性 | 八组（信号与维度 / 无屈折 / 闭集值 / 16 字段与必填可空 / 计错判据 / 消费隔离 / 观测面禁载） | **E18** · **E14** · **E1** |
| 兼容与回滚（ADR-0042 第 11 节） | 守卫测试四落点 (a)(b)(c)(d) | (a)(b) → **C1** · **C2** · **C3** · **C4**；(c) → **E20**；(d) → **S1** |
| 决策源中立与通用化（ADR-0042 第 3 节） | 中立性清单 ①–⑤ | ① 类型与常量命名不绑 AI → **E1**；② 取值域不绑 AI → **C6**（闭集穷举）· **E9** · **E10**；③ 必填性不预设「必定经过模型调用」 → **C6**（矩阵逐格的理由登记）· **E9** · **E10**；④ 依赖方向 → **S3**；⑤ 装配 → **S2** |

### §5.3 模块与构建（面 7）的推入项

| 来源 | 推入项 | 断言落点 |
|---|---|---|
| 模块与构建 | 依赖账本与方向（④ 的**构造形态**、enforcer 不采用） | **S3** |
| | 装配形态（两个配置类、五个替换点、marker 类、`spring.factories` 增两条） | **S2** |
| | 三层关闭矩阵的位点（全局 = 运行期门控；推面无节点开关；全局关提交为 no-op 非失败） | **S4** |
| | 引擎级装配（主闸**叠加式**、`ValidatorSet` 具名、复核不随开关消失） | **E3** · **S4** |
| | 应用级默认数据源集（缺失 / 空集按空集、禁隐式全集兜底） | **E4** |
| | 读写两侧护栏（G1–G7） | **C9** · **C10** · **C7**（G6）· **S4**（G5） |
| | 指标装配（机制专属替换点类型、无 micrometer 无 Bean、**启动期不急切创建指标名**） | **S2** |
| P2 受控压力测试探针 | 三态三上下文（参照 / 关态 / 开态）的构造；四断言 + **面④ 事务语义**的具名形态与**冻结判定式**；两边同挂 fixture；归一化**第二处**（含登记措辞）；防空转两条 | **S5**（内部形态见 `docs/impl/0042-two-path-isolation-probe.md`，唯一住所） |

### §5.4 面 6 的推入项

| 来源 | 推入项 | 断言落点 |
|---|---|---|
| 八靶子具名击穿实验 | 击穿实验的坐标常量（`EXPERIMENTS` 恰八项 / `VARIANTS` 可空，**两个并列承载位**） | **§3.1** `DecisionBreachExperiments` |
| | 元守卫六条（规模与名逐字对账 + 变体母实验名在册 + 常量无结论字段 + 准入条件 ① 的受限源码扫描） | **E21** `DecisionBreachExperimentsTest` |
| | 八项击穿实验的**主落点（承裁定）**与同面格 | 本文件既有 **`S5` / `E4` / `E14` / `E12` / `E11` / `E20` / `E3` 各行**（无新增落点） |

> **击穿实验的具名形态、准入条件 ①–⑥ 的逐条落位、同构变体通道的登记格式** —— **唯一住所 = `docs/impl/0042-kill-switch-experiments.md`**；执行面 §3 只留「主落点（承裁定）」列与指针。

---

## §6 强制机制三分类（命名宪章 §2.A.1）

### §6.1 机械可判

本文件 §2–§4 三表中标注「机械可判」的全部断言 —— 即落点表的主干。全部可写成断言、由守卫测试在既有 CI 矩阵内执行。

### §6.2 结构保证（违反即写不出来，故不另设断言）

| # | 结构事实 | 承载 |
|---|---|---|
| 1 | `MARKER_PREFIX` / `MARKER_SUFFIX` **私有** + 标记由 `CommentType.DECISION_EVIDENCE.name()` 派生 ⇒ 写侧读侧无法自行拼装 | C5 的反射部分 |
| 2 | `DecisionCredential` 的**不透明类型三约束**（类型系统性质，非纪律） | E16 |
| 3 | **不引 extension ⇒ 零决策 Bean** | S2 的空集断言 |
| 4 | **`src/test/resources` 不建** ⇒ recorded fixture 无栖身处 | E19 |
| 5 | `DecisionRuntimeControl` 的状态**由应用持有**（框架不持久化、不带端点） | E17 |
| 6 | 命名空间与 URI **成对收口单一常量类** ⇒ 裸字面量写不出来 | E1 · E2 |
| 7 | 新增测试类全部落 surefire **默认 includes** ⇒ CI 零配置 | E1 的 `#allNewTestClassesMatchSurefireIncludes()` |
| 8 | CI 工作流文件**零改动**（矩阵轴不变） | §7（以 diff 为空验收） |

### §6.3 文档纪律（人评审可判，无机器落点）

| # | 义务 | 住所 |
|---|---|---|
| 1 | C2 类 javadoc 须写明「**放行内部只读探活**」的基线，**不得**写成「core 运行时零新增活动」 | C2 |
| 2 | 「**stub 是契约，recorded 不进 v1**」 | E19 类 javadoc + ADR-0042 第 7 节 |
| 3 | 必填 / 可空矩阵**逐格写必填理由**，理由只能引 `outcome` 分支 / 产出路径 / `subjectType`（命名宪章 §2.B.5.2） | C6 · E9 |
| 4 | E12 类 javadoc 须**写明**「读侧顺序**不得**照 `HistoryWorkflow.java:267` 的注释假定升序」（引擎实为 `TIME_ desc` 且无次级排序键） | E12 |
| 5 | 「命名宪章 §1.1 的推论：`src/test` 类型不受 §2.C / §2.D、**仍受 T1**」 | 命名宪章 §4.5 判例留痕（同提交） |

---

## §7 CI 与既有矩阵

**结论：零改动矩阵轴，零改动工作流文件。**

- **既有矩阵**：`os ∈ {ubuntu-latest, windows-latest}` × `java ∈ {8}` × `db ∈ {h2}`，`include` 追加 ubuntu 的 `mysql` / `postgresql` ⇒ **4 个 job**。
- **执行**：`mvn clean verify --batch-mode --no-transfer-progress -Dflowable.test.db=${{ matrix.db }}` —— 全模块一次跑完。
- **新增测试的收录**：全部以 `Test` 结尾（`*Test` / `*IntegrationTest`），落在 surefire **默认 includes** 内；主仓**未观察** `maven-failsafe-plugin`，两类均由 surefire 执行 ⇒ **无需改 pom 的测试配置**。
- **extension 真引擎基座固定 H2、不读 `flowable.test.db`** ⇒ 在 `mysql` / `postgresql` 轴上行为与 `h2` 轴一致，不产生分叉、也不新增轴。
- **依赖账归探索期决议**：extension 需新增 **test-scope** 依赖（`junit-jupiter` / `mockito-core` / `assertj-core` / `h2`；`flowable-engine` 已由 core 传递、无需新增）；本件只登记需求，**不代探索期决议定账**。

---

## §8 实现期默认数值（**指针** — 由探索期决议收录）

本件**只定形态与常量名**，数值归实现期：

> **数值已定稿（2026-09-26，收口决议）** —— 下表全部数值已由「收口：方案成文 + 轻量开工闸门裁定」落定；本表仍只给**形态与落点**（不复制数值，守 SSOT）。

| 常量 / 参数 | 落点 | 义项 |
|---|---|---|
| `MIN_SCANNED_TYPES` | E1 | 反射命中类数的下限（防空转） |
| `MIN_SCANNED_IDENTIFIERS` | E1 | 扫描到的标识符总数下限（防空转） |
| 标记源码扫描「访问源文件数 ≥ N」 | C5 | 防空转 |
| 结局映射表行数常量（18） | E14 | 行数对账 |
| 准入原因枚举值数常量（13） | E10 | 键集相等的对账 |
| 观测事实字段数常量（16） | E18 | 字段集相等的对账 |
| core 读侧解析护栏阈值 | — | **已在探索期决议名下**，本文件只引用 |
| `MAX_EVIDENCE_BYTES`（core `DecisionEvidenceWriteGuard`）· `MAX_NESTING_DEPTH` / `MAX_PARSE_BYTES`（core `DecisionEvidenceReadGuard`） | C9 / C10 | **由「模块与构建」钉「形态 + 常量名 + 纪律 + 候选值」，数值入探索期决议**；G1–G7 见 `docs/impl/0042-module-and-build.md` §4 |
| 框架硬上限常量类 `DecisionGuardrails` 的各值 | **S4**（三条：`#guardrailsCoversExactlyTheNumericKeys()` / `#guardrailsConstantsEqualPropertyFieldDefaults()` / `#overLimitConfigIsClampedWithStartupWarn()`） | 等于 `flowable.plus.decision.*` 冻结默认值（「只许调低、不可放大」的锚）；**住所与边界 B1–B4 与超限收口形态**见 `docs/impl/0042-module-and-build.md` §2.4 附 |
| S5 的**有界等待**上限（等机制异步产出证据行） | **S5**（`#mechanismSideLeavesNoAutoSubmitTrace()` / `#submissionNeverAdvancesProcessState()` 的收敛条件） | **测试自定值**，**不入**框架默认、不复制 `DecisionGuardrails` 的数值（防第二处真相）；取值属实现期产物 |

---

## §9 与其他来源的边界

| 边界 | 本件给 | 归其他来源 |
|---|---|---|
| **模块与构建** | 中立性 ④ / ⑤ 的**断言形态与落点**（S3 / S2） | 装配与依赖的**构造形态**：对 extension 的 optional 依赖写法 / `@ConditionalOnClass` 位置 / `spring.factories` 登记 / `MeterRegistry` 条件装配；**是否**把 ④ 升为构建期强制（enforcer）；extension 新增 test 依赖的账 |
| **K2-001** | (c) 的**类名**（E20）与其承载的义务 | 参照装配**如何构造** / 对拍**哪些字段** / 动态字段（时间戳 / ID / 序号）**如何归一化** —— 即 E20 的内部形态。**已落定：K2-001 关闭，成文 `docs/impl/0042-equivalence-harness.md`**（本行只留指针） |
| **八靶子** | 机制侧断言的**落点**（E3 的部署期阻断、E11 的动作可用性） | **击穿实验**的具名化与准入条件表达（靶子⑥ / ⑧）；**扩实验集不扩结论集** —— **已落定：八靶子关闭，成文 `docs/impl/0042-kill-switch-experiments.md`**（唯一住所；本件按其结论增补 §3.1 常量、`E21`、§5.4 与执行面 §3 的「主落点」列） |
| **P2 探针** | **落点 `S5`** 与其承载义务（四断言 + 面④） | **内部形态**（三态三上下文如何构造 / 参照态边界 / fixture / 冻结判定式 / 归一化第二处 / 防空转）—— **已落定：P2 探针关闭，成文 `docs/impl/0042-two-path-isolation-probe.md`**（本行只留指针）；**靶子 → 落点的归属裁定仍归八靶子** |
| **收口** | 本文件（**以指针引用、不摘抄**）+ §8 的数值清单 | 方案成文与开工闸门裁定 |

---

## §10 复现免责声明

本文件是**落点与形态的记录**，不是可执行的测试套件。文中三张落点表为**骨架**（类名 × 承哪些义务 × 断言名形态 × 强制机制），其断言体、常量取值与默认数值属实现期产物。任何据此复现的尝试，须自行搭建被测工程——探索工作区**不留构建根**，也不对未实现阶段的复现结果作承诺。**本件不跑实测**：实测（含八靶子的具名击穿实验）是 ADR-0042 第 13 节的**撤销条件**，属执行段。
