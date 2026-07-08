# Flowable Plus

Flowable Plus 是为中式工作流审批场景设计的 Flowable 引擎增强工具包，提供发起、同意、驳回、撤回、撤销等操作的高级封装。

## Language

**发起**:
发起人启动一个流程实例，产生初始用户任务。
_Avoid_: 启动、创建

**同意**:
审批人通过当前节点，流程向前推进。内部自动处理组任务认领。
_Avoid_: 批准、提交

**认领**:
候选人将组任务指定为自己处理，防止他人重复处理。通常无需手动调用。
_Avoid_: 抢单

**驳回**:
审批人不同意当前任务，退回至上一审批节点或发起人节点。
_Avoid_: 拒绝、退回

**撤回**:
已提交任务的审批人主动收回待办，阻止当前审批人处理。
_Avoid_: 收回、取回

**撤销**:
发起人取消整个流程实例，历史记录保留。
_Avoid_: 取消

**会签**:
同一审批节点上多个审批人并行审批，全部同意后流程才继续推进。基于 Flowable 原生多实例实现。
_Avoid_: 多人审批、并行审批

**或签**:
同一审批节点上多个审批人并行审批，任一同意后流程即推进。通过 BPMN completionCondition 与会签区分，API 层复用 counterSign。
_Avoid_: 竞签、抢签

**加签**:
会签/或签进行中由发起人动态追加审批人，通过 RuntimeService.addMultiInstanceExecution() 实现。
_Avoid_: 追加审批人

**减签**:
会签/或签进行中由发起人动态移除审批人，通过 RuntimeService.deleteMultiInstanceExecution() 实现。
_Avoid_: 移除审批人

**用户上下文**:
框架通过此 SPI 接口获取当前操作用户 ID，实现方可按需适配认证机制。
_Avoid_: 当前用户

**批量补充流程信息**:
输入一组流程实例 ID，批量返回运行时摘要信息，解决列表页 N+1 查询问题。
_Avoid_: 批量查询流程状态、流程实例批量查询

**待办**:
指定用户当前等待审批的任务列表，含分页支持和流程实例摘要。
_Avoid_: 我的任务、待审批、待处理

**已办**:
指定用户已完成审批的历史任务列表，含分页支持和时间范围过滤。
_Avoid_: 已审批、已完成、历史任务

**审批轨迹**:
流程实例的完整审批节点历史，按时间排列，含各节点审批人、时间、意见。
_Avoid_: 审批记录、流转记录

**下一节点审批人**:
发起流程前或审批过程中，查询当前任务下一节点的审批人列表，支持按 candidateGroup 展开成员。
_Avoid_: 下一审批人、后续审批人

**下一节点**:
当前任务可流转至的下游节点列表，用于审批页面展示分支选项。
_Avoid_: 下一任务节点、后续节点

**关键字搜索**:
待办/已办列表查询的 keyword 参数，对 businessKey 做模糊匹配（LIKE %keyword%）。不支持 processDefinitionName 模糊搜索——Flowable 原生 API 不提供此能力，且框架不提供业务关联表。业务标题等自定义字段的搜索，请通过 TaskQueryEnhancer 回调 + processVariableValueLike 自行实现。
_Avoid_: 全字段模糊搜索、标题搜索

**业务关联表**:
参考项目会维护 INSTANCE_BUSINESS 表存储业务标题、创建人等信息，在待办/已办查询中直接 JOIN + SQL LIKE 实现模糊搜索。flowable-plus 不提供此类业务表——保持框架中立，不与具体业务数据模型耦合。需要业务字段搜索的用户，可在发起流程时将业务标题等字段作为流程变量存入，查询时通过 TaskQueryEnhancer 回调中的 processVariableValueLike 实现。
_Avoid_: 业务表、INSTANCE_BUSINESS
