# ADR-0007: 流程查询权限不内建，推荐接入方自行实现

*2026-07-10，2026-07-10 修订*

## 背景

根据项目权限评估（`docs/planning/permission-integration-evaluation.md`），flowable-plus 当前仅覆盖**流程操作权限**（校验用户是否为 assignee/发起人/上一节点审批人），完全未涉及**流程查询权限**（哪些流程实例对当前用户可见）。

## 决策

**不实现查询权限回调。** 推荐接入方自行实现待办/已办列表查询 + DataScope 注入。

## 演进过程

### 初版方案：回调扩展模式

最初设计了一个 `QueryPermissionCallback` SPI 接口，在待办/已办查询结果上做内存侧权限过滤。

**拒绝原因**：

Flowable 的 `TaskQuery` 是 DB 层分页后返回结果。内存侧过滤后 `PageResult.total` 不准确（为引擎原始计数）。如需精确分页，只能退回 SQL 层注入。框架不持有 Flowable 引擎内部的 Mapper XML，无从注入。

### 调研确认

| 参考 | 结论 |
|------|------|
| Spring Security `@PostFilter` | 接受 total 不精确，无补偿方案 |
| Keycloak #27512 | 相同问题，2024-03 提出至今 open |
| Flowable 官方 | 不提供业务级 data scope 过滤机制 |
| 若依 `@DataScope` | 应用层可行（控制自己的 Mapper XML），框架层不可行 |

### 最终方案

待办/已办查询 API **保留**，在 `QueryOperations` 的 Javadoc 中明确说明定位：适用于首页摘要、我的待办小卡片等无精确分页要求的场景。接入方如需精确分页 + 业务级数据权限过滤，推荐自行实现 MyBatis-Plus Mapper XML 直查 Flowable 内部表 + DataScope 注入，配合 `batchQueryProcessSummaries()` 批量补充流程信息。

## 替代方案

| 方案 | 拒绝原因 |
|------|---------|
| 回调扩展模式（初版） | 内存过滤导致 PageResult.total 不精确 |
| SQL 注入（参考 jw-zhyg-api） | 框架不持有 Mapper XML，无法注入 |
| MyBatis-Plus DataPermissionInterceptor | 引入不必要 ORM 依赖 |
| Spring Security ACL | 过重，每条流程实例生成 ACL 条目不经济 |
| Flowable 内建 Authorization | 引擎级配置侵入大，灵活性不足 |

## 后果

- 不提供查询权限过滤能力
- 待办/已办 API 保留，Javadoc 标注定位
- 接入方按需自行实现
