# CODEBUDDY.md

本文件为 CodeBuddy Code 在此仓库中工作提供指导。

## 项目概览

flowable-plus 是面向 Java 8 的 Flowable (6.8.0) 工作流引擎增强工具包，提供简化 API 和中式工作流特性（发起、同意、驳回、撤回、撤销、会签）。基于 Spring Boot 2.7.18，Maven 多模块结构。

**GroupId**: `io.github.flowable.plus` · **Version**: `1.0.0`

领域词汇与语言规范见 `CONTEXT.md`。所有依赖版本一律以父 POM / BOM（`flowable-root`）为准，不要自行指定。

## 模块架构

```
flowable-plus (父 POM, packaging=pom)
├── flowable-plus-core                 -- 核心模块
├── flowable-plus-spring-boot-starter  -- Spring Boot 自动配置粘合层
└── flowable-plus-extension            -- 储备位模块（reserved slot），边界见 ADR-0029
```

- **flowable-plus-core** — 封装 Flowable 核心服务（RuntimeService、TaskService、HistoryService 等），通过 SPI 接口解耦运行时框架。依赖 spring-tx 仅用于 `@Transactional` 注解元数据声明（无 DI/AOP 运行时），在无 Spring AOP 的环境中无害忽略。包含 BPMN 模型缓存（`BpmnModelCache`）消除重复引擎 I/O。可在任意 Java 8+ 应用中使用。
- **flowable-plus-spring-boot-starter** — 通过 `META-INF/spring.factories` 实现自动配置，配置属性前缀 `flowable.plus.*`，classpath 存在 `org.flowable.engine.ProcessEngine` 时条件激活。
- **flowable-plus-extension** — 储备位模块，等待「依赖隔离」（必须引入 core 未引入的依赖）或「真正可选的领域能力」类功能入住；薄壳包装 core 已有功能属双轨，不入住。定位与判据见 ADR-0029。

## 构建与测试

- 一律 `mvn clean ...` —— JDK 8 增量编译会 NPE。
- 定位单个测试：`mvn clean test -pl flowable-plus-core -Dtest=<类名>`。
- 注解处理器（Lombok / MapStruct / Configuration Processor）已在父 POM 通过 `annotationProcessorPaths` 配置；新增 MapStruct mapper 无需任何额外配置。
- CI 是 PR 合并的必需门禁：新增/改动测试必须在矩阵两端（ubuntu + windows，H2 / MySQL / PostgreSQL）全部通过。矩阵组合、运行命令与报告细节见 `.github/workflows/ci.yml`——改动测试或 CI 配置前先读它确认门禁范围。

## Spring Boot 自动配置

starter 模块通过 `META-INF/spring.factories` 注册 `FlowablePlusAutoConfiguration`：
- `@ConditionalOnClass("org.flowable.engine.ProcessEngine")` — 仅在 Flowable 引擎存在时激活
- `@ConditionalOnProperty(name = "flowable.plus.enabled")` — 开关，默认 `true`

自动注册的 Bean：
- `BpmnModelCache` — 基于 ConcurrentHashMap 的 BPMN 模型缓存，永不过期
- `NodeFinder` — DefaultNodeFinder，注入 BpmnModelCache
- `FlowablePlus` — 查询门面（仅读操作），注入 ProcessEngine、UserContext、NodeFinder、BpmnModelCache；写操作注入对应 `*Operations` 接口（见 ADR-0010 门面范围）
- `UserContext` — classpath 存在 Spring Security 时注册 SecurityContextUserContext；否则 SystemPropertyUserContext 兜底（从系统属性 `flowable.plus.user-id` 读取）

## 已知边界

- 权限：流程操作权限已覆盖（assignee/发起人/上一节点审批人身份校验）；数据权限待以回调扩展模式补充（见 `docs/planning/permission-integration-evaluation.md`）。

## Agent skills

### Issue tracker

使用 GitHub Issues（仓库 `head-down/flowable-plus`），通过 `gh` CLI 操作。详见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认标准标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### Domain docs

单一上下文布局：`CONTEXT.md` + `docs/adr/` 在仓库根目录。详见 `docs/agents/domain.md`。

### 实现流程规范

`/implement` 完成后必须执行 `/code-review` 双轴审查（Standards + Spec），审查通过后方可提交。

### 架构决策记录 (ADR)

改动核心逻辑前先查索引确认已有决策（含会签、驳回、查询、权限等 41 项）：完整编号→标题→日期索引见 `docs/adr/README.md`。
