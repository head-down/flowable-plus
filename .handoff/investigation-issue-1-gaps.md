# Issue #1 收尾调查结论

**日期**：2026-07-17
**来源会话**：`/clear` 后检查 https://github.com/head-down/flowable-plus/issues/1 并全项目探索
**用法**：新会话中读取本文件，按 P0 → P1 → P2 顺序实现

---

## 背景

Issue #1 是项目的初始 PRD，定义了 3 个模块 + 7 个阶段的功能范围。经代码审查，所有 7 个阶段的功能代码已全部实现。问题是项目还缺一些收尾工作才能从 1.0-SNAPSHOT 升到正式版。

---

## 当前状态确认

### 7 个阶段全部完成

| 阶段 | 功能 | 状态 |
|------|------|:--:|
| 一期 | 发起/同意/驳回/撤回/撤销（ApprovalOperations 10 个方法） | 已完成 |
| 二期 | 会签/或签/加签减签（CounterSignOperations 5 个方法） | 已完成 |
| 三期 | 待办/已办/流程摘要/审批轨迹/节点预览（QueryOperations 11 个方法） | 已完成 |
| 四期 | 任意跳转/自动提交（jumpToNode + getJumpableNodes + AutoApprovalRule） | 已完成 |
| 五期 | 委派+收回/转办（delegateTask + resolveDelegate + transferTask） | 已完成 |
| 六期 | 审批历史/流程图高亮（getApprovalHistory + getProcessDiagram） | 已完成 |
| 七期 | 事件监听器 SPI（8 种事件类型 + ProcessEventListener + AsyncEventPublisher） | 已完成 |

### 代码量统计

- **flowable-plus-core**：55 个 Java 源文件，7 个 Workflow 模块，约 2800 行核心逻辑
- **flowable-plus-spring-boot-starter**：8 个类，自动配置 + Spring Security 适配
- **flowable-plus-extension**：空壳，仅 `package-info.java`
- **测试**：core 14 个测试文件 + starter 6 个测试文件，总计约 21 个测试类
- **ADR**：10 个架构决策记录（但 0008 编号重复）
- **CI**：已有 GitHub Actions（Java 8 + Maven，x2 OS）

---

## 待处理事项

### P0 — 阻碍发布

#### 1. README.md 严重过时

**当前状态**：只写"处于开发阶段，已实现审批核心操作"，完全没有提到 Phase 2-7 的 28 个 API 方法。

**需要做的**：
- 列出全部 5 个操作接口（ApprovalOperations / CounterSignOperations / QueryOperations / DiagramOperations / HistoryOperations）的方法清单
- 每个核心场景给代码片段：发起流程、驳回、会签投票、或签设置、任意跳转、待办查询、审批历史、流程图
- 补充 Spring Boot 快速开始（引入 Maven 坐标、@Autowired FlowablePlus 即用）
- 补充配置项说明（`flowable.plus.enabled`, `flowable.plus.event.async`, `flowable.plus.countersign.enabled`）
- 补充 SPI 扩展点一览表（UserContext / ProcessEventListener / AutoApprovalRule / CounterSignCallback / ApproverResolver / GroupResolver / IdentityResolver / TaskQueryEnhancer）

**参考文件**：
- `CODEBUDDY.md` — 模块架构和依赖关系
- `CONTEXT.md` — 领域术语定义
- `docs/completion-condition.md` — completionCondition 表达式编写指南

#### 2. Extension 模块空壳

**当前状态**：`flowable-plus-extension` 仅有 `package-info.java`，无任何实现。但 README 和 CODEBUDDY 都把它作为正式模块列出。

**建议方案**：

**方案 A（推荐）— 删除模块**：
- 从父 POM 中移除 `<module>flowable-plus-extension</module>`
- 删除 `flowable-plus-extension/` 目录
- 更新 `CODEBUDDY.md` 和 `README.md` 中的模块结构和模块职责描述
- 优点：干净，不拖累

**方案 B — 填入内容**：
- 把 `docs/completion-condition.md` 中的 completionCondition 工厂方法放进去（CompletionConditionBuilder 工具类）
- 或放一些高级审批场景的辅助工具（如审批超时自动处理）
- 优点：模块有存在价值

**需要先确认**：选 A 还是 B。

#### 3. ADR 编号冲突

**当前状态**：`docs/adr/` 下有两个 `0008` 文件：
- `0008-auto-approval-rule-spi.md`
- `0008-optimistic-lock-retry.md`

