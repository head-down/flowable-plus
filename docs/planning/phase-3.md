# Phase 3 规划

> **阶段编号说明**：查询与视图原本不在 Issue #1 的初始规划中。因列表流程信息补充是高频需求（参考成熟业务项目的批量补充模式），提前纳入 Phase 3。Issue #1 原定的三期（任意跳转 + 自动提交）→ [Phase 4](phase-4.md)，原定四期（流程历史 + 流程图）→ [Phase 5](phase-5.md)。

## 范围

**查询与视图**，聚焦以下子方向：

### 本期范围

| 子方向 | 优先级 | 说明 |
|--------|--------|------|
| 批量补充流程信息 | P0 | 输入一组流程实例ID，批量返回运行时状态（当前节点、审批人、挂起状态等），解决列表页展示流程信息的 N+1 查询问题 |
| 待办列表查询 | P1 | 查询指定用户的待办任务列表，含流程实例摘要信息 |
| 已办列表查询 | P1 | 查询指定用户已完成的审批历史 |
| 审批轨迹 | P2 | 查询流程实例的审批节点流转历史（各节点审批人/时间/意见/耗时） |
| 下一节点审批人（发起前） | P0 | 发起流程前，查询初始审批节点及对应审批人 |
| 下一节点审批人（审批中） | P1 | 审批过程中，查询当前任务下一节点的审批人 |
| 下一节点（审批中） | P1 | 审批过程中，查询当前任务可流转至的下游节点（含分支选择） |

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

批量补充流程信息 API 采用批量设计，输入 `List<String> instanceIds`，内部一次性查询引擎后分组映射，消除列表场景的 N+1 问题。

**理由**：参考成熟业务项目的批量补充模式，列表分页是最高频的使用场景。

### 业务自定义过滤：Consumer 回调

待办/已办列表接口提供可选的 `Consumer<TaskQuery>` 回调参数，由调用方在回调中直接调用 Flowable 原生 `processVariableValueXXX` 系列方法追加过滤条件。详见 [ADR-0006](../../docs/adr/0006-query-custom-filter-callback.md)。

## 代码组织

三期新增方法按领域拆分为三个接口，`FlowablePlus` 实现全部：

```
FlowablePlus implements
  ├── TaskOperations           (Phase 1)
  ├── CounterSignOperations    (Phase 2)
  ├── RejectionOperations      (Phase 1)
  ├── ProcessLifecycle         (Phase 1)
  ├── ProcessQueryOperations   ← 新增 (S1 + S4)
  ├── TaskListOperations       ← 新增 (S2 + S3)
  └── NodePreviewOperations    ← 新增 (S5 + S6 + S7)
```

| 接口 | 方法 | 共享 VO |
|------|------|---------|
| `ProcessQueryOperations` | `batchQueryProcessSummaries`、`getApprovalTrace` | `ProcessSummaryVO`、`ApprovalTraceVO` |
| `TaskListOperations` | `queryTodoTasks`、`queryDoneTasks` | `TodoTaskVO`、`DoneTaskVO`、`TaskQueryDTO`、`PageResult` |
| `NodePreviewOperations` | `getNextNodeApproversByProcessKey`、`getNextTaskApprovers`×2、`getNextTaskNodes` | `NodeApproverVO`、`ApproverInfoVO`、`NextTaskNodeVO` |

## 通用类型

### PageResult

```java
public class PageResult<T> {
    long total;       // 总记录数
    int pageNum;      // 当前页码（从 1 开始，与 MyBatis-Plus 一致）
    int pageSize;     // 每页大小
    List<T> records;  // 当前页数据
}
```

### TaskQueryDTO

```java
public class TaskQueryDTO {
    int pageNum = 1;                // 页码（从 1 开始）
    int pageSize = 20;              // 每页大小
    String processDefinitionKey;    // 流程定义 Key
    String taskName;                // 节点名称（精确匹配）
    String keyword;                 // 模糊搜索（businessKey + 流程定义名称）
    Date beginDate;                 // 创建时间起
    Date endDate;                   // 创建时间止
}
```

