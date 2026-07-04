# Phase 3 规划

> **阶段编号说明**：查询与视图原本不在 Issue #1 的初始规划中。因列表流程信息补充是高频需求（参考成熟业务项目的批量补充模式），提前纳入 Phase 3。Issue #1 原定的三期（任意跳转 + 自动提交）→ [Phase 4](phase-4.md)，原定四期（流程历史 + 流程图）→ [Phase 5](phase-5.md)。

## 范围

**查询与视图**，聚焦以下子方向：

### 本期范围

| 子方向 | 优先级 | 说明 |
|--------|--------|------|
| 流程信息批量补充 | P0 | 输入一组流程实例ID，批量返回运行时状态（当前节点、审批人、挂起状态等），解决列表页展示流程信息的 N+1 查询问题 |
| 待办列表查询 | P1 | 查询指定用户的待办任务列表，含流程实例摘要信息 |
| 已办列表查询 | P1 | 查询指定用户已完成的审批历史 |
| 流程追踪 | P2 | 查询流程实例的审批轨迹（节点流转历史、各节点审批人/时间/意见） |

### 下期预留

| 方向 | 说明 | 归属 |
|------|------|------|
| 我发起的 | 查询当前用户发起的流程实例列表及状态 | 后续版本 |
| 任意跳转 | 驳回至任意历史审批节点 | [Phase 4](phase-4.md) |
| 自动提交 | 基于规则的自动审批 | [Phase 4](phase-4.md) |
| 委托/转办 | 审批人任务转移 | 后续版本 |
| 流程历史/流程图 | 审批记录与可视化 | [Phase 5](phase-5.md) |

## 设计原则

### 非侵入式

与某些项目采用的 `ProcessEntity` 基类 + 反射注入方式不同，flowable-plus 采用 **独立 VO 返回** 设计：

- 不要求用户业务实体继承任何基类
- 不通过反射注入字段
- 返回标准 VO 对象，由调用方自行映射到自己的 DTO/VO

**理由**：flowable-plus 定位为"贴近引擎的增强工具包"，不应约束用户的实体设计。独立 VO 返回解耦更彻底，对任意 Java 8+ 项目可用。

### 批量查询

所有信息补充 API 采用批量设计，输入 `List<String> instanceIds`，内部一次性查询引擎后分组映射，消除列表场景的 N+1 问题。

**理由**：参考成熟业务项目的批量补充模式，列表分页是最高频的使用场景。

## API 设计

### S1: 流程信息批量补充

```java
/**
 * 批量获取流程实例的运行时摘要信息。
 *
 * @param processInstanceIds 流程实例ID列表
 * @return instanceId -> ProcessSummaryVO 的映射，不存在的实例不在 map 中
 */
Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> processInstanceIds);
```

### ProcessSummaryVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `instanceId` | String | 流程实例ID |
| `currentTaskId` | String | 当前活跃任务ID（无活跃任务时为 null） |
| `currentTaskName` | String | 当前节点名称 |
| `currentNodeId` | String | 当前节点 definitionKey |
| `suspendState` | String | 实例挂起状态：1=激活, 2=挂起 |
| `isEnded` | Boolean | 流程是否已结束 |
| `activeAssignees` | List\<String\> | 当前活跃审批人列表 |

**对应参考项目的映射**：参考项目的 `ProcessEntity` 字段 → `ProcessSummaryVO` 返回，由调用方自行映射。

### S2: 待办列表查询（P1）

```java
/**
 * 查询指定用户的待办任务列表。
 *
 * @param userId 用户ID
 * @param query 查询条件（分页、流程定义Key筛选等）
 * @return 分页待办列表
 */
PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query);
```

### S3: 已办列表查询（P1）

```java
/**
 * 查询指定用户的已办任务列表。
 *
 * @param userId 用户ID
 * @param query 查询条件（分页、流程定义Key筛选、时间范围等）
 * @return 分页已办列表
 */
PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query);
```

### S4: 流程追踪（P2）

