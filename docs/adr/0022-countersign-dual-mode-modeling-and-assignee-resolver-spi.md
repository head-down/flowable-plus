# ADR-0022: 会签建模双模式规范与 AssigneeResolver 扩展点

**日期**: 2026-08-06
**状态**: 已接受

## 上下文

### 当前缺口

flowable-plus 作为"贴近引擎的增强工具包"（ADR-0003），在会签功能的通用完备性上存在两个缺口：

1. **缺少建模指引**：用户不知道在不同业务场景下，MI 节点的 `collection` 变量应该如何注入。是放空列表让前端触发加签？还是提前塞满审批人列表？
2. **缺少自动化解析入口**：下游项目需要在业务代码中手动塞 `assigneeList` 等流程变量，无法通过 SPI 从组织架构、角色等数据源自动生成会签名单。

### 已有基础

- `docs/completion-condition.md`：已覆盖 completionCondition 表达式模板
- `ApproverResolver` SPI：读侧审批人解析（"下一节点审批人"查询）
- `CounterSignCallback` SPI：会签生命周期回调

## 决策

### 1. 输出《BPMN 会签建模与变量注入规范》

在 `docs/` 下新增 `countersign-modeling-guide.md`，明确两种接入模式的配置姿势、变量注入时机和 Jar 包适配行为。

#### 模式 A：偶发性会签（动态加签）

**BPMN 配置**：
```xml
<userTask id="countersignTask" flowable:assignee="${assignee}">
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="${assigneeList}"
      flowable:elementVariable="assignee">
    <completionCondition>${nrOfCompletedInstances >= nrOfInstances}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

**变量注入时机**：发起流程或上游节点提交时，`assigneeList` 只放 1 个 Owner：
```java
Map<String, Object> variables = new HashMap<>();
variables.put("assigneeList", Collections.singletonList("owner"));
runtimeService.startProcessInstanceByKey("myProcess", variables);
```

**Jar 包行为**：
- 初始渲染：前端展示伪单例 UI（只有一个审批人，看起来像普通审批节点）
- 发起人会签：通过 `addCounterSigner` 动态追加审批人
- 投票：通过 `counterSign` 逐人投票
- 减签：通过 `removeCounterSigner` 移除未投票人

**适用场景**：审批人事先不确定，由发起人根据情况动态选择。

#### 模式 B：固定/前置会签（预填充列表）

**BPMN 配置**：与模式 A 相同。

**变量注入时机**：在上游节点提交时或网关条件计算时，`assigneeList` 预填完整列表：
```java
Map<String, Object> variables = new HashMap<>();
variables.put("assigneeList", Arrays.asList("userA", "userB", "userC"));
taskService.complete(taskId, variables);
```

**Jar 包行为**：
- 自动屏蔽"发起会签"按钮（`activeCount > 1`，由 `isMultiInstanceFinished` 返回 false → 不是新轮次）
- 直接进入多人投票模式
- 投票：通过 `counterSign` 逐人投票

**适用场景**：审批人在流程设计时就已确定（如固定部门角色、固定岗位等）。

### 2. 新增 AssigneeResolver SPI（非侵入式）

为模式 B 提供自动化变量注入能力：定义一个 SPI 接口，由下游项目按需实现，在 MI 节点的 `TaskListener(create)` 中绑定调用。

#### 接口定义

```java
package io.github.flowable.plus.core.spi;

