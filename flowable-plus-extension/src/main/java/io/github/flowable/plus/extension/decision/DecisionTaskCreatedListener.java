package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.event.TaskCreatedEvent;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.spi.ProcessEventListener;
import org.flowable.bpmn.model.BaseElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 到点信号订阅（{@code #36} 决议 §一「到点信号与回调职责」）：实现 core
 * {@link ProcessEventListener#onTaskCreated(TaskCreatedEvent)}，<b>只做纯读门控 + 入队</b>。
 *
 * <p><b>回调内三格次序冻结</b>：全局开关 → 节点声明 → 入队。回调内<b>零引擎命令、零网络、零状态写入</b>
 * —— 本类不持任何引擎服务：节点声明从<b>装配面预热的声明面索引</b>（{@code nodeId → UserTask 元素}）
 * 纯读取得，未命中即按「未声明」fail-closed（<b>冷启动未命中是登记过的已知边界</b>：
 * 部署在扩展之前、或索引尚未预热时，本节点不触发）。</p>
 *
 * <p><b>为何是「声明面索引」而非运行期读模型</b>：{@code RepositoryService#getBpmnModel} 一类取证
 * 需要引擎命令 / I/O，而回调在流程事务内、且被冻结为「零引擎命令」。索引由装配面在部署期 / 启动期
 * 预热（部署期主闸本就在读同一份已解析的 {@code BpmnModel}）。</p>
 *
 * <p><b>键 = {@code nodeId}</b>（= 引擎的 {@code taskDefinitionKey}）：本机制的声明形态只允许
 * UserTask 级元素属性（非 UserTask 承载是部署期阻断项），故索引键即节点标识。</p>
 *
 * <p><b>不入队的三类</b>（皆属「不触发」，产出<b>零观测</b>）：全局开关关闭（未激活）、节点未声明
 * （关闭态）、节点显式禁用；声明字面量非法（主闸被绕过的部署）时<b>落一条 WARN 后跳过</b>
 * —— 显式降级，<b>不静默当「未声明」</b>，也不重复部署期校验（第二真相处处不写）。</p>
 *
 * <p><b>池满</b>：入队被拒 ⇒ 落 {@code OVERLOADED}（按政策未产出、<b>不落证据行</b>、不计错误）——
 * 承载面 = 结构化日志 + 独立计数；池满意味着<b>记录任务自身进不去执行器</b>，正合 ADR-0042 第 8 节
 * 第 6 条「失败记录自身写不下去时退到更外层的可见面」。</p>
 *
 * <p><b>事件面关闭不 fail-fast</b>：核心事件面关闭（{@code flowable.plus.event.enabled=false}）时
 * 监听器收不到回调，本机制取「<b>启动期 WARN + 登记为已知边界</b>」—— 该组合有正当用途
 * （只要推面不要拉面），且「未触发」属<b>关闭状态</b>而非静默。</p>
 */
public final class DecisionTaskCreatedListener implements ProcessEventListener {

    /** 观测面唯一 logger（名取自 {@link DecisionMetrics#LOGGER_NAME} 单一来源） */
    private static final Logger LOG = LoggerFactory.getLogger(DecisionMetrics.LOGGER_NAME);

    /** 全局启用开关（部署期配置状态，构造期定值；关 = 未激活、零观测） */
    private final boolean enabled;

    /** 专属有界线程池（本机制专属；不复用事件执行器） */
    private final Executor executor;

    /** 拉管线（池线程内的闸门链执行体） */
    private final DecisionPipeline pipeline;

    /** 声明面索引（{@code nodeId → UserTask 元素}；装配面预热，未命中即未声明） */
    private final Map<String, BaseElement> declaredNodeElements;

    /** 观测分发点（池满的 OVERLOADED 观测由此产出） */
    private final DecisionObservationEmitter observationEmitter;

    /**
     * 构造监听器。
     *
     * @param enabled              全局启用开关（部署期配置状态）
     * @param eventChannelEnabled  核心事件面是否启用（{@code false} ⇒ 启动期 WARN、<b>不</b> fail-fast）
     * @param executor             专属有界线程池，不得为 null
     * @param pipeline             拉管线，不得为 null
     * @param declaredNodeElements 声明面索引，不得为 null（可为空表 —— 冷启动即空表）
     * @param observationEmitter   观测分发点，不得为 null
     */
    public DecisionTaskCreatedListener(final boolean enabled,
                                       final boolean eventChannelEnabled,
                                       final Executor executor,
                                       final DecisionPipeline pipeline,
                                       final Map<String, BaseElement> declaredNodeElements,
                                       final DecisionObservationEmitter observationEmitter) {
        this.enabled = enabled;
        this.executor = Objects.requireNonNull(executor, "专属线程池不得为 null");
        this.pipeline = Objects.requireNonNull(pipeline, "拉管线不得为 null");
        this.declaredNodeElements = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(declaredNodeElements, "声明面索引不得为 null")));
        this.observationEmitter = Objects.requireNonNull(observationEmitter, "观测分发点不得为 null");
        if (!eventChannelEnabled) {
            // 事件面关闭 = 本机制收不到到点信号：不 fail-fast（有正当用途：只要推面不要拉面），
            // 只落一条启动期 WARN 并登记为已知边界。
            LOG.warn("决策到点事件面未启用（flowable.plus.event.enabled=false）：拉管线收不到回调，"
                    + "本机制按「未触发」处置 —— 登记为已知边界");
        }
    }

    @Override
    public void onTaskCreated(final TaskCreatedEvent event) {
        // stage 1：全局启用开关（未激活 ⇒ 不触发、零观测）
        if (!enabled) {
            return;
        }
        // stage 2：节点声明（纯读；索引未命中 = 未声明 ⇒ 关闭态、零观测）
        final BaseElement nodeElement = declaredNodeElements.get(event.getNodeId());
        if (nodeElement == null) {
            return;
        }
        final String rawEnabled = DecisionNodeDeclarationReader.declaredValue(nodeElement,
                DecisionNodeDeclaration.DECISION_ENABLED);
        final Boolean declaredEnabled = DecisionNodeDeclarationReader.parseEnabled(rawEnabled);
        if (declaredEnabled == null) {
            if (rawEnabled != null) {
                // 声明在场但取值非法（主闸被绕过的部署 / 运行期直改模型）⇒ 显式降级：留痕后跳过，
                // 既不静默当「未声明」，也不在此重复部署期校验。
                final String illegalNodeId = event.getNodeId();
                LOG.warn("节点声明的启用字面量非法，按显式降级处置（不触发）：nodeId={}", illegalNodeId);
            }
            return;
        }
        if (!declaredEnabled) {
            // 显式禁用 / 节点关：不触发、无记录（关闭态，非失败）
            return;
        }
        enqueue(event, nodeElement);
    }

    /**
     * 入队（池满 ⇒ {@code OVERLOADED}：日志 + 独立计数，<b>不落证据行</b>、不计错误）。
     *
     * @param event       到点事件
     * @param nodeElement 声明面索引命中的 UserTask 元素
     */
    private void enqueue(final TaskCreatedEvent event, final BaseElement nodeElement) {
        final String taskId = event.getTaskId();
        final String processInstanceId = event.getProcessInstanceId();
        try {
            executor.execute(() -> pipeline.pull(taskId, processInstanceId, nodeElement));
        } catch (RejectedExecutionException saturated) {
            // 有意的宽捕获：拒绝语义 = AbortPolicy，池满即本机制按设计工作（过载保护），故不计错误。
            final String saturatedNodeId = event.getNodeId();
            LOG.warn("决策专属线程池已满 ⇒ 落 OVERLOADED（不落证据行）：nodeId={}", saturatedNodeId);
            observationEmitter.emit(new DecisionObservation(
                    taskId,
                    event.getNodeId(),
                    processInstanceId,
                    DecisionOutcome.NO_SUGGESTION_BY_POLICY,
                    null,
                    DecisionPolicyReason.OVERLOADED,
                    DecisionSeverity.INFO,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
    }
}
