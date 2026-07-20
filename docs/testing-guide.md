# 集成测试使用指南

## 测试分层

项目采用两层测试策略：

| 层级 | 模块 | 技术栈 | 特点 | 测试数 |
|---|---|---|---|---|
| 单元测试 | `flowable-plus-core` | JUnit 5 + Mockito | 纯 mock，无框架启动，秒级执行 | ~258 |
| 集成测试 | `flowable-plus-spring-boot-starter` | JUnit 5 + @SpringBootTest + H2 | 嵌入式 Flowable 引擎 + H2 内存数据库 | ~49 |

## 运行命令

```bash
# 运行所有测试（含单元测试 + 集成测试）
mvn clean test

# 运行完整验证（含测试 + 打包）
mvn clean verify

# 仅运行单元测试，跳过集成测试
mvn clean test -pl flowable-plus-core

# 运行单个集成测试类
mvn clean test -pl flowable-plus-spring-boot-starter -Dtest=CounterSignIntegrationTest

# 建议始终加 clean，避免 JDK 8 增量编译偶发 NPE
```

## H2 集成测试架构

集成测试基于 `BpmnQueryIntegrationTestApplication` —— 一个最小化的 `@SpringBootConfiguration`：

```
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = FlowablePlusAutoConfiguration.class)
public class BpmnQueryIntegrationTestApplication {}
```

- 启用 Flowable 自动配置（嵌入式引擎 + H2 数据源）
- 扫描 flowable-plus 自动配置类，注册所有 Bean
- BPMN 文件放在 `src/test/resources/bpmn/` 下，测试中通过 `repositoryService.createDeployment()` 动态部署
- 每个测试在 `@AfterEach` 中清理部署和流程实例，确保用例间隔离

### 动态用户上下文

集成测试使用 `DynamicUserContext`（`ThreadLocal<String>`）模拟请求用户：

```java
DynamicUserContext.set("userId");
// ... 执行操作 ...
DynamicUserContext.CURRENT_USER.remove();  // tearDown 中清理
```

用于绕过 Spring Security 依赖，在 H2 环境中直接设置当前用户 identity。

## ADR-0013: Native SQL 精确分页

- 目标文档：`docs/adr/0013-native-sql-done-query.md`
- 对应测试：`DoneTaskPreciseIntegrationTest`（5 个 @Test，验证已办查询的正确性和分页边界）
- 覆盖场景：发起人无已办、活跃任务不计入、候选人无已办、多流程实例分页

## 新增测试指南

**单元测试**：core 模块的测试通过 Mockito mock 构造被测对象，无 Spring 依赖：

```java
@Test
void testMyFeature() {
    TaskService taskService = mock(TaskService.class);
    // ... 构造被测对象，调用方法，断言结果
}
```

**集成测试**：starter 模块的测试需要真实引擎上下文：

```java
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class)
@Import(SharedTestConfiguration.class)
class MyIntegrationTest {

    @BeforeEach
    void setUp() {
        // 部署 BPMN，设置 DynamicUserContext
    }

    @AfterEach
    void tearDown() {
        // 清理部署和流程实例
    }

    @Test
    void testScenario() {
        // 发起流程 → 完成任务 → 断言结果
    }
}
```
