# Phase 7 规划

> **阶段编号说明**：事件监听器是 Phase 2 "D. 基础设施" 中延后处理的能力之一。委派/转办（Phase 5 插入）和流程可视化（Phase 6）落定后，将事件监听器纳入 Phase 7 补齐基础设施。

## 范围

**流程事件监听器 SPI**，为接入方提供类型安全、零侵入的流程生命周期事件回调。

### 本期范围

| 子方向 | 优先级 | 说明 | 原始归属 |
|--------|--------|------|----------|
| ProcessEventListener SPI | P0 | 定义语义化事件接口，覆盖流程发起、审批、驳回、撤销、委派、转办、结束等生命周期 | Phase 2 "D. 基础设施" |
| EventPublisher 组件 | P0 | 内部的中央事件发布器，在各操作 API 末尾触发事件 | Phase 2 "D. 基础设施" |
| Spring Boot 自动配置 | P1 | 多监听器注册、异步线程池、配置开关 | Phase 2 "D. 基础设施" |

## SPI 接口

```java
/**
 * 流程事件监听器。实现此接口并注册为 Spring Bean 即可接收流程生命周期事件。
 * 所有回调默认异步执行，异常不向上传播。
 */
public interface ProcessEventListener {
    default void onProcessStarted(ProcessStartedEvent event) {}
    default void onTaskCompleted(TaskCompletedEvent event) {}
    default void onTaskRejected(TaskRejectedEvent event) {}
    default void onTaskWithdrawn(TaskWithdrawnEvent event) {}
    default void onProcessRevoked(ProcessRevokedEvent event) {}
    default void onTaskDelegated(TaskDelegatedEvent event) {}
    default void onTaskTransferred(TaskTransferredEvent event) {}
    default void onProcessEnded(ProcessEndedEvent event) {}
}
```

## 事件对象

所有事件对象为不可变 POJO，各操作独立一个事件类型：

| 事件类型 | 触发操作 | 关键字段 |
|----------|---------|---------|
| `ProcessStartedEvent` | `startProcess` | processInstanceId, processDefinitionKey, businessKey, startUserId |
| `TaskCompletedEvent` | `completeTask`, `counterSign` | taskId, processInstanceId, taskName, assignee, comment |
| `TaskRejectedEvent` | `rejectTask`, `rejectTaskToInitiator`, `rejectToNode` | taskId, processInstanceId, reason |
| `TaskWithdrawnEvent` | `withdrawTask` | taskId, processInstanceId, reason |
| `ProcessRevokedEvent` | `revokeProcess` | processInstanceId, reason |
| `TaskDelegatedEvent` | `delegateTask` | taskId, processInstanceId, delegateUserId, reason |
| `TaskTransferredEvent` | `transferTask` | taskId, processInstanceId, transferUserId, reason |
| `ProcessEndedEvent` | 流程自然结束/撤销 | processInstanceId, endReason |

## 架构决策

### 模块范围

涉及 `flowable-plus-core`（事件对象 + SPI + EventPublisher）和 `flowable-plus-spring-boot-starter`（自动配置）。

### 触发方式

不依赖 Flowable 原生 `FlowableEventListener`。在 `FlowablePlus` 各公开 API 方法的最后一步通过 `EventPublisher` 发布事件：

```java
// FlowablePlus 内部
public void completeTask(String taskId, String comment) {
    // ... 现有逻辑 ...
    eventPublisher.publish(new TaskCompletedEvent(...));
}
```

理由：
- flowable-plus 方法已封装操作语义，触发点明确
- 直接发布事件对象比监听引擎原生事件更精准
- 解耦 flowable-plus SPI 与 Flowable 内部实现

### 异步与异常隔离

- 默认通过线程池异步执行回调，不阻塞引擎主流程
- 单个监听器异常不影响其他监听器和主流程
- 配置项：`flowable.plus.event.enabled` / `flowable.plus.event.async`

### 多监听器支持

支持注册多个 `ProcessEventListener` Bean，`EventPublisher` 遍历调用所有注册的监听器。

## 决策记录

- 2026-07-14：事件监听器从 Phase 2 "D. 基础设施" 正式纳入 Phase 7。
- 2026-07-14：不依赖 Flowable 原生 `FlowableEventListener`，在各操作 API 末尾直接发布事件。

## 实现切片

| Slice | 内容 | 优先级 | 状态 |
|-------|------|--------|------|
| S1: 核心 SPI + EventPublisher | 事件对象定义 + SPI 接口 + EventPublisher 组件 | P0 | 待开发 |
| S2: 各 API 集成 | FlowablePlus 各方法中集成 EventPublisher | P0 | 待开发 |
| S3: Spring Boot 自动配置 | 多监听器注册、线程池、配置开关 | P1 | 待开发 |
| S4: 测试 + 文档 | 单元测试、集成测试、使用文档 | — | 待开发 |

## 范围外

- 流式事件处理（事件流 + 规则引擎）
- 分布式事件总线（Kafka/RabbitMQ 集成）
- 事件重试/补偿机制
- 事件历史记录持久化
- `FlowableEventListener` 的替代或桥接
