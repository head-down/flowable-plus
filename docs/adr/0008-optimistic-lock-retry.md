# ADR-0008: 乐观锁冲突不采用通用 AOP 重试，仅在具体方法内精准重试

*2026-07-15*

## 背景

千问代码审计建议：在 flowable-plus-core 中增加 `@FlowableRetry` 注解或 AOP 切面，统一捕获 `FlowableOptimisticLockingException`，自动休眠 50ms 后重试（最多 3 次），覆盖抢单（或签）、加签/减签、批量审批等场景。

## 决策

**不引入通用 AOP 重试机制。** `FlowableOptimisticLockingException` 在不同操作上下文中语义完全不同，不能用统一策略处理。

## 场景分析

| 场景 | 冲突概率 | 应重试？ | 理由 |
|------|:---:|:---:|------|
| 抢单 claimTask | 高 | **否** | 异常本身就是正确答案——"任务已被别人抢走"，重试无意义。应告知用户刷新列表 |
| 批量审批 completeTask | 中 | **否** | 应在上层编排处理冲突逻辑，不下沉到 core 模块 |
| 会签加签 addCounterSigner | 低 | **方法内** | 唯一适合重试的场景——管理操作、可重读状态、可安全重放 |
| 会签减签 removeCounterSigner | 低 | **否** | 要删的 Execution 可能已被删，重试语义错误 |
| 驳回/撤回/跳转 | 极低 | **否** | 单用户操作，几乎不存在并发冲突 |

## 替代方案

| 方案 | 拒绝原因 |
|------|---------|
| 通用 AOP `@FlowableRetry` 注解 | 同一种异常跨越多种语义：抢单冲突是预期行为，加签冲突可以重试，减签冲突重试会出错。AOP 切面无法区分 |
| Spring-retry `@Retryable` | 引入新依赖，增加代理层开销，同样无法区分语义 |
| Flowable 内部重试机制 | Flowable 已有针对 `FlowableOptimisticLockingException` 的默认处理——让调用方感知并决定 |

## 后果

- 不提供乐观锁异常通用重试机制
- 抢单场景：乐观锁异常是正常流控信号，调用方自行提示用户
- 加签场景：若将来出现并发冲突，在 `CounterSignWorkflow.addCounterSigner` 方法内用 `for` 循环做精准重试，休眠和次数在方法内硬编码，不引入注解/切面/第三方库