`userId` 不放在 DTO 中，由 API 方法参数显式传入，语义更清晰。

## API 设计

### S1: 批量补充流程信息

```java
/**
 * 批量获取流程实例的运行时摘要信息。
 *
 * @param processInstanceIds 流程实例ID列表
 * @return instanceId -> ProcessSummaryVO 的映射，不存在的实例不在 map 中
 * @throws IllegalArgumentException 如果 instanceIds 为 null 或空
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
| `suspendState` | int | 实例挂起状态：1=激活, 2=挂起（与 Flowable SuspensionState 一致） |
| `isEnded` | Boolean | 流程是否已结束 |
| `activeAssignees` | List\<String\> | 当前活跃审批人列表（统一 List，普通节点为单元素） |

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

/**
 * 查询指定用户的待办任务列表，支持自定义过滤条件。
 *
 * @param userId   用户ID
 * @param query    查询条件
 * @param enhancer 可选的自定义过滤条件（如 processVariableValueXXX）
 * @return 分页待办列表
 */
PageResult<TodoTaskVO> queryTodoTasks(String userId, TaskQueryDTO query, Consumer<TaskQuery> enhancer);
```

### TodoTaskVO

| 字段 | 类型 | 来源 |
|------|------|------|
| `taskId` | String | Task.id |
| `taskName` | String | Task.name |
| `processInstanceId` | String | Task.processInstanceId |
| `processDefinitionKey` | String | ProcessDefinition.key |
| `processDefinitionName` | String | ProcessDefinition.name |
| `businessKey` | String | Task.businessKey |
| `startUserId` | String | ProcessInstance.startUserId |
| `createTime` | Date | Task.createTime |
| `assignee` | String | Task.assignee（会签时有值，组任务时 null） |

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

/**
 * 查询指定用户的已办任务列表，支持自定义过滤条件。
 *
 * @param userId   用户ID
 * @param query    查询条件
 * @param enhancer 可选的自定义过滤条件（如 processVariableValueXXX）
 * @return 分页已办列表
 */
PageResult<DoneTaskVO> queryDoneTasks(String userId, TaskQueryDTO query, Consumer<HistoricTaskInstanceQuery> enhancer);
```

### DoneTaskVO

| 字段 | 类型 | 来源 |
|------|------|------|
| `taskId` | String | HistoricTaskInstance.id |
| `taskName` | String | HistoricTaskInstance.name |
| `processInstanceId` | String | HistoricTaskInstance.processInstanceId |
| `processDefinitionKey` | String | ProcessDefinition.key |
| `processDefinitionName` | String | ProcessDefinition.name |
| `businessKey` | String | HistoricTaskInstance.businessKey？ |
| `startUserId` | String | ProcessInstance.startUserId |
| `createTime` | Date | HistoricTaskInstance.createTime |
| `endTime` | Date | HistoricTaskInstance.endTime |
| `assignee` | String | HistoricTaskInstance.assignee |
| `deleteReason` | String | HistoricTaskInstance.deleteReason |

不含 `comment` 字段——审批意见归属审批轨迹详情。

### S4: 审批轨迹（P2）

```java
/**
 * 获取流程实例的完整审批轨迹。
 *
 * @param processInstanceId 流程实例ID
 * @return 按时间排序的审批节点列表，含审批人、时间、意见、耗时
 */
List<ApprovalTraceVO> getApprovalTrace(String processInstanceId);
```

### ApprovalTraceVO

| 字段 | 类型 | 来源 |
|------|------|------|
| `taskId` | String | 任务 ID |
| `taskName` | String | 节点名称 |
| `nodeId` | String | 节点 definitionKey |
| `assignee` | String | 审批人 |
| `startTime` | Date | 任务开始时间 |
| `endTime` | Date | 任务结束时间（当前节点为 null） |
| `durationMillis` | Long | 耗时（毫秒） |
| `comment` | String | 审批意见（来自 Comment 表） |
| `approved` | Boolean | 是否同意（由 deleteReason 推断） |
| `isRejected` | Boolean | 是否驳回（由 deleteReason 推断） |

### S5: 下一节点审批人—发起前（P0）

```java
/**
 * 根据流程定义 Key 获取初始审批节点及审批人。
 * 用于发起流程前展示审批链路，支持多节点。
 * 不评估网关条件表达式。
 *
 * @param processKey 流程定义 Key
 * @return 初始审批节点列表，每个节点包含审批人列表
 */
