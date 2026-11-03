# 中式审批实现横向对比：参考项目 R

> 调研时间：2026-11-03
> 调研对象：**参考项目 R** —— 某省级交通集团的协同处置 / 审批平台（RuoYi 体系 + Activiti + MyBatis-Plus + Nacos/Kafka/Redis + Vue2），生产运行多年
> 写作纪律：按**可公开标准**书写（同 ADR-0042）——不含真实项目名 / 公司名 / 人名 / 本地路径；类名以职责代称。
> **本文自足**：所有承重结论均在正文摘录关键代码，读者无需访问原仓即可复核；未摘录处只给职责位置，不给行号。
> 调研目的：为 flowable-plus 的中式审批能力寻找可借鉴的设计意图，以及可引用的反面证据
> 关联文档：`docs/research/available-actions-seam-design.md`（由本文 §5.1 衍生的接口设计）

## 0. 结论摘要

参考项目的**审批执行内核很粗糙**：审批服务全类零 `@Transactional`、每个动作手抄一遍四表同步、无会签/加签/或签、撤回是物理删除、跳转直接改流程定义缓存对象。

但它有**三处工程模型比我们完整**，且都不依赖 AI 就能独立成立：

1. **节点声明是单一事实源** —— 同一份 BPMN 扩展配置同时驱动「按钮可用性」与「候选人解析」。
2. **按角色/岗位投影** —— 同一节点的任务内容按岗位分派，而不是一人一份同样的任务。
3. **审批链之外的响应式协同** —— 知悉 / 回复 / 转单 / 转工单 / 催办 / 超时，是一整套我们完全没有的动作族。

本文产出不是"抄功能"，而是：**借意图、换机制**（§5），以及**明确不走的八条路**（§6）。

## 1. 对象的真实形态

| 项 | 事实 |
|---|---|
| 流程引擎 | **Activiti**（`activiti-spring-boot-starter-basic`），非 Flowable |
| 引擎获取方式 | 全仓 `ProcessEngines.getDefaultProcessEngine()` 静态调用，无注入 |
| ORM | MyBatis-Plus，实体直通 API |
| 部署 | 配置中心 + 消息队列 + Redis + 多数据源 |
| 前端 | Vue2 巨石 + Activiti Modeler（Oryx / AngularJS 1.2） |
| 多租户 | 租户编码（`lesseeCode`）贯穿全链路 |

### 1.1 两套"审批"（第一课）

| | 业务审批（Activiti） | 协同处置（自研状态机） |
|---|---|---|
| 载体 | 业务任务服务（审批链实现） | 协同主表 + 操作记录表 |
| 场景 | 月报、征地、用地规模：填报→初审→审核→审批 | 事件/预警：发布→知悉/回复/转单/转工单→关闭 |
| 状态来源 | 引擎历史 + `ExpandInfo` 侧表 | `synergiaState`（0–16）+ 追加式操作记录 |
| 参与人 | 组织中心按岗位/角色解析 | 协同方 `(userCode, orgCode)` 列表 |
| 动作族 | 通过/驳回/驳回上级/撤回/跳转 | 知悉/回复/转单/转工单/催办/关闭/处置 |

**本次调研最有价值的结构性观察**：中式工作流不是只有"审批链"。事故、预警、巡检类场景需要的是"知悉 + 处置"，其动作语义（已读、我已处理、无需处置、转他处）与审批链正交。本仓当前 10 个操作方法全部是审批链语义。

## 2. 审批链实现解剖

### 2.1 动作执行骨架：四表手写双写

每个动作（`completeTask` / `completeTaskBack` / `pass` / `backToEveryNodeWithOperator`）都是同一段约 90 行的模板，逐表同步：

| 表 | 作用 | 写入时机 |
|---|---|---|
| 引擎表 | 任务/历史 | `taskService.complete()` |
| `ExpandInfo` | 审核意见、审核人、角色、状态、审核时间 | 引擎完成**之后** |
| `TodoList` | 统一待办（含组织路径、多视角标识） | 逐条 `update df=1` |
| `BusinessTask` / `BusinessTaskForm` | 业务↔任务关联、表单字段名快照 | 重新查 `newTasks` 后 `saveBatch` |

