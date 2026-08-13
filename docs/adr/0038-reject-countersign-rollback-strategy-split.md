# ADR-0038: 否决会签回退策略工厂拆分（架构审查 C5）

**日期**: 2026-08-13
**状态**: 已接受（否决）

## 上下文

2026-08-13 架构审查候选 C5：`CountersignRollbackStrategies`
（`strategy` 包，96 行）同一类承担「工厂」（3 个装配方法 strict / autoRedirect /
autoRebuild）与「静态工具」（`resolveMultiInstancePredecessor`）两种职责。
报告建议将 `resolveMultiInstancePredecessor` 提取为独立的前置节点解析模块
（`MiPredecessorResolver` 深模块），策略与 `TaskExecutionWorkflow.getJumpableNodes`
都依赖它，工厂只保留装配方法。

报告 Wins 声称：职责单一 / 删除静态工具绕行、前置解析可独立测试、
策略间共享逻辑有落点、locality 回退判定集中。

## 决策

**否决**。逐条核对后，报告 Wins 或被现状满足、或自相矛盾：

1. **统一入口已存在**：`resolveMultiInstancePredecessor` 是 3 个调用点
   （`AutoRedirectCountersignRollbackStrategy`、`AutoRebuildCountersignRollbackStrategy`、
   `TaskExecutionWorkflow.getJumpableNodes`）共享的**唯一判定入口**，单点定义已
   保证「两处解析逻辑一致」——这正是 locality 要的效果，不因类名含「工厂」而失效。
2. **可独立测试已被现状满足**：`AutoRedirectCountersignRollbackStrategyTest` 已有
   4 个直接单测覆盖该方法（单例前置 / 多个前置 / 空 / MI 过滤），静态方法可测性已达标。
3. **Wins 自相矛盾**：两策略（autoRedirect / autoRebuild）实际共享的是完整骨架
   （count 判断 + 前置解析 + 重定向消息构造），C5 只提取「前置解析」一环并不解决
   共享骨架问题；而提取完整骨架即落入 C2 已否决的模板方法。所谓「策略间共享逻辑有
   落点」在现状下无落点可加。
4. **代价不小**：拆分需改动 3 个生产类 + 自动配置新增 Bean +
   `TaskExecutionWorkflow` 构造器 11 → 12 参数 + `resolveMultiInstancePredecessor`
   公开 static 方法移除的破坏面（下游 `jw-zhyg-api` 若引用则编译失败）+ 测试迁移。
5. **推翻既有 ADR**：ADR-0021 明示「共享的前置单例节点解析工具
   `resolveMultiInstancePredecessor` 亦收敛在该工厂类中」系**有意设计**。拆分即
   推翻既有决策，但审查摩擦（96 行类的双重职责标签）未达重审 ADR 的真实阈值。

与 C2 同构：统一入口已存在、此规模拆分属过度抽象。

## 备选方案

- **实例化深模块 `MiPredecessorResolver`（被否决）**：唯一实质收益是 5 参数调用
  收窄为 3 参数（注入 nodeFinder / multiInstanceDetector），但代价（构造器链 +
  Bean + 公开 API 破坏面 + 测试迁移）远高于收益；且深模块收益（独立测试 / 单一
  入口）已被现状满足。
- **纯静态类搬家（被否决）**：零行为变化、零收益，纯搬移。
- **移除 public 改包私有（被否决）**：`TaskExecutionWorkflow` 跨包调用（`workflow`
  包 vs `strategy` 包），改为包私有需同时引入新的包间通道，复杂化。

## 后果

- **正面**：`CountersignRollbackStrategies` 保持 96 行，回退判定集中一处，
  ADR-0021 决策延续，无公开 API 破坏。
- **负面 / 风险**：无。「双重职责」标签是类注释层面问题，可后续通过
  Javadoc 澄清，无需代码重构。
- **重审条件**：`resolveMultiInstancePredecessor` 出现第 4 个调用点且逻辑
  开始分叉，或策略装配方法超过 3 个。

## 交叉引用

- 架构审查 2026-08-13 候选 C5（Speculative）
- ADR-0021（会签节点回退运行时检测与自动重定向——该方法收敛于工厂类系有意设计）
- ADR-0037（否决执行模板提取，C2——「过度抽象」判据同源）
- `CountersignRollbackStrategies.java`（96 行）、`AutoRedirectCountersignRollbackStrategyTest.java`
