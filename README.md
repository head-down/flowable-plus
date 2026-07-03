# flowable-plus

> Flowable 工作流引擎增强工具包，提供简化 API 和中式工作流特性。

[![Java](https://img.shields.io/badge/java-8-blue?style=flat-square&logo=java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/spring--boot-2.7.18-brightgreen?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Flowable](https://img.shields.io/badge/flowable-6.8.0-red?style=flat-square)](https://www.flowable.com/)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](./LICENSE)

## 模块结构

```
flowable-plus (父 POM)
├── flowable-plus-core                  核心模块（纯 Java，无框架依赖）
├── flowable-plus-spring-boot-starter   Spring Boot 自动配置
└── flowable-plus-extension             可选扩展（多实例、高级审批等）
```

- **flowable-plus-core** — 封装 Flowable 核心服务（RuntimeService、TaskService、HistoryService），可在任意 Java 8+ 应用中使用
- **flowable-plus-spring-boot-starter** — 自动配置粘合层，配置前缀 `flowable.plus.*`
- **flowable-plus-extension** — 高级功能，仅依赖 core 模块

## 快速开始

```bash
# 完整构建
mvn clean package

# 安装到本地仓库
mvn clean install -DskipTests

# 运行测试
mvn test
```

## 依赖

| 依赖 | 版本 |
|------|------|
| JDK | 1.8 |
| Spring Boot | 2.7.18 |
| Flowable | 6.8.0 |
| Lombok | 1.18.30 |
| Hutool | 5.8.28 |

## 当前状态

处于开发阶段。`flowable-plus-core` 已实现审批核心操作（发起、同意、驳回、撤回、撤销、会签），含 BPMN 模型缓存。`flowable-plus-extension` 为空壳模块。

## 许可证

MIT License
