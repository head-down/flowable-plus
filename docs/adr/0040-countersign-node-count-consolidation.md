# ADR-0040: 会签节点计数口径收敛至 MultiInstanceDetector（架构审查 C7）

**日期**: 2026-08-19
**状态**: 已接受

## 上下文

2026-08-19 架构深化审查第二轮候选 C7：「会签节点人数」基础计数在 4 个类中
重复实现。`createTaskQuery().processInstanceId(pi).taskDefinitionKey(key).active()`
骨架在生产代码重复 6+ 次；`taskDefinitionKey(` 在 7 个类共出现 19 处。最尖锐的重叠：
`MultiInstanceDetector.isPseudoSingleton`（活跃数 + 全局历史数双查询）与
`CountersignRoundResolver.isRoundFinished`（活跃数 + 周期内完成数双查询）的判据
边界极其微妙（伪单例 vs 真最后一人 vs 折返周期），是 ADR-0034 / ADR-0019 系列判据
演进时反复出 bug 的区域。这是 C1 收敛 `csRoundIndex` 之后**残留的次一层重复**。

跨类的「运行时多实例」判定口径同样分散：`getJumpableNodes:279-303` 与
`AutoRedirectCountersignRollbackStrategy:58-96` 各自拼装「模型 MI 检查 +
全局历史 count > 1」骨架，且语义完全同源（回退重定向判定，ADR-0021 场景）。
`AutoRebuildCountersignRollbackStrategy:68-77` 与之逐字同构。

## 决策

**计数口径收敛至 `MultiInstanceDetector`，不新建计数类**：

1. **MID 内部私有收敛**：新增 `countActiveTasks` / `countHistoryTasks` 私有助手，
   消除 `isPseudoSingleton` 与 `isInitiatorDecisionTask` 的类内双查询。
2. **MID 对外新增语义判定**：`isRuntimeMultiInstanceNode(processDefinitionId,
   processInstanceId, taskDefinitionKey)` —— 复合判据（模型 MI && 全局历史数 > 1）
   单点定义。`getJumpableNodes` / `AutoRedirect` / `AutoRebuild` 的运行时判定段
   全部切换到此方法。
3. **CRR 周期限定计数不动**：`resolveCycleBoundary` 作为 CRR 全类**唯一周期边界
   计算点**（C1 已归档 grilling 决策 #4），CRR 依赖面刻意只有 HistoryService +
   TaskService。周期内完成计数是 CRR 自有领域，不参与本次收敛。

### 两个运行时 MI 判定有意并存

| | `isRuntimeMultiInstance(PlusTask)` | `isRuntimeMultiInstanceNode(defId, pi, nodeId)` |
|---|---|---|
| 输入 | PlusTask（有 taskId/assignee） | defId + piId + nodeId（无任务锚点） |
| 判据 | 模型 MI && !(active==1 && history==1) | 模型 MI && history > 1 |
| 用途 | **常规审批操作拦截**（ADR-0034） | **回退重定向判定**（ADR-0021） |
| 是否看活跃数 | 是（伪单例排除） | 否（只看全局历史数） |
| 边缘分叉 | active==0 时 true（安全侧） | history==1 时 false（直连放行） |

两口径在 history==1 时结论一致（均放行/非运行时多实例）；分叉出现在活跃数边界，
属各自语义的正确行为。**严禁未来以「统一」为名合并两者**——意图不同。

### 不新建 `CountersignNodeCounter` 的理由（deletion test）

两个一行查询装进新类，interface（2 方法）≈ implementation（2 行查询链）。
Deletion test：删掉后复杂度只是移回原处，不消失——属 pass-through 浅模块，正是
ADR-0037 / ADR-0038 / ADR-0039 三连否的「过度抽象」形态。语义判定留在 MID
（已拥有「运行时 MI 判定」职责，ADR-0034 落点）让口径与判定同位，深度更高。

### 行为修正（附带）

旧 AutoRedirect / AutoRebuild 的 Step 1 **不含模型检查**，仅看 count > 1。
对**非 MI 模型节点被多次执行**（如回退循环使普通任务节点累计历史任务）的边界，
旧实现会走 MI 重定向路径甚至抛 `InvalidTargetNodeException`——这与 ADR-0021 意图
（仅针对会签节点的回退策略）不符。新复合判据含模型检查，将这种节点直连放行，
与 `getJumpableNodes` 既有行为一致。本修正未发现既有测试用例依赖旧行为。

## 备选方案

- **新建 `CountersignNodeCounter` 计数深模块（被否决）**：见 deletion test 理由。
- **CRR 扩展对外计数（被否决）**：破坏其依赖面收窄（Javadoc 明示仅依赖两 Service），
  且把「全局历史口径」（与轮次无关）塞进轮次解析器，职责错位。
- **裸 `countHistoryTasks` 公开方法（被否决）**：口径仍两处拼装，C7 白做；
  语义判定收进 MID 才能让 `getJumpableNodes` 一行拿结论。

## 后果

- **正面**：口径单点（后续 C8 重定向骨架共享直接消费）；MID 类内双查询消除；
  跨类 4 处 historyCount 拼装收敛为 1；两个策略类的 HistoryService 死依赖一并
  清理（构造器与工厂签名收窄）；行为修正使非 MI 循环节点回退不再误判。
- **破坏性变更**（已实施）：`CountersignRollbackStrategies.autoRedirect` /
  `autoRebuild` 公开工厂签名删除 `HistoryService` 参数。仓内调用点仅 starter
  自动配置一处，已同步。项目处于无人使用内测期（参见 ADR-0014 / 项目记忆
  project_no_release_urgency）；下游 `jw-zhyg-api` 不直接调用这两个工厂
  （通过配置项切换策略），无下游编译影响。
- **重审条件**：第三个运行时 MI 判定口径出现、或 MID 类显著膨胀（>250 行）。

## 交叉引用

- 架构审查 2026-08-19 候选 C7（Strong）
- ADR-0034（常规操作运行时多实例检测——拦截口径 `isRuntimeMultiInstance(task)` 落点）
- ADR-0021（会签节点回退运行时检测与自动重定向——本判定口径的服务场景）
- ADR-0019 / ADR-0020（csRoundIndex 与周期边界——CRR 不动的依据）
- ADR-0037 / ADR-0038 / ADR-0039（否决的过度抽象判例——不新建计数类的判据来源）
- `MultiInstanceDetector.java`、`CountersignRoundResolver.java`、
  `TaskExecutionWorkflow.java`、`AutoRedirectCountersignRollbackStrategy.java`、
  `AutoRebuildCountersignRollbackStrategy.java`、`CountersignRollbackStrategies.java`、
  `FlowablePlusAutoConfiguration.java`
