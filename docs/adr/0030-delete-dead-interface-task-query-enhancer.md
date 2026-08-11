# ADR-0030: 删除死接口 TaskQueryEnhancer，回调收敛为 Consumer 单一形态

**日期**: 2026-08-11
**状态**: 已接受

## 上下文

`io.github.flowable.plus.core.spi.TaskQueryEnhancer` 是待办/已办查询自定义过滤条件
回调接口，自 Phase 3 早期（S2/S3）引入。但实际公开查询 API 从 ADR-0006 起就以
`Consumer<TaskQuery>` 回调参数作为唯一形态：

- `QueryOperations.queryTodoTasks(userId, query, Consumer<TaskQuery>)`
- `QueryOperations.queryDoneTasks(userId, query, Consumer<HistoricProcessInstanceQuery>)`

即 `TaskQueryEnhancer` 是**从未被消费的死接口**：全库 main 代码零引用；仅测试
`BpmnQueryIntegrationTest` 残留一个未使用的 import（测试实际走的是 `Consumer`
回调路径）。其与 `Consumer` 回调并存制造了「回调有两个入口」的假象，
误导调用方选择。

本删除与 extension 空壳处置同属架构审查 2026-08-10 Card F（删除测试通过的残留），
extension 部分已由 ADR-0029 落地。

## 决策

1. **删除** `spi/TaskQueryEnhancer.java`，公开回调形态收敛为唯一的
   `Consumer<TaskQuery>`（待办）与 `Consumer<HistoricProcessInstanceQuery>`（已办）。
2. **清理测试残留**：移除 `BpmnQueryIntegrationTest` 中未使用的 import 与
   方法名/注释中的 `TaskQueryEnhancer` 字样（测试行为不变）。
3. **文档同步**：README SPI 表删除该行；CONTEXT.md 两处「TaskQueryEnhancer 回调」
   改述为「Consumer&lt;TaskQuery&gt; 回调」。

## 备选方案

- **保留接口（被否决）**：一个从未被消费、且与 `Consumer` 回调语义完全重叠的
  接口没有存在价值，保留只会延续「两个回调入口」的误导。
- **改为扩展 TaskQueryEnhancer 语义（被否决）**：将其从 SPI 转为内部实现类
  同样无消费方，属于无谓保留。

## 后果

- **正面**：
  - 消除「回调有两个入口」的假象，文档与代码只呈现一种回调形态
  - 公开 SPI 面缩小（core 少一个公开接口）
- **负面 / 风险**：
  - **破坏性变更**：删除已发布库（1.0.0 GA）的公开接口 `TaskQueryEnhancer`；
    全库零引用、无下游消费证据，风险可控

## 交叉引用

- 架构审查 2026-08-10 Card F（extension 空壳 + 死接口 TaskQueryEnhancer 处置）
- ADR-0029（extension 储备位定位，Card F 的 extension 部分）
- ADR-0006（自定义过滤回调，Consumer 形态的原始决策）
