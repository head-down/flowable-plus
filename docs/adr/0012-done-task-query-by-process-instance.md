# 已办查询基于流程实例维度的两阶段查询

## 背景

2026-07-17 架构审查（`/improve-codebase-architecture` 候选项 #5）发现已办查询存在全量内存分页 OOM 风险：

```java
// TaskQueryModule.queryDoneTasks() - 当前实现
List<HistoricTaskInstance> allHistoricTasks = historicQuery.list();  // 全量加载
List<HistoricTaskInstance> deduped = voAssembler.dedupByNode(allHistoricTasks);  // 内存去重
long total = deduped.size();
List<HistoricTaskInstance> pageTasks = deduped.subList(fromIndex, toIndex);  // 内存分页
```

随着用户已办数据增长，`list()` 全量加载必然触发 OOM。

## 决策

**将已办查询从"任务维度"切换到"流程实例维度"，采用两阶段查询。**

### 方案选择

| 方案 | 判据 |
|---|---|
| A. SQL 窗口去重（ROW_NUMBER OVER PARTITION） | 依赖 MySQL 8+ 方言，Flowable API 不支持原生 SQL，拒绝 |
| B. 流式循环分页 + 内存去重 | 复杂度高，边界情况多，拒绝 |
| C. 加保护上限（list() 最多 N 条） | 掩耳盗铃，不根本解决问题，拒绝 |
| **D. 基于流程实例的两阶段查询 — 选中** | 全程 Flowable 公开 API，零内部表耦合，语义匹配用户期望 |

### 不查 Flowable 内部表的决策

千问曾建议"自定义 MyBatis Mapper 直查 ACT_HI_TASKINST 和 ACT_HI_PROCINST"，经评估被拒绝。理由：

1. **耦合 Flowable 内部表结构** — ACT_HI_* 表结构和列名随版本变化，升级风险不可控
2. **双数据通路** — 其他地方用 Flowable API，已办走自己写的 SQL，维护心智负担大
3. **建议在 Flowable 表上加组合索引** — 升级 Flowable 时索引可能冲突

最终选择全程使用 Flowable 公开 API。

### 核心实现

```java
// Phase 1：流程维度分页（involvedUser 不指定 TYPE，匹配所有身份链接类型）
HistoricProcessInstanceQuery procQuery = historyService
    .createHistoricProcessInstanceQuery()
    .involvedUser(userId)       // 匹配任何类型的身份链接
    .or()
        .startedBy(userId)      // 或用户发起的
    .endOr();

long total = procQuery.count();
List<HistoricProcessInstance> processes = procQuery.listPage(offset, pageSize);

// Phase 2：批量取任务详情
List<String> procInstIds = processes.stream()
    .map(HistoricProcessInstance::getId)
    .collect(Collectors.toList());

List<HistoricTaskInstance> tasks = historyService
    .createHistoricTaskInstanceQuery()
    .processInstanceIdIn(procInstIds)
    .taskAssignee(userId)        // Phase 2 的 assignee 过滤是精确的
    .finished()
    .orderByHistoricTaskInstanceEndTime().desc()
    .list();

// 每个流程实例取 endTime 最新的那条任务
```

### involvedUser(userId) 源码证据

Flowable 6.8.0 `HistoricProcessInstance.xml` MyBatis 映射：

```xml
<!-- involvedUser(userId) 单参数版本：不指定 TYPE，匹配所有身份链接 -->
<if test="involvedUser != null">
  and (exists(select LINK.USER_ID_ from ${prefix}ACT_HI_IDENTITYLINK LINK 
    where USER_ID_ = #{involvedUser} 
    and LINK.PROC_INST_ID_ = ${queryTablePrefix}ID_))
</if>

<!-- involvedUser(userId, type) 双参数版本：指定 TYPE 过滤 -->
<if test="involvedUserIdentityLink != null">
  and EXISTS(select ID_ from ${prefix}ACT_HI_IDENTITYLINK I 
    where I.PROC_INST_ID_ = ${queryTablePrefix}ID_  
    and I.USER_ID_ = #{involvedUserIdentityLink.userId} 
    and I.TYPE_ = #{involvedUserIdentityLink.type})
</if>
```

**最终选择**：使用单参数 `involvedUser(userId)`，不指定 TYPE。

理由：
1. Flowable 的身份链接 TYPE 值取决于引擎配置（`Authenticator` 的实现），在不同环境中可能不同（`assignee` vs `participant`）。指定 TYPE 会引入环境依赖。
2. Phase 1 只是"候选流程集"（候选集可以偏宽），Phase 2 的 `taskAssignee(userId)` 是最精确的过滤。即使 Phase 1 多返回了假阳性流程实例，Phase 2 也会过滤掉（没有该用户完成的任务则 VOl 为空）。
3. 不指定 TYPE 也避免了 `or()` 块内调用 `involvedUser(userId, type)` 时可能出现的 MyBatis 参数传递问题。

**注意**：Phase 1 不使用 `.finished()` 过滤，因为用户可能在流程进行中就查询已办。时间范围过滤（`finishedAfter`/`finishedBefore`）隐式限制了已结束的流程。

### 语义变化：从"按节点去重"到"每流程 1 条"

- **旧语义**：按 `(processInstanceId + taskDefinitionKey)` 去重，同一流程实例不同节点可能展示多条
- **新语义**：按流程实例去重，每个流程实例最多展示 1 条记录
- **理由**：用户打开"已办"的心智模型是"我参与过哪些流程"，而非"我点过多少次同意按钮"。审批轨迹详情（时间轴）在流程详情页展示，不在列表中。

同步变更：
- `TaskQueryDTO.taskName` 字段保留（待办/已办共用 DTO，待办查询仍需使用），已办查询中不再应用此过滤条件

### enhancer 回调签名变更

`queryDoneTasks(userId, query, enhancer)` 的 enhancer 从 `Consumer<HistoricTaskInstanceQuery>` 变更为 `Consumer<HistoricProcessInstanceQuery>`，匹配 Phase 1 的查询维度。

## 后果

- 消除 OOM 风险，已办分页始终使用数据库分页
- 删除 `VOAssembler.dedupByNode()` 方法（不再需要内存去重）
- 删除 `TaskQueryDTO.taskName` 字段
- 全程 Flowable 公开 API，零内部表耦合，Flowable 版本升级不受影响
- `beginDate`/`endDate` 语义从"任务完成时间"变为"流程完成时间"，实际业务无感知差异
- API 签名变更（enhancer 类型 + taskName 移除）为 Breaking Change，项目早期无下游消费者
