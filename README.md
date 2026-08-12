# flowable-plus

> Flowable 工作流引擎增强工具包，提供简化 API 和中式工作流特性。

[![Java](https://img.shields.io/badge/java-8-blue?style=flat-square&logo=java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/spring--boot-2.7.18-brightgreen?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Flowable](https://img.shields.io/badge/flowable-6.8.0-red?style=flat-square)](https://www.flowable.com/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](./LICENSE)
[![Status](https://img.shields.io/badge/status-stable-brightgreen?style=flat-square)](https://github.com/head-down/flowable-plus)

## 状态

**Stable — v1.0.0 GA 已发布。**

CI 矩阵覆盖 H2 / MySQL 8.0 / PostgreSQL 14 三种数据库，全量测试通过。详见 [ADR-0014](docs/adr/0014-multi-database-ci-gate.md)。

## 功能特性

### 流程操作

- **流程发起与撤销** — `startProcess` / `invalidateProcess`，支持自动提交（`AutoApprovalRule` SPI）
- **审批推进** — `completeTask`（自动认领 + 添加审批意见）
- **驳回/退回** — `rejectTask`（退回上一审批节点）、`rejectTaskToInitiator`（退回发起人）
- **任意跳转** — `jumpToNode`（跳转至任意历史审批节点）
- **撤回** — `withdrawTask`（上一节点审批人收回已提交任务）
- **会签** — `counterSign`（多实例投票，支持 completionCondition 自定义规则）
- **加签/减签** — `addCounterSigner` / `removeCounterSigner`（会签中动态调整审批人；**减签业务层不提供**，见 jw-zhyg-api 仓库 ADR-0004）
- **委派与转办** — `delegateTask`（临时委派，可收回） / `transferTask`（永久转移）
- **认领** — `claimTask`（手动认领候选任务）

### 查询能力

- **待办/已办列表** — `queryTodoTasks` / `queryDoneTasks`，支持分页、关键字搜索、自定义过滤回调
- **批量补充流程信息** — `batchQueryProcessSummaries`（解决列表页 N+1 查询）
- **审批历史** — `getApprovalHistory`（完整审批记录时间线，含操作类型推断与会签子记录）

### 可视化

- **流程图** — `getProcessDiagramXml`（获取 BPMN XML）+ `getProcessDiagramStates`（节点状态 / 已完成连线 / 活跃任务），供前端 bpmn.js 渲染
- **节点预览** — `getNextNodeApprovers`（发起前预览审批链路）/ `getNextTaskNodes`、`getNextTaskApprovers`（审批中查询下游节点与审批人），支持全遍历 / 紧邻遍历（`TraversalMode`）

### 事件监听

实现 `ProcessEventListener` SPI 即可零侵入监听流程生命周期事件（发起、审批、驳回、撤回、撤销、委派、转办、结束），异步执行，异常隔离。

## 模块结构

```
flowable-plus (父 POM, packaging=pom)
├── flowable-plus-core                  核心模块（API 封装层，不启动 Spring DI 容器，可在任意 Java 8+ 应用中使用）
├── flowable-plus-spring-boot-starter   Spring Boot 自动配置粘合层
└── flowable-plus-extension             储备位模块（reserved slot，当前无功能内容）
```

| 模块 | 职责 |
|------|------|
| `flowable-plus-core` | 封装 Flowable 核心服务，定义所有 API 接口、SPI 扩展点、VO 和事件对象。可在任意 Java 8+ 应用中使用 |
| `flowable-plus-spring-boot-starter` | `FlowablePlusAutoConfiguration` 自动注册 Bean，配置前缀 `flowable.plus.*`。条件激活：`ProcessEngine` 存在 + `flowable.plus.enabled=true`（默认） |
| `flowable-plus-extension` | 储备位模块，当前无功能内容，等待「依赖隔离」或「真正可选的领域能力」入住（边界见 ADR-0029） |

## API 接口一览

`FlowablePlus` 是统一入口门面，实现以下六个接口：

| 接口 | 方法数 | 职责 |
|------|--------|------|
| `ProcessLifecycleOperations` | 2 | `startProcess`, `invalidateProcess` |
| `TaskExecutionOperations` | 10 | `completeTask`, `claimTask`, `rejectTask`×2, `rejectTaskToInitiator`, `withdrawTask`×2, `transferTask`, `jumpToNode`, `getJumpableNodes` |
| `CounterSignOperations` | 5 | `counterSign`, `addCounterSigner`, `removeCounterSigner`, `delegateTask`, `resolveDelegate` |
| `QueryOperations` | 13 | `queryTodoTasks`×2, `queryDoneTasks`×2, `queryDoneTasksPrecise`, `getNextNodeApprovers`×2, `getNextTaskNodes`, `getNextTaskApprovers`, `getProcessSummary`, `batchQueryProcessSummaries`, `getApprovalPersonnel`, `getBusinessKeyByProcessInstanceId` |
| `HistoryOperations` | 1 | `getApprovalHistory` |
| `DiagramOperations` | 2 | `getProcessDiagramXml`, `getProcessDiagramStates` |

## v1.0.0 API 迁移

v1.0.0 节点预览 API 已收窄为三入口（见 [ADR-0031](docs/adr/0031-node-preview-api-consolidation.md)）。升级依赖后请按以下映射适配：

| 旧 API（已删除） | 新 API |
|------------------|--------|
| `getNextNodeApproversByProcessKey(key)` | `getNextNodeApprovers(key, TraversalMode.FULL)` |
| `getNextNodeApproversByProcessKey(key, vars)` | `getNextNodeApprovers(key, TraversalMode.FULL, vars)` |
| `getAdjacentNodeApproversByProcessKey(key, vars)` | `getNextNodeApprovers(key, TraversalMode.ADJACENT, vars)` |
| `getNextTaskApprovers(taskId)` | `getNextTaskApprovers(taskId, TraversalMode.FULL)` |
| `getNextTaskApprovers(taskId, targetNodeId)` | `getNextTaskApprovers(taskId, TraversalMode.FULL)` 后按 `nodeId` 过滤：<br/>`result.stream().filter(v -> targetNodeId.equals(v.getNodeId())).collect(Collectors.toList())`（`targetNodeId` 为 null 时等价于「不过滤」，即 `getNextTaskApprovers(taskId, TraversalMode.FULL)`） |
| `getNextTaskNodes(processInstanceId, taskId)` | `getNextTaskNodes(taskId, TraversalMode.FULL)`（processInstanceId 已由 taskId 自动推导，不再传参） |
| `getAdjacentTaskNodes(processInstanceId, taskId)` | `getNextTaskNodes(taskId, TraversalMode.ADJACENT)` |
| `getAdjacentTaskApprovers(taskId)` | `getNextTaskApprovers(taskId, TraversalMode.ADJACENT)` |

`TraversalMode` 取值：`FULL`（全遍历，完整审批链路）/ `ADJACENT`（紧邻遍历，仅第一个审批层级）。

## SPI 扩展点

| SPI | 用途 |
|-----|------|
| `ProcessEventListener` | 流程生命周期事件监听，异步执行，异常隔离 |
| `AutoApprovalRule` | 自动审批规则，`startProcess` 后自动完成匹配的首任务 |
| `IdentityResolver` | 身份解析，将用户/组 ID 解析为显示名称 |
| `UserContext` | 用户上下文，获取当前登录用户 |
| `ApproverResolver` | 审批人解析，获取指定节点的审批人列表；支持 `ApproverContext` 运行上下文感知（流程变量 / 当前用户 / 任务锚点），可完成表达式求值与动态审批人计算 |
| `GroupResolver` | 候选组解析，将候选组 ID 展开为成员列表 |
| `CounterSignCallback` | 会签回调，监听会签发起/投票/完成事件 |
| `ExecutionTreeHelper` | 执行树辅助，隔离 Flowable 内部依赖 |

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/head-down/flowable-plus.git
cd flowable-plus

# 完整构建
mvn clean package

# 安装到本地仓库
mvn clean install -DskipTests

# 运行测试
mvn clean test
```

### Spring Boot 集成

```xml
<dependency>
    <groupId>io.github.flowable.plus</groupId>
    <artifactId>flowable-plus-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
@Autowired
private FlowablePlus flowablePlus;

// 发起流程
PlusProcessInstance instance = flowablePlus.startProcess("leave", "BIZ-001", variables);

// 审批
flowablePlus.completeTask("task-123", variables, "同意请假");

// 驳回至上一审批节点
flowablePlus.rejectTask("task-456", "材料不齐全");

// 查询待办
PageResult<TodoTaskVO> todos = flowablePlus.queryTodoTasks("user1", new TaskQueryDTO());

// 审批历史
List<ApprovalRecordVO> history = flowablePlus.getApprovalHistory("proc-001");
```

## 依赖

| 依赖 | 版本 | 作用范围 |
|------|------|----------|
| Java | 1.8 | 编译目标 |
| Spring Boot | 2.7.18 | 通过 BOM 管理 |
| Flowable | 6.8.0 | 通过 BOM 管理 |
| Lombok | 1.18.30 | 所有模块 |
| Hutool | 5.8.28 | core、extension |

## 架构决策记录 (ADR)

| 编号 | 标题 |
|------|------|
| ADR-0001 | 使用自定义跳转逻辑实现中式审批流转 |
| ADR-0002 | 并行网关汇合节点驳回：直接拒绝 |
| ADR-0003 | 会签采用 Flowable 原生多实例 |
| ADR-0004 | 会签驳回计数否决模式 |
| ADR-0005 | BPMN 模型加载使用独立缓存模块 |
| ADR-0006 | 查询接口支持自定义过滤回调 |
| ADR-0007 | 流程查询权限采用回调扩展模式 |
| ADR-0008 | 自动提交采用 AutoApprovalRule SPI，异常快速失败 |
| ADR-0009 | 审批历史 Comment→Action 推断采用三级策略 |
| ADR-0010 | FlowablePlus 门面保持纯聚合角色 |
| ADR-0011 | DispatchableEvent 自分发替代 instanceof 链 |
| ADR-0012 | 已办查询基于流程实例维度的两阶段查询 |
| ADR-0013 | 已办查询精确分页引入 Native SQL |
| ADR-0014 | 多数据库 CI 矩阵作为 v1.0.0 GA 硬性准入条件 |
| ADR-0015 | 公开 API 准入标准：禁止裸透传 Flowable 原生方法 |
| ADR-0016 | 正向 EndEvent 终止检测作为独立 NodeFinder 方法 |
| ADR-0017 | 乐观锁冲突不采用通用 AOP 重试，仅在具体方法内精准重试 |
| ADR-0018 | 紧邻遍历使用 stopAtUserTask 参数复用现有遍历引擎 |
| ADR-0019 | 会签多轮次追踪采用 Task 局部变量 csRoundIndex |
| ADR-0020 | 审批历史会签轮次边界统一使用 csRoundIndex，不再依赖 miBody |
| ADR-0021 | 会签节点回退采用运行时判断 + 原地重建策略 |
| ADR-0022 | 会签建模双模式规范与 AssigneeResolver 扩展点 |
| ADR-0023 | 模式A加签/减签权限放宽为"会签发起人 OR 当前节点活跃审批人" |
| ADR-0024 | 加签查重 fast fail 与查重口径 |
| ADR-0025 | CommentType 业务/操作分组解耦审批意见提取与操作注释识别 |
| ADR-0026 | 会签节点 assignee 必须引用元素变量（建模约束体系） |
| ADR-0027 | 操作注释多值化（operationComments 列表字段） |
| ADR-0028 | 审批轨迹收敛为单一入口（删除 getApprovalTrace） |
| ADR-0029 | flowable-plus-extension 定位为储备位（reserved slot） |
| ADR-0030 | 删除死接口 TaskQueryEnhancer，回调收敛为 Consumer 单一形态 |
| ADR-0031 | 节点预览 API 收窄为三入口（8 方法 → 3 入口） |
| ADR-0032 | 范围外功能判据：脱离业务数据即失去价值的功能归业务层 |
| ADR-0033 | ApproverResolver 支持运行上下文感知（ApproverContext） |

详见 `docs/adr/` 目录。

## 参与贡献

欢迎提交 issue、PR 和讨论。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)（构建/测试/提交规范）与 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)（行为准则）。

## 许可证

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
