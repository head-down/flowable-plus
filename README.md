# flowable-plus

> Flowable 工作流引擎增强工具包，提供简化 API 和中式工作流特性。

[![Java](https://img.shields.io/badge/java-8-blue?style=flat-square&logo=java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/spring--boot-2.7.18-brightgreen?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Flowable](https://img.shields.io/badge/flowable-6.8.0-red?style=flat-square)](https://www.flowable.com/)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](./LICENSE)
[![Status](https://img.shields.io/badge/status-beta-brightgreen?style=flat-square)](https://github.com/head-down/flowable-plus)

## 功能特性

### 流程操作

- **流程发起与撤销** — `startProcess` / `revokeProcess`，支持自动提交（`AutoApprovalRule` SPI）
- **审批推进** — `completeTask`（自动认领 + 添加审批意见）
- **驳回/退回** — `rejectTask`（退回上一审批节点）、`rejectTaskToInitiator`（退回发起人）
- **任意跳转** — `jumpToNode`（跳转至任意历史审批节点）
- **撤回** — `withdrawTask`（上一节点审批人收回已提交任务）
- **会签** — `counterSign`（多实例投票，支持 completionCondition 自定义规则）
- **加签/减签** — `addCounterSigner` / `removeCounterSigner`（会签中动态调整审批人）
- **委派与转办** — `delegateTask`（临时委派，可收回） / `transferTask`（永久转移）
- **认领** — `claimTask`（手动认领候选任务）

### 查询能力

- **待办/已办列表** — `queryTodoTasks` / `queryDoneTasks`，支持分页、关键字搜索、自定义过滤回调
- **批量补充流程信息** — `batchQueryProcessSummaries`（解决列表页 N+1 查询）
- **审批轨迹** — `getApprovalTrace`（流程节点审批时间线，含审批人/时间/意见/耗时）
- **审批历史** — `getApprovalHistory`（完整审批记录，含操作类型推断与会签子记录）

### 可视化

- **流程图** — `getProcessDiagram`（生成含节点状态高亮的 SVG 流程图）
- **节点预览** — `getNextNodeApproversByProcessKey`（发起前预览审批链路）/ `getNextTaskApprovers`（审批中查询下一节点审批人）

### 事件监听

实现 `ProcessEventListener` SPI 即可零侵入监听流程生命周期事件（发起、审批、驳回、撤回、撤销、委派、转办、结束），异步执行，异常隔离。

## 模块结构

```
flowable-plus (父 POM, packaging=pom)
├── flowable-plus-core                  核心模块（纯 Java，无框架依赖）
├── flowable-plus-spring-boot-starter   Spring Boot 自动配置粘合层
└── flowable-plus-extension             可选扩展（高级审批模式等）
```

| 模块 | 职责 |
|------|------|
| `flowable-plus-core` | 封装 Flowable 核心服务，定义所有 API 接口、SPI 扩展点、VO 和事件对象。可在任意 Java 8+ 应用中使用 |
| `flowable-plus-spring-boot-starter` | `FlowablePlusAutoConfiguration` 自动注册 Bean，配置前缀 `flowable.plus.*`。条件激活：`ProcessEngine` 存在 + `flowable.plus.enabled=true`（默认） |
| `flowable-plus-extension` | 可选高级功能，仅依赖 core 模块 |

## API 接口一览

`FlowablePlus` 是统一入口门面，实现以下六个接口：

| 接口 | 方法数 | 职责 |
|------|--------|------|
| `ProcessLifecycleOperations` | 2 | `startProcess`, `revokeProcess` |
| `TaskExecutionOperations` | 8 | `completeTask`, `claimTask`, `rejectTask`, `rejectTaskToInitiator`, `withdrawTask`, `transferTask`, `jumpToNode`, `getJumpableNodes` |
| `CounterSignOperations` | 5 | `counterSign`, `addCounterSigner`, `removeCounterSigner`, `delegateTask`, `resolveDelegate` |
| `QueryOperations` | 10 | `queryTodoTasks`×2, `queryDoneTasks`×2, 节点预览×5, `batchQueryProcessSummaries`, `getApprovalTrace` |
| `HistoryOperations` | 1 | `getApprovalHistory` |
| `DiagramOperations` | 1 | `getProcessDiagram` |

## SPI 扩展点

| SPI | 用途 |
|-----|------|
| `ProcessEventListener` | 流程生命周期事件监听，异步执行，异常隔离 |
| `AutoApprovalRule` | 自动审批规则，`startProcess` 后自动完成匹配的首任务 |
| `IdentityResolver` | 身份解析，将用户/组 ID 解析为显示名称 |
| `UserContext` | 用户上下文，获取当前登录用户 |
| `ApproverResolver` | 审批人解析，获取指定节点的审批人列表 |
| `GroupResolver` | 候选组解析，将候选组 ID 展开为成员列表 |
| `CounterSignCallback` | 会签回调，监听会签发起/投票/完成事件 |
| `ExecutionTreeHelper` | 执行树辅助，隔离 Flowable 内部依赖 |
| `TaskQueryEnhancer` | 待办查询自定义过滤条件（Consumer 回调模式） |

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
    <version>1.0-SNAPSHOT</version>
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
| ADR-0011 | 事件监听器采用 DispatchableEvent 自分发 |
| ADR-0012 | 已办查询切换至流程实例维度 |

详见 `docs/adr/` 目录。

## 许可证

MIT License
