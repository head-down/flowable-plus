# CODEBUDDY.md

本文件为 CodeBuddy Code 在此仓库中工作提供指导。

## 项目概览

flowable-plus 是一个面向 Java 8 的 Flowable (6.8.0) 工作流引擎增强工具包，提供简化 API 和中式工作流特性。项目基于 Spring Boot 2.7.18，采用 Maven 多模块结构，当前处于早期骨架搭建阶段。

**GroupId**: `io.github.flowable.plus`  
**Version**: `1.0-SNAPSHOT`

## 模块架构

```
flowable-plus (父 POM, packaging=pom)
├── flowable-plus-core                 -- 纯 Java 核心模块，无 Spring 依赖
├── flowable-plus-spring-boot-starter  -- Spring Boot 自动配置粘合层
└── flowable-plus-extension            -- 可选扩展模块（多实例、高级审批等）
```

### 依赖关系

```
flowable-plus-core
├── flowable-engine (6.8.0)
└── hutool-all (5.8.28)

flowable-plus-spring-boot-starter
├── flowable-plus-core
├── spring-boot-starter (2.7.18)
├── flowable-spring-boot-starter (6.8.0)
└── spring-boot-configuration-processor (可选)

flowable-plus-extension
├── flowable-plus-core
└── hutool-all (5.8.28)
```

### 各模块职责
- **flowable-plus-core** — 框架无关的包装层，封装 Flowable 的 RuntimeService、TaskService、HistoryService 等核心服务。可在任意 Java 8+ 应用中使用。
- **flowable-plus-spring-boot-starter** — 通过 `META-INF/spring.factories` 实现自动配置。配置属性前缀为 `flowable.plus.*`。当 classpath 上存在 `org.flowable.engine.ProcessEngine` 时条件激活。
- **flowable-plus-extension** — 可选模块，提供高级功能（多实例处理、高级审批模式等）。仅依赖 core 模块。

## 常用命令

```bash
# 完整构建（编译 + 打包所有模块）
mvn clean package

# 仅编译，跳过测试
mvn clean compile -DskipTests

# 运行所有测试
mvn test

# 运行单个测试类
mvn test -pl flowable-plus-core -Dtest=MyTestClass

# 安装到本地 Maven 仓库
mvn clean install -DskipTests

# 构建指定模块
mvn clean package -pl flowable-plus-core

# 生成源码 jar 包
mvn clean package -Dmaven.source.skip=false
```

## 关键依赖

| 依赖 | 版本 | 作用范围 |
|---|---|---|
| Java | 1.8 | 编译目标 |
| Spring Boot | 2.7.18 | 通过 BOM 管理 |
| Flowable | 6.8.0 | 通过 BOM 管理 (`flowable-root`) |
| Lombok | 1.18.30 | 所有模块 |
| Hutool | 5.8.28 | core、extension |
| MapStruct | 1.5.5.Final | 通过父 BOM 可用 |

## 注解处理

项目使用 Lombok、MapStruct 和 Spring Boot Configuration Processor 作为注解处理器。父 POM 中的 `maven-compiler-plugin` 已通过 `annotationProcessorPaths` 统一配置。新增 MapStruct mapper 时无需额外配置 — 处理器已在父级配置好。

## Spring Boot 自动配置

starter 模块通过 `META-INF/spring.factories` 注册 `FlowablePlusAutoConfiguration`，其中：
- `@ConditionalOnClass("org.flowable.engine.ProcessEngine")` — 仅在 Flowable 引擎存在时激活
- `@EnableConfigurationProperties(FlowablePlusProperties.class)` — 绑定 `flowable.plus.*` 配置属性

当前唯一配置项为 `flowable.plus.enabled`（布尔值，默认 `true`）。

## 当前状态

三个模块均处于骨架阶段。每个模块仅包含一个 `package-info.java`，starter 模块额外包含一个空壳自动配置类和一个布尔开关属性。核心领域服务、测试用例和扩展功能尚未实现。
