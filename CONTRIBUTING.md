# Contributing to flowable-plus

感谢你有兴趣为 flowable-plus 贡献代码。本项目当前处于早期阶段，贡献者主要为主维护者，但任何有价值的 PR、issue 和讨论都欢迎。本指南说明如何构建、测试和提交改动。

## 项目概览

flowable-plus 是面向 Java 8 的 Flowable (6.8.0) 工作流引擎增强工具包，提供简化 API 和中式工作流特性。

- **定位**：领域增强工具，不是通用 API 包装层。公开 API 必须有领域增值（审批语义、权限校验、事件集成、VO 转换等），禁止裸透传 Flowable 原生方法（见 [ADR-0015](docs/adr/0015-public-api-entry-criteria.md)）。
- **范围边界**：凡脱离业务数据即失去独立价值的功能归业务层，框架只做流程引擎领域内自洽的能力（见 [ADR-0032](docs/adr/0032-feature-out-of-scope-criteria.md)）。

## 环境准备

| 依赖 | 版本 |
|------|------|
| JDK | 1.8 |
| Maven | 3.6+ |

推荐在提交前先阅读 [CONTEXT.md](CONTEXT.md)（领域概念与统一语言）和 [UBIQUITOUS_LANGUAGE.md](UBIQUITOUS_LANGUAGE.md)。

## 模块结构

```
flowable-plus (父 POM)
├── flowable-plus-core                 -- 核心模块（API/SPI/VO/事件），不启动 Spring DI 容器
├── flowable-plus-spring-boot-starter  -- Spring Boot 自动配置粘合层
└── flowable-plus-extension            -- 储备位模块（reserved slot，无功能内容，见 ADR-0029）
```

- 核心领域逻辑（审批、会签、查询、流程图、事件）全部位于 `flowable-plus-core`。
- 自动配置（`FlowablePlusAutoConfiguration`）位于 `flowable-plus-spring-boot-starter`。
- **不要**往 `flowable-plus-extension` 里塞功能——它仅用于「依赖隔离」或「真正可选的领域能力」，边界见 ADR-0029。

## 构建与测试

```bash
# 完整构建（编译 + 打包所有模块）
mvn clean package

# 仅编译，跳过测试
mvn clean compile -DskipTests

# 运行所有测试（建议加 clean 避免 JDK 8 增量编译 NPE）
mvn clean test

# 运行单个测试类
mvn clean test -pl flowable-plus-core -Dtest=MyTestClass

# 在指定数据库上运行完整验证（H2 / MySQL / PostgreSQL）
mvn clean verify -Dflowable.test.db=h2
mvn clean verify -Dflowable.test.db=mysql
mvn clean verify -Dflowable.test.db=postgresql
```

**测试要求**：

- 所有测试（约 335 个，含约 77 个集成测试）是 PR 合并的必需门禁。
- CI 矩阵覆盖 H2 / MySQL 8.0 / PostgreSQL 14（见 [ADR-0014](docs/adr/0014-multi-database-ci-gate.md)）。新增测试必须在矩阵两端全部通过。
- MySQL / PostgreSQL 通过 Testcontainers 拉起容器，本机需可运行 Docker。
- 集成测试位于 `flowable-plus-spring-boot-starter/src/test/java`，继承 `AbstractIntegrationTest`。

## 提交规范

### Commit message

**必须使用中文书写 commit message**，遵循 Conventional Commits 格式：

```
<type>[optional scope]: <description>

[optional body]
```

常用类型：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `ci` / `style`。破坏性变更用 `!` 后缀（如 `refactor!:`）或 `BREAKING CHANGE:` footer。

示例：

```
docs(adr): 新增 ADR-0032 范围外功能判据

fix(countersign): 修复重复加签静默跳过的 bug
```

### 代码审查

实现完成后**必须**执行 `/code-review` 双轴审查（Standards + Spec），审查通过后方可提交，不得跳过。

## 提交 issue

使用 GitHub Issues（仓库 `head-down/flowable-plus`），通过 `gh` CLI 操作，详见 [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)。

标签约定（详见 [docs/agents/triage-labels.md](docs/agents/triage-labels.md)）：

- `needs-triage` — 等待维护者评估
- `needs-info` — 信息不足，等待补充
- `ready-for-agent` — 已充分描述，可交给 agent 实现
- `ready-for-human` — 需要人工实现
- `wontfix` — 确认不处理

提 issue 时请说明：现状、问题、期望行为。若涉及架构取舍，附上相关 ADR 编号。

## 提交 PR

1. 从 `master` 切出分支，改动聚焦单一逻辑。
2. 确保 `mvn clean verify`（至少 H2 矩阵）通过。
3. 提交信息遵循上文规范。
4. 在 PR 描述中说明改动动机、涉及模块、是否有 ADR 支撑。

## 架构决策记录（ADR）

任何影响公开 API、模块边界或领域语义的决策，应在 `docs/adr/` 下新增 ADR（编号递增，格式参考现有条目），并在 [CODEBUDDY.md](CODEBUDDY.md) 的 ADR 表格中登记。ADR 编号当前为 0035，新条目从 0036 开始。

## 许可

本项目基于 Apache License 2.0。贡献代码即视为同意在该许可下分发。
