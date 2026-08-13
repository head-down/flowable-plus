package io.github.flowable.plus.core.model;

import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import io.github.flowable.plus.core.domain.PlusTask;

/**
 * 多实例检测模块，判断 BPMN 节点是否配置了多实例（会签/或签），
 * 并提供<b>运行时</b>多实例/伪单例判定。
 *
 * <p>模型判定（{@link #isMultiInstance} / {@link #isMultiInstanceNode}）复用
 * {@link BpmnModelCache} 加载 BPMN 模型，不直接访问引擎。
 * 运行时判定（{@link #isRuntimeMultiInstance} / {@link #isPseudoSingleton}）
 * 基于 TaskService + HistoryService 查询活跃任务数与全局历史任务数（ADR-0034）。</p>
 *
 * <p>普通节点在模型判定处短路，运行时判定仅对模型多实例节点产生 2 次额外查询
 * （活跃计数 + 历史计数），可接受。</p>
 *
 * @author flowable-plus
 */
public class MultiInstanceDetector {

    /** 流程实例级变量前缀：会签发起人，后接 taskDefinitionKey 实现多节点隔离（ADR-0035） */
    private static final String COUNTERSIGN_INITIATOR_VAR_PREFIX = "countersignInitiator_";

    private final BpmnModelCache bpmnModelCache;
    private final TaskService taskService;
    private final HistoryService historyService;

    public MultiInstanceDetector(BpmnModelCache bpmnModelCache,
                                 TaskService taskService, HistoryService historyService) {
        if (bpmnModelCache == null) {
            throw new IllegalArgumentException("BpmnModelCache 不可为 null");
        }
        if (taskService == null) {
            throw new IllegalArgumentException("TaskService 不可为 null");
        }
        if (historyService == null) {
            throw new IllegalArgumentException("HistoryService 不可为 null");
        }
        this.bpmnModelCache = bpmnModelCache;
        this.taskService = taskService;
        this.historyService = historyService;
    }

