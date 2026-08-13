# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)，但当前处于内测阶段（无外部使用者），版本号按项目实际需要调整。

## [Unreleased]

### 架构收敛

**NodeFinder 正向遍历接口收窄（C3, ADR-0036）**
- `NodeFinder` 正向遍历三方法（`findAllReachableUserTasks` / `findNextUserTasks` / `findAdjacentUserTasks`）收敛为单一 `findDownstreamUserTasks(defId, startNodeId, TraversalMode, vars)` 入口，锚点由调用方解析
- 消除 `findNextUserTasks` 僵尸 `processInstanceId` 参数（原实现忽略）；接口 8 方法 → 6 方法
- 破坏性变更：直接注入 `NodeFinder` 的下游（jw-zhyg-api）需按 README 迁移表适配 1 处调用

### Bug 修复

**常规操作多实例拦截（ADR-0034）**
- 常规审批操作（`completeTask` / `rejectTask` / `rejectTaskToInitiator` / `withdrawTask` / `jumpToNode`）的多实例拦截从模型静态判定改为运行时判定：伪单例（会签节点运行时仅 1 个活跃子任务、且该节点全局历史任务数==1）放行常规操作，解决"财务/风控负责人"等伪单例会签节点无可用提交/同意路径的问题；真多实例（含会签剩最后 1 人未投）保持拦截，必须走 counterSign
- 删除 `completeTaskAsSingleton`（伪单例放行被 `completeTask` 自动覆盖；8/6 新增、零测试、无人使用）

## [1.0.0] - 2026-08-11（重新发布）

本次为 v1.0.0 的**重新发布**，替代 2026-07-21 的首次 GA。首次 GA 后累计 82 个提交（+15112 / -2031 行），含 20 个 bug 修复、22 个新功能与多轮架构收敛。因项目尚无外部使用者，破坏性变更不影响任何下游，故保持版本号 1.0.0 而非按语义化版本升 minor/major。

### Bug 修复

**会签核心**
- 修复会签轮次判定与周期边界隐患 A/B/C/D
- 会签轮次边界统一使用 `csRoundIndex`，不再依赖 `miBody`（ADR-0020）
- 修复 `isMultiInstanceFinished()` 伪单例误判导致的会签轮次跳号
- `isMultiInstanceFinished` 排除当前调用任务自身，正确检测加签新轮次
- 修复多轮会签按 `miBody` 边界拆分导致的归组 bug
- 重复/已投票加签 fast fail 抛异常而非静默跳过（ADR-0024，下游 jw-zhyg-api 反馈）
- 修复 `ADD_SIGN` 掩盖 `INITIATE_COUNTERSIGN` 导致发起会签记录丢失
- `addCounterSigner` 始终设置 `csRoundIndex` 并移除降级路径
- 修复多前置节点时抛 `AmbiguousPreviousNodeException`，提供策略重载

**审批历史**
- 操作注释多值化：同一任务多次操作注释不再覆盖丢失（ADR-0027）
- 修复加签/减签操作注释抢占业务审批意见槽位（ADR-0025）

**流程引擎**
- gateway 直达 UserTask 短路误伤 SubProcess 与条件表达式
- 修复非受控汇合导致的并行网关误判，引入运行时历史数据辅助静态拓扑解析
- 正向遍历增加条件兜底，防止路由变量缺失导致下一节点预览为空
- 自动提交守卫条件只查已完成历史任务
- `traceForwardAll` 中 `stopAtUserTask` 被 Filter 跳过节点错误中断遍历
- 删除 `withdrawTask` 中冗余的 assignee 身份校验
- 修正画布尺寸与 overlay 坐标使用绝对坐标定位
- surefire 统一测试 JVM 编码为 UTF-8（修复非 UTF-8 JVM 下中文评论乱码）

### 新功能

**会签（核心演进）**
- 会签多轮次显式追踪：Task 局部变量 `csRoundIndex` 替代启发式推断（ADR-0019）
- 会签节点回退策略族：`CountersignRollbackStrategy` 策略接口 + 自动重定向 + 原地重建（`AssigneeResolver` SPI + `moveActivityIdTo`，ADR-0021/0022）
- 模式 A 加签/减签权限放宽为「会签发起人 OR 当前节点活跃审批人」，引入 `countersignInitiator` 变量（ADR-0023）
- 新增 `CountersignAssigneesListener` 与会签建模指南
- 会签节点 assignee 必须引用元素变量（建模约束体系，ADR-0026）
- `ApprovalAction`/`CommentType` 新增 `INITIATE_COUNTERSIGN` 发起会签枚举

**遍历与查询**
- 新增 `UserTaskTraversalFilter` SPI 支持正向遍历节点过滤
- 新增 `SkipInitiatorNodeFilter` 默认遍历过滤器（原 `SkipStartTaskFilter`）
- 实现 `findAdjacentUserTasks` 紧邻遍历并暴露到公开 API
- 节点预览新增 `getAdjacentTaskNodes`、`getAdjacentTaskApprovers`（ADR-0018）
- EndEvent 与 UserTask 并列为候选节点；新增 `NodeFinder.findForwardEndEvents` 终止节点检测（ADR-0016）
- 新增 `getApprovalPersonnel` 审批人员分组查询
- 新增 `getProcessSummary` 单条流程摘要查询
- 新增 `getBusinessKeyByProcessInstanceId` 反查接口
- 驳回/撤回支持多候选节点选择策略
- 审批人解析增加同节点优先级去重

**流程操作**
- 新增 `completeTaskAsSingleton` 伪单例任务完成接口
- `jumpToNode` 增加 `TaskJumpedEvent` 事件发布；事件发布收敛为 EventBus 深层模块（ADR-0011）
- `ApprovalAction` 新增 `RETURN` 枚举，`CommentType.RETURN` 不再折叠为 `AGREE`

**可视化**
- 删除服务端 PNG+SVG 叠加方案，改为 bpmn.js 前端渲染双接口
- 中文字体自动降级及流程图状态标注层坐标修复

### API 变更（破坏性）

升级本版本需关注以下变更：

| 变更 | 说明 |
|------|------|
| `revokeProcess` → `invalidateProcess` | 方法重命名，`CommentType`/`ApprovalAction` 的 `REVOKE` 同步改为 `INVALID` |
| 删除 `getApprovalTrace` | 审批轨迹收敛为单一入口 `getApprovalHistory`（ADR-0028） |
| 节点预览 API 收窄 | 8 方法 → 3 入口（ADR-0031），迁移映射见 README「v1.0.0 API 迁移」 |
| 会签回退策略族收敛 | 收敛为深层模块，拆分 `addCounterSigner`（ADR-0021） |
| 删除 `PreviousNodeAuthorizer` | 权限校验收敛至 `TaskValidation` |
| `SkipStartTaskFilter` → `SkipInitiatorNodeFilter` | SPI 实现类重命名 |
| `DiagramOperations` 重构 | 删除 `getProcessDiagram`（SVG），改为 `getProcessDiagramXml` + `getProcessDiagramStates` 供前端渲染 |
| 删除死接口 `TaskQueryEnhancer` | 回调收敛为 `Consumer` 单一形态（ADR-0030） |
| 事件发布收敛 | 事件发布统一走 EventBus 深层模块 |

### 其他

- 开源协议从 MIT 更换为 **Apache 2.0**
- 新增 ADR-0015 ~ ADR-0031 共 17 篇架构决策记录
- 新增会签建模指南、Flowable 6.8.0 MI 节点原地重建技术验证报告等文档