摘录（`completeTask` 中段，已删日志与耗时统计行）：

```java
// 引擎已完成
taskService.complete(taskId);

// ① 写扩展（审核意见/角色/状态）
final ExpandInfo expandInfo = new ExpandInfo();
expandInfo.setStatus(status);
expandInfo.setReviewer(reviewe);
expandInfo.setOpinion(opinion);
expandInfo.setTaskId(taskId);
expandInfoService.save(expandInfo);

// ② 删待办
UpdateWrapper<TodoList> wrapper = new UpdateWrapper<>();
wrapper.set("df", 1);
wrapper.eq("todo_target_id", taskId);
todoListService.update(null, wrapper);

// ③ 软删关联
businessTask.setDf(DeleteStatus.DELETED.getCode());
businessTaskService.updateTaskRelate(businessTask);

// ④ 按"新任务"重建关联 + 表单快照
if (CollectionUtils.isEmpty(newTasks) ||
        !newTasks.get(0).getTaskDefinitionKey().equals(customTaskById.getTaskDefinitionKey())) {
    /* ... 批量软删旧节点任务与待办 ... */
}
if (CollectionUtils.isNotEmpty(newTasks) && !newTasks.get(0).getTaskDefinitionKey()
        .equals(customTaskById.getTaskDefinitionKey())) {
    businessTaskService.saveBatch(businessTasks);
    businessTaskFormService.saveBatch(businessTaskForms);
}
```

**关键事实**：审批服务全类 `grep -c 'Transactional'` = **0**。引擎已完成、上述任一写入失败 ⇒ 永久不一致，且无补偿。

### 2.2 动作语义的编码方式

`backToEveryNodeWithOperator` 用 `operator` 0/1/2/3 表示"通过/驳回/驳回上级/撤回"，落库为 `ExpandInfo.state = operator + 1` 与 `TodoList.status`。

对照本仓：ADR-0025 / ADR-0009 用 `CommentType` 随引擎事务落注释、读侧推断 `ApprovalAction`。**本仓更强**：单一事实源、随事务、可读侧推断；对方是侧表与引擎注释两套真相必须 join，且读侧 join 不到就丢行（见 §2.5）。

### 2.3 回退与跳转

可回退目标来自配置表 `NodeConf(sourceNode → targetNode)`，按**流程定义版本**（`procDefId`）维护，由管理端逐版本录入。

摘录（`getBackNodeMap` 收尾，把"多目标"压成单个）：

```java
Map<String, List<NodeConf>> collect = list.stream()
        .collect(Collectors.groupingBy(NodeConf::getSourceNode));
// ... 每个 sourceNode 组装多个 target ...
Map<String, String> map = new HashMap<>();
nodes.forEach(i -> map.put(i.getNodeName(), i.getTarget().get(0).getNodeName()));
```

**两个问题**：① 每个源节点只保留**第一个**目标，配置多目标等于白配；② 配置按版本维护，重新部署即需重录，且不校验目标是否为该节点的合法上游。

跳转的实现在引擎内部 API 上硬操作：

```java
ActivityImpl targetActivity = processDefinitionEntity.findActivity(targetTaskDefinitionKey);
if (activitiDTO != null) {
    targetActivity.setVariables(activitiDTO.getVars());   // ① 污染流程定义缓存对象
}
// ... 删除该执行上的全部任务 ...
executionEntity.executeActivity(targetActivity);           // ② 直接改执行位置
```

① `ActivityImpl` 属流程定义缓存，`setVariables` 会让**并发实例串味**；② 跳转前把所有任务删掉再重建，候选人/候选组需手工恢复（同一方法后半段就是在补做这件事）。

对照本仓：`getJumpableNodes`（`TaskExecutionWorkflow.java:266`）= **历史已完成节点 ∩ BPMN 回溯**，零配置、目标天然合法、变量走参数不碰模型。

### 2.4 审批人解析：引擎不持有人员

节点声明只声明**岗位/角色**，实际人员由业务侧人员中心按「租户 + 机构 + 是否本级 + 岗位列表 + 角色列表 + 姓名」查询返回。

对照本仓：`ApproverResolver` SPI（ADR-0022 / ADR-0033）已达同等能力，且更灵活。

