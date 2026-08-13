# ADR-0036: NodeFinder 正向遍历接口收窄为 TraversalMode 入口

**日期**: 2026-08-13
**状态**: 已接受

## 上下文

`NodeFinder` 接口（`model` 包，8 方法）中正向遍历三方法共享同一引擎，差异仅两点：

| 方法 | 起点 | stopAtUserTask | 引擎 |
|------|------|----------------|------|
| `findAllReachableUserTasks(defId, variables)` | StartEvent（内部自找） | false | `traceForwardAll` |
| `findNextUserTasks(defId, nodeId, instanceId, variables)` | 指定节点 | false | `traceForwardFromOutgoing` |
| `findAdjacentUserTasks(defId, nodeId, variables)` | 指定节点 | true | `traceForwardFromOutgoing` |

由此产生的问题：

- **参数化断层**：上层 `NodePreviewWorkflow` 已按 ADR-0031 将遍历深度降格为 `TraversalMode` 参数（`FULL` / `ADJACENT`），下层 `NodeFinder` 仍三方法并列——上层"一个入口 + mode"，下层"两个方法二选一"，同层语义在两个模块分裂表达。
- **僵尸参数**：`findNextUserTasks` 的 `processInstanceId` 参数被实现完全忽略（`DefaultNodeFinder` 直接透传 `traceForwardFromOutgoing` 不消费该参数），接口承诺了实现不兑现的能力，且调用方需自行维护与流程实例一致的双 ID 传参。
- **接口宽度接近实现**：三方法是对同一引擎的薄壳包装，接口并列数量 = 遍历变体数量，未提供比实现更多的杠杆。

**下游消费证据**：`jw-zhyg-api` 直接注入 `NodeFinder`，调用点 3 处（`JwProcessService.java`）：

| 调用 | 行号 | C3 影响 |
|------|------|---------|
| `findNextUserTasks(defId, taskDefKey, instanceId, emptyMap)` | 623 | **破坏性** |
| `findInitiatorNode(defId)` | 934 | 零影响 |
| `findPreviousNodes(defId, currentNodeId, instanceId)` | 935 | 零影响 |

破坏点场景为伪单例注入：任务完成后用空 map 反查后继节点、判断是否多实例并回写 `assigneeList` 变量。该能力无法迁移至公开门面 API（定义锚点 + 裸 nodeIds 语义），下游需继续依赖 `NodeFinder` 并同步迁移签名。与 ADR-0031「迁移由下游承担」同路径。

## 决策

1. **三方法收敛为单一入口**（锚点 + 遍历深度归组，深度降格为枚举参数）：

   ```java
   List<String> findDownstreamUserTasks(String processDefinitionId, String startNodeId,
                                        TraversalMode mode, Map<String, Object> variables);
   ```

   - `startNodeId` **非空**，锚点由调用方解析（流程定义锚点需先定位 StartEvent ID 再传入）。
   - `mode` 决定 `stopAtUserTask`：`FULL` = false，`ADJACENT` = true。
   - 原 `findAllReachableUserTasks` 内部"自找 StartEvent"的职责上移调用方，`NodePreviewWorkflow` 复用既有 `findStartEventId`。

2. **直接删除三方法，不做 deprecated 过渡**。延续 ADR-0031 备选方案裁决：Deprecated 并存使 API 面反而更大（新旧双倍维护），且破坏面已确认可控（下游 1 处）。

3. **僵尸 `processInstanceId` 参数随删随消**。`findNextUserTasks` 的 `instanceId` 不进入新签名，行为不变（原实现本就忽略）。

4. **异常语义调整**：定义锚点 StartEvent 查找上移 `NodePreviewWorkflow.findStartEventId`，缺失时抛 `IllegalStateException`（原 `findAllReachableUserTasks` 抛 `NotFoundException`）。接受该变化——无 StartEvent 的流程定义在预览场景属模型配置错误，`IllegalStateException` 语义更准确；且无依赖原异常类型的调用/断言。

## 备选方案

- **Deprecated 过渡（被否决）**：新旧并存双倍维护，接口反而更宽，且下游已确认可接受直接迁移。
- **`startNodeId` 可空 = 从 StartEvent 出发（被否决）**：隐式约定（null=StartEvent），接口承诺双语义，不显式。
- **合并回溯方法（被否决）**：`findPreviousNodes`（STOP_AT_FIRST + 历史过滤）与 `findCompletedUserTasks`（COLLECT_ALL + 全量历史确认）差异大、调用方不同，合并是过度抽象。回溯体系保持现状。
- **两入口拆分（被否决）**：`findDownstreamUserTasks` + `findAllReachableFromStart` 使接口又多一方法，违背收窄初衷。

## 后果

- **正面**：
  - 接口面 8 方法 → 6 方法，正向遍历三方法并列消失
  - 僵尸 `processInstanceId` 消除，接口承诺与实现一致
  - 参数化断层修复：`TraversalMode` 在 NodePreviewWorkflow 与 NodeFinder 两层统一
  - 未来新增遍历策略只需加枚举值
- **负面 / 风险**：
  - **破坏性变更**：删除三方法，`jw-zhyg-api` 的 `JwProcessService.java:623` 需同步迁移为 `findDownstreamUserTasks(defId, taskDefKey, TraversalMode.FULL, emptyMap)`（`instanceId` 可删除）。README「v1.0.0 API 迁移」已附映射表。
  - 定义锚点 FULL 模式异常类型从 `NotFoundException` 变为 `IllegalStateException`（无 StartEvent 场景）。
  - 测试改写约 50 处（机械替换 mode 参数），行为断言不变。
- **迁移**：README「v1.0.0 API 迁移」新增「NodeFinder 接口」子块，含三旧方法 → 新方法映射与僵尸参数说明。
