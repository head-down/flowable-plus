# Phase 6 规划

## 范围

**流程可视化与历史追溯**，聚焦以下子方向：

### 本期范围

| 子方向 | 优先级 | 说明 | 原始归属 |
|--------|--------|------|----------|
| 流程历史 | P0 | 查询流程实例的完整审批流转记录（谁在什么时间做了什么操作） | Issue #1 原定四期 |
| 流程图展示 | P1 | 生成流程图，高亮当前节点和已走路径 | Issue #1 原定四期 |

## API 设计

### 流程历史

```java
/**
 * 查询流程实例的完整审批记录。
 *
 * @param processInstanceId 流程实例ID
 * @return 按时间排序的审批记录列表
 */
List<ApprovalRecordVO> getApprovalHistory(String processInstanceId);
```

### ApprovalRecordVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `nodeId` | String | activity definitionKey |
| `nodeName` | String | 节点名称 |
| `action` | ApprovalAction | 操作类型：START / AGREE / REJECT / WITHDRAW / REVOKE / COUNTER_SIGN / TRANSFER |
| `actorId` | String | 操作人ID |
| `actorName` | String | 操作人名称（由回调填充） |
| `comment` | String | 审批意见 |
| `startTime` | Date | 任务开始时间 |
| `endTime` | Date | 任务完成时间 |
| `duration` | Long | 耗时（毫秒） |

### ApprovalAction（枚举）

| 值 | 说明 |
|----|------|
| `START` | 流程发起 |
| `AGREE` | 同意 |
| `REJECT` | 驳回 |
| `WITHDRAW` | 撤回 |
| `REVOKE` | 撤销 |
| `COUNTER_SIGN_AGREE` | 会签同意 |
| `COUNTER_SIGN_REJECT` | 会签驳回 |
| `TRANSFER` | 转办/委派 |
| `ADD_SIGN` | 加签 |
| `DELETE_SIGN` | 减签 |
| `TERMINATE` | 终止 |

### 流程图

```java
/**
 * 获取流程定义的高亮图数据。
 * 返回 BPMN 元素的坐标和状态信息，由前端渲染。
 */
ProcessDiagramVO getProcessDiagram(String processInstanceId);
```

### ProcessDiagramVO

| 字段 | 类型 | 说明 |
|------|------|------|
| `processDefinitionId` | String | 流程定义ID |
| `nodes` | List\<DiagramNode\> | 所有节点的坐标和状态 |
| `flows` | List\<DiagramFlow\> | 所有连线的坐标和状态 |
| `currentNodeIds` | List\<String\> | 当前活跃节点的 activityId 列表 |
| `completedNodeIds` | List\<String\> | 已完成节点的 activityId 列表 |

## 架构决策

- **流程历史数据来源**：通过 `HistoryService.createHistoricActivityInstanceQuery()` 查询历史活动实例，关联 `HistoricTaskInstance` 获取审批意见。不需要引入自定义日志表。
- **流程图实现**：使用 Flowable 原生的 `ProcessDiagramGenerator`，通过 `BpmnModel` 和已走/当前节点 ID 生成高亮图。返回 Base64 图片数据或 JSON 坐标数据供前端渲染。
- **操作类型推断**：审批记录中的操作类型通过 Comment 类型字段（Phase 1 设计）和历史活动实例的边界事件推断。

## 决策记录

- 2026-07-04：原定五期（流程可视化与历史追溯），因委派/转办（[Phase 5](phase-5.md)）和事件监听器（[Phase 7](phase-7.md)）插入，顺延为六期。
- 2026-07-14：本阶段内容为 Issue #1 原定的四期内容。

## 实现切片

| Slice | 内容 | 优先级 | 状态 |
|-------|------|--------|------|
| S1: 流程审批历史 | `getApprovalHistory`，基于 HistoryService 查询并聚合 | P0 | 已完成 |
| S2: 流程图高亮 | `getProcessDiagram`，基于 ProcessDiagramGenerator 生成 | P1 | 已完成 |
| S3: 测试 + 文档 | 集成测试、使用文档 | — | 已完成 |

## 范围外

- 自定义流程图样式
- BPMN 设计器
- 统计分析面板
