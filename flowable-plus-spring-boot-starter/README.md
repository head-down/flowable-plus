# flowable-plus-spring-boot-starter

Flowable-Plus 的 Spring Boot 自动配置模块，提供开箱即用的集成。

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.flowable.plus</groupId>
    <artifactId>flowable-plus-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. 注入使用

```java
@Service
public class MyApprovalService {

    @Autowired
    private FlowablePlus flowablePlus;

    /**
     * 查找上一审批节点
     */
    public List<String> getPreviousApprovers(String taskId) {
        Task task = flowablePlus.getTaskService()
                .createTaskQuery().taskId(taskId).singleResult();

        return flowablePlus.findPreviousNodes(
                task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(),
                task.getProcessInstanceId()
        );
    }

    /**
     * 查找流程发起人节点
     */
    public String getInitiatorNode(String processDefinitionId) {
        return flowablePlus.findInitiatorNode(processDefinitionId);
    }
}
```

## 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `flowable.plus.enabled` | `boolean` | `true` | 是否启用 Flowable-Plus 增强功能 |

```yaml
# application.yml
flowable:
  plus:
    enabled: true
```

## 自动配置

当 classpath 上存在 `ProcessEngine` 时（由 `flowable-spring-boot-starter` 提供），自动配置类 `FlowablePlusAutoConfiguration` 会注册以下 Bean：

| Bean | 类型 | 说明 |
|------|------|------|
| `bpmnModelCache` | `BpmnModelCache` | 基于 ConcurrentHashMap 的 BPMN 模型缓存，永不过期 |
| `nodeFinder` | `NodeFinder` | DefaultNodeFinder，注入 BpmnModelCache |
| `flowablePlus` | `FlowablePlus` | 审批统一入口，注入 ProcessEngine、UserContext、NodeFinder、BpmnModelCache |
| `userContext` | `UserContext` | classpath 存在 Spring Security 时注册 SecurityContextUserContext；否则注册 SystemPropertyUserContext 兜底（`flowable.plus.user-id` 系统属性，默认 `"system"`） |

如需自定义，可声明同名 Bean 覆盖：

```java
@Configuration
public class MyConfig {

    @Bean
    public FlowablePlus flowablePlus(ProcessEngine processEngine, UserContext userContext,
                                     NodeFinder nodeFinder, BpmnModelCache bpmnModelCache,
                                     GroupResolver groupResolver,
                                     List<CounterSignCallback> counterSignCallbacks) {
        return new FlowablePlus(processEngine, userContext, nodeFinder, bpmnModelCache,
                groupResolver, counterSignCallbacks);
    }
}
```

也可分别覆盖 NodeFinder、BpmnModelCache 或 UserContext：

```java
@Bean
public BpmnModelCache bpmnModelCache(ProcessEngine processEngine) {
    return new DefaultBpmnModelCache(processEngine.getRepositoryService());
}

@Bean
public NodeFinder nodeFinder(BpmnModelCache bpmnModelCache, ProcessEngine processEngine) {
    ExpressionManager expressionManager = ((ProcessEngineConfigurationImpl) processEngine
            .getProcessEngineConfiguration()).getExpressionManager();
    return new DefaultNodeFinder(bpmnModelCache, processEngine.getHistoryService(), expressionManager);
}

@Bean
public UserContext userContext() {
    return () -> "currentUser";
}
```

如果不想引入 Spring Security，可通过系统属性指定用户 ID：

```bash
java -Dflowable.plus.user-id=admin -jar app.jar
```