List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey);
```

### NodeApproverVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `nodeId` | String | 节点 definitionKey |
| `nodeName` | String | 节点名称 |
| `approvers` | List\<ApproverInfoVO\> | 该节点的审批人列表 |

### ApproverInfoVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 审批人ID（用户名） |
| `name` | String | 审批人显示名称 |
| `type` | String | 来源类型：`assignee`（指定人）、`candidateUser`（候选用户）、`candidateGroup`（候选组内成员） |
| `groupId` | String | 候选组ID（type=candidateGroup 时有值） |
| `groupName` | String | 候选组名称（type=candidateGroup 时有值） |

### S6: 下一节点审批人—审批中（P1）

```java
/**
 * 获取当前任务所有下一节点的审批人（扁平列表，不分组）。
 * 基于运行时上下文评估网关条件表达式。
 *
 * @param taskId 当前任务ID
 * @return 所有下一节点的审批人列表
 */
List<ApproverInfoVO> getNextTaskApprovers(String taskId);

/**
 * 获取当前任务指定目标节点的审批人。
 *
 * @param taskId       当前任务ID
 * @param targetNodeId 目标节点 definitionKey
 * @return 指定目标节点的审批人列表
 */
List<ApproverInfoVO> getNextTaskApprovers(String taskId, String targetNodeId);
```

采用方法重载：无参版返回所有下一节点审批人，带参版过滤至指定节点。

### S7: 下一节点—审批中（P1）

```java
/**
 * 获取当前任务可流转至的下游节点列表。
 * 用于审批页面展示分支选项（如多分支网关后的不同节点）。
 *
 * @param processInstanceId 流程实例ID
 * @param taskId            当前任务ID
 * @return 下一节点列表，含节点信息和表单配置
 */
List<NextTaskNodeVO> getNextTaskNodes(String processInstanceId, String taskId);
```

### NextTaskNodeVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskCode` | String | 节点 definitionKey |
| `taskName` | String | 节点名称 |
| `formData` | String | 节点扩展属性内容（BPMN `extensionElements` 中的自定义元素，JSON 格式，可包含表单配置等） |

## 新增 SPI

### TaskQueryEnhancer 回调（S2/S3）

```java
/**
 * 待办查询自定义过滤条件。
 * 调用方在此回调中直接调用 Flowable TaskQuery 的 processVariableValueXXX 方法。
 */
@FunctionalInterface
public interface TaskQueryEnhancer {
    void enhance(TaskQuery query);
}
```

自定义过滤的前提是业务数据必须作为 process variables 存储在流程实例中。

### GroupResolver（S5/S6）

```java
/**
 * 候选组解析器。将候选组 ID 展开为组��员列表。
 * 默认实现基于 Flowable IdentityService，用户可替换为自定义组织架构服务。
 */
public interface GroupResolver {
    /**
     * 根据组 ID 获取组成员用户 ID 列表。
     * @param groupId 候选组 ID
     * @return 组成员用户 ID 列表，空组返回空列表
     */
    List<String> getGroupMembers(String groupId);
}
```

## 架构决策

