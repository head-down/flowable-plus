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

当 classpath 上存在 `ProcessEngine` 时（由 `flowable-spring-boot-starter` 提供），自动配置类 `FlowablePlusAutoConfiguration` 会注册一个 `FlowablePlus` Bean。

如需自定义，可声明同名 Bean 覆盖：

```java
@Configuration
public class MyConfig {

    @Bean
    public FlowablePlus flowablePlus(ProcessEngine processEngine, UserContext userContext, NodeFinder nodeFinder) {
        return new FlowablePlus(processEngine, userContext, nodeFinder);
    }
}
```

也可分别覆盖 NodeFinder 或 UserContext：

```java
@Bean
public NodeFinder nodeFinder(ProcessEngine processEngine) {
    return new CachedNodeFinder(new DefaultNodeFinder(
        processEngine.getRepositoryService(), processEngine.getHistoryService()
    ));
}

@Bean
public UserContext userContext() {
    return () -> "currentUser";
}
```