**需要做的**：将 `0008-optimistic-lock-retry.md` 重命名为 `0010-optimistic-lock-retry.md`，并更新文件内序号和 `CODEBUDDY.md` 中的 ADR 表格。

---

### P1 — 发布前应做

#### 4. 缺少 CHANGELOG

**当前状态**：项目没有 CHANGELOG.md。

**需要做的**：创建 `CHANGELOG.md`，记录 1.0.0 版本的完整功能列表（按 7 个阶段组织）：
- 一期：发起/同意/驳回/撤回/撤销
- 二期：会签/或签/加签/减签
- 三期：待办/已办/流程摘要/审批轨迹/节点预览
- 四期：任意跳转/自动提交
- 五期：委派+收回/转办
- 六期：审批历史/流程图高亮
- 七期：事件监听器 SPI

#### 5. 全项目 Code Review

**项目规范要求**：`CODEBUDDY.md` 规定 "/implement 完成后必须执行 /code-review 双轴审查（Standards + Spec），审查通过后方可提交"

**需要做的**：运行 `/code-review` skill，对 `master` 的整个 diff 做 Standards（代码规范）+ Spec（是否正确实现 Issue #1 的功能规格）两轴审查。

#### 6. README 缺少 API 用法示例

不同于 P0#1（信息缺失），这是"有了信息但缺少增量的代码示例"。

**需要为以下场景补充代码片段**：
- 引入依赖（Maven）
- Spring Boot 自动配置（零配置使用）
- 发起流程
- 同意审批 + 驳回 + 驳回至发起人
- 撤回 + 撤销
- 会签投票 + 加签/减签
- 任意跳转 + 查询可跳转节点
- 待办/已办查询 + 批量流程摘要
- 审批历史 + 流程图
- 实现 ProcessEventListener 订阅事件
- 实现 AutoApprovalRule 自动审批
- 实现 UserContext 自定义身份注入

---

### P2 — 锦上添花

#### 7. Maven Central 发布配置

**需要的**：
- GPG 签名配置（`pom.xml` 中配置 `maven-gpg-plugin`）
- `settings.xml` 模板
- 发布命令文档（`mvn clean deploy -P release`）

#### 8. 事件系统缺少端到端集成测试

事件测试目前全是单元测试（mock），缺少真实 Flowable 引擎的集成测试。

**建议**：在 starter 模块的集成测试中加一个 `EventIntegrationTest`，真实启动 Flowable 引擎，执行完整审批流程，验证事件回调正确触发。

#### 9. 已有文档的可补充项

- `docs/completion-condition.md` 中 `nrOfInstances` 加签后不更新的说明存在歧义（原文说"不会自动更新"，但实际上 `nrOfInstances` 在加签后会增长）— 需核实并修正
- `CONTEXT.md` 中"退回"和"驳回"的关系需澄清（目前"退回"是"驳回"的 Avoid 词，但 Issue #1 中二者语义不同）

---

## 实施路线建议

```
Round 1: P0 修复（阻塞项）
  ├── 确认 extension 模块方案（问用户）
  ├── 重写 README.md
  ├── 删除/填充 extension 模块
  └── 修复 ADR-0008 编号冲突

Round 2: P1 收尾
  ├── 写 CHANGELOG.md
  ├── 运行 /code-review
  └── README 补充代码示例

Round 3: P2 可选
  ├── Maven Central 发布配置
  ├── 事件集成测试
  └── 文档勘误
```

---

## 关键文件索引

| 文件 | 用途 |
|------|------|
| `README.md` | 项目首页（过时） |
| `CODEBUDDY.md` | 项目结构 + 构建命令 + ADR 表 |
| `CONTEXT.md` | 领域术语表 |
| `CHANGELOG.md` | 不存在，需创建 |
| `flowable-plus-extension/` | 空壳模块 |
| `docs/adr/0008-*` | ADR 编号冲突 |
| `docs/completion-condition.md` | CompletionCondition 使用指南 |
| `.github/workflows/ci.yml` | CI 配置（已有） |

---

## 注意事项

- 所有 commit message 必须用中文（项目 memory 中有记录）
- README 应包含 Maven 中央仓库的 `groupId` 和 `artifactId`（`io.github.flowable.plus` / `flowable-plus-core` 等）
- 不要修改超过本节范围的代码——保持收尾工作聚焦
