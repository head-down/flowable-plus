# ADR-0033: ApproverResolver 支持运行上下文感知（ApproverContext）

**日期**: 2026-08-12
**状态**: 已接受

## 上下文

业务系统通过 flowable-plus-spring-boot-starter 接入。业务 BPMN 节点的审批人建模有两种方式：

1. **标准属性**：`activiti:assignee="${applyUserId}"`、`activiti:candidateUsers`、`activiti:candidateGroups`
   （candidateGroups 中常存放业务角色 key，而非 Flowable 身份表 groupId）；
2. **自定义扩展元素**（如 `jw:assigneeRoleId`、`jw:candidateUsersRoleId`），以及节点级过滤配置
   （如 `jw:deptFilter` 按申请人部门过滤审批人）。

其中 `${applyUserId}`（当前登录用户）、`${nextApprover}`（运行时上一步审批人）属于依赖运行上下文的动态表达式。

### 现状问题

`ApproverResolver` 契约签名只传 `UserTask`，无法感知任何运行上下文：

```java
List<ApproverInfoVO> resolveApprovers(UserTask userTask);
```

而调用它的 `NodePreviewWorkflow` 内部实际已经拿到了上下文，但没有传给 SPI：

- `getNextNodeApprovers(processKey, mode, variables)`：`variables` 只用于网关条件评估，调用
  `approverResolver.resolveApprovers(userTask)` 未传递；
- `getNextTaskApprovers(taskId, mode)`：已从 `runtimeService.getVariables(...)` 取到运行时全量流程变量，
  同样未传给 SPI。

**后果**：审批人列表中出现 `${applyUserId}` / `${nextApprover}` 等原始表达式字符串，或配置在扩展元素 /
业务角色中的审批人解析不到。调用方必须在 SPI 外部自行过滤、替换、兜底解析——职责被不必要地转移给业务方。

`NodeFinder` 先例佐证：`findNextUserTasks(..., variables)` / `findAdjacentUserTasks(..., variables)`
早已支持上下文传递，唯独 `ApproverResolver` 契约设计时遗漏。这是接口设计缺陷，不是功能缺失。

## 决策

### 方案 A：ApproverResolver 增加带上下文的重载（已采纳）

原单参方法保留为默认方法，新增抽象两参方法：

```java
@FunctionalInterface
public interface ApproverResolver {
    default List<ApproverInfoVO> resolveApprovers(UserTask userTask) {
        return resolveApprovers(userTask, ApproverContext.EMPTY);
    }
    List<ApproverInfoVO> resolveApprovers(UserTask userTask, ApproverContext context);
}
```

新增 `ApproverContext` 值对象（core spi 包）：

```java
public class ApproverContext {
    Map<String, Object> variables;      // 流程变量，可为空
    String currentUserId;               // 当前操作用户（来自 UserContext SPI），可为空
    String processInstanceId;           // 运行时有值（任务锚点）；定义锚点无值
    String taskId;                      // 运行时有值（任务锚点）；定义锚点无值
}
```

### 关键设计决策

1. **ApproverContext 全字段可空 + `EMPTY` 常量**。两个锚点上下文不对称：
   - 定义锚点（发起前预览）：无 `processInstanceId` / `taskId`，`variables` 来自调用方（可为 null）；
   - 任务锚点（审批中）：三者都有值（variables 为运行时全量流程变量）。
2. **`NodePreviewWorkflow` 注入 `UserContext`**：构造函数新增参数，`FlowablePlusAutoConfiguration.nodePreviewWorkflow`
   Bean 定义同步更新。两个锚点均组装 context 并传给 SPI。
3. **默认实现 `UserTaskApproverResolver` 不升级**：两参方法委托单参方法，不消费 context，
   输出严格保持 1.0.0。框架不内建表达式解析规则（`${nextApprover}` 语义只有业务方懂，
   避免越过 ADR-0032「脱离业务数据即失去价值的功能归业务层」边界）。
4. **`@FunctionalInterface` 保持**：接口仍只有 1 个抽象方法（两参），单参为 default 方法。

### 否决方案 B（独立 ExpressionResolver SPI）

只能做「输出前对 id 统一求值」，覆盖不了「基于变量动态计算」「结合当前用户过滤」两项能力；
且拆散 SPI 内聚策略（一个业务关注点一个 SPI 入口，而非多个零散 SPI）。

## 兼容性

抽象方法从单参切换为两参（单参保留为 default 方法），各接入方的兼容情况如下：

| 接入方形态 | 兼容情况 |
|-----------|---------|
| 调用方（调用 `resolveApprovers(userTask)` 单参） | ✅ 零改动：单参 default 方法转发到两参 `EMPTY`，行为不变 |
| 仅实现单参的实现类 | ⚠️ 需补实现两参抽象方法（可委托单参方法，行为保持 1.0.0）；不补则编译失败 |
| 单参 lambda（`userTask -> ...`） | ⚠️ 需改写为两参 lambda（`(userTask, context) -> ...`）；`@FunctionalInterface` 的唯一抽象方法现为两参 |
| 默认实现 `UserTaskApproverResolver` | ✅ 保持 `@ConditionalOnMissingBean` 语义，两参委托单参，输出严格 1.0.0 |

> **说明**：行为层完全向后兼容（输出与 1.0.0 一致），但 SPI 实现方存在**编译期**源码适配（补实现/改写签名）。这是 SPI 契约进化的固有成本——框架通过 default 方法保住了调用点兼容，无法保住实现方签名不迁移。现有仓库内无单参 lambda 实现，风险集中在业务侧接入方。

## 后果

- **正面**：
  - 业务方可在 SPI 内完成表达式解析（`${applyUserId}` → 当前登录用户，`${nextApprover}` → 上一步审批人）、
    基于流程变量做动态审批人计算、结合当前操作用户上下文做过滤（如部门过滤），职责回归 SPI；
  - 与 `NodeFinder` 的 variables 上下文传递先例一致，契约缺口闭合；
  - 现有接入方零行为变化，无破坏性变更。
- **负面 / 风险**：
  - SPI 实现方需做**编译期**源码适配：仅实现单参的实现类补两参方法、单参 lambda 改写为两参签名（行为层无变化，详见「兼容性」表）；
  - `ApproverContext` 的 `variables` 为调用方/运行时 map 的引用，SPI 实现不应修改；
  - 未来若提取 `ExpressionEvaluator` 公共工具（复用 `DefaultNodeFinder.evaluateCondition` +
    `MapVariableContainer`），本 ADR 的 context 字段设计已预留空间（非必须，按需触发）。