**真正值得借的是"同一份声明被两个消费者读"**：同一份节点配置同时供给 ① 按钮可用性（见 §2.6）② 候选人解析。

### 2.5 审批轨迹投影

用「历史任务实例 + `ExpandInfo`」拼装，按"上一个节点 key 相同"做**相邻归组**，末尾追加当前活动任务，再从模型侧追加未来节点占位。

三个可引用的缺陷：

1. `if (activityHistoryVo.getAuditTime() != null) { historys.add(...); }` —— **没有审核时间的记录直接丢行**。
2. 相邻同节点归组把「驳回后重新经过同一节点」合并成一组（无轮次概念）。
3. 未来节点占位用 `for (i > 0) data.get(i)` 拼，与当前节点位置无关。

对照本仓：ADR-0028 单入口 + 活动级时间线 + 轮次归组（ADR-0019/0020），**不丢行**。本条本仓明显更好。

### 2.6 通知与回调

从节点扩展元素取 `startUrl/endUrl`，**在审批主路径内同步 HTTP POST**：

```java
R<Object> post1 = thirdPartyService.post(url, activitiCommonDTO, ResultTypeEnum.object);
return post1 != null && post1.getCode() == R.SUCCESS;
```

调用方拿到 `false` 即 `return R.fail()` —— **回调失败等于审批失败**。第三方偶发抖动直接变成审批不可用。

对照本仓：ADR-0011 自派发 + `ProcessEventListener`，回调不进审批事务。**本仓更强**。

### 2.7 并发幂等：Redis 锁 + 过期时间

```java
String lockKey = PASS_LOCK_KEY_PREFIX + businessId + "_" + modelKey + "_" + taskId;
boolean locked = redisCache.getLock(lockKey, "1", PASS_LOCK_EXPIRE_SECONDS);
if (!locked) {
    log.info("任务处理中，跳过重复审批，taskId={}", taskId);
    return R.fail(false, "任务处理中，请勿重复操作");
}
```

锁的语义是"键在 TTL 内即视为处理中"。**审批耗时超过 TTL 即自动放行**，多端并发仍可能重复审批；锁失效与业务失败之间也没有关联（业务失败不回滚锁，只是等它过期）。

对照本仓：ADR-0017 乐观锁 + 已完成任务检测，判定依据是**任务状态**而不是**时间窗口**。

## 3. 协同处置实现解剖

### 3.1 状态机

`synergiaState` 0–16 与操作记录枚举对齐：发布/转工单/已知悉/完成/已查看/转单/撤回/关闭/协同回复/催办/已处理/结束/未生效/无需处置/处置完成/处置中。

**形态 = 当前状态字段 + 追加式操作记录表**（记录中冗余写入组织路径、手机号、工单号）。查询快，但改状态与写记录不在同一事务，可能不一致。

### 3.2 身份 = (用户, 机构) 二元组

参与者去重键是 `userCode + "_" + orgCode`；任务内容按 `postCode` 分派。

对照本仓：`ApprovalRecordVO.actorId` 单值。集团型组织里"一人多机构/多岗位"是常态，AI 证据链要回答"凭什么批"时岗位身份是真缺口。

### 3.3 超时 / 提醒 / 统计

- 超时阈值走配置中心热更新；
- 超时告警由 `KeyExpirationEventMessageListener`（**Redis key 过期事件**）驱动，再投消息队列；
- 响应率、超时次数、间隔分布按日汇总成统计表。

需求真实（中文审批必有催办与时效考核），机制不可取（键过期是尽力而为，重启/淘汰即丢，且无法补发）。

### 3.4 撤回与跨系统联动

```java
if (state == PUBLISH_SYNERGIA || state == PUBLISH_WORKORDER_SYNERGIA) {
    if (StringUtils.isNotBlank(old.getSynergyWorkId())) {
        R<Boolean> res = workOrderService.collaborativeCancel(old.getSynergyWorkId());
        if (res.isSuccess() && Boolean.TRUE.equals(res.getData())) {
            synergyService.removeById(id);        // 物理删除
        } else {
            return R.fail("该协同涉及工单已进行处理，无法撤销！");
        }
    } else {
        synergyService.removeById(id);            // 物理删除
    }
}
```

