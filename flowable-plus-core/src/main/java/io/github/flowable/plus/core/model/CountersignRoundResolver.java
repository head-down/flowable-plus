package io.github.flowable.plus.core.model;

import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会签轮次解析器（C1 轮次状态机读写双实现收敛，2026-08-13）。
 *
 * <p>将 {@code csRoundIndex} 轮次概念在写侧（{@code CounterSignWorkflow}）与读侧
 * （{@code HistoryWorkflow}）的双实现收敛为<b>单一查询深模块</b>：轮次索引解析、
 * 多实例结束判定、已投票人解析、执行周期边界计算全部收敛至此。写侧打标
 * （{@code setVariableLocal}）与 modeA 检测（读 {@code countersignInitiator}）保留原位，
 * 因本类依赖面只有 HistoryService + TaskService。</p>
 *
 * <p>周期边界（{@link #resolveCycleBoundary}）是本类<b>唯一计算点</b>，所有周期限定查询
 * 共用；无缓存，保留每操作 2-3 次历史查询（grilling 决策 #4）。</p>
 *
 * <p>纯查询深模块（grilling 决策 #2）：5 个公共查询方法 + package-private
 * {@code resolveCycleBoundary}；不提供任何写操作。</p>
 *
 * @author flowable-plus
 */
public final class CountersignRoundResolver {

    /** Task 局部变量名：会签轮次索引 */
    public static final String CS_ROUND_INDEX_VAR = "csRoundIndex";

    private final HistoryService historyService;
    private final TaskService taskService;

    public CountersignRoundResolver(HistoryService historyService, TaskService taskService) {
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        this.historyService = historyService;
        this.taskService = taskService;
    }

    /**
     * 判断多实例节点本轮是否已结束。
     *
     * <p>原 {@code CounterSignWorkflow.isMultiInstanceFinished}，搬移时仅将
     * {@code task.getId()} 改为入参 {@code taskId}（唯一活跃任务 == 操作者自身判断）。</p>
     *
     * @param processInstanceId 流程实例 ID
     * @param activityId        任务定义 KEY
     * @param taskId            当前任务 ID（加签场景中操作者任务仍活跃）
     * @return true 如果本轮已结束（无活跃任务）
     */
    public boolean isRoundFinished(String processInstanceId, String activityId, String taskId) {
        long activeCount = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .active()
                .count();
        if (activeCount == 0) {
            return true;
        }
        // 排除当前任务自身（addCounterSigner 场景中当前任务仍活跃）
        if (activeCount == 1) {
            // 区分"伪单例"和"真正的最后一人"：
            // 伪单例（只有 1 人且无人已完成）：未完成
            // 真正最后一人（他人已完成，只剩当前任务）：即将完成
            // 已完成数按当前执行周期限定——折返（重新进入会签节点）后，
            // 上一周期的已完成任务不计入本轮，否则会误判"本轮即将结束"。
            Date cycleBoundary = resolveCycleBoundary(processInstanceId, activityId);
            long finishedCount = countFinishedInCurrentCycle(processInstanceId, activityId, cycleBoundary);
            if (finishedCount == 0) {
                return false;
            }
            Task sole = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .taskDefinitionKey(activityId)
                    .active()
                    .singleResult();
            if (sole != null && sole.getId().equals(taskId)) {
                // 唯一活跃任务 == 操作者自己（加签场景）：操作者任务仍活跃，本轮尚未结束。
                // 隐患 C 修复（2026-08-08）：无论操作者是否带 csRoundIndex，一律返回 false 并入当前轮。
                // 此前"无 csRoundIndex → 判定新一轮"的残留路径已消除——该路径在折返重建后
                // （多实例重建注入多人、owner 本轮未加签过）仍可达，会与单实例路径行为不一致。
                return false;
            }
            // 唯一活跃任务不是操作者自己（counterSign 场景，task 已完成）：本轮同样未结束。
            return false;
        }
        return false;
    }

    /**
     * 确定下一个会签轮次索引。
     * 查询历史 csRoundIndex Task 局部变量，按 taskDefinitionKey 过滤以避免跨节点污染，
     * 然后计算 max + 1。若无历史数据（老数据或首轮），返回 1（原始审批人轮次为隐式 0）。
     *
     * <p><b>执行周期限定</b>：折返（驳回/退回/跳转重新进入会签节点）会创建新的执行周期，
     * 轮次编号应在周期内重新计数。通过 {@link #resolveCycleBoundary} 确定当前周期
     * 的历史边界，仅统计边界之后的 csRoundIndex，避免沿用上一周期的全局 max。</p>
     *
     * @param processInstanceId 流程实例 ID
     * @param activityId        任务定义 KEY
     * @return 下一轮次索引（最小 1）
     */
    public int nextRoundIndex(String processInstanceId, String activityId) {
        Date cycleBoundary = resolveCycleBoundary(processInstanceId, activityId);

        // 按 taskDefinitionKey 获取所有历史任务 ID，用于 csRoundIndex 范围限定
        List<HistoricTaskInstance> tasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .list();

        java.util.Set<String> taskIds = tasks.stream()
                .filter(t -> isWithinCycle(t, cycleBoundary))
                .map(HistoricTaskInstance::getId)
                .collect(Collectors.toSet());

        if (taskIds.isEmpty()) {
            return 1;
        }

        // 查询所有 csRoundIndex，内存过滤到当前节点的 taskId
        List<HistoricVariableInstance> vars = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(CS_ROUND_INDEX_VAR)
                .list();

        int maxRound = 0;
        for (HistoricVariableInstance var : vars) {
            if (taskIds.contains(var.getTaskId()) && var.getValue() instanceof Integer) {
                maxRound = Math.max(maxRound, (Integer) var.getValue());
            }
        }
        return maxRound > 0 ? maxRound + 1 : 1;
    }

    /**
     * 确定当前会签轮次索引（非新轮次加签场景）。
     *
     * <p>策略：
     * <ol>
     *   <li>优先从当前节点活跃任务读取 csRoundIndex 运行时变量</li>
     *   <li>降级：从历史数据推断，nextRound - 1（nextRound 最小为 1，
     *       因此 currentRound 最小为 0，即原始审批人隐式轮次）</li>
     * </ol>
     *
     * @param processInstanceId 流程实例 ID
     * @param activityId        任务定义 KEY
     * @return 当前轮次索引（最小 0）
     */
    public int currentRoundIndex(String processInstanceId, String activityId) {
        // 1. 优先从活跃任务的 csRoundIndex 读取当前轮次
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .active()
                .list();
        for (Task t : activeTasks) {
            Object var = taskService.getVariableLocal(t.getId(), CS_ROUND_INDEX_VAR);
            if (var instanceof Integer) {
                return (Integer) var;
            }
        }
        // 2. 降级：活跃任务无 csRoundIndex（如原始审批人轮次隐式 0）
        // nextRoundIndex 最小返回 1 → currentRound = 0 ✓
        return nextRoundIndex(processInstanceId, activityId) - 1;
    }

    /**
     * 解析当前执行周期内、指定轮次已投过票的审批人集合（ADR-0024）。
     *
     * <p>判定口径：仅统计 {@link #resolveCycleBoundary} 限定周期内的同节点已完成任务；
     * 轮次匹配规则（{@link #matchesRound}）：{@code roundIndex > 0} 时要求任务局部变量
     * {@code csRoundIndex == roundIndex}；{@code roundIndex == 0} 时无 csRoundIndex
     * 或 {@code csRoundIndex == 0} 均视为隐式轮次 0（原始审批人/模式 B 固定会签未打标）。</p>
     *
     * <p><b>周期限定</b>修复折返后跨周期撞号误拦：上一周期已投票人的 csRoundIndex 可能与本周期
     * 撞号，按 startTime 限定周期后不参与本周期匹配（漏洞 B）。</p>
     *
     * <p><b>剔除被删除任务</b>：减签（{@code deleteMultiInstanceExecution}）也会留下 finished
     * 历史记录（{@code deleteReason} 非 null），被减签者从未投票，不应误判为"已投票"
     * （否则"减签后再加签回"被误拦）。</p>
     *
     * @param processInstanceId 流程实例 ID
     * @param activityId        任务定义 KEY
     * @param roundIndex        目标轮次
     * @return 该轮次已投过票的审批人集合
     */
    public Set<String> votedAssigneesInRound(String processInstanceId, String activityId,
                                             int roundIndex) {
        Date cycleBoundary = resolveCycleBoundary(processInstanceId, activityId);

        List<HistoricTaskInstance> finishedTasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId)
                .finished()
                .includeTaskLocalVariables()
                .list();

        return finishedTasks.stream()
                // 剔除被删除（减签/终止）的任务：deleteReason 非 null 表示从未投票，
                // 仅统计正常投票完成（deleteReason 为 null）的任务
                .filter(t -> t.getDeleteReason() == null)
                // 周期限定：仅统计当前执行周期内的任务
                .filter(t -> isWithinCycle(t, cycleBoundary))
                .filter(t -> matchesRound(t, roundIndex))
                .map(HistoricTaskInstance::getAssignee)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 查询 Task 局部变量 csRoundIndex，返回 taskId → roundIndex 映射。
     * Task 局部变量通过 {@code setVariableLocal(taskId, ...)} 设置，
     * 持久化到 ACT_HI_VARINST（关联 taskId，而非 executionId）。
     *
     * @param processInstanceId 流程实例 ID
     * @return taskId → roundIndex 映射
     */
    public Map<String, Integer> roundIndexByTaskId(String processInstanceId) {
        Map<String, Integer> result = new HashMap<>();

        List<HistoricVariableInstance> vars = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(CS_ROUND_INDEX_VAR)
                .list();

        for (HistoricVariableInstance var : vars) {
            String taskId = var.getTaskId();
            if (taskId != null && var.getValue() instanceof Integer) {
                result.put(taskId, (Integer) var.getValue());
            }
        }
        return result;
    }

    // ======================== 内部私有 ========================

    /**
     * 统计当前执行周期内（开始时间不早于 {@code cycleBoundary}）的已完成会签任务数。
     * 无周期边界（首个周期/老数据）时不做过滤，等价于历史全局计数。
     */
    private long countFinishedInCurrentCycle(String processInstanceId, String taskDefinitionKey,
                                             Date cycleBoundary) {
        List<HistoricTaskInstance> finished = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .finished()
                .list();
        if (cycleBoundary == null) {
            return finished.size();
        }
        return finished.stream()
                .filter(t -> isWithinCycle(t, cycleBoundary))
                .count();
    }

    /**
     * 判断历史任务是否属于当前执行周期（startTime 不早于 {@code cycleBoundary}）。
     * 周期边界为 null（无历史周期分隔/老数据）时不过滤；startTime 为 null（历史数据异常）
     * 视为早于周期边界，不计入当前周期。
     */
    private boolean isWithinCycle(HistoricTaskInstance task, Date cycleBoundary) {
        return cycleBoundary == null
                || (task.getStartTime() != null && !task.getStartTime().before(cycleBoundary));
    }

    /**
     * 轮次匹配：{@code roundIndex > 0} 时要求 {@code csRoundIndex == roundIndex}；
     * {@code roundIndex == 0} 时无标（缺失）或 == 0 均视为隐式轮次 0。
     */
    private boolean matchesRound(HistoricTaskInstance task, int roundIndex) {
        Map<String, Object> taskLocalVariables = task.getTaskLocalVariables();
        Object roundVar = taskLocalVariables != null
                ? taskLocalVariables.get(CS_ROUND_INDEX_VAR) : null;
        if (roundIndex > 0) {
            return roundVar instanceof Integer && ((Integer) roundVar).intValue() == roundIndex;
        }
        return roundVar == null
                || (roundVar instanceof Integer && ((Integer) roundVar).intValue() == 0);
    }

    /**
     * 确定当前执行周期的历史边界：按开始时间升序遍历历史任务，
     * 取最后一组连续同 {@code taskDefinitionKey} 任务中最早任务的开始时间。
     *
     * <p>折返重新进入会签节点后，新周期任务与上一周期之间必然隔着其它节点任务
     * （如 confirmTask），据此切分周期。当前周期内多次加签/轮次仍属同一周期，
     * 不会被拆分。无历史任务或无法切分时返回 {@code null}（不做过滤，兼容老数据）。</p>
     *
     * <p><b>建模约束（隐患 D，2026-08-08）</b>：周期切分依赖折返路径上存在
     * <b>非本节点 key 的中间任务</b>。若建模让会签节点<b>直接环回自己</b>（无中间节点），
     * 时间线上同 key 任务连续，边界会退化为全历史最早任务，导致周期重置失效、
     * 轮次沿用上一周期全局 max。折返路径应至少经过一个中间节点（如确认/回迁节点）；
     * 约束行为由单元测试 {@code testAddCounterSignerDirectLoopKeepsGlobalMaxRound} 固定。</p>
     *
     * <p>本方法为全类<b>唯一周期边界计算点</b>，所有周期限定查询共用。</p>
     *
     * @param processInstanceId  流程实例 ID
     * @param taskDefinitionKey  任务定义 KEY
     * @return 当前执行周期的历史边界（最早开始时间），无法切分时为 {@code null}
     */
    Date resolveCycleBoundary(String processInstanceId, String taskDefinitionKey) {
        List<HistoricTaskInstance> tasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime().asc()
                .list();

        Date boundary = null;
        boolean inCurrentRun = false;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            HistoricTaskInstance t = tasks.get(i);
            // startTime 为 null（历史数据异常）无法参与边界切分，跳过避免污染边界
            if (t.getStartTime() == null) {
                continue;
            }
            if (taskDefinitionKey.equals(t.getTaskDefinitionKey())) {
                // 从后向前持续更新：最终停留在本周期 run 中最早任务（周期起始点）
                inCurrentRun = true;
                boundary = t.getStartTime();
            } else if (inCurrentRun) {
                break;
            }
        }
        return boundary;
    }
}
