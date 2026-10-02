package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 拉管线（ADR-0042 第 2 节定案 8 / 第 7 节出站调用链 / 第 10 节运行护栏）：<b>闸门链与各阶段执行体</b>。
 *
 * <p><b>闸门链次序冻结</b>（{@code #36} 决议 §四；前两格由 {@link DecisionTaskCreatedListener} 承担）：</p>
 *
 * <pre>
 * 回调内（纯读）：  全局开关 → 节点声明 → 入队
 * 池线程内（本类）：锚点可见性检查（有界等待）→ 运行暂停 → 装配（空装配短路）
 *                  → 出域策略 → clamp → Provider 缝 → 入站加工 → 位点提交
 * </pre>
 *
 * <p><b>整段在流程事务之外</b>（本类只在专属有界池的线程上执行，不复用事件执行器）。</p>
 *
 * <p><b>最外层 {@code catch (Exception)} 绝不 rethrow</b>（{@code #36} 决议 §十二）：异步监听器异常在主仓
 * 无落点；<b>不 catch {@code Throwable}</b>（不吞 {@code Error}）。观测面 best-effort —— 其自身故障只落日志、
 * 绝不上抛、绝不改结局或流程状态。</p>
 *
 * <p><b>两个显式事实由本类算出交证据面</b>（状态驱动、<b>不枚举路径</b>）：
 * {@code hasOutboundPayload} = ¬空装配 ∧ 载荷非空 ∧ 策略放行；{@code hasInboundPayload} = 入站有内容 ∧
 * 策略判可落盘。</p>
 *
 * <p><b>凭据、clamp、入站加工、证据写入器由本类自持</b>（extension 包内实现细节）—— 构造面只取
 * extension 可见类型（引擎服务 / 公开契约类型 / 数值），使装配面不必命名内部件。</p>
 *
 * <p><b>一处实现期口径（如实披露）</b>：{@link SuggestionSubmission} 不承载「加工记录」（脱敏 / 截断）与
 * 「入站政策性不可落盘」两项事实，而写侧矩阵要求它们。故<b>产出且入站可落盘</b>的普通路径走位点服务
 * （共享产出契约 {@code #33} 边界 3），<b>入站政策性不可落盘</b>（{@code RESTRICTED}）的产出路径由本类
 * 直接物质化 —— 否则该信号会被折叠成 {@code NO_PAYLOAD}，与 ADR-0042 第 6 节「入站两类降级必须先分类型」
 * 相冲。</p>
 *
 * <p><b>重试的观测口径</b>：一次决策的失败<b>只落一行证据、只产一条观测</b>（{@code #36} 决议 §十三 行 8 /
 * 9 / 12 的「整次决策一行，值取最后一次尝试」）；中间尝试只落结构化日志，不逐次计入错误指标。</p>
 */
public final class DecisionPipeline {

    /** 观测面唯一 logger（名取自 {@link DecisionMetrics#LOGGER_NAME} 单一来源） */
    private static final Logger LOG = LoggerFactory.getLogger(DecisionMetrics.LOGGER_NAME);

    /** 锚点可见性有界等待的次数上限（回调先于事务提交，池线程须容忍「尚未可见」） */
    static final int ANCHOR_VISIBILITY_CHECKS = 5;

    /** 锚点可见性有界等待的轮询间隔（毫秒） */
    static final long ANCHOR_VISIBILITY_INTERVAL_MS = 200L;

    /** 毫秒 → 纳秒的换算因子 */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** 退避抖动的区间下界比例（取计算值的一半起算；保证有效且不退化为 0） */
    private static final int JITTER_LOWER_BOUND = 2;

    /** 「按政策未产出」证据行的文本兜底前缀（本类两处共用的单一来源）。 */
    private static final String POLICY_NARRATIVE_PREFIX = "按政策未产出：";

    /**
     * 出域载荷序列化器（{@code inputSnapshot} = 「模型实际看到的」那串的计量口径）。
     *
     * <p><b>为何是自有实例、不是容器注入</b>：extension 子包零 Spring 坐标（ADR-0042 第 2 节定案 5 /
     * ADR-0029 结构性约束），且本类只需要「载荷四段裸 JSON」这一默认形态。</p>
     */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    /** 装配器（单命令一致读；空装配是显式事实） */
    private final DecisionContextAssembler assembler;

    /** 任务服务：锚点存活检查与证据行写入 */
    private final TaskService taskService;

    /** 运行服务：流程实例存活判别（锚点检查 / 写入期降级归因） */
    private final RuntimeService runtimeService;

    /** 决策目标集（按 key 引用；收口与去重由装配面完成） */
    private final List<DecisionTarget> targets;

    /** 出域策略集（按 key 引用；收口与去重由装配面完成） */
    private final List<DecisionPolicy> policies;

    /** Provider 缝（整体替换点） */
    private final DecisionProvider provider;

    /** 运行暂停控制面（应用持有状态） */
    private final DecisionRuntimeControl runtimeControl;

    /** 位点服务（产出路径的准入与物质化；未产出两列由本类自行物质化） */
    private final SuggestionSubmissionService submissionService;

    /** 观测分发点（本类是拉面成功路径与未产出两列观测的构造点） */
    private final DecisionObservationEmitter observationEmitter;

    /** 证据写入器（extension 内部件，不入公开 API） */
    private final DecisionEvidenceWriter writer;

    /** 框架硬上限 clamp（出域 / 入站同一上限） */
    private final DecisionClamp clamp;

    /** 入站加工（只共用 clamp、不共用策略） */
    private final DecisionInboundProcessor inboundProcessor;

    /** 整次决策总预算（毫秒；含全部重试，绑定本次管线执行） */
    private final long totalBudgetMs;

    /** 尝试次数上限（1 + 重试） */
    private final int maxAttempts;

    /** 退避初值（毫秒） */
    private final long backoffInitialMs;

    /** 退避倍率 */
    private final double backoffMultiplier;

    /** 退避上限（毫秒） */
    private final long backoffMaxMs;

    /**
     * 构造拉管线。
     *
     * <p><b>构造面只取 extension 可见类型</b>：引擎服务、公开契约类型与数值；内部件（写入器 / clamp /
     * 入站加工）由本类自持。</p>
     *
     * @param assembler          决策上下文装配器，不得为 null
     * @param taskService        任务服务（锚点检查与证据行写入），不得为 null
     * @param runtimeService     运行服务（实例存活判别），不得为 null
     * @param targets            决策目标集，不得为 null（可为空集）
     * @param policies           出域策略集，不得为 null（可为空集）
     * @param provider           Provider 缝，不得为 null
     * @param runtimeControl     运行暂停控制面，不得为 null
     * @param submissionService  位点服务，不得为 null
     * @param observationEmitter 观测分发点，不得为 null
     * @param totalBudgetMs      整次决策总预算（毫秒），正数
     * @param maxAttempts        尝试次数上限，正数
     * @param backoffInitialMs   退避初值（毫秒），非负
     * @param backoffMultiplier  退避倍率，正数
     * @param backoffMaxMs       退避上限（毫秒），非负
     */
    public DecisionPipeline(final DecisionContextAssembler assembler,
                            final TaskService taskService,
                            final RuntimeService runtimeService,
                            final List<DecisionTarget> targets,
                            final List<DecisionPolicy> policies,
                            final DecisionProvider provider,
                            final DecisionRuntimeControl runtimeControl,
                            final SuggestionSubmissionService submissionService,
                            final DecisionObservationEmitter observationEmitter,
                            final long totalBudgetMs,
                            final int maxAttempts,
                            final long backoffInitialMs,
                            final double backoffMultiplier,
                            final long backoffMaxMs) {
        this.assembler = Objects.requireNonNull(assembler, "装配器不得为 null");
        this.taskService = Objects.requireNonNull(taskService, "任务服务不得为 null");
        this.runtimeService = Objects.requireNonNull(runtimeService, "运行服务不得为 null");
        this.targets = immutableCopy(targets, "决策目标集不得为 null");
        this.policies = immutableCopy(policies, "出域策略集不得为 null");
        this.provider = Objects.requireNonNull(provider, "Provider 缝不得为 null");
        this.runtimeControl = Objects.requireNonNull(runtimeControl, "运行暂停控制面不得为 null");
        this.submissionService = Objects.requireNonNull(submissionService, "位点服务不得为 null");
        this.observationEmitter = Objects.requireNonNull(observationEmitter, "观测分发点不得为 null");
        requirePositive(totalBudgetMs, maxAttempts, backoffInitialMs, backoffMultiplier, backoffMaxMs);
        this.totalBudgetMs = totalBudgetMs;
        this.maxAttempts = maxAttempts;
        this.backoffInitialMs = backoffInitialMs;
        this.backoffMultiplier = backoffMultiplier;
        this.backoffMaxMs = backoffMaxMs;
        this.writer = new DecisionEvidenceWriter();
        this.clamp = new DecisionClamp(DecisionClamp.MAX_PAYLOAD_BYTES, PAYLOAD_MAPPER);
        this.inboundProcessor = new DecisionInboundProcessor(this.clamp);
    }

    /**
     * 执行一次拉取（在专属有界池的线程上调用）。
     *
     * @param taskId            锚点任务标识（触发事件的 {@code taskId}）
     * @param processInstanceId 流程实例标识
     * @param nodeElement       承载节点声明的 BPMN 元素（回调内按 {@code nodeId} 纯读取得）
     */
    public void pull(final String taskId, final String processInstanceId, final BaseElement nodeElement) {
        final long startedNanos = System.nanoTime();
        try {
            pullInternal(taskId, processInstanceId, nodeElement, startedNanos);
        } catch (Exception unexpected) {
            // 有意的宽捕获（不是吞异常）：ADR-0042 第 8 节第 6 条与拉管线决议 §十二 要求管线最外层
            // catch (Exception) 且绝不 rethrow（异步监听器异常在主仓无落点）；不 catch Throwable（不吞 Error）。
            // 兜底归 INTERNAL_ERROR 并尽力留痕、尽力观测。
            LOG.warn("拉管线出现未归口的异常 ⇒ 兜底归 INTERNAL_ERROR：taskId={}", taskId);
            defendUnhandled(taskId, startedNanos);
        }
    }

    // ======================== 闸门链（池线程内） ========================

    private void pullInternal(final String taskId,
                              final String processInstanceId,
                              final BaseElement nodeElement,
                              final long startedNanos) {
        // ---- stage 3：锚点可见性检查（含事务可见性有界等待） ----
        final Task anchor = awaitAnchor(taskId, processInstanceId);
        if (anchor == null) {
            // 发起前无活锚点（含可见性等待超时）⇒ 不触发、无记录、不新增第四态
            return;
        }
        // 声明面取值：出域策略与决策目标的 key（读取口径与校验侧同源，不在管线里另写裁剪）
        final String policyKey = DecisionNodeDeclarationReader.declaredKey(nodeElement,
                DecisionNodeDeclaration.DECISION_POLICY);
        final String targetKey = DecisionNodeDeclarationReader.declaredKey(nodeElement,
                DecisionNodeDeclaration.DECISION_TARGET);
        // ---- stage 4：运行暂停（置于装配前以省掉装配开销） ----
        if (runtimeControl.isPaused()) {
            // 暂停 = 已激活但被暂止 ⇒ 留记录、不计错误
            finalizePolicy(anchor, DecisionPolicyReason.SUSPENDED, null, startedNanos);
            return;
        }
        // ---- stage 5：装配（零 token ⇒ 空装配是显式事实 ⇒ 短路） ----
        final DecisionAssemblyResult assembly;
        try {
            assembly = assembler.assemble(nodeElement, taskId, processInstanceId);
        } catch (RuntimeException assemblyFailure) {
            // 有意的宽捕获：装配器异常归 ASSEMBLY_FAILED / INTERNAL_ERROR（异常 message 不进观测面）
            LOG.warn("决策上下文装配失败 ⇒ 归 INTERNAL_ERROR：taskId={}", taskId);
            finalizeFailure(anchor, DecisionFailureKind.INTERNAL_ERROR, false, targetKey, null, null, null,
                    startedNanos);
            return;
        }
        final List<DecisionContextSource> dropped = assembly.getDroppedContextSources();
        if (assembly.isEmptyAssembly()) {
            // 零 token / 空装配：建模漏配（声明面），与策略拒绝（内容面）必须独立
            finalizePolicy(anchor, DecisionPolicyReason.NO_SOURCE_DECLARED, dropped, startedNanos);
            return;
        }
        // ---- stage 6：出域策略 ----
        final DecisionPolicy policy = findByKey(policies, policyKey, DecisionPolicy::key);
        if (policy == null) {
            // 运行期不自洽（注册表漂移 / 绕过校验的部署）⇒ 最后防御：阻断式不出域
            LOG.warn("节点声明引用的出域策略在运行期不可解析 ⇒ 阻断式不出域：policyKey={}", policyKey);
            finalizeFailure(anchor, DecisionFailureKind.INTERNAL_ERROR, false, targetKey, dropped, null, null,
                    startedNanos);
            return;
        }
        final DecisionOutboundResult outbound;
        try {
            outbound = policy.applyOutbound(assembly.getPayload());
        } catch (RuntimeException policyFailure) {
            // 有意的宽捕获：策略抛异常 = 系统故障、计错误（与「合规拒绝」严禁混用）
            LOG.warn("出域策略抛异常 ⇒ 归 INTERNAL_ERROR：taskId={}", taskId);
            finalizeFailure(anchor, DecisionFailureKind.INTERNAL_ERROR, false, targetKey, dropped, null, null,
                    startedNanos);
            return;
        }
        if (!outbound.isPermitted()) {
            // 主动拒绝 = 合规、不计错误（内容面，与空装配独立）
            finalizePolicy(anchor, DecisionPolicyReason.POLICY_REJECTED, dropped, startedNanos);
            return;
        }
        // ---- stage 7：框架硬上限 clamp（策略之后、Provider 缝之前） ----
        final DecisionPayload clamped;
        final boolean clampDropped;
        try {
            clamped = clamp.clampOutbound(outbound.getPayload());
            clampDropped = clamped != outbound.getPayload();
        } catch (DecisionClampRejectedException rejected) {
            // 丢到全空仍超限 ⇒ 兜底拒绝（硬闸 ⇒ 出站调用不发生，符合 I2）
            LOG.warn("出域载荷丢到全空仍超框架上限 ⇒ 归 INTERNAL_ERROR：taskId={}", taskId);
            finalizeFailure(anchor, DecisionFailureKind.INTERNAL_ERROR, false, targetKey, dropped, null, null,
                    startedNanos);
            return;
        }
        final String inputSnapshot = serializePayload(clamped);
        // ---- stage 8：Provider 缝（含同步重试 / 总预算 / 退避） ----
        final DecisionTarget target = findByKey(targets, targetKey, DecisionTarget::key);
        if (target == null) {
            LOG.warn("节点声明引用的决策目标在运行期不可解析 ⇒ 阻断式不出域：targetKey={}", targetKey);
            finalizeFailure(anchor, DecisionFailureKind.INTERNAL_ERROR, false, targetKey, dropped, inputSnapshot,
                    null, startedNanos);
            return;
        }
        final DecisionProviderResponse response = callProvider(target, clamped, anchor, startedNanos);
        if (response.getFailureKind() != null) {
            final String failureRawOutput = response.getFailureKind() == DecisionFailureKind.RESPONSE_UNPARSEABLE
                    ? response.getRawOutput()
                    : null;
            finalizeFailure(anchor, response.getFailureKind(), true, targetKey, dropped, inputSnapshot,
                    failureRawOutput, startedNanos);
            return;
        }
        if (response.getPolicyReason() != null) {
            // 本地短路（未发起出站调用）⇒ 按政策未产出、不计错误、无出处痕迹
            finalizeLocalShortCircuit(anchor, response, dropped, startedNanos);
            return;
        }
        if (Boolean.TRUE.equals(response.getDeclined())) {
            // 模型主动不产出：走过一次出站调用 ⇒ 出处组与 modelId 必填
            finalizeDeclined(anchor, response, dropped, startedNanos);
            return;
        }
        // ---- stage 9：入站加工（策略入站方法 → 共用 clamp） ----
        final DecisionInboundResult inbound;
        try {
            inbound = policy.applyInbound(response.getRawOutput());
        } catch (RuntimeException inboundFailure) {
            LOG.warn("入站策略抛异常 ⇒ 归 INBOUND_PROCESSING_FAILED：taskId={}", taskId);
            finalizeInboundFailure(anchor, response, dropped, inputSnapshot, startedNanos);
            return;
        }
        final boolean overLimit = inbound.isPersistable()
                && StringUtils.isNotEmpty(inbound.getRawOutput())
                && inboundProcessor.process(inbound.getRawOutput()) == null;
        if (overLimit) {
            LOG.warn("入站载荷超框架上限 ⇒ 归 INBOUND_PROCESSING_FAILED：taskId={}", taskId);
            finalizeInboundFailure(anchor, response, dropped, inputSnapshot, startedNanos);
            return;
        }
        // ---- stage 10：位点提交 ----
        submitProduced(anchor, response, inbound, outbound, clampDropped, dropped, inputSnapshot, startedNanos);
    }

    // ======================== 锚点可见性 ========================

    /**
     * 有界等待锚点可见（事务提交先于可见性：回调在提交前发生，池线程立刻查会看不见未提交的任务）。
     *
     * <p>超时仍不可见 ⇒ 返回 {@code null}（<b>不触发、无记录</b>，沿用 ADR-0042 第 10 节「发起前无活锚点」，
     * <b>不新增第四态</b>）。</p>
     *
     * @param taskId            锚点任务标识
     * @param processInstanceId 流程实例标识
     * @return 活锚点任务；等待耗尽仍不可见时为 null
     */
    private Task awaitAnchor(final String taskId, final String processInstanceId) {
        for (int attempt = 0; attempt < ANCHOR_VISIBILITY_CHECKS; attempt++) {
            // 活锚点 = 实例存活 ∧ 任务活跃（两者都要，故不合并成一次查询）
            final Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task != null && instanceAlive(processInstanceId)) {
                return task;
            }
            if (attempt + 1 < ANCHOR_VISIBILITY_CHECKS) {
                sleepQuietly(ANCHOR_VISIBILITY_INTERVAL_MS);
            }
        }
        return null;
    }

    /**
     * 流程实例是否存活（锚点检查与写入期降级归因共用）。
     *
     * <p>取 {@code singleResult()} 而非计数：存在性判定不用 {@code count}（个人规范的数据库面纪律，
     * 且此处只需「有没有」这一个事实）。</p>
     *
     * @param processInstanceId 流程实例标识
     * @return 存活返回 true；标识为空（缺锚点）返回 false
     */
    private boolean instanceAlive(final String processInstanceId) {
        if (StringUtils.isEmpty(processInstanceId)) {
            return false;
        }
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult() != null;
    }

    /**
     * 静默等待（睡眠被打断即提前结束）。
     *
     * @param millis 毫秒
     */
    private static void sleepQuietly(final long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            // 有意的宽捕获：专池线程被要求中止时恢复中断标记并提前结束本次等待（等待本身不承诺完整性），
            // 中断不是失败结局，故不落记录、不改结局。
            Thread.currentThread().interrupt();
        }
    }

    // ======================== Provider 缝调用（重试 / 预算 / 退避） ========================

    /**
     * 调用 Provider 缝：<b>同步、同一次管线执行内完成</b>（框架推导一次键、重试不重新生成），
     * 可重试集合 = 超时 / HTTP 错误 / 响应不可解析；总预算含全部重试，超预算即放弃整次决策且
     * {@code failureKind} 取<b>最后一次尝试</b>的值。
     *
     * @param target       决策目标
     * @param payload      已过策略与 clamp 的载荷
     * @param anchor       活锚点
     * @param startedNanos 本次管线执行的起点（总预算的基准）
     * @return 响应（失败时带 {@code failureKind}）
     */
    private DecisionProviderResponse callProvider(final DecisionTarget target,
                                                  final DecisionPayload payload,
                                                  final Task anchor,
                                                  final long startedNanos) {
        final long deadlineNanos = startedNanos + totalBudgetMs * NANOS_PER_MILLI;
        final String providerKey = target.key();
        DecisionProviderResponse lastResponse = DecisionProviderResponse.failed(DecisionFailureKind.INTERNAL_ERROR);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            final DecisionProviderResponse response;
            try {
                response = provider.send(new DecisionProviderRequest(target, payload));
            } catch (RuntimeException providerFailure) {
                // 有意的宽捕获：替换 Provider 缝后其失败类型不受框架限定 ⇒ 归 INTERNAL_ERROR（不可重试）
                LOG.warn("Provider 缝抛出异常 ⇒ 归 INTERNAL_ERROR：provider={}", providerKey);
                return DecisionProviderResponse.failed(DecisionFailureKind.INTERNAL_ERROR);
            }
            if (response == null) {
                LOG.warn("Provider 缝返回空响应 ⇒ 归 INTERNAL_ERROR：provider={}", providerKey);
                return DecisionProviderResponse.failed(DecisionFailureKind.INTERNAL_ERROR);
            }
            if (response.getFailureKind() == null) {
                return response;
            }
            lastResponse = response;
            final DecisionFailureKind failureKind = response.getFailureKind();
            if (!retryable(failureKind) || attempt == maxAttempts) {
                break;
            }
            final long delayMs = backoffDelayMs(attempt);
            if (System.nanoTime() + delayMs * NANOS_PER_MILLI > deadlineNanos) {
                // 总预算已不足以容纳下一次退避（更不必说一次尝试）⇒ 放弃整次决策，取最后一次尝试的值
                final String budgetAnchorId = anchor.getId();
                LOG.warn("决策总预算不足以容纳下一次尝试 ⇒ 放弃整次决策（取最后一次尝试的失败类别）：taskId={}",
                        budgetAnchorId);
                break;
            }
            // 中间尝试只落日志（一次决策只落一行证据、只产一条观测 ⇒ 不逐次计入错误指标）
            LOG.warn("决策出站调用尝试失败，将在退避后重试：provider={}，failureKind={}，attempt={}",
                    providerKey, failureKind, attempt);
            sleepQuietly(delayMs);
        }
        return lastResponse;
    }

    /**
     * 退避时长：指数退避 + 抖动，受退避上限约束。
     *
     * @param attempt 本次尝试序号（从 1 起）
     * @return 本次退避毫秒数
     */
    private long backoffDelayMs(final int attempt) {
        final double exponential = backoffInitialMs * Math.pow(backoffMultiplier, attempt - 1);
        final long capped = Math.min(backoffMaxMs, (long) exponential);
        if (capped < JITTER_LOWER_BOUND) {
            return capped;
        }
        // 抖动：取 [capped/2, capped] 区间内的一致随机值（削峰用；下界取一半，保证不退化为 0）
        final long lower = capped / JITTER_LOWER_BOUND;
        return lower + ThreadLocalRandom.current().nextLong(capped - lower + 1);
    }

    /** 可重试集合 = 超时 / HTTP 错误 / 响应不可解析（判据取结局映射表，不在缝内另立取值）。 */
    private static boolean retryable(final DecisionFailureKind failureKind) {
        // 同一 failureKind 可能对应多行产生点（如凭据失效的 401 与解析侧失败），
        // 取「对应行全部可重试」为真（任一行不可重试即视为不可重试）。
        return EnumSet.allOf(DecisionOutcomeMapping.class).stream()
                .filter(row -> row.getFailureKind() == failureKind)
                .allMatch(DecisionOutcomeMapping::isRetryable);
    }

    // ======================== 产出路径（位点提交） ========================

    /**
     * 产出路径：构造建议提交并交位点服务；<b>入站政策性不可落盘</b>时由本类直接物质化
     * （见类 javadoc 的实现期口径）。
     */
    private void submitProduced(final Task anchor,
                                final DecisionProviderResponse response,
                                final DecisionInboundResult inbound,
                                final DecisionOutboundResult outbound,
                                final boolean clampDropped,
                                final List<DecisionContextSource> dropped,
                                final String inputSnapshot,
                                final long startedNanos) {
        final SuggestionSubmission submission = producedSubmission(anchor, response, inbound, inputSnapshot);
        if (!inbound.isPersistable()) {
            final DecisionEvidenceDraft draft = DecisionEvidenceDraft.of(submission);
            draft.setOutboundPayloadPresent(true);
            draft.setOutboundRedacted(outbound.getRecord().isRedacted());
            draft.setOutboundTruncated(outbound.getRecord().isTruncated());
            draft.setOutboundClampDropped(clampDropped);
            // RESTRICTED = 有载荷但按政策不可落盘 ⇒ 载荷字段恒空、未加工（与「有落盘载荷」互斥）
            draft.setInboundPayloadPresent(false);
            draft.setRawOutput(null);
            draft.setInboundRestricted(true);
            draft.setInboundRedacted(inbound.getRecord().isRedacted());
            draft.setInboundTruncated(inbound.getRecord().isTruncated());
            if (writeRow(anchor, draft)) {
                emit(anchor, DecisionOutcome.SUGGESTION_PRODUCED, null, null,
                        response.getModelId(), response.getChainStage(), latencyMs(startedNanos),
                        response.getInputTokens(), response.getOutputTokens(), null, dropped);
            }
            return;
        }
        try {
            submissionService.submit(submission);
        } catch (SuggestionAdmissionException rejected) {
            // 准入拒绝的观测与失败行已由位点服务产出 ⇒ 管线只接住：不再重复产出观测、不再重复落行
            return;
        } catch (RuntimeException lastDefense) {
            // 有意的宽捕获：写入器最后防御（主闸放行、写入器拒绝）已由位点服务归 INTERNAL_ERROR 并落行
            // ⇒ 同样只接住（不静默，不重复归口）。
            return;
        }
        emit(anchor, DecisionOutcome.SUGGESTION_PRODUCED, null, null,
                response.getModelId(), response.getChainStage(), latencyMs(startedNanos),
                response.getInputTokens(), response.getOutputTokens(), null, dropped);
    }

    /** 产出提交样本（出处组三字段与 {@code modelId} 由拉面填好；直提双射由写侧保证）。 */
    private static SuggestionSubmission producedSubmission(final Task anchor,
                                                           final DecisionProviderResponse response,
                                                           final DecisionInboundResult inbound,
                                                           final String inputSnapshot) {
        return SuggestionSubmission.builder()
                .taskId(anchor.getId())
                // 拉面键 = 一元锚点 taskId（框架自造、不透明串、不规范化；同一次执行内推导一次、重试复用）
                .idempotencyKey(anchor.getId())
                .subjectType(DecisionSubjectType.AI)
                .suggestedAction(response.getSuggestedAction())
                .actionSummary(response.getActionSummary())
                .rationaleFacts(response.getRationaleFacts())
                .rationaleNarrative(response.getRationaleNarrative())
                .rawOutput(StringUtils.isNotEmpty(inbound.getRawOutput()) ? inbound.getRawOutput() : null)
                .modelId(response.getModelId())
                .inputSnapshot(inputSnapshot)
                .provider(response.getProvider())
                .chainStage(response.getChainStage())
                .degraded(response.getDegraded())
                .build();
    }

    // ======================== 未产出两列的物质化 ========================

    /** 「按政策未产出」列（C 列）的收口：落行 + 观测（框架侧原因，无 Provider 自报叙述）。 */
    private void finalizePolicy(final Task anchor,
                                final DecisionPolicyReason reason,
                                final List<DecisionContextSource> dropped,
                                final long startedNanos) {
        if (writePolicyRow(anchor, reason, null)) {
            emitPolicy(anchor, reason, dropped, startedNanos);
        }
    }

    /**
     * 本地短路（未出站）的 C 列收口：Provider 缝在<b>未发起出站调用</b>时按政策不产出
     * （载荷缺上下文 / 凭据不可用）。
     *
     * <p>与 {@link #finalizeDeclined} 恰好相反：本路径的出处组与 {@code modelId} 结构上全空
     * （未发生出站调用），<b>不计错误</b>；证据 VO 本就无 token 字段（token 只在观测面，本路径不产生）。</p>
     */
    private void finalizeLocalShortCircuit(final Task anchor,
                                           final DecisionProviderResponse response,
                                           final List<DecisionContextSource> dropped,
                                           final long startedNanos) {
        final DecisionPolicyReason reason = response.getPolicyReason();
        if (writePolicyRow(anchor, reason, response.getRationaleNarrative())) {
            emitPolicy(anchor, reason, dropped, startedNanos);
        }
    }

    /** C 列观测的单一构造点（token 恒不适用：未出站调用即无 usage）。 */
    private void emitPolicy(final Task anchor,
                            final DecisionPolicyReason reason,
                            final List<DecisionContextSource> dropped,
                            final long startedNanos) {
        emit(anchor, DecisionOutcome.NO_SUGGESTION_BY_POLICY, null, reason, null, null,
                latencyMs(startedNanos), null, null, null, dropped);
    }

    /** 模型主动不产出（C 列，走过一次出站调用 ⇒ 出处组与 {@code modelId} 必填）。 */
    private void finalizeDeclined(final Task anchor,
                                  final DecisionProviderResponse response,
                                  final List<DecisionContextSource> dropped,
                                  final long startedNanos) {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.NO_SUGGESTION_BY_POLICY)
                .policyReason(DecisionPolicyReason.MODEL_DECLINED)
                .idempotencyKey(anchor.getId())
                .subjectType(DecisionSubjectType.AI)
                .modelId(response.getModelId())
                .provider(response.getProvider())
                .chainStage(response.getChainStage())
                .degraded(response.getDegraded())
                .rationaleFacts(declinedFacts(response))
                .rationaleNarrative(declinedNarrative(response))
                .build();
        if (writeRow(anchor, draft)) {
            emit(anchor, DecisionOutcome.NO_SUGGESTION_BY_POLICY, null, DecisionPolicyReason.MODEL_DECLINED,
                    response.getModelId(), response.getChainStage(), latencyMs(startedNanos),
                    response.getInputTokens(), response.getOutputTokens(), null, dropped);
        }
    }

    /**
     * 失败列（D 列）的收口：落行 + 观测，或（行落不下时）走写入期降级槽位。
     *
     * <p><b>出处组 ⇔ 确已出站</b>（ADR-0042 第 8 节第 4 条 (iv)）：出处组与 {@code modelId} / token 一样，
     * 是「本次确已发起主链路出站调用」的外部痕迹 —— 已出站时整组填入（{@code provider} = 目标 key、
     * {@code chainStage = PRIMARY}、{@code degraded = false}）；<b>从未出站</b>的失败（装配失败 / 策略拒绝 /
     * clamp 拒绝 / 目标不可解析 / 未归口兜底）整组为 null，不制造未发生调用的痕迹。</p>
     *
     * @param targetKey          决策目标 key；不可解析时为 null
     * @param outboundDispatched 本次失败是否发生在框架<b>确已发起主链路出站调用</b>之后（同时决定证据面
     *                           出处组与观测的 {@code chainStage}：已出站 ⇒ 填值，从未出站 ⇒ 留空）
     * @param inputSnapshot      出域方向实际载荷（模型实际看到的串）；未发生出站调用时为 null
     * @param inboundRawOutput   入站方向原文（仅「响应不可解析」时可得）
     */
    private void finalizeFailure(final Task anchor,
                                 final DecisionFailureKind failureKind,
                                 final boolean outboundDispatched,
                                 final String targetKey,
                                 final List<DecisionContextSource> dropped,
                                 final String inputSnapshot,
                                 final String inboundRawOutput,
                                 final long startedNanos) {
        final boolean provenanceKnown = outboundDispatched && StringUtils.isNotEmpty(targetKey);
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .failureKind(failureKind)
                .idempotencyKey(anchor.getId())
                .subjectType(DecisionSubjectType.AI)
                .provider(provenanceKnown ? targetKey : null)
                .chainStage(provenanceKnown ? DecisionChainStage.PRIMARY : null)
                .degraded(provenanceKnown ? Boolean.FALSE : null)
                .build();
        if (StringUtils.isNotEmpty(inputSnapshot)) {
            draft.setOutboundPayloadPresent(true);
            draft.setInputSnapshot(inputSnapshot);
        }
        if (StringUtils.isNotEmpty(inboundRawOutput)) {
            draft.setInboundPayloadPresent(true);
            draft.setRawOutput(inboundRawOutput);
        }
        if (writeRow(anchor, draft)) {
            // 观测的链路阶段只认「确已出站」这一事实（不按失败名称粗分类）：已出站 ⇒ PRIMARY、失败响应
            // 无 modelId 可取 ⇒ 留空；从未出站的失败 ⇒ chainStage 亦留空 —— 与证据面的出处组同一判据。
            final DecisionChainStage observationChainStage =
                    outboundDispatched ? DecisionChainStage.PRIMARY : null;
            emit(anchor, DecisionOutcome.SUGGESTION_FAILED, failureKind, null, null, observationChainStage,
                    latencyMs(startedNanos), null, null, null, dropped);
        }
    }

    /**
     * 入站加工失败的产出态例外（{@code INBOUND_PROCESSING_FAILED} 是唯一可在产出态出现的失败类别；
     * 其与「策略抛异常 / clamp 超限」压成同一声明，观测面同样不可分 —— 不得据此判因）。
     */
    private void finalizeInboundFailure(final Task anchor,
                                        final DecisionProviderResponse response,
                                        final List<DecisionContextSource> dropped,
                                        final String inputSnapshot,
                                        final long startedNanos) {
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_PRODUCED)
                .failureKind(DecisionFailureKind.INBOUND_PROCESSING_FAILED)
                .idempotencyKey(anchor.getId())
                .subjectType(DecisionSubjectType.AI)
                .suggestedAction(response.getSuggestedAction())
                .actionSummary(response.getActionSummary())
                .rationaleFacts(response.getRationaleFacts())
                .rationaleNarrative(response.getRationaleNarrative())
                .modelId(response.getModelId())
                .provider(response.getProvider())
                .chainStage(response.getChainStage())
                .degraded(response.getDegraded())
                .build();
        draft.setOutboundPayloadPresent(true);
        draft.setInputSnapshot(inputSnapshot);
        draft.setInboundPayloadPresent(false);
        draft.setRawOutput(null);
        if (writeRow(anchor, draft)) {
            emit(anchor, DecisionOutcome.SUGGESTION_PRODUCED, DecisionFailureKind.INBOUND_PROCESSING_FAILED, null,
                    response.getModelId(), response.getChainStage(), latencyMs(startedNanos),
                    response.getInputTokens(), response.getOutputTokens(), null, dropped);
        }
    }

    /**
     * 未归口异常的兜底：尽力落一条 INTERNAL_ERROR 的 D 列行并观测（失败也不再上抛）。
     *
     * <p><b>不变量</b>：出站调用（{@code callProvider}）之后的各步骤均已就地收口异常（入站策略与位点提交
     * 各自接住、入站加工与草稿构造不抛），故本兜底<b>只会接住出站前的未归口异常</b> ⇒ 观测的
     * {@code chainStage} 恒留空（{@code outboundDispatched = false}）。若日后在出站后新增可抛步骤，
     * 须同步把「已出站」这一事实带到这里，否则观测会漏报链路阶段。</p>
     */
    private void defendUnhandled(final String taskId, final long startedNanos) {
        try {
            final Task anchor = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (anchor == null) {
                return;
            }
            finalizeFailure(anchor, DecisionFailureKind.INTERNAL_ERROR, false, null, null, null, null,
                    startedNanos);
        } catch (RuntimeException hopeless) {
            // 有意的宽捕获：兜底路径自身失败时观测面已是最后一道可见面，只落日志（不上抛、不改流程状态）
            LOG.warn("拉管线兜底路径自身失败（已无处可退）：taskId={}", taskId);
        }
    }

    // ======================== 证据行与观测 ========================

    /**
     * 写一行「按政策未产出」的证据（C 列）。
     *
     * <p>类型化依据的键按 {@code policyReason} 分支取（单一来源 = {@link DecisionEvidenceWriter#policyFactKeyOf}）；
     * 其值与该原因的文本兜底依据<b>同源</b> —— 皆取 {@link DecisionPolicyReason#getDescription() 可自诊中文描述}，
     * 使下游界面无需回查枚举名即可读懂（类型化事实值不是机器码）。文本兜底依据另可由调用方给
     * （本地短路时可带 Provider 自报的中文原因），缺省亦用该描述。</p>
     */
    private boolean writePolicyRow(final Task anchor,
                                   final DecisionPolicyReason reason,
                                   final String narrativeOverride) {
        final List<DecisionRationaleFact> facts = new ArrayList<>();
        facts.add(new DecisionRationaleFact(DecisionEvidenceWriter.policyFactKeyOf(reason), reason.getDescription()));
        return writeRow(anchor, DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.NO_SUGGESTION_BY_POLICY)
                .policyReason(reason)
                .idempotencyKey(anchor.getId())
                .subjectType(DecisionSubjectType.AI)
                .rationaleFacts(facts)
                .rationaleNarrative(StringUtils.isNotBlank(narrativeOverride)
                        ? narrativeOverride
                        : POLICY_NARRATIVE_PREFIX + reason.getDescription())
                .build());
    }

    /**
     * 物质化并落一行证据。
     *
     * <p><b>返回值 = 该草稿的结局是否已如实落行</b>（结果面判据，不得用前置面代替）：
     * {@code true} 表示原结局已落行，调用方可以按原结局发观测；{@code false} 表示原结局<b>没有</b>落行
     * （写入期降级，或草稿被写入器拒绝而改落 INTERNAL_ERROR 行）—— 两种情况都已在内部发过观测，
     * 调用方<b>不得</b>再报原结局。</p>
     */
    private boolean writeRow(final Task anchor, final DecisionEvidenceDraft draft) {
        final DecisionEvidenceVO evidence;
        try {
            evidence = writer.materialize(draft);
        } catch (IllegalArgumentException lastDefense) {
            // 有意的宽捕获：本类自造的行也应「写不出来即无出生路径」⇒ 归 INTERNAL_ERROR 的最小化失败行
            final String defenseAnchorId = anchor.getId();
            LOG.warn("拉管线自造的证据草稿被写入器拒绝 ⇒ 归 INTERNAL_ERROR：taskId={}", defenseAnchorId);
            return writeMinimalInternalErrorRow(anchor, draft);
        }
        try {
            taskService.addComment(anchor.getId(), anchor.getProcessInstanceId(),
                    DecisionEvidenceComment.COMMENT_TYPE.name(), writer.row(evidence));
            return true;
        } catch (RuntimeException writeFailure) {
            // 有意的宽捕获：ADR-0042 第 9 节第 10 条要求写入失败一律降级、绝不上抛，
            // 而引擎写入面的失败类型不受框架限定（异常 message 与堆栈也不进观测面）。
            emit(anchor, null, null, null, null, null, null, null, null,
                    classifyDegradation(anchor.getProcessInstanceId()), null);
            return false;
        }
    }

    /**
     * 最后防御的替代行：{@code SUGGESTION_FAILED} / {@code INTERNAL_ERROR}（零新增枚举值）。
     *
     * <p><b>归因 / 链路</b>：替代行不新增事实，其出处组直接承继被拒草稿（{@code provider} /
     * {@code chainStage} / {@code degraded} 取自 {@code refused}），观测随之取<b>同一来源</b> ——
     * 被拒草稿来自已出站的结局（产出 / 模型主动不产出 / 入站加工失败 / 出站失败）时
     * {@code chainStage} 非空，来自未出站的结局时为空。故替代证据行与观测行在这两个字段上恒同值。</p>
     */
    private boolean writeMinimalInternalErrorRow(final Task anchor, final DecisionEvidenceDraft refused) {
        final DecisionEvidenceDraft minimal = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .failureKind(DecisionFailureKind.INTERNAL_ERROR)
                .idempotencyKey(anchor.getId())
                .subjectType(refused.getSubjectType())
                .provider(refused.getProvider())
                .chainStage(refused.getChainStage())
                .degraded(refused.getDegraded())
                .build();
        try {
            final String rowText = writer.row(writer.materialize(minimal));
            taskService.addComment(anchor.getId(), anchor.getProcessInstanceId(),
                    DecisionEvidenceComment.COMMENT_TYPE.name(), rowText);
            // 链路阶段承继被拒草稿（与上面的替代证据行同值）；失败列 modelId 恒留空
            emit(anchor, DecisionOutcome.SUGGESTION_FAILED, DecisionFailureKind.INTERNAL_ERROR, null,
                    null, refused.getChainStage(), null, null, null, null, null);
        } catch (RuntimeException writeFailure) {
            emit(anchor, null, null, null, null, null, null, null, null,
                    classifyDegradation(anchor.getProcessInstanceId()), null);
        }
        // 原结局未落行（改落了 INTERNAL_ERROR），调用方不得再报原结局
        return false;
    }

    /** 写入期降级原因：运行期已无该流程实例 ⇒ {@code INSTANCE_ENDED}；其余 ⇒ {@code ANCHOR_LOST}。 */
    private WriteDegradedCause classifyDegradation(final String processInstanceId) {
        return instanceAlive(processInstanceId) ? WriteDegradedCause.ANCHOR_LOST : WriteDegradedCause.INSTANCE_ENDED;
    }

    /**
     * 决策观测的<b>单一构造点</b>（本类只构造拉面路径的观测；准入拒绝与写入期降级的观测由位点服务产）。
     *
     * <p><b>归因 / 链路的填值口径</b>：只在框架<b>确已发起主链路出站调用</b>时填值 ——
     * 产出 / 模型主动不产出 / 入站加工失败 / 出站失败四处带 {@code modelId}（可空，失败时响应业务字段全空）
     * 与 {@code chainStage}；未出站的结局（装配失败 / 策略拒绝 / clamp 拒绝 / 目标不可解析 / 暂停 /
     * 池满 / 未物质化）保持 {@code null}。判据是「是否已出站」这一事实，<b>不</b>按失败名称分类 ——
     * 使「取不到」与「不适用」在这两个字段上也可分。</p>
     */
    private void emit(final Task anchor,
                      final DecisionOutcome outcome,
                      final DecisionFailureKind failureKind,
                      final DecisionPolicyReason policyReason,
                      final String modelId,
                      final DecisionChainStage chainStage,
                      final Long latencyMs,
                      final Long inputTokens,
                      final Long outputTokens,
                      final WriteDegradedCause writeDegradedCause,
                      final List<DecisionContextSource> droppedContextSources) {
        observationEmitter.emit(new DecisionObservation(
                anchor.getId(),
                anchor.getTaskDefinitionKey(),
                anchor.getProcessInstanceId(),
                outcome,
                failureKind,
                policyReason,
                severityOf(outcome, failureKind, policyReason, writeDegradedCause),
                DecisionSubjectType.AI,
                modelId,
                chainStage,
                latencyMs,
                inputTokens,
                outputTokens,
                writeDegradedCause,
                null,
                droppedContextSources));
    }

    /**
     * 严重度：取结局映射表的唯一权威。
     *
     * <p>同一 {@code failureKind} 可能对应多行产生点（如凭据失效的 401 与解析侧失败），故取首个命中
     * —— 各行的严重度一致，行的区分在产生点而不在 severity。未物质化三行（三结局字段皆 null）恒 ERROR。</p>
     */
    private static DecisionSeverity severityOf(final DecisionOutcome outcome,
                                               final DecisionFailureKind failureKind,
                                               final DecisionPolicyReason policyReason,
                                               final WriteDegradedCause writeDegradedCause) {
        if (writeDegradedCause != null) {
            return DecisionSeverity.ERROR;
        }
        return EnumSet.allOf(DecisionOutcomeMapping.class).stream()
                .filter(row -> row.getOutcome() == outcome
                        && row.getFailureKind() == failureKind
                        && row.getPolicyReason() == policyReason)
                .findFirst()
                .map(DecisionOutcomeMapping::getSeverity)
                .orElse(DecisionSeverity.ERROR);
    }

    // ======================== 辅助 ========================

    /** 本次执行的耗时（毫秒）。 */
    private static long latencyMs(final long startedNanos) {
        return (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
    }

    /**
     * 按 key 取唯一命中。
     *
     * <p>装配面已保证 key 不重复（重复 key ⇒ 启动期 fail-fast），故取首个命中即是唯一命中
     * （{@code findFirst} 的理由）。</p>
     */
    private static <T> T findByKey(final List<T> candidates, final String key, final Function<T, String> keyReader) {
        if (StringUtils.isEmpty(key)) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> StringUtils.equals(keyReader.apply(candidate), key))
                .findFirst()
                .orElse(null);
    }

    /** 载荷 → 「模型实际看到的」串（四段无信封；序列化实现取 clamp 的包内共用入口）。 */
    private static String serializePayload(final DecisionPayload payload) {
        return new String(DecisionClamp.serialize(payload, PAYLOAD_MAPPER), StandardCharsets.UTF_8);
    }

    /** {@code MODEL_DECLINED} 行的类型化依据（响应未给依据时由框架按规则记录，值取可自诊中文描述）。 */
    private static List<DecisionRationaleFact> declinedFacts(final DecisionProviderResponse response) {
        if (response.getRationaleFacts() != null) {
            return response.getRationaleFacts();
        }
        return Collections.singletonList(new DecisionRationaleFact(DecisionRationaleFactKey.POLICY_RULE,
                DecisionPolicyReason.MODEL_DECLINED.getDescription()));
    }

    /** {@code MODEL_DECLINED} 行的文本兜底依据（C 列必填；响应未给时由框架按规则记录）。 */
    private static String declinedNarrative(final DecisionProviderResponse response) {
        return StringUtils.isNotBlank(response.getRationaleNarrative())
                ? response.getRationaleNarrative()
                : POLICY_NARRATIVE_PREFIX + DecisionPolicyReason.MODEL_DECLINED.getDescription();
    }

    /** 集合的不可修改副本（构造期收口：装配面后续改动不影响已建管线）。 */
    private static <T> List<T> immutableCopy(final List<T> values, final String nullMessage) {
        return Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, nullMessage)));
    }

    /** 数值入参的合法性（只校验「上限语义」的符号与量级关系，收口由装配面承担）。 */
    private static void requirePositive(final long totalBudgetMs,
                                        final int maxAttempts,
                                        final long backoffInitialMs,
                                        final double backoffMultiplier,
                                        final long backoffMaxMs) {
        if (totalBudgetMs <= 0 || maxAttempts <= 0 || backoffInitialMs < 0 || backoffMultiplier <= 0
                || backoffMaxMs < 0) {
            throw new IllegalArgumentException("拉管线数值入参非法：totalBudgetMs=" + totalBudgetMs
                    + "，maxAttempts=" + maxAttempts + "，backoffInitialMs=" + backoffInitialMs
                    + "，backoffMultiplier=" + backoffMultiplier + "，backoffMaxMs=" + backoffMaxMs);
        }
    }
}
