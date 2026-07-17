# DispatchableEvent 自分发替代 instanceof 链

## 背景

2026-07-17 架构审查（`/improve-codebase-architecture` 候选项 #3）发现事件系统的 `DefaultEventPublisher.dispatch()` 方法包含一个 8 分支的 `if-else instanceof` 链，每增加一个新事件类型就需要同时修改 3 个文件：

1. 新建事件 POJO
2. 在 `ProcessEventListener` 中新增 `default` 空方法
3. 在 `dispatch()` 中新增 `else if` 分支

这违背了开闭原则——发布器需要了解所有事件类型才能完成分发。

## 决策

**引入 `DispatchableEvent` 接口，采用自分发模式替代 instanceof 链。**

### DispatchableEvent 接口

```java
// 位于 core/event/ 包，extends ProcessEvent
public interface DispatchableEvent extends ProcessEvent {
    void accept(ProcessEventListener listener);
}
```

### 改动点

1. **8 个事件类**：`implements ProcessEvent` → `implements DispatchableEvent`，各加 1 个 `accept()` 方法
2. **`DefaultEventPublisher.dispatch()`**：8 分支 `instanceof` 链替换为单次 `DispatchableEvent` 检查

```java
// 改造后
private void dispatch(ProcessEventListener listener, ProcessEvent event) {
    if (event instanceof DispatchableEvent) {
        ((DispatchableEvent) event).accept(listener);
    }
}
```

3. **`ProcessEventListener`**：不变（保持向后兼容）
4. **`ProcessEvent`**：不变（保持纯数据契约）

### 替换方案回顾

| 方案 | 判据 |
|---|---|
| A. 自分发 (`DispatchableEvent`) — **选中** | 不破坏 SPI 兼容，发布器对事件类型无感知 |
| B. 单一泛型回调 (`void onEvent(ProcessEvent)`) | 破坏现有 SPI 实现，监听器需各自 instanceof，拒绝 |
| C. 保持现状 | 承认 3 点联动可接受，但架构审查评级为 "Worth exploring"，决定推进 |

## 后果

- 新增事件类型的变更点从 3 个缩减为 2 个（POJO + SPI），`dispatch()` 无需再改动
- `DefaultEventPublisher` 不再依赖任何具体事件类型，满足开闭原则
- `ProcessEvent` 保持纯数据契约，第三方自定义事件不受影响
- `DispatchableEvent` 是框架内部实现工具接口，不写入 CONTEXT.md 领域术语表
- 若将来出现非 `DispatchableEvent` 的 `ProcessEvent` 传入发布器，当前实现静默忽略（发布器仅由框架内部工作流类调用，入参始终为 `DispatchableEvent`）