外部取消成功、本地删除失败 ⇒ 状态分裂且无补偿；删除后审计面直接消失。

### 3.5 批量审批

`oneClickCheck` 逐条 `try/catch`，任一条失败继续下一条 —— **隐式"部分成功"语义**，调用方拿不到失败明细（只打日志），也无法整体回滚。

## 4. 能力矩阵

| 能力 | 参考项目 R | flowable-plus | 判定 |
|---|:--:|:--:|---|
| 会签 / 加签 / 减签 / 或签 / 委派 | ✗（Java 全仓 grep 零命中） | ✓ 原生 MI + 轮次 + 回滚策略 | 本仓显著领先 |
| 任意跳转 | ✓ 配置表驱动 | ✓ 历史节点校验 | 本仓机制更好 |
| 撤回 | ✗ 物理删除 | ✓ 留痕 | 本仓显著领先 |
| 抄送 / 知悉 / 催办 | ✓ | ✗ | 本仓缺失 |
| 超时提醒 / 升级 | ✓（Redis 过期） | ✗ | 本仓缺失 |
| 批量审批 | ✓（部分成功） | ✗ | 本仓缺失 |
| 按钮级权限（岗位/角色/用户） | ✓ 节点声明 | ✗（仅服务端身份校验） | 本仓缺失 |
| 审批人解析 | ✓ 人员中心 | ✓ SPI | 持平 |
| 表单字段级快照 | ✓ `BusinessTaskForm` | ✗（变量级） | 本仓有边界（ADR-0032） |
| 统一待办 + 多视角数据权限 | ✓ 5 种视角 | ✗（ADR-0007 明确不内建） | 本仓有意不做 |
| 审批轨迹 | ✓ 含未来节点占位（有丢行缺陷） | ✓ 不丢行 | 本仓显著领先 |

## 5. 可借鉴清单（借意图，不借机制）

### 5.1 节点声明 → 公开的「可用动作集」

**它的做法**：节点扩展元素挂 JSON，含通过/驳回按钮的显示开关与岗位/角色/用户白名单；后端把该配置与任务候选人求交，产出给前端直接决定按钮可见性。

**我们的现状**：`ComparableAction.isAvailableFor`（`ComparableAction.java:94`）**已是同一个内核** —— 但包内可见、只认 4 个动作、只判结构（多实例 / 折返决策任务），且只服务 AI 建议准入，UI 拿不到。

**建议**：提升为公开的「单任务可用动作集」读取接口，一份判定两个消费者（UI 画按钮、ADR-0042 的建议准入）。详见 `available-actions-seam-design.md`。

**风险**：属公开 API 新增，须过 ADR-0015（有增值：权限校验 + 审批语义 + MI 判定，**不是裸透传**）与 ADR-0032（岗位/角色规则只能以**回调**形式由应用填，框架不得内建 RBAC）。

### 5.2 AI 建议必须先过「动作可用性」

参考项目没有 AI，但这套配置恰好就是「合法动作集」的完整形态。本仓 ADR-0042 用 `ComparableAction` 校验建议"是不是人能做的动作"，方向正确，但人的一侧只有 4 个动作、无角色投影。**AI 审批的合法性判据天然应与 UI 按钮同源**，否则会出现"按钮不给点、AI 却建议点"。

### 5.3 身份补上岗位/机构快照

**它的做法**：参与者以 `(userCode, orgCode)` 为身份，全程带组织路径。

**我们的现状**：`ApprovalRecordVO.actorId` 单值；ADR-0042 已显式把"被谁覆盖"列为已知边界（只承诺"是否被覆盖"）。

**建议**：作为**可选**字段在写侧记录审批时的岗位/机构快照（不改动作语义、不改读侧投影规则），供审计与 AI 归因使用。若采纳，须登记为 ADR-0042 契约面变更。

### 5.4 决策建议按角色投影

**它的做法**：同一任务包，不同岗位拿到不同的任务项清单（任务模板按岗位实例化）。

**我们的现状**：`DecisionTarget` 是节点级（一个 key），一个节点只产出一份建议。