- **返回类型**：独立 VO 对象，不要求用户继承基类。与某些项目的反射注入方式不同，flowable-plus 保持库的中立性。
- **批量优先**：批量补充流程信息采用批量设计（S1 批量、S2/S3 分页、S4 单条详情）。
- **分页工具**：`PageResult` 为内部简单 POJO，pageNum 从 1 开始（与 MyBatis-Plus 一致），不引入第三方分页依赖。
- **实现位置**：核心查询逻辑放 `flowable-plus-core`，仅依赖 Flowable 引擎 API。不使用自定义 SQL 表。
- **BPMN 节点遍历**：S5/S6/S7 通过 BPMN 模型的递归拓扑遍历查找下一 UserTask 节点，递归深入网关、子流程、调用活动。遍历中通过 `visited` 集合防止循环引用。
- **审批人解析**：S5/S6 的审批人解析分为三种来源——候选组（`candidateGroups`）、候选用户（`candidateUsers`）、指定人（`assignee`）。候选组通过 `GroupResolver` SPI 展开为成员列表，默认基于 Flowable IdentityService，用户可替换。
- **网关条件评估**：S6/S7 在运行时上下文中评估排他/包容网关的条件表达式，使用 Flowable `ExpressionManager` + `RuntimeService.getVariables()`。S5 为发起前静态预览，不评估条件。
- **自定义过滤**：S2/S3 通过可选的 `Consumer<TaskQuery>` 回调注入自定义过滤条件。不提供 SQL JOIN 或内置中间表。详见 ADR-0006。

### S5 实现思路

```java
public List<NodeApproverVO> getNextNodeApproversByProcessKey(String processKey) {
    // 1. 通过 repositoryService 获取最新版本活跃的 ProcessDefinition
    // 2. 获取 BPMN 模型（复用 BpmnModelCache）
    // 3. 从 StartEvent 出发，递归遍历所有下游 UserTask 节点
    // 4. 对每个 UserTask 解��审批人（candidateGroups/candidateUsers/assignee）
    // 5. candidateGroups 通过 GroupResolver.getGroupMembers() 展开
    // 6. 按节点分组返回 NodeApproverVO
}
```

**关键简化**：不引入参考项目的 draft 节点和 initialNodes 扩展属性概念。flowable-plus 直接从标准 StartEvent 出发，返回所有初始可达的 UserTask。

### S6 实现思路

```java
public List<ApproverInfoVO> getNextTaskApprovers(String taskId, String targetNodeId) {
    // 1. 通过 taskService 获取当前 Task
    // 2. 获取 BPMN 模型（复用 BpmnModelCache）
    // 3. 从当前任务所在的 FlowNode 出发，递归查找所有直接下游 UserTask
    // 4. 评估网关条件表达式（通过 ExpressionManager + RuntimeService.getVariables()）
    // 5. 按 targetNodeId 筛选（null 则全返回）
    // 6. 解析审批人（含 GroupResolver 展开）并返回扁平列表
}
```

**与 S5 的关键差异**：S6 基于运行时任务（有 processInstanceId），可评估网关条件表达式，只返回条件满足的分支。S5 为静态预览，不评估条件。

### S7 实现思路

```java
public List<NextTaskNodeVO> getNextTaskNodes(String processInstanceId, String taskId) {
    // 1. 获取当前 Task 和 BPMN 模型
    // 2. 从当前节点递归找出所有下一 UserTask（含条件评估）
    // 3. 从节点的 extensionElements 自定义属性中提取 formData
    // 4. 返回节点信息（不含审批人）
}
```

**与 S6 的差异**：S7 返回节点元信息（含表单配置），供前端做分支选择；S6 返回审批人信息，供前端展示谁会审批。

## 实现切片

| Slice | 内容 | 优先级 | 状态 |
|-------|------|--------|------|
| S1: 批量补充流程信息 | `ProcessQueryOperations.batchQueryProcessSummaries` + `ProcessSummaryVO`，批量查询引擎状态并分组映射 | P0 | 待开发 |
| S2: 待办列表查询 | `TaskListOperations.queryTodoTasks` + `TodoTaskVO` + `TaskQueryDTO` + `TaskQueryEnhancer`，基于引擎 TaskService/TaskQuery 封装分页 | P1 | 待开发 |
| S3: 已办列表查询 | `TaskListOperations.queryDoneTasks` + `DoneTaskVO` + `TaskQueryDTO` + `TaskQueryEnhancer`，基于引擎 HistoryService 封装分页 | P1 | 待开发 |
| S4: 审批轨迹 | `ProcessQueryOperations.getApprovalTrace` + `ApprovalTraceVO`，基于 HistoryService 查询历史活动实例并聚合审批意见 | P2 | 待开发 |
| S5: 下一节点审批人（发起前） | `NodePreviewOperations.getNextNodeApproversByProcessKey` + `NodeApproverVO` + `ApproverInfoVO` + `GroupResolver`，基于 BPMN 模型遍历和审批人解析 | P0 | 待开发 |
| S6: 下一节点审批人（审批中） | `NodePreviewOperations.getNextTaskApprovers`×2 + 条件分支评估，基于当前任务获取下一节点审批人 | P1 | 待开发 |
| S7: 下一节点（审批中） | `NodePreviewOperations.getNextTaskNodes` + `NextTaskNodeVO`，基于当前任务获取可流转的下游节点 | P1 | 待开发 |
| S8: Starter 适配 + 测试 | 自动配置注册（`GroupResolver` 默认 Bean、`BpmnModelCache`、三个新接口的 `FlowablePlus` 实现）、集成测试、文档 | — | 待开发 |

