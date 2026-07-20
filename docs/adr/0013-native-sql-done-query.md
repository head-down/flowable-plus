# 已办查询精确分页引入 Native SQL

## 背景

ADR-0012 将已办查询从"任务维度"切换到"流程实例维度"，采用两阶段查询：

- **Phase 1**: `HistoricProcessInstanceQuery.involvedUser(userId)` → 候选流程集 + 分页
- **Phase 2**: `HistoricTaskInstanceQuery.taskAssignee(userId).finished()` → 精确任务过滤

Phase 1 使用 `involvedUser`（不指定 TYPE，匹配所有身份链接类型），Phase 2 使用 `taskAssignee(userId).finished()`（仅已完成 assignee 任务）。两者口径不同：

- Phase 1：匹配 `ACT_HI_IDENTITYLINK` 表中该用户的**所有身份链接记录**（assignee、participant、candidate 等）
- Phase 2：仅匹配 `ACT_HI_TASKINST` 表中 `ASSIGNEE_ = userId AND END_TIME_ IS NOT NULL`

**结果**：Phase 1 的 count 可能包含"有身份链接但没有已完成 assignee 任务"的流程实例，导致 `PageResult.total` 虚高。

## 决策

**新增 `queryDoneTasksPrecise` 方法，Phase 1 使用 Flowable `NativeHistoricProcessInstanceQuery` 写精确 SQL，直查只有已完成任务的流程实例。**

### 方案选择

| 方案 | 判据 |
|---|---|
| A. 保留现状，前端接受虚高 total | 不解决根本问题，paginator 不可用 |
| B. Phase 2 全量加载后内存去重计数 | 回到 ADR-0012 已解决的 OOM 问题 |
| C. 修改 Phase 1 为 `taskAssignee(userId)` 维度 | Flowable API 层次不支持"流程实例维度 + task 条件"的查询 |
| **D. Native SQL — 选中** | Phase 1 精确，total 准确，风险可控 |

### 为什么选 Native SQL 而不是自定义 MyBatis Mapper

| 维度 | Native SQL | 自定义 MyBatis Mapper |
|---|---|---|
| 代码路径 | Flowable 内置 → 最终走的还是 Flowable 的 MyBatis 映射 | 自己写一套 MyBatis 映射 |
| 耦合 | `sql()` 传入 SQL 字符串，依赖 ACT_HI_PROCINST / ACT_HI_TASKINST 列名 | 同样依赖表名和列名 + 额外的 MyBatis SQLSession 配置 |
| 维护面 | 一处 Native SQL 字符串 | 多处 XML 文件 + Java Mapper 接口 + MyBatis 集成配置 |
| 一致性 | `HistoryService.listPage` 走同一条查询链 | 独立通路，升级时需要额外验证对比 |

Native SQL 虽然也耦合了内部表结构，但 `ACT_HI_PROCINST` 和 `ACT_HI_TASKINST` 在 Flowable 6.x 中结构极其稳定，且 API 调用链（`sql()` → `count()`/`listPage()`）与现有公开 API 一致，集成成本最低。

### 核心实现

```java
// Phase 1: Native SQL 精确查询流程实例
StringBuilder sql = new StringBuilder(512);
sql.append("SELECT RES.* FROM ACT_HI_PROCINST RES WHERE EXISTS (")
   .append("SELECT 1 FROM ACT_HI_TASKINST T WHERE ")
   .append("T.PROC_INST_ID_ = RES.ID_ AND T.ASSIGNEE_ = '")
   .append(escapeSql(userId))
   .append("' AND T.END_TIME_ IS NOT NULL)");

// 动态过滤条件
if (query.getProcessDefinitionKey() != null) {
    sql.append(" AND RES.PROC_DEF_ID_ LIKE '").append(escapeSql(key)).append(":%'");
}
if (query.getKeyword() != null) {
    sql.append(" AND RES.BUSINESS_KEY_ LIKE '%").append(escapeLike(keyword)).append("%'");
}
if (query.getBeginDate() != null) {
    sql.append(" AND RES.END_TIME_ >= '").append(formatDate(beginDate)).append("'");
}
if (query.getEndDate() != null) {
    sql.append(" AND RES.END_TIME_ <= '").append(formatDate(endDate)).append("'");
}

sql.append(" ORDER BY RES.END_TIME_ DESC");

NativeHistoricProcessInstanceQuery nativeQuery = historyService
    .createNativeHistoricProcessInstanceQuery()
    .sql(sql.toString());

long total = nativeQuery.count();  // 精确
List<HistoricProcessInstance> processes = nativeQuery.listPage(offset, pageSize);

// Phase 2: 复用现有批量取任务 + 去重逻辑
```

### 参数绑定方式

Flowable 的 Native Query 模板 `${sql}` 在 MyBatis 层做文本替换，SQL 内部不能再使用 `#{param}`（会导致 MyBatis 二次解析失败）。`${param}` 同样是文本替换，存在注入风险。

**最终选择**：Java 层完成参数值转义后直接拼入 SQL 字符串，通过 `sql()` 传入完整 SQL。

- `escapeSql()`: 替换 `'` → `''`（SQL 标准单引号转义）
- `escapeLike()`: 额外转义 `%` → `\%`、`_` → `\_`（防止通配符注入）
- 日期参数: 格式化为 `yyyy-MM-dd HH:mm:ss` 字符串

### 依赖的 Flowable 内部表

| 表名 | 别名 | 用途 |
|---|---|---|
| `ACT_HI_PROCINST` | RES | 历史流程实例，Phase 1 分页源 |
| `ACT_HI_TASKINST` | T | 历史任务，EXISTS 子查询中的已办条件 |

这两张表结构在 Flowable 6.x 中极其稳定。Flowable 6.8.0 → 7.x 升级时，需验证列名和索引未变（低风险）。

### API 设计

- 新方法 `queryDoneTasksPrecise` **不提供** enhancer 参数（native SQL 无法链式扩展）
- 需要自定义过滤的场景继续使用原 `queryDoneTasks` + enhancer
- 需要精确分页的场景使用新 `queryDoneTasksPrecise`

### 不添加租户隔离

本项目**没有多租户设计**：
- `UserContext` SPI 仅 `getCurrentUserId()`，无 `getTenantId()` 方法
- `TaskQueryDTO` 无 `tenantId` 字段
- 代码库中 `TENANT_ID_` 引用为 0
- 当前 `queryDoneTasks` 原有实现同样不做租户隔离，保持一致

## 后果

- 新增 `QueryOperations.queryDoneTasksPrecise(userId, query)` 接口方法
- 新增 `TaskQueryModule.queryDoneTasksPrecise()` 实现，Phase 1 使用 Native SQL
- 新增 `FlowablePlus.queryDoneTasksPrecise()` 委托方法
- `PageResult.total` 在 `queryDoneTasksPrecise` 中为精确值
- Native SQL 依赖 `ACT_HI_PROCINST` 和 `ACT_HI_TASKINST` 表结构，Flowable 大版本升级时需验证
- 无 enhancer 参数，这是一个有意的限制而非遗漏
- 无租户过滤，与项目当前架构一致
