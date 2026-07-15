# 自动提交采用 AutoApprovalRule SPI 模式，支持多规则 OR 链，异常快速失败

引入 `AutoApprovalRule` SPI 接口，在 `startProcess` 内部对发起人的首审批任务自动完成。无注册 Bean 时行为不变（向后兼容），多规则时 OR 逻辑合并。

## 背景

流程发起后，发起人通常需要再手动完成自己的首审批任务，形成"发起 → 提交"两步操作。通过可选的自动提交规则，发起流程后自动完成发起人首审批任务，减少不必要的重复操作。

## 决策

1. **接口返回 `String`**：返回非 null 表示触发自动提交（值为审批意见），返回 null 表示不触发。结构化对象留待未来 YAGNI 评估。

2. **遍历所有首任务**：`startProcess` 发起后可能存在多个首任务（如并行网关分出的多个 UserTask），对所有首任务逐一调用 `evaluate`，而非仅处理第一个。

3. **快速失败（fail-fast）**：自动提���不包裹 try-catch，任何异常正常向上传播，由类级 `@Transactional(rollbackFor = Exception.class)` 保证整个 `startProcess`（含流程创建 + 自动提交）原子回滚。自动提交在 `setAuthenticatedUserId(null)` 之前执行，`finally` 块保证身份清理不受异常影响。

4. **浅层不可变保护**：`evaluate` 传入的 variables 使用 `Collections.unmodifiableMap` 浅层包裹，同时接口文档写明契约"请勿修改"。不进行深拷贝（成本不可控）。

5. **日志策略**：成功自动提交打 info 日志（记录 taskId + 意见），失败打 warn 日志。不在 `PlusProcessInstance` 返回值上附加自动提交结果标记——调用方可从审批轨迹中查询确认。

6. **多规则 OR 模式**：采用 `@Autowired(required = false) List<AutoApprovalRule>` 注入，参考 `CounterSignCallback` 的现有模式。多个 rule 时 OR 逻辑：任一 rule 返回非 null 即触发自动提交，comment 取第一个非 null 结果。

7. **commentType 使用 AUTO_COMPLETE**：通过 `TaskRepository.addComment` 追加类型为 `AUTO_COMPLETE` 的评论，与用户手动同意的 COMPLETE 区分，方便审计追踪。

8. **首任务快照隔离**：自动提交前先调用一次 `findActiveTasksByProcessInstance` 收集首任务快照，仅对 snapshot 中的任务调用 evaluate + complete。自动完成后产生的新任务不在初始 snapshot 中，不会级联触发。

9. **isFirstStart 重入守卫**：自动提交前检查历史任务是否为空（`historicTasks.isEmpty()`），若流程实例已有历史任务则不触发自动提交。防止重新启动、边界事件补偿等场景下误自动提交。此守卫与快照隔离形成双重保护——前者防重入，后者防级联，职责互补。参考业界 Activiti 项目中 `GlobalTaskEventListener` 的自动完成首任务实践。

## 实现细节

### 类级 `@Transactional` 的事务边界

`TaskWorkflow` 类标注 `@Transactional(rollbackFor = Exception.class)`，所有由外部调用进入 `TaskWorkflow` 的公共方法均在同一事务边界内执行。关键行为：

- **外部调用入口**：Spring AOP 代理在方法入口开启事务，方法返回时提交。每个外部触发的业务动作（一次发起、一次驳回、一次撤回等）具备完整的事务保障。
- **内部方法互相调用**：`withdrawTask` → `executeRollback` 等内部调用不经过 Spring AOP 代理，因此不创建嵌套事务，而是共用同一个事务。这是期望行为——业务动作内的多个 Flowable 操作应在同一事务中原子执行。
- **只读方法不受影响**：`getJumpableNodes` 等查询方法虽然也在代理范围内，但仅执行 SELECT，事务开销可忽略。
- **非 Spring 环境**：`spring-tx` 仅提供 `@Transactional` 注解元数据，无 DI/AOP。在纯 Java 单元测试中，注解无处理器，行为不变。

### 事务与 `finally` 块的协作

`startProcess` 中的 `finally { identityService.setAuthenticatedUserId(null); }` 不参与事务语义——它只是清理线程本地身份上下文，在事务提交/回滚之前执行。异常从 `autoCompleteFirstTasks` 向上传播时，`finally` 块先完成清理，然后 Spring 事务管理器回滚事务。

## 考虑的方案

| 方案 | 结论 | 理由 |
|------|------|------|
| 单个 `AutoApprovalRule`（`@Autowired(required = false)` 单对象）| 不采纳 | 多模块注册冲突时 Spring 启动报错，扩展性差 |
| `evaluate` 返回结构化对象 | 不采纳 | 当前仅需"同意+意见"语义，YAGNI |
| 仅处理第一个首任务 | 不采纳 | 并行网关场景下会遗漏其他首任务 |
| 深拷贝变量保护 | 不采纳 | 成本不可控，契约约束即可 |
| `PlusProcessInstance` 附加自动提交标记 | 不采纳 | 保持值对象纯净，调用方可通过审批轨迹查询 |
| ActivitiEventListener 事件驱动方式 | 不采纳 | 每次任务创建都触发监听，非首任务也走判断逻辑，性能浪费。`startProcess` 内嵌更精确 |
| BPMN 扩展元素标记机制（`isstarttask`）| 文档建议 | 声明式标记有价值，但框架不做 BPMN 解析侵入，由 AutoApprovalRule 实现者自行结合 NodeFinder 读取 |

## 后果

- 新增 `AutoApprovalRule` SPI 接口到 `flowable-plus-core` 模块 `spi/` 目录
- `TaskWorkflow` 构造函数新增 `@Nullable List<AutoApprovalRule>` 参数
- `FlowablePlusAutoConfiguration` 中 taskWorkflow Bean 方法新增可选参数注入
- 自动提交发生时会追加 `AUTO_COMPLETE` 类型的流程注释——下游查询注释的代码需正确处理此新类型
- `HistoricRepository` 需新增 `hasHistoricTasks(processInstanceId)` 方法用于 isFirstStart 守卫
- `flowable-plus-core` 新增 `spring-tx` 依赖（注解级别，无 DI/AOP 运行时），用于 `TaskWorkflow` 类上的 `@Transactional` 声明
- 自动提交异常不再被静默吞没，调用方如服务层未标注 `@Transactional`，异常仍会传播但无事务回滚保护——建议调用方配合 `@Transactional` 使用