S1 和 S5 优先实现——解决最高频的列表页流程状态展示和发起前审批人预览需求。

## S1 详细设计

### 定位

批量补充流程信息是流程信息查询的入口，对标成熟业务项目的批量补充方法。

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
    // 5. 多实例节点通过 BpmnModelCache.isMultiInstance() 检测，isMultiInstance=true 时取所有 assignee
    // 6. 组装 ProcessSummaryVO，返回 Map
}
```

核心流程参照参考项目的批量补充模式，但不包含自定义表查询（业务特性）。

## 决策记录

- 2026-07-04：三期聚焦查询与视图。任意跳转/自动提交（原三期）移至 Phase 4，流程历史/流程图（原四期）移至 Phase 5。
- 2026-07-04：采用独立 VO 返回而非反射注入基类，保持库的中立性，不与用户实体设计耦合。
- 2026-07-04：参考成熟业务项目的批量补充模式，但去除反射和实体绑定的耦合。
- 2026-07-05：新增 S5/S6/S7 三个"向前预审"功能，补齐发起前和审批中展示下一节点/审批人的能力。
- 2026-07-05：S5 基于标准 BPMN StartEvent 遍历，不引入 draft 节点和 initialNodes 扩展属性概念，保持框架中立。
- 2026-07-05：S6/S7 支持网关条件表达式评估，因为基于运行时任务有 processInstanceId 可用。
- 2026-07-05：S7 设计 `NextNodeFilterStrategy` 扩展点（预留），允许业务模块注册过滤器排除不应展示的节点。
- 2026-07-06：待办/已办列表自定义过滤采用 `TaskQueryEnhancer` 回调模式。不提供 SQL JOIN 或中间表。详见 ADR-0006。
- 2026-07-06：S5/S6 候选组展开通过 `GroupResolver` SPI，默认基于 Flowable IdentityService，用户可替换为自定义组织架构服务。
- 2026-07-06：`ProcessSummaryVO.suspendState` 使用 `int` 类型，与 Flowable `SuspensionState` 常量一致。
- 2026-07-06：`ProcessSummaryVO.activeAssignees` 统一返回 `List<String>`，普通节点为单元素列表。
- 2026-07-06：`PageResult.pageNum` 从 1 开始，与 MyBatis-Plus 风格一致。
- 2026-07-06：`DoneTaskVO` 不含 `comment` 字段，审批意见归属审批轨迹。
- 2026-07-06：S6 `getNextTaskApprovers` 采用方法重载，无参版返回所有下一节点审批人，带参版过滤至指定节点。
- 2026-07-06：S7 `NextTaskNodeVO.formData` 来自 BPMN `extensionElements` 自定义扩展属性。
- 2026-07-06：S6/S7 网关条件表达式通过 Flowable `ExpressionManager` + `RuntimeService.getVariables()` 评估。
- 2026-07-06：三期新增 `ProcessQueryOperations`、`TaskListOperations`、`NodePreviewOperations` 三个接口，`FlowablePlus` 实现全部。
- 2026-07-06：`TaskQueryDTO.userId` 不放入 DTO，由 API 方法参数显式传入。
