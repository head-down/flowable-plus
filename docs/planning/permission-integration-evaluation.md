# flowable-plus 权限整合评估

> 参考 jw-zhyg-api 数据权限整合方案，审视 flowable-plus 的权限设计现状、缺失项与后续路线。

---

## 一、现状

| 层级 | 当前状态 | 详细说明 |
|------|:---:|------|
| 流程操作权限 | ✅ 已实现 | `TaskValidation` 集中校验 assignee/发起人/上一节点审批人身份。覆盖：同意、驳回、撤回、撤销、会签投票、加签、减签 |
| 数据行级权限 | ❌ 未涉及 | 无 DataScope、无 RBAC、无部门隔离、无自建数据过滤 |
| 流程实例可见性 | ❌ 未涉及 | `QueryOperations` 返回的待办/已办列表不做任何过滤，依赖 Flowable 原生查询（按 assignee/candidate 过滤任务，history 查已参与实例） |
| 权限扩展点 | ⚠️ 仅身份获取 | `UserContext` SPI 仅提供 `getCurrentUserId()`，无权限判定回调节点 |

**设计上属于事实缺失，而非显式决策为"范围外"。** 所有 Phase 规划（2-5 期）和 6 个 ADR 均未涉及数据权限。

---

## 二、与参考文档的差异性分析

参考文档 **jw-zhyg-api** 的权限场景是一个**单体OA应用**，业务单据（用车、印章、档案）与流程实例（Activiti）同库紧耦合。权限模型是"数据权限为主、流程参与人豁免为辅"。

### flowable-plus 的定位差异

| 维度 | jw-zhyg-api (应用) | flowable-plus (框架/SDK) |
|------|-------------------|--------------------------|
| 角色 | 应用层，直接实现所有权限逻辑 | 工具包层，提供权限集成扩展点 |
| 数据权限 | 自己实现 @DataScope + Mapper XML | **不持有业务表**，无法替应用决定过滤条件 |
| 流程查询 | 自定义 SQL + DataScope 注入 | 委托 Flowable 原生 API 查询 |
| 用户体系 | RuoYi 的 sys_user/sys_role/sys_dept | 无，通过 `UserContext` SPI 适配 |
| ORM | MyBatis-Plus + Mapper XML | 无 ORM（仅依赖 Flowable 引擎 API） |

**核心结论**：flowable-plus 不能照搬参考文档的"DataScope SQL 注入"方案，因为它不持有业务数据表。需要的是**流程查询权限过滤**的扩展机制。

---

## 三、当前权限覆盖范围

### 3.1 已覆盖 — 流程操作权限

| 操作 | 权限规则 | 校验位置 |
|------|---------|---------|
| 同意 | 自动 claim 后操作，无显式校验 | `TaskWorkflow:62` |
| 驳回 | 必须是当前任务 assignee | `TaskWorkflow:90` |
| 驳回至发起人 | 必须是当前任务 assignee | `TaskWorkflow:110` |
| 撤回 | 必须是上一节点审批人（不能是自己） | `TaskWorkflow:124-151` |
| 撤销 | 必须是流程发起人 | `TaskWorkflow:166` |
| 会签投票 | 必���是子任务 assignee | `CounterSignWorkflow:53` |
| 加签 | 上一节点审批人或发起人 | `CounterSignWorkflow:92` |
| 减签 | 上一节点审批人或发起人 | `CounterSignWorkflow:144` |

**质量评价**：集中校验、异常语义清晰、测试覆盖良好（见 `TaskWorkflowTest` 和 `CounterSignWorkflowTest` 中的 8 个权限异常测试）。

### 3.2 未覆盖 — 缺失的能��

| 缺失项 | 影响 | 参考文档怎么做的 |
|--------|------|----------------|
| **待办/已办查询数据过滤** | 任何用户调用 `getTodoList()` / `getDoneList()` 都返回 Flowable 原生的全量结果。如果接入方没有部门/角色体系，可能暴露非本部门流程实例 | 列表 SQL 注入 `WHERE dept_id = ?` |
| **流程实例详情权限** | `getProcessInstance()` 和 `getProcessDiagram()` 不做权限校验，知道 instanceId 就能查到 | `queryById` 双层权限（参与人豁免 + 数据权限降级） |
| **租户隔离** | 无 `tenantId` 概念。多租户场景下无法隔离流程数据 | 独立 `TenantLineInnerInterceptor` 维度 |
| **委托/转办权限** | 已规划但未实现（Phase 4），权限规则待定义 | 无对应参考 |
| **权限扩展点** | `UserContext` 只有 `getCurrentUserId()`，无权限判定回调 | 无（参考文档同样缺失扩展点设计） |

---

## 四、推荐方案

基于 flowable-plus 作为**框架/SDK** 的定位，采用 **回调扩展模式**，而非内建权限实现。

### 4.1 核心思路

```
应用层：实现 QueryPermissionCallback SPI
    │
    ▼
框架层：QueryOperations 查询 → 回调过滤 → 返回权限可见结果
    │
    ▼
Flowable 引擎：原生查询（按 assignee/history 查全量）
```

**为什么是回调而非 SQL 注入**：
- flowable-plus 没有自己的 Mapper XML，SQL 注入无处可注
- Flowable 的查询 API 返回的是引擎内部的 POJO / VO，过滤只能在应用层做
- 回调模式让应用自主决定权限逻辑（部门、角色、自定义等），框架不绑定具体模型