```java
/**
 * 获取流程实例的完整审批轨迹。
 *
 * @param processInstanceId 流程实例ID
 * @return 按时间排序的审批节点列表，含审批人、时间、意见、耗时
 */
List<ApprovalTraceVO> getApprovalTrace(String processInstanceId);
```

## 架构决策

- **返回类型**：独立 VO 对象，不要求用户继承基类。与某些项目的反射注入方式不同，flowable-plus 保持库的中立性。
- **批量优先**：所有查询 API 的批量版本优先设计（S1 批量、S2/S3 分页、S4 单条详情）。
- **分页工具**：`PageResult` 为内部简单 POJO，不引入 MyBatis-Plus 等第三方分页依赖。用户可自行转换。
- **实现位置**：核心查询逻辑放 `flowable-plus-core`，仅依赖 Flowable 引擎 API。不使用自定义 SQL 表。

## 决策记录

- 2026-07-04：三期聚焦查询与视图。任意跳转/自动提交（原三期）移至 Phase 4，流程历史/流程图（原四期）移至 Phase 5。
- 2026-07-04：采用独立 VO 返回而非反射注入基类，保持库的中立性，不与用户实体设计耦合。
- 2026-07-04：参考成熟业务项目的批量补充模式，但去除反射和实体绑定的耦合。

## 实现切片

| Slice | 内容 | 优先级 | 状态 |
|-------|------|--------|------|
| S1: 流程信息批量补充 | `ProcessInfoService` + `ProcessSummaryVO`，批量查询引擎状态并分组映射 | P0 | 待开发 |
| S2: 待办列表查询 | `TodoTaskQuery` + `TodoTaskVO`，基于引擎 TaskService/TaskQuery 封装分页 | P1 | 待开发 |
| S3: 已办列表查询 | `DoneTaskQuery` + `DoneTaskVO`，基于引擎 HistoryService 封装分页 | P1 | 待开发 |
| S4: 流程追踪 | `ApprovalTraceVO`，基于 HistoryService 查询历史活动实例并聚合审批意见 | P2 | 待开发 |
| S5: Starter 适配 + 测试 | 自动配置注册、集成测试、文档 | — | 待开发 |

S1 优先实现最小可用版本——解决最高频的列表页流程状态展示需求。

## S1 详细设计

### 定位

`ProcessInfoService` 是流程信息批量补充的入口，对标成熟业务项目的批量补充方法。

### 与参考项目的差异

| 维度 | 参考项目方案 | flowable-plus 方案 |
|------|---------|-------------------|
| 实体绑定 | 要求继承 ProcessEntity 基类 | 无要求，返回独立 VO |
| 数据注入 | 反射 setter 注入字段 | 返回 Map，调用方映射 |
| 会签判断 | 内联查询自定义 countersign 表 | 通过 BPMN 模型检测多实例节点（复用 ADR-0003 路线） |
| 当前用户依赖 | SecurityUtils.getUsername() | 通过 UserContext SPI（已实现） |
| 异常处理 | 吞异常，不阻断主流程 | 抛 FlowablePlusException，由调用方决定 |
| 子流程 | 查询自定义子流程表 | Flowable 原生 `HistoryService` 子流程查询 |

### 实现思路

```java
public Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> instanceIds) {
    // 1. 批量查询活跃任务（TaskService.createTaskQuery().processInstanceIdIn(ids)）
    // 2. 批量查询流程实例（RuntimeService.createProcessInstanceQuery().processInstanceIds(ids)）
    // 3. 批量查询历史实例（HistoryService，补充已结束的流程）
    // 4. 对每个实例：有活跃任务→提取任务信息，无活跃任务→判断是否已结束/挂起
    // 5. 组装 ProcessSummaryVO，返回 Map
}
```

核心流程参照参考项目的批量补充模式，但不包含自定义表查询（业务特性）。

### 异常策略

```java
/**
 * @throws IllegalArgumentException 如果 instanceIds 为 null 或空
 */
Map<String, ProcessSummaryVO> batchQueryProcessSummaries(List<String> instanceIds);
```

与参考项目的"吞异常不阻断"不同，flowable-plus 的 API 风格是参数校验前置 + 异常透传（见 Phase 1 FlowablePlus 设计）。