    /**
     * 判断任务是否为多实例子任务（会签/或签），仅依据 BPMN 模型。
     *
     * @param task 任务领域对象，不可为 null
     * @return true 如果对应 BPMN 节点配置了 multiInstanceLoopCharacteristics
     */
    public boolean isMultiInstance(PlusTask task) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(task.getProcessDefinitionId());
        return isMultiInstanceInternal(bpmnModel, task.getTaskDefinitionKey());
    }

    /**
     * 判断指定流程定义的节点是否为多实例（会签/或签），仅依据 BPMN 模型。
     *
     * @param processDefinitionId 流程定义 ID
     * @param taskDefinitionKey   任务定义 KEY
     * @return true 如果对应 BPMN 节点配置了 multiInstanceLoopCharacteristics
     */
    public boolean isMultiInstanceNode(String processDefinitionId, String taskDefinitionKey) {
        BpmnModel bpmnModel = bpmnModelCache.getBpmnModel(processDefinitionId);
        return isMultiInstanceInternal(bpmnModel, taskDefinitionKey);
    }

    /**
     * 判断任务是否为<b>运行时</b>多实例子任务（会签/或签），供常规操作拦截使用。
     *
     * <p>判据（ADR-0034）：BPMN 模型为多实例节点 <b>且</b> 运行时非伪单例。
     * 伪单例（模型为会签但运行时仅 1 个活跃子任务、且该节点全局历史任务数==1）
     * 放行常规审批操作；真多实例（含"会签剩最后 1 人未投"，历史任务数 &gt; 1）
     * 保持拦截，必须走 counterSign。</p>
     *
     * @param task 任务领域对象，不可为 null
     * @return true 如果对应 BPMN 节点配置了 multiInstanceLoopCharacteristics 且运行时非伪单例
     */
    public boolean isRuntimeMultiInstance(PlusTask task) {
        return isMultiInstance(task) && !isPseudoSingleton(task);
    }

    /**
     * 判断任务是否处于伪单例状态：活跃审批人仅 1 人，且该节点自进入以来从未出现过第二个任务。
     *
     * <p>判据：全局历史任务数（含活跃/已完成/被减签删除）== 1，即只有当前这一个活跃任务。
     * 与 finished 计数口径无关，因此：
     * <ul>
     *   <li>模式 A 伪单例首次加签：历史任务数 == 1 → 伪单例 ✓</li>
     *   <li>模式 B 固定会签减签至 1 人：历史任务数 &gt; 1 → 非伪单例，不会被误翻转 ✓</li>
     *   <li>模式 B 折返后新周期 1 人：全局历史任务数仍 &gt; 1（含上一周期）→ 非伪单例 ✓</li>
     *   <li>会签剩最后 1 人未投：他人已完成，历史任务数 &gt; 1 → 非伪单例 ✓</li>
     * </ul></p>
     *
     * @param task 任务领域对象，不可为 null
     * @return true 如果活跃任务数==1 且该节点全局历史任务数==1
     */
    public boolean isPseudoSingleton(PlusTask task) {
        long activeCount = taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .active()
                .count();
        if (activeCount != 1) {
            return false;
        }
        long historyTaskCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .count();
        return historyTaskCount == 1;
    }

    /**
     * 判断任务是否为<b>折返后发起人决策任务</b>：会签发起人单持的 MI 决策任务（ADR-0035）。
     *
     * <p>判据（与折返场景运行时特征一一对应）：
     * <ul>
     *   <li>BPMN 模型为多实例节点；</li>
     *   <li>活跃任务数 == 1（只有发起人一个）；</li>
     *   <li>该节点全局历史任务数 &gt; 1（含上一轮会签投票任务，排除伪单例）；</li>
     *   <li>当前任务 assignee == 流程变量 {@code countersignInitiator_<taskDefinitionKey>}。</li>
     * </ul></p>
     *
     * <p><b>识别变量</b>：使用 flowable-plus 自产流程变量（模式A下由
     * {@code CounterSignWorkflow.trySetCounterSignInitiator} 在首次加签时写入），
     * <b>无 fallback 裸变量</b>，不引入 SPI（上游仅有模式A动态会签，SPI 无使用者）。
     * 变量缺失（理论不可达）→ 不识别 → 保持拦截，属安全侧失败。</p>
     *
     * <p><b>模式A不变量（上游用法约定）</b>：发起人加签后其待办消失、不投票，因此
     * "会签剩最后 1 人未投"时最后一个未投票人不可能是发起人——该场景 assignee 是投票人
     * 而非发起人，不满足本判据，保持拦截（必须走 counterSign）。</p>
     *
     * @param task 任务领域对象，不可为 null
     * @return true 如果任务为折返后发起人决策任务
     */
    public boolean isInitiatorDecisionTask(PlusTask task) {
        if (!isMultiInstance(task)) {
            return false;
        }
        long activeCount = taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .active()
                .count();
        if (activeCount != 1) {
            return false;
        }
        long historyTaskCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .count();
        if (historyTaskCount <= 1) {
            return false;
        }
        Object initiator = taskService.getVariable(task.getId(),
                buildCountersignInitiatorVarName(task.getTaskDefinitionKey()));
        return initiator != null && initiator.toString().equals(task.getAssignee());
    }

    /**
     * 构建会签发起人流程变量名：{@code countersignInitiator_<taskDefinitionKey>}。
     *
     * <p>变量由 {@code CounterSignWorkflow} 在模式A首次加签时写入流程实例级，
     * 本方法统一变量命名约定，供识别折返后发起人决策任务复用（ADR-0035）。</p>
     *
     * @param taskDefinitionKey 任务定义 KEY
     * @return 会签发起人流程变量名
     */
    public static String buildCountersignInitiatorVarName(String taskDefinitionKey) {
        return COUNTERSIGN_INITIATOR_VAR_PREFIX + taskDefinitionKey;
    }

    private boolean isMultiInstanceInternal(BpmnModel bpmnModel, String taskDefinitionKey) {
        if (bpmnModel == null) {
            return false;
        }
        FlowElement flowElement = bpmnModel.getFlowElement(taskDefinitionKey);
        if (flowElement == null) {
            return false;
        }
        if (flowElement instanceof Activity) {
            Activity activity = (Activity) flowElement;
            return activity.getLoopCharacteristics() != null;
        }
        return false;
    }
}