### 4.2 SPI 设计

```java
/**
 * 流程查询权限回调。
 * 接入方实现此接口，对框架的查询结果做二次过滤。
 * <p>
 * 每个方法返回 {@code true} 表示该流程实例对当前用户可见，{@code false} 表示不可见（应从结果集中移除）。
 */
public interface QueryPermissionCallback {

    /**
     * 判断当前用户是否有权查看指定的流程实例。
     *
     * @param processInstance 流程实例（含 businessKey、startUserId 等）
     * @param currentUserId   当前用户 ID
     * @return true 可见，false 不可见
     */
    default boolean canViewProcessInstance(ProcessInstance processInstance, String currentUserId) {
        return true; // 默认全量可见，保持向后兼容
    }

    /**
     * 判断当前用户是否有权查看指定的历史流程实例。
     */
    default boolean canViewHistoricProcessInstance(HistoricProcessInstance historicProcessInstance,
                                                    String currentUserId) {
        return true;
    }
}
```

### 4.3 调用点

| API | 当前行为 | 集成后行为 |
|-----|---------|-----------|
| `getTodoList()` | Flowable TaskQuery 结果直接返回 | 查询 → 按 processInstanceId 回调 `canViewProcessInstance` → 过滤 |
| `getDoneList()` | Flowable HistoricTaskInstanceQuery 结果直接返回 | 查询 → 按 processInstanceId 回调 `canViewHistoricProcessInstance` → 过滤 |
| `getProcessInstance()` | 直接通过 RuntimeService 查询 | 查询 → 回调校验 → 无权限抛 PermissionDeniedException |
| `getProcessDiagram()` | 直接通过 RepositoryService 查询 | 查询 → 回调校验 → 无权限抛 PermissionDeniedException |
| `getApprovalHistory()` (Phase 5) | 直接通过 HistoryService 查询 | 查询 → 回调校验 → 无权限抛 PermissionDeniedException |

### 4.4 自动配置

```java
@Bean
@ConditionalOnMissingBean
public QueryPermissionCallback queryPermissionCallback() {
    return new QueryPermissionCallback() {}; // 默认全量可见
}
```

接入方只需注入一个 `QueryPermissionCallback` Bean 即可覆盖默认行为。

---

## 五、为什么不选其他方案

| 方案 | 不适合原因 |
|------|-----------|
| **AOP + SQL 注入**（参考文档当前方案） | flowable-plus 没有 Mapper XML，不直接写 SQL，也无法注入 Flowable 引擎的查询 |
| **MyBatis-Plus DataPermissionInterceptor** | flowable-plus 没有 MyBatis-Plus 依赖，也不应引入（核心模块是纯 Java） |
| **Spring Security ACL** | 过重。flowable-plus 是框架，不应绑定 ACL 模型。每条流程实例生成 ACL 条目也不经济 |
| **Flowable Authorization** | Flowable 内建授权是引擎级配置，对使用方侵入大，且灵活性不如回调 |
| **OPA/Casbin** | 引入额外服务，运维成本高，与框架定位不匹配 |

---

## 六、后期需完成事项

### P0 — 流程查询权限回调（Phase 2 补充）

| # | 事项 | 说明 |
|---|------|------|
| 1 | 设计 `QueryPermissionCallback` SPI 接口 | 定义 `canViewProcessInstance` / `canViewHistoricProcessInstance` |
| 2 | `QueryOperations` 集成回调过滤 | 待办/已办查询结果按回调过滤 |
| 3 | 单实例查询加权限校验 | `getProcessInstance()` / `getProcessDiagram()` 加回调校验 |
| 4 | 默认实现（全量可见）+ 自动配置 | 保持向后兼容 |
| 5 | 单元测试 | 覆盖回调返回 false 时的过滤行为 |

### P1 — 租户隔离支持

| # | 事项 | 说明 |
|---|------|------|
| 6 | `QueryPermissionCallback` 增加 `getTenantId()` 默认方法 | 返回 null 表示不启用租户隔离 |
| 7 | `FlowablePlus` 查询方法传入 tenantId | 调用 Flowable 的 `processInstanceQuery.processInstanceTenantId(tenantId)` |

### P1 — 委托/转办权限定义

| # | 事项 | 说明 |
|---|------|------|
| 8 | 定义委托 (delegate) 和转办 (transfer) 的权限规则 | 谁能委托？委托给谁？被委托人是否有子委托权？ |
| 9 | 权限规则 ADR | 记录决策，与 Phase 4（委托与任务委派）一起实现 |

### P2 — 用户上下文扩展

| # | 事项 | 说明 |
|---|------|------|
| 10 | `UserContext` 扩展 `getRoles()` / `getDeptId()` | 让接入方的权限回调能获取更多上下文信息 |
| 11 | 评估是否需要 `PermissionEvaluator` SPI | 通用权限判定接口，用于撤操作等场景（参考参考文档的 `DataScopeChecker`） |

### P3 — 流程实例可见性审计

| # | 事项 | 说明 |
|---|------|------|
| 12 | 评估是否需要"我参与的流程"过滤 | 当前 `getDoneList()` 已按 assignee 过滤历史任务，但如果用户通过 candidate group 参与而非直接 assignee，可能遗漏 |

---

## 七、决策记录

- 2026-07-10：评估确认项目未规划数据权限。决定采用**回调扩展模式**而非内建实现，保持框架轻量。权限实现职责归属于接入方应用。