**建议**：AI 审批落地后遇到的第一个产品问题就是"给财务看的建议 ≠ 给业务看的建议"。该维度是否进 `DecisionTarget`，须走 ADR-0042 的受限重开（承重不变量与四行残留账本）。

### 5.5 时效运营面（纯读侧）

**它的做法**：响应率、超时次数、间隔分布按日汇总，超时阈值热更新。

**我们的现状**：`DecisionMetrics` 只有技术指标（token、失败率），没有一个能证明"AI 建议有用"的业务指标。

**建议**：采纳率、覆盖留痕率、首次响应时长、超时率下降 —— 全部可由现有证据行在**读侧**算出，零契约变更。这是最容易落地、也最能证明产品价值的一项。

### 5.6 抄送 / 知悉 / 催办

本仓 `grep -E '抄送|知悉|carbonCopy'` 零命中。ADR-0025 已把 `CommentType` 分成「操作注释组 / 证据注释组 / 业务意见组」，**新增一组动作类型是既有机制的扩张，不是新机制**，代价可控。

### 5.7 超时提醒：用引擎 timer，不用 Redis 过期

需求真实（对方用 Redis 键过期实现，重启/淘汰即丢）。本仓应走 **BPMN boundary timer + 事件 SPI 新增 `onTaskOverdue`** —— 引擎持久化、事务内、重启可恢复。属新能力，须过 ADR-0032 判据（超时判定不依赖业务表，成立）。

## 6. 明确不借鉴（八条，均已在正文给出证据）

| # | 反模式 | 证据位置 | 本仓现状 |
|---|---|---|---|
| 6.1 | 每个动作手抄一遍四表同步 | §2.1 摘录；`completeTask` / `completeTaskBack` / `pass` 逐行同构 | 引擎为单一事实源（ADR-0028） |
| 6.2 | 同节点判定用 `newTasks.get(0)` | §2.1 摘录（if 条件） | 轮次状态机（ADR-0019/0020） |
| 6.3 | 全类零 `@Transactional` | §2.1 关键事实 | 写路径随引擎事务 |
| 6.4 | Redis `getLock` 做幂等 | §2.7 摘录 | 乐观锁 + 已完成任务检测（ADR-0017） |
| 6.5 | 同步 HTTP 回调阻塞审批 | §2.6 摘录 | 自派发事件（ADR-0011） |
| 6.6 | 撤回 = 物理删除 | §3.4 摘录 | 留痕撤回 + 可重新提交 |
| 6.7 | 跳转污染流程定义缓存对象 | §2.3 摘录 | `BpmnModelCache` 只读、变量走参数（ADR-0005） |
| 6.8 | 回退目标靠版本级配置表 | §2.3 摘录（多目标被压平） | 计算式（历史 ∩ 回溯），缺的只是"可收窄"的策略回调 |

## 7. 不做清单

| 提案 | 结论 | 判据 |
|---|---|---|
| 统一待办 + 多视角数据权限 | 不做 | ADR-0007 / ADR-0032：列表过滤由应用注入 |
| 表单字段级快照 | 不做（除非合规强需求） | ADR-0032：表单是业务数据 |
| 版本级回退配置表 | 不做 | 计算式更准，见 §6.8 |
| 侧表存动作语义 | 不做 | 现有 CommentType 更强，见 §2.2 |
| 通知/短信/语音多通道编排 | 不做 | 业务层职责，框架只发事件 |

## 8. 后续

§5.1 已落成接口设计：`docs/research/available-actions-seam-design.md`。
§5.2 / §5.3 依赖 §5.1 的落地形态；§5.4 须动 ADR-0042 契约面；§5.5 / §5.6 / §5.7 可独立排期。
是否进入实现，待维护者评估后按 `/implement` 流程推进。

## 附录：取证与复核说明

本文所有承重结论均以两种方式之一留证：

1. **正文内联摘录** —— §2.1 / §2.3 / §2.6 / §3.4 四处，可直接核对逻辑，无需原仓；
2. **职责位置** —— 只给「类职责 + 方法名」，不给行号：行号会随参考项目演进腐烂，而本仓读者通常无该仓访问权。

原仓标识、绝对路径与行号级取证记录不入公开仓；如需复核，向维护者索取内部调研备忘（含提交锚点与完整 diff）。
