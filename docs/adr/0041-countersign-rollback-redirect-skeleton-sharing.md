# ADR-0041: 会签回退重定向骨架共享 + 文案参数化（架构审查 C8）

**日期**: 2026-08-19
**状态**: 已接受

## 上下文

2026-08-19 架构深化审查第二轮候选 C8（Strong）：「运行时 MI 判定 →
`resolveMultiInstancePredecessor` → 重定向展示文案」骨架在
`TaskExecutionWorkflow.getJumpableNodes` 与 `AutoRedirectCountersignRollbackStrategy`
两处逐字级重复——count 查询结构相同、前置解析调用相同、文案
「系统将重定向至: xxx」vs「系统已自动重定向至前置准备节点」是同义措辞两份维护。

ADR-0040（C7）已让**判定步**单点化（`isRuntimeMultiInstanceNode`）；
`resolveMultiInstancePredecessor` 也已在 `CountersignRollbackStrategies` 单点定义
（ADR-0021 落点）。C8 真正剩下的余量是**判定 + 前置解析 + 文案拼装的整段骨架**
仍内联在两处消费方，加上文案的同义两份维护。

## 决策

**在 `CountersignRollbackStrategies` 新增 `resolveRedirectOutcome` 静态助手**，
收敛判定 + 前置解析 + 文案拼装骨架。三态语义：

1. 非运行时多实例（模型非 MI 或全局历史数 ≤ 1）→
   返回 `RollbackResult.direct(targetActivityId)`
2. 运行时多实例 + 存在唯一前置单例节点 →
   返回 `RollbackResult.redirect(predecessorId, message)`，
   其中 message 由 `messageBuilder` 回调拼装
3. 运行时多实例 + 无前置单例节点 → 返回 `null`，
   消费方按语义各自处置（预览态 `continue`，执行态 `throw` 引导）

签名末参 `BiFunction<String, String, String> messageBuilder`——
助手内统一通过 `NodeFinder.getNodeName` 解析两个名字，
做 null fallback（用 ID 兜底）后交由回调拼装最终消息。
**文案单点调用、措辞参数化**：预览态（`getJumpableNodes`）传
「系统将重定向至」措辞，执行态（`AutoRedirect`）传
「系统已自动重定向至前置准备节点」措辞。

### 覆盖范围

- **getJumpableNodes（预览态）**：改调 `resolveRedirectOutcome`，
  lambda = `(t, p) -> t + "（系统将重定向至: " + p + "）"`，
  取 `RollbackResult.getRedirectMessage()` 作 `displayName`
  （direct 态 message=null，displayName 也 null，行为一致）
- **AutoRedirect（执行态）**：改调 `resolveRedirectOutcome`，
  lambda 用「系统已自动重定向至前置准备节点」措辞；
  返回 null 时抛 `InvalidTargetNodeException` 引导 rejectTaskToInitiator / auto-rebuild
- **AutoRebuild（不纳入）**：其判定时序与 SPI 重建分支纠缠
  （判定 → SPI 重建 → 降级重定向），强行同构会引入「判定时序」歧义。
  AutoRebuild 降级段继续直接调用 `resolveMultiInstancePredecessor`（保持现状）
- **Strict 不动**：不涉及运行时判定，仅静态模型检查

### 护栏

- **非 ADR-0037 模板方法**：共享的是判定+前置解析+文案这一个骨架，
  不是全部回退操作样板；助手是 `static` 方法，非抽象基类
- **非 ADR-0038 工厂拆分**：`resolveMultiInstancePredecessor` 保持现落点
  （`CountersignRollbackStrategies` 内），助手与它并列，未搬家
- **不合并 MID 两个运行时 MI 判定**（ADR-0040）：助手调用
  `isRuntimeMultiInstanceNode`（重定向口径），未触
  `isRuntimeMultiInstance(task)`（拦截口径），二者仍有意并存

### 行为修正（附带）

旧 `getJumpableNodes` 在 `nodeName == null` 时拼出
`"null（系统将重定向至: X）"` 字符串——这是 bug。新代码在助手内统一
null fallback 到 ID，拼出 `"nodeId（系统将重定向至: X）"`，更合理。
既有测试 `testGetJumpableNodesIncludesMultiMIWithPredecessor` 设置了
非 null nodeName，未覆盖 null case，本修正未发现既有测试依赖旧行为。

## 备选方案

- **A. 仅共享判定+前置解析（不含文案）**（被否决）：C7 已让判定单点化、
  `resolveMultiInstancePredecessor` 也已单点；不含文案时共享助手只是一层 wrapper，
  深化价值接近零。报告原文 Problem 明示三处共享含**文案拼装**。
- **B. 新建包私有 `RedirectResolution` 数据载体**（被否决）：语义最清晰，
  但 A 方案框架内用 `BiFunction` 回调可达成同样单点效果，零新类更轻。
- **C. 模板字符串参数**（被否决）：`String.format` 模板比 lambda 简单但
  不够灵活（多个文案变体需多个模板参数）；lambda 自由格式更贴合措辞差异。
- **三处都纳入共享助手**（被否决）：AutoRebuild 的判定时序与 SPI 重建分支纠缠，
  共享助手内「判定」步会重复一次（多一次 BpmnModelCache 查询 + 历史计数查询），
  引入「判定时序」歧义；保持 AutoRebuild 不动是最小变更

## 后果

- **正面**：判定+前置解析+文案拼装单点（`resolveRedirectOutcome`）；
  预览态与执行态一致性由构造保证而非测试对拍；null fallback 单点消除两处胶水；
  附带修正 `getJumpableNodes` 在 nodeName=null 时的字符串 `"null"` bug
- **测试**：新增 `CountersignRollbackStrategiesTest` 9 用例覆盖三态 + null fallback
  + 措辞参数化 + null 参数校验；既有 `AutoRedirectCountersignRollbackStrategyTest`
  （10 用例）与 `TaskExecutionWorkflowTest` 的 getJumpableNodes 段（5 用例）
  机械换 stub 全绿；`AutoRebuildCountersignRollbackStrategyTest`（12 用例）未动
- **公开 API 增加**：`CountersignRollbackStrategies.resolveRedirectOutcome` 为
  `public static`——已是工具+工厂类的 `CountersignRollbackStrategies` 现承载
  共享判定+前置+文案骨架，落点与现有 `resolveMultiInstancePredecessor` 一致，
  不破坏 ADR-0015（含 MI 判定 + 前置解析 + null fallback 增值，非裸透传）
- **重审条件**：第三个消费方出现要求不同的文案措辞形态（如需 messageBuilder
  接受更多参数）、或 `resolveRedirectOutcome` 与 `resolveMultiInstancePredecessor`
  语义边界开始模糊

## 交叉引用

- 架构审查 2026-08-19 候选 C8（Strong）
- ADR-0040（C7：MID 计数口径收敛——本助手的判定步直接消费
  `isRuntimeMultiInstanceNode`，护栏：不合并两运行时口径）
- ADR-0021（会签节点回退运行时检测与自动重定向——本助手服务的场景）
- ADR-0037 / ADR-0038 / ADR-0039（否决的过度抽象判例——本助手的护栏边界）
- ADR-0015（公开 API 准入：禁止裸透传——本助手含增值判据，合规）
- `CountersignRollbackStrategies.java`、`AutoRedirectCountersignRollbackStrategy.java`、
  `TaskExecutionWorkflow.java`、`AutoRebuildCountersignRollbackStrategy.java`、
  `CountersignRollbackStrategiesTest.java`
