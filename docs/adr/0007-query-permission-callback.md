# ADR-0007: 流程查询权限采用回调扩展模式

*2026-07-10*

## 背景

根据项目权限评估（`docs/planning/permission-integration-evaluation.md`），flowable-plus 当前仅覆盖**流程操作权限**（校验用户是否为 assignee/发起人/上一节点审批人），完全未涉及**流程查询权限**（哪些流程实例对当前用户可见）。

作为框架/SDK，flowable-plus 不持有业务数据表，无法在 SQL 层注入 WHERE 条件。需要一种轻量机制让接入方自行裁定查询权限。

## 决策

采用 **回调扩展模式**：提供 `QueryPermissionCallback` SPI 接口，接入方实现后注入 Spring 容器，框架在所有查询路径中回调过滤。

**不在框架内建任何权限模型**（RBAC、ABAC、ACL、DataScope），权限判定逻辑完全交由接入方实现。

## 理由

1. **框架定位**：flowable-plus 是 Flowable 包装层，不管理业务数据。强制内建权限模型会限制适用范围。
2. **最小侵入**：一个 `FunctionalInterface` 足以覆盖核心场景，无需引入新依赖。
3. **向后兼容**：默认实现返回全量可见，已有接入方无感知升级。
4. **灵活**：接入方可自由选择若依的 DataScope、Spring Security 鉴权、自定义规则等任意方案。

## 替代方案

| 方案 | 拒绝原因 |
|------|---------|
| SQL 注入（参考 jw-zhyg-api） | 框架不持有 Mapper XML，无法注入 |
| MyBatis-Plus DataPermissionInterceptor | 引入不必要 ORM 依赖 |
| Spring Security ACL | 过重，每条流程实例生成 ACL 条目不经济 |
| Flowable 内建 Authorization | 引擎级配置侵入大，灵活性不足 |

## 后果

- 接入方需要自行实现 `QueryPermissionCallback`（或接受默认全量可见行为）
- 框架保持零外部权限依赖
