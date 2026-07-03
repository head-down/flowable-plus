# Phase 2 规划

## 范围

**多实例/多人审批能力**，聚焦以下子方向：

### 本期范围

| 子方向 | 优先级 | 说明 |
|--------|--------|------|
| 会签 | P0 | 所有审批人同意才通过 |
| 或签 | P1 | 任一审批人同意即通过，复用 `counterSign` 方法，通过 BPMN completionCondition 区分 |
| 加签 | P2 | 审批中动态增加审批人 |
| 减签 | P2 | 审批中动态移除审批人 |

### 下期预留

| 方向 | 说明 |
|------|------|
| B. 查询与视图 | 待办列表、已办列表、我发起的、流程追踪 |
| C. 审批模式深化 | 委托、转办、前置驳回、子流程支持 |
| D. 基础设施 | 真实嵌入式引擎集成测试、事件监听器、发布准备 |

## API 设计

### 会签专属方法

| 方法 | 说明 | 优先级 |
|------|------|--------|
| `counterSign(taskId, variables, comment)` | 完成当前用户的会签任务，引擎根据 completionCondition 判断是否全部完成 | P0 |
| `addCounterSigner(taskId, assignees)` | 加签：在会签进行中追加审批人 | P2 |
| `removeCounterSigner(taskId, assignee)` | 减签：在会签进行中移除审批人 | P2 |

### Phase 1 操作与会签交互规则

- `rejectTask` / `withdrawTask`：在会签多实例子任务上调用时**直接报错**，引导使用 `counterSign`
- `revokeProcess`：**不拦截**，发起人撤销整个流程实例的语义在会签中一致有效

### 决策

- 2026-07-03：会签操作使用新增专属方法（counterSign），不复用 completeTask。理由：(1) 会签有独立业务语义 (2) 可在方法内部校验多实例节点 (3) 未来加签/减签需要独立 API 入口
- 2026-07-03：会签中的拒绝/驳回行为采用计数否决模式，completionCondition 由业务侧自定义
- 2026-07-03：rejectTask/withdrawTask 在多实例会签子任务上直接报错，revokeProcess 不拦截

## SPI 扩展点

| SPI 回调 | 触发时机 | 用途 |
|---------|---------|------|
| `CounterSignCallback.onStart(processInstanceId, taskId, assignees)` | 会签发起/加签时 | 业务侧推送待办通知、更新业务单据状态 |
| `CounterSignCallback.onVote(processInstanceId, taskId, assignee, approved, comment)` | 单个会签人投票完成时 | 记录审批轨迹、更新汇总统计 |
| `CounterSignCallback.onFinish(processInstanceId, taskId, result)` | 整轮会签结束时 | 汇总审批结果、触发后续业务流程 |

## 架构决策

- **实现路线**：Flowable 原生 `multiInstanceLoopCharacteristics` + SPI 扩展点
  - 原因：flowable-plus 定位为"贴近引擎的增强工具包"，不应引入自定义数据库表
  - 加签/减签通过 `RuntimeService.addMultiInstanceExecution()` / `deleteMultiInstanceExecution()` 实现
  - 多轮次、业务回调等原生不足的能力通过 SPI/事件机制留给应用层

## 决策记录

- 2026-07-03：二期聚焦多实例/多人审批，会签优先。B/C/D 不纳入二期范围。
- 2026-07-03：确认 flowable-plus 定位为"贴近引擎的增强工具包"，而非"开箱即用的业务审批系统"
- 2026-07-03：AGREED with user: All decisions documented above

## 实现切片

| Slice | 内容 | 状态 |
|-------|------|------|
| S1: 多实例检测 | 判断 Task 是否属于多实例子任务的工具方法 | 已完成 |
| S2: counterSign 核心 | `counterSign(taskId, approved, comment)` 方法 + completionCondition 辅助 | 已完成 |
| S3: SPI 回调机制 | `CounterSignCallback` 接口定义 + FlowablePlus 中的调用点 | 待开发 |
| S4: 加签/减签 | `addCounterSigner` / `removeCounterSigner` | 待开发 |
| S5: Starter 适配 | 自动配置注册 CounterSignCallback、健康检查扩展 | 待开发 |
| S6: 测试 + 文档 | 多实例 BPMN 集成测试、completionCondition 示例模板 | 待开发 |

S1+S2 优先实现最小可用版本。

## S1 实现记录

- **实现位置**：`FlowablePlus.resolveMultiInstance(Task task)`（包级私有方法）+ `FlowablePlus.MultiInstanceInfo`（静态内部类）
- **决策记录**：
  - 检测依据：BPMN 模型层 `MultiInstanceLoopCharacteristics`
  - 返回类型：`MultiInstanceInfo`（`isSequential` + `completionCondition`），非多实例返回 `null`
  - 不限制 Activity 类型（UserTask/ServiceTask/CallActivity 均可检测）
  - 构造器新增 `RepositoryService` 字段
- **测试**：6 个单元测试覆盖普通节点、并行多实例、串行多实例、completionCondition、BPMN 模型 null、FlowElement 不存在
- **完成时间**：2026-07-03

## S2 实现记录

- **实现位置**：
  - `FlowablePlus.counterSign(taskId, approved, variables, comment)` — 公开方法
  - `FlowablePlus.assertNotMultiInstance(task, taskId)` — 私有辅助方法
- **决策记录**：
  - 方法签名：`counterSign(taskId, approved, variables, comment)`，三者都要
  - 执行步骤：validateTaskAndPermission → resolveMultiInstance → claim → addComment(AGREE/COUNTER_SIGN_REJECT) → complete
  - 非多实例任务调用：抛出 `IllegalArgumentException`（全部中文错误消息）
  - Comment 类型：`AGREE`（同意） / `COUNTER_SIGN_REJECT`（驳回），不与 rejectTask 的 `REJECT` 混淆
  - `completeTask`、`rejectTask`、`withdrawTask`、`rejectTaskToInitiator` 四个方法加多实例拦截
  - `completeTask` 重构为查询 Task 后检测（接受一次额外 DB 查询）
  - `approved` 参数不单独存为流程变量，仅通过 Comment 类型区分投票方向
- **测试**：9 个新增测试（6 counterSign + 3 方法拦截）覆盖同意/驳回、非多实例报错、空/null 评论、拦截场景
- **完成时间**：2026-07-03
