# ADR-0005：BPMN 模型加载使用独立缓存模块替代直接调用 RepositoryService

## 状态

已接受

## 日期

2026-07-03

## 背景

`FlowablePlus.resolveMultiInstance()` 和 `DefaultNodeFinder` 的 `findPreviousNodes()` / `findInitiatorNode()` 在每次调用时都通过 `repositoryService.getBpmnModel()` 重新加载 BPMN XML 并解析。这导致 5 个调用点各自穿透引擎 I/O：

- `resolveMultiInstance`：被 `assertNotMultiInstance`（同意、驳回、撤回）和 `counterSign` 调用
- `findPreviousNodes`：被驳回、撤回调用
- `findInitiatorNode`：被驳回至发起人、撤销调用

BPMN 模型在部署后不可变，重复加载纯属浪费。

## 选项

### 选项 A：在 DefaultNodeFinder 和 FlowablePlus 内部分别加缓存

- **优点**：改动极小，各自独立
- **缺点**：两处缓存逻辑重复，无法共享同一个缓存实例。`resolveMultiInstance` 和 `findPreviousNodes` 可能加载同一个 BPMN 模型，各自维护一份缓存副本。

### 选项 B：扩展 NodeFinder 接口添加 getBpmnModel() 方法

- **优点**：缓存成为接口的实现细节
- **缺点**：NodeFinder 接口变更影响所有实现者，且 `getBpmnModel()` 语义不属于"节点查找"职责范围

### 选项 C：独立缓存模块 BpmnModelCache

- **优点**：单一缓存实例供 FlowablePlus 和 DefaultNodeFinder 共享；接口不变（NodeFinder 签名不变，FlowablePlus 仅构造器增加参数）；缓存策略可独立替换
- **缺点**：FlowablePlus 构造函数签名变更（新增 BpmnModelCache 参数），属于破坏性变更

## 决策

**选择选项 C（独立缓存模块 BpmnModelCache）**。

### 理由

1. **单一共享缓存**：FlowablePlus 和 DefaultNodeFinder 经常加载相同的 BPMN 模型（同一个 `processDefinitionId`），共享一个缓存实例避免重复
2. **接口隔离**：NodeFinder 接口零变更，职责保持纯粹
3. **可替换性**：通过接口抽象，应用可注入自定义缓存策略（LRU、TTL 等）。默认实现基于 ConcurrentHashMap，BPMN 模型部署后不可变，永不过期
4. **现有代码零影响**：调用方仅需在构造时多传一个参数，现有业务逻辑不变

### 实现细节

```
BpmnModelCache (接口)
├── getBpmnModel(processDefinitionId) → BpmnModel
└── DefaultBpmnModelCache (ConcurrentHashMap + computeIfAbsent)
```

- 线程安全：基于 ConcurrentHashMap 的 `computeIfAbsent`
- 失效策略：永不过期（BPMN 模型部署后不可变）
- null 值不缓存：`computeIfAbsent` 遇到 null 不缓存，保证后续调用可重试

## 后果

- **正面**：BPMN 模型 I/O 从 O(N) 降为 O(1)，5 个调用点共享一处缓存；缓存行为可独立测试、独立替换
- **负面**：FlowablePlus 和 DefaultNodeFinder 构造函数签名变更，属于破坏性变更。项目尚未正式发布，影响可控
- **风险**：永不过期策略在热部署 BPMN 的场景下会导致旧模型残留。如需支持热部署，可在 DefaultBpmnModelCache 中增加失效方法或替换为 TTL 实现