@FunctionalInterface
public interface AssigneeResolver {
    /**
     * 为会签（多实例）节点解析审批人列表。
     * 在 MI 节点的 TaskListener(Create) 中调用，
     * 仅当 assigneeList 为空/null 时触发。
     *
     * @param processInstanceId 流程实例 ID
     * @param taskDefinitionKey 多实例节点 KEY
     * @return 审批人 ID 列表，无审批人时返回空列表
     */
    List<String> resolveCountersignAssignees(String processInstanceId, String taskDefinitionKey);
}
```

#### Spring 装配

与现有 SPI 模式一致，通过 `@Autowired(required = false) List<AssigneeResolver>` 收集所有实现：

```java
public class FlowablePlusAutoConfiguration {
    @Bean
    public AssigneeResolverRegistry assigneeResolverRegistry(
            @Autowired(required = false) List<AssigneeResolver> resolvers) {
        return new AssigneeResolverRegistry(resolvers != null ? resolvers : Collections.emptyList());
    }
}
```

提供 `AssigneeResolverRegistry` 聚合类，供 TaskListener 调用：
```java
public List<String> resolve(String processInstanceId, String taskDefinitionKey) {
    for (AssigneeResolver resolver : resolvers) {
        List<String> assignees = resolver.resolveCountersignAssignees(processInstanceId, taskDefinitionKey);
        if (assignees != null && !assignees.isEmpty()) {
            return assignees;
        }
    }
    return Collections.emptyList();
}
```

#### 非侵入式绑定

框架**不自动注入** TaskListener。下游项目按需在 BPMN 中配置：
```xml
<userTask id="countersignTask" ...>
  <extensionElements>
    <flowable:taskListener event="create"
        delegateExpression="${countersignAssigneesListener}" />
  </extensionElements>
  <multiInstanceLoopCharacteristics ...>
    ...
  </multiInstanceLoopCharacteristics>
</userTask>
```

`CountersignAssigneesListener` 的实现由框架提供，内部调用 `AssigneeResolverRegistry.resolve()`：
```java
@Component("countersignAssigneesListener")
public class CountersignAssigneesListener implements TaskListener {
    private final AssigneeResolverRegistry registry;
    
    @Override
    public void notify(DelegateTask delegateTask) {
        // 仅当 assigneeList 为空时触发
        Object existing = delegateTask.getVariable("assigneeList");
        if (existing != null && !((List<?>) existing).isEmpty()) {
            return;
        }
        List<String> assignees = registry.resolve(
            delegateTask.getProcessInstanceId(),
            delegateTask.getTaskDefinitionKey()
        );
        if (!assignees.isEmpty()) {
            delegateTask.setVariable("assigneeList", assignees);
        }
    }
}
```

### 3. 与 ADR-0021 的协同

- **模式 A**：初始 `assigneeList` 只有 1 人 → 运行时历史任务数 = 1 → ADR-0021 判定为非多实例 → 允许直接回退
- **模式 B**：初始 `assigneeList` 多个人 → 运行时历史任务数 > 1 → ADR-0021 判定为多实例 → 触发自动重定向或拦截
- **前置准备节点规范**：如果 MI 节点前有单例准备节点，ADR-0021 的自动重定向将自然生效

## 备选方案

- **框架自动注入 TaskListener（BpmnParseListener）**：在解析 BPMN 时自动为所有 MI 节点注入 `CountersignAssigneesListener`。被否决——侵入性强，可能与用户自定义 Listener 冲突，与 flowable-plus 的非侵入式设计哲学不一致。
- **仅文档，不提供 SPI**：被否决——下游项目仍需各自实现，无法复用标准化能力。
- **AssigneeResolver 绑定到 ExecutionListener(Start)**：被否决——Start 事件在 MI 节点入口触发时，子任务尚未创建，`assigneeList` 的变更无法影响已初始化的子实例。Create 事件作用于每个子任务创建时，时机更合适。

## 后果

- **正面**：
  - 用户有明确的建模指引，知道怎么配、怎么注入、会有什么行为
  - AssigneeResolver SPI 提供标准化扩展点，下游项目无需重复实现变量注入逻辑
  - 非侵入式设计保持框架中立，不与现有 SPI 冲突
  - 与 ADR-0021 形成完整的会签回退解决方案

- **负面**：
  - 需要下游项目在 BPMN 中手动配置 TaskListener（增加建模步骤）
  - AssigneeResolver 的调用时机依赖 `assigneeList` 为空/null 的判断，需要文档明确约束

- **风险**：
  - 低：SPI 接口为 `@FunctionalInterface`，无额外依赖；TaskListener 为可选组件
