package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 位点服务的默认实现（ADR-0042 第 2 节定案 5 / 第 8 节第 4 条 / 第 9 节第 9 条）。
 *
 * <p><b>本类做四件事</b>：① 全局开关（构造期定值）关时 {@code submit} 为 <b>no-op 非失败</b>；
 * ② 同步、按序、首个失败即抛的 <b>准入校验</b>（11 条规则 → 闭集十三原因）；③ 准入通过的提交
 * 物质化为一条证据行（直提落 B 列、拉面落 A 列的判别由出处组结构性判定）；④ 带齐<b>三项前置</b>
 * （{@code taskId} ∧ {@code idempotencyKey} ∧ {@code subjectType} 皆非空）的准入失败
 * <b>先落行再抛</b>（{@code SUGGESTION_FAILED} / {@code SITE_ADMISSION_REJECTED}）。</p>
 *
 * <p><b>落行判据是状态驱动，不是路径驱动</b>：承诺与否只取决于该次提交是否带齐三项前置，
 * <b>不</b>取决于「第几条规则先失败」，也不由拒绝原因枚举反推。缺任一前置 ⇒ 不落行，退
 * 结构化日志 + 指标。</p>
 *
 * <p><b>三处判定点不在写入器</b>（ADR-0042 第 8 节第 4 条订正 / {@code #54} 的边界推入）：
 * ① 全局关的 no-op；② <b>锚点失效 / 实例已结束</b>走既有「写入期降级」槽位（接住 {@code addComment}
 * 的异常 ⇒ 日志 + 指标、<b>不成行</b>、不产生 {@code SUGGESTION_FAILED}）；③ 写入器构造期守卫的
 * <b>最后防御</b>（见下）。</p>
 *
 * <p><b>写入器的构造期守卫是防御面，本类的准入校验是主闸</b>：主闸未拦住而写入器拒绝（{@code IllegalArgumentException}），
 * 按「最后防御」口径归 {@code INTERNAL_ERROR} —— 物质化为一条 {@code SUGGESTION_FAILED} /
 * {@code INTERNAL_ERROR} 的失败行、发一条 ERROR 观测，并<b>上抛原异常</b>（不静默：调用方不得把
 * 一次未成功的提交读成成功）。</p>
 *
 * <p><b>持久化</b>：{@code TaskService#addComment(taskId, processInstanceId, TYPE_, message)}，其中
 * {@code TYPE_} 取 {@link DecisionEvidenceComment#COMMENT_TYPE} 的线上取值（与证据行标记同源），
 * {@code message} = 写入器交回的整行文本。<b>不传、不设任何 userId</b> —— 证据主体只走证据行的 JSON
 * 三字段（{@code ACT_HI_COMMENT.USER_ID_} 由调用线程的认证上下文决定，框架不能指定）。</p>
 *
 * <p><b>构造依赖只取 extension 可见类型</b>（starter 装配纪律）：core 的 {@link MultiInstanceDetector}
 * Bean + 引擎服务（{@link TaskService} / {@link RuntimeService}）+ extension 的观测分发点 + 开关定值；
 * 内部件（写入器 / 入站加工 / clamp）由本类自持，不进构造面。</p>
 */
public final class DefaultSuggestionSubmissionService implements SuggestionSubmissionService {

    /** 观测面唯一 logger（名取自 {@link DecisionMetrics#LOGGER_NAME} 单一来源） */
    private static final Logger LOG = LoggerFactory.getLogger(DecisionMetrics.LOGGER_NAME);

    /** 出处组的字段个数（三者同 null 或同非 null 的判据用） */
    private static final int PROVENANCE_GROUP_SIZE = 3;

    /**
     * 载荷序列化器（{@link DecisionClamp} 的计量口径入参）。
     *
     * <p><b>为何是自有默认实例、不是容器注入</b>：extension 子包<b>零 Spring 坐标</b>（ADR-0042 第 2 节定案 5 /
     * ADR-0029 结构性约束），注入容器实例在这条依赖方向上不可能；而本类<b>只走 clamp 的入站方向</b>
     * （{@link DecisionClamp#clampInbound(String)} 不触本序列化器），出域方向的计量口径归拉管线
     * （管线按构造缝注入自己的 mapper）。故此处取<b>默认配置</b>即够，无自定义序列化需求。</p>
     */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    /** 可用动作判定的三个事实来源（模型级 / 运行时多实例与折返后发起人决策任务） */
    private final MultiInstanceDetector multiInstanceDetector;

    /** 任务服务：锚点读取与证据行写入（写入的固有能力，非「反查引擎状态」） */
    private final TaskService taskService;

    /** 运行服务：写入期降级时判「实例是否已终结」（区分两个降级取值） */
    private final RuntimeService runtimeService;

    /** 观测分发点：日志 → 指标 → 观测回调三面的单一出口 */
    private final DecisionObservationEmitter observationEmitter;

    /** 证据写入器（extension 内部件，不入公开 API） */
    private final DecisionEvidenceWriter writer;

    /** 入站加工（只共用 clamp、不共用策略；直提的入站加工不经出域策略） */
    private final DecisionInboundProcessor inboundProcessor;

    /** 全局启用开关（部署期配置状态，构造期定值；关时提交为 no-op） */
    private final boolean enabled;

    /**
     * 构造位点服务。
     *
     * @param multiInstanceDetector 多实例检测（core Bean），不得为 null
     * @param taskService           任务服务，不得为 null
     * @param runtimeService        运行服务（写入期降级原因判别），不得为 null
     * @param observationEmitter    观测分发点，不得为 null
     * @param enabled               全局启用开关（构造期定值；{@code false} 时 {@code submit} 为 no-op）
     */
    public DefaultSuggestionSubmissionService(final MultiInstanceDetector multiInstanceDetector,
                                              final TaskService taskService,
                                              final RuntimeService runtimeService,
                                              final DecisionObservationEmitter observationEmitter,
                                              final boolean enabled) {
        this.multiInstanceDetector = Objects.requireNonNull(multiInstanceDetector,
                "多实例检测不得为 null：可用动作判定依赖它");
        this.taskService = Objects.requireNonNull(taskService, "任务服务不得为 null：锚点读取与证据行写入依赖它");
        this.runtimeService = Objects.requireNonNull(runtimeService,
                "运行服务不得为 null：写入期降级的原因判别依赖它");
        this.observationEmitter = Objects.requireNonNull(observationEmitter, "观测分发点不得为 null");
        this.writer = new DecisionEvidenceWriter();
        this.inboundProcessor = new DecisionInboundProcessor(
                new DecisionClamp(DecisionClamp.MAX_PAYLOAD_BYTES, PAYLOAD_MAPPER));
        this.enabled = enabled;
    }

    @Override
    public void submit(final SuggestionSubmission submission) {
        if (!enabled) {
            // 全局关：不触发、不留记录、非失败（不落行、不抛准入异常）—— 开关是部署期配置状态
            return;
        }
        Objects.requireNonNull(submission, "建议提交不得为 null");

        // 准入规则 ①–⑤：纯判定，不触引擎
        if (StringUtils.isEmpty(submission.getTaskId())) {
            throw reject(submission, SuggestionAdmissionReason.TASK_ID_REQUIRED, null);
        }
        if (StringUtils.isBlank(submission.getIdempotencyKey())) {
            throw reject(submission, SuggestionAdmissionReason.IDEMPOTENCY_KEY_REQUIRED, null);
        }
        if (submission.getSuggestedAction() == null) {
            throw reject(submission, SuggestionAdmissionReason.ACTION_REQUIRED, null);
        }
        if (StringUtils.isBlank(submission.getActionSummary())) {
            throw reject(submission, SuggestionAdmissionReason.ACTION_SUMMARY_REQUIRED, null);
        }
        if (!ComparableAction.isComparable(submission.getSuggestedAction())) {
            throw reject(submission, SuggestionAdmissionReason.ACTION_NOT_COMPARABLE, null);
        }

        // 准入规则 ⑥：动作与当前任务可用投票动作匹配（需要引擎读取；这是位点服务唯一的引擎读取点）
        final Task task = loadTask(submission.getTaskId());
        if (task == null) {
            // 任务不存在 ⇒ 走既有「锚点失效」槽位：日志 + 指标、不成行、不产生 SUGGESTION_FAILED
            degrade(WriteDegradedCause.ANCHOR_LOST, submission, null);
            return;
        }
        if (!isAvailableForTask(submission.getSuggestedAction(), task)) {
            throw reject(submission, SuggestionAdmissionReason.ACTION_NOT_AVAILABLE_FOR_TASK, task);
        }

        // 准入规则 ⑦–⑪：纯判定
        if (submission.getSubjectType() == null) {
            throw reject(submission, SuggestionAdmissionReason.SUBJECT_TYPE_REQUIRED, task);
        }
        if (!isProvenanceGroupConsistent(submission)) {
            throw reject(submission, SuggestionAdmissionReason.PROVENANCE_GROUP_INCONSISTENT, task);
        }
        if (isDirect(submission)) {
            if (submission.getModelId() != null) {
                throw reject(submission, SuggestionAdmissionReason.MODEL_ID_NOT_ALLOWED_ON_DIRECT, task);
            }
            if (submission.getInputSnapshot() != null) {
                throw reject(submission,
                        SuggestionAdmissionReason.INPUT_SNAPSHOT_NOT_ALLOWED_ON_DIRECT, task);
            }
            if (!containsFactKey(submission.getRationaleFacts(), DecisionRationaleFactKey.BASIS_CODE)) {
                // 直提准入：类型化依据须至少含一条 BASIS_CODE（不是「任意一条 rationaleFact」）
                throw reject(submission, SuggestionAdmissionReason.BASIS_CODE_REQUIRED, task);
            }
        }
        if (!isRationaleFactsValid(submission.getRationaleFacts())) {
            throw reject(submission, SuggestionAdmissionReason.RATIONALE_FACT_INVALID, task);
        }
        if (!isAttestedDataSourcesValid(submission.getAttestedDataSources())) {
            throw reject(submission, SuggestionAdmissionReason.ATTESTED_SOURCES_INVALID, task);
        }

        persistProducedRow(submission, task);
    }

    // ======================== 准入失败：先落行、再抛 ========================

    /**
     * 落定一次准入失败：先物质化失败行（<b>仅当带齐三项前置且锚点在手</b>），再发观测，最后交回待抛异常。
     *
     * <p><b>「已物质化」的判据落在结果面、不落在前置面</b>：报 {@code SUGGESTION_FAILED} 的前提是
     * <b>失败行真的落了</b>。故三种形态各自成一条观测：① 缺任一前置 ⇒ 不落行，原因落
     * {@code admissionReason}；② 前置带齐但锚点已失效（读取即落空 / 写入失败）⇒ 走既有「锚点失效」槽位
     * （{@code writeDegradedCause}，<b>不成行、不产生</b> {@code SUGGESTION_FAILED} 结局）；③ 前置带齐且
     * 行已落 ⇒ {@code SUGGESTION_FAILED} / {@code SITE_ADMISSION_REJECTED}。</p>
     *
     * @param submission 建议提交
     * @param reason     本次提交的首个失败原因
     * @param task       已加载的锚点任务；未加载时为 null（此时按需补一次读取以取 {@code processInstanceId}）
     * @return 由调用方 {@code throw} 的异常（携带闭集原因与锚点上下文）
     */
    private SuggestionAdmissionException reject(final SuggestionSubmission submission,
                                                final SuggestionAdmissionReason reason,
                                                final Task task) {
        if (!hasAllPreconditions(submission)) {
            emit(submission, null, null, null, reason, task);
        } else {
            final Task anchor = task != null ? task : loadTask(submission.getTaskId());
            if (anchor == null) {
                // 锚点读取即落空：提交已发生、写入期落不下 —— 结构性写入失败，不产生 SUGGESTION_FAILED
                degrade(WriteDegradedCause.ANCHOR_LOST, submission, null);
            } else if (persistFailureRow(submission, anchor)) {
                emit(submission, DecisionOutcome.SUGGESTION_FAILED,
                        DecisionFailureKind.SITE_ADMISSION_REJECTED, null, null, anchor);
            }
            // 行没落下 ⇒ 已由写入期降级槽位发过观测，此处不重复发「失败结局」
        }
        return new SuggestionAdmissionException(reason, submission.getTaskId());
    }

    /**
     * 落行判据 = 三项前置皆非空（{@code taskId} ∧ {@code idempotencyKey} ∧ {@code subjectType}）。
     *
     * <p>三项<b>各自成因、逐条附理由、不合并、不造上位词</b>：① {@code taskId} = <b>锚点</b>
     * （缺失时证据无处可挂）；② {@code idempotencyKey} = <b>可判别性</b>（无身份时无从判别，落记录会把
     * 同一第三方的反复重试变成无法归并的审计噪声）；③ {@code subjectType} = <b>判别式</b>
     * （矩阵 D 列要求必填，缺它写不出一条合法的失败行 —— 不得用占位值硬凑）。</p>
     */
    private static boolean hasAllPreconditions(final SuggestionSubmission submission) {
        return StringUtils.isNotEmpty(submission.getTaskId())
                && StringUtils.isNotBlank(submission.getIdempotencyKey())
                && submission.getSubjectType() != null;
    }

    /**
     * 失败行的物质化（D 列）。
     *
     * <p><b>出处组的携带规则</b>：只在出处组<b>完整</b>（三者同非 null）时原样搬入 —— 这样直提失败行的
     * 出处组全 null、拉面失败行必带 {@code provider}，ADR-0042 第 8 节第 4 条 (iv) 的双射在失败行上
     * 依然成立。<b>半填</b>的出处组（正是规则 ⑧ 拒绝的形态）既不是直提也不是拉面，写入器要求它
     * 「同 null 或同非 null」⇒ 无法构成合法的双射见证，故按矩阵的 {@code nullable} 落入 null；
     * 本次拒绝的权威信号是异常与观测面上的闭集原因，不由半填的出处组兼职表达。</p>
     *
     * @return 行真的落下返回 true；写入期降级（已成观测、不成行）返回 false
     */
    private boolean persistFailureRow(final SuggestionSubmission submission, final Task anchor) {
        final boolean provenanceComplete = provenancePresentCount(submission) == PROVENANCE_GROUP_SIZE;
        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .failureKind(DecisionFailureKind.SITE_ADMISSION_REJECTED)
                .idempotencyKey(submission.getIdempotencyKey())
                .subjectType(submission.getSubjectType())
                .subjectId(submission.getSubjectId())
                .subjectName(submission.getSubjectName())
                .provider(provenanceComplete ? submission.getProvider() : null)
                .chainStage(provenanceComplete ? submission.getChainStage() : null)
                .degraded(provenanceComplete ? submission.getDegraded() : null)
                .build();
        return writeRow(submission, anchor, writer.row(writer.materialize(draft)));
    }

    // ======================== 产出路径的写入与最后防御 ========================

    /**
     * 准入通过：把提交物质化为产出列（B 直提 / A 经出站调用）的证据行并持久化。
     *
     * <p><b>入站事实按入站加工的结果声明</b>（{@code #54} 的边界推入）：交上来的载荷经
     * {@link DecisionInboundProcessor#process(String)} 卫生与 clamp；<b>超限（返回 {@code null}）</b> ⇒
     * {@code inboundPayloadPresent = false} + {@code rawOutput = null} +
     * {@code failureKind = INBOUND_PROCESSING_FAILED}（该取值是产出态的唯一例外，双向守卫在写入器构造期强制）。</p>
     *
     * <p><b>出域事实</b>：直提列恒无载荷（{@code modelId} / {@code inputSnapshot} 已由准入规则 ⑨ 判空）；
     * 非直提列按 {@code inputSnapshot} 是否非空如实声明。</p>
     */
    private void persistProducedRow(final SuggestionSubmission submission, final Task anchor) {
        final String rawOutput = submission.getRawOutput();
        final String processed = inboundProcessor.process(rawOutput);
        final boolean inboundOverLimit = StringUtils.isNotEmpty(rawOutput) && processed == null;

        final DecisionEvidenceDraft draft = DecisionEvidenceDraft.of(submission);
        draft.setOutboundPayloadPresent(StringUtils.isNotEmpty(submission.getInputSnapshot()));
        draft.setInboundPayloadPresent(StringUtils.isNotEmpty(processed));
        draft.setRawOutput(StringUtils.isNotEmpty(processed) ? processed : null);
        if (inboundOverLimit) {
            draft.setFailureKind(DecisionFailureKind.INBOUND_PROCESSING_FAILED);
        }

        final DecisionEvidenceVO evidence;
        try {
            evidence = writer.materialize(draft);
        } catch (IllegalArgumentException defense) {
            // 最后防御：主闸（准入校验）未拦住而写入器拒绝 ⇒ 归 INTERNAL_ERROR，并上抛（不静默）
            lastDefense(submission, anchor, defense);
            return;
        }
        writeRow(submission, anchor, writer.row(evidence));
    }

    /**
     * 最后防御：把一次「主闸放行、写入器拒绝」的提交归 {@code INTERNAL_ERROR} ——
     * 物质化一条最小化失败行（不落被拒的内容体） + 发一条 ERROR 观测，随后<b>上抛原异常</b>。
     *
     * <p>不静默的理由：调用方（拉管线 / 应用）不得把一次未成功的提交读成成功；ADR-0042 第 8 节
     * 第 6 条的三条底线（不致命、不动流程状态、<b>不静默</b>）由此满足 —— 「不致命」= 不改变流程状态，
     * 上抛只把事实交给调用方的建议通道处理。</p>
     */
    private void lastDefense(final SuggestionSubmission submission, final Task anchor,
                             final IllegalArgumentException defense) {
        // 日志只载观测面允许的定位字段（taskId）：异常 message 与堆栈不在观测面禁载允许集内
        LOG.warn("位点提交触发写入器最后防御（主闸未拦住的非法态）⇒ 归 INTERNAL_ERROR：taskId={}",
                submission.getTaskId());
        final DecisionEvidenceDraft minimal = DecisionEvidenceDraft.builder()
                .outcome(DecisionOutcome.SUGGESTION_FAILED)
                .failureKind(DecisionFailureKind.INTERNAL_ERROR)
                .idempotencyKey(submission.getIdempotencyKey())
                .subjectType(submission.getSubjectType())
                .subjectId(submission.getSubjectId())
                .subjectName(submission.getSubjectName())
                .provider(submission.getProvider())
                .chainStage(submission.getChainStage())
                .degraded(submission.getDegraded())
                .build();
        emit(submission, DecisionOutcome.SUGGESTION_FAILED, DecisionFailureKind.INTERNAL_ERROR,
                null, null, anchor);
        writeRow(submission, anchor, writer.row(writer.materialize(minimal)));
        throw defense;
    }

    // ======================== 持久化与写入期降级 ========================

    /**
     * 落一行证据：{@code TYPE_} 取证据组线上取值（与标记同源），{@code message} = 整行文本。
     *
     * <p>写入失败（任务已消失 / 实例已终结）<b>不外抛</b>：降级为日志 + 指标，<b>不成行</b>、
     * 不产生 {@code SUGGESTION_FAILED}（ADR-0042 第 9 节第 10 条的两个写入失败槽位）。</p>
     *
     * <p><b>观测条数 ≠ 证据行数</b>：若这一行本身写不下去，降级观测与本次提交的其它事实
     * （如准入拒绝 / 最后防御）各自成一条 —— 它们都是「已触发的提交」的独立事实，
     * 不因同一次调用而合并。</p>
     *
     * @param submission 建议提交（降级观测的归因字段来源），不得为 null
     * @param anchor     锚点任务，不得为 null
     * @param row        整行文本
     * @return 行真的落下返回 true；写入期降级（已成观测、不成行）返回 false
     */
    private boolean writeRow(final SuggestionSubmission submission, final Task anchor, final String row) {
        try {
            taskService.addComment(anchor.getId(), anchor.getProcessInstanceId(),
                    DecisionEvidenceComment.COMMENT_TYPE.name(), row);
            return true;
        } catch (RuntimeException writeFailure) {
            // 有意的宽捕获（不是吞异常）：ADR-0042 第 9 节第 10 条要求写入失败一律降级、绝不上抛，
            // 而引擎写入面的失败类型不受框架限定 ⇒ 只能按 RuntimeException 兜底；降级本身由
            // degrade(...) 发一条 ERROR 观测（日志 + 指标 + 回调），故此处不另行记录异常 message
            // （观测面禁载异常 message 与堆栈）。
            degrade(classifyDegradation(anchor.getProcessInstanceId()), submission, anchor);
            return false;
        }
    }

    /**
     * 写入期降级原因：运行期已无该流程实例 ⇒ {@link WriteDegradedCause#INSTANCE_ENDED}；
     * 其余（锚点任务已消失而实例仍在）⇒ {@link WriteDegradedCause#ANCHOR_LOST}。
     */
    private WriteDegradedCause classifyDegradation(final String processInstanceId) {
        if (processInstanceId != null
                && runtimeService.createProcessInstanceQuery()
                        .processInstanceId(processInstanceId).count() == 0L) {
            return WriteDegradedCause.INSTANCE_ENDED;
        }
        return WriteDegradedCause.ANCHOR_LOST;
    }

    // ======================== 观测构造 ========================

    /**
     * 降级：发一条 ERROR 观测（日志 + 指标 + 回调三面同值），<b>不成行</b>。
     *
     * <p>两个入口：① 锚点读取即失败（此时无锚点，定位面只剩 {@code taskId}）；② 写入期失败
     * （此时锚点在手，{@code nodeId} / {@code processInstanceId} 一并可载）。</p>
     *
     * @param cause      降级原因（两个写入失败槽位之一）
     * @param submission 建议提交（归因字段来源），不得为 null
     * @param anchor     锚点任务；未加载 / 已消失时为 null
     */
    private void degrade(final WriteDegradedCause cause,
                         final SuggestionSubmission submission,
                         final Task anchor) {
        emit(submission, null, null, cause, null, anchor);
    }

    /**
     * 决策观测的<b>单一构造点</b>：发一条 ERROR 观测（日志 + 指标 + 回调三面同值）。
     *
     * <p><b>已物质化</b> ⇒ {@code outcome = SUGGESTION_FAILED} / {@code failureKind = SITE_ADMISSION_REJECTED}
     * （按该 kind 的 {@code severity = ERROR} 进告警并计入错误指标，与拉面对等）；
     * <b>未物质化</b>（缺任一前置）⇒ 三结局字段皆 null + {@code admissionReason} 承载原因；
     * <b>写入期降级</b> ⇒ 三结局字段皆 null + {@code writeDegradedCause} 承载原因
     * （未物质化的两族都无结局，不新增第四叶子态）。</p>
     */
    private void emit(final SuggestionSubmission submission,
                      final DecisionOutcome outcome,
                      final DecisionFailureKind failureKind,
                      final WriteDegradedCause cause,
                      final SuggestionAdmissionReason reason,
                      final Task task) {
        observationEmitter.emit(new DecisionObservation(
                submission.getTaskId(),
                task == null ? null : task.getTaskDefinitionKey(),
                task == null ? null : task.getProcessInstanceId(),
                outcome, failureKind, null, DecisionSeverity.ERROR,
                submission.getSubjectType(), submission.getModelId(), submission.getChainStage(),
                null, null, null,
                cause, reason, null));
    }

    // ======================== 判定辅助 ========================

    /** 加载锚点任务（位点服务唯一的引擎读取点；判可用动作与写入留痕都需要它）。 */
    private Task loadTask(final String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult();
    }

    /** 动作对当前任务可用（逐动作对齐 core 三个守卫的映射；见 {@link ComparableAction#isAvailableFor}）。 */
    private boolean isAvailableForTask(final ApprovalAction action, final Task task) {
        final PlusTask plusTask = PlusTask.from(task);
        return ComparableAction.isAvailableFor(action,
                multiInstanceDetector.isMultiInstance(plusTask),
                multiInstanceDetector.isRuntimeMultiInstance(plusTask),
                multiInstanceDetector.isInitiatorDecisionTask(plusTask));
    }

    /**
     * 出处组的已填字段数（0 / 1 / 2 / 3）。
     *
     * <p>出处组 = {@code provider} ∧ {@code chainStage} ∧ {@code degraded}；三个谓词
     * （{@link #isProvenanceGroupConsistent} / {@link #isDirect} / 失败行的携带规则）都由它导，
     * <b>不各写一遍</b>。</p>
     */
    private static int provenancePresentCount(final SuggestionSubmission submission) {
        int present = 0;
        if (submission.getProvider() != null) {
            present++;
        }
        if (submission.getChainStage() != null) {
            present++;
        }
        if (submission.getDegraded() != null) {
            present++;
        }
        return present;
    }

    /** 出处组双射：三者同 null 或同非 null（半填即非法态）。 */
    private static boolean isProvenanceGroupConsistent(final SuggestionSubmission submission) {
        final int present = provenancePresentCount(submission);
        return present == 0 || present == PROVENANCE_GROUP_SIZE;
    }

    /** 直提的判别式 = 出处组三字段全为 {@code null}。 */
    private static boolean isDirect(final SuggestionSubmission submission) {
        return provenancePresentCount(submission) == 0;
    }

    private static boolean containsFactKey(final List<DecisionRationaleFact> facts,
                                           final DecisionRationaleFactKey key) {
        if (facts == null) {
            return false;
        }
        return facts.stream().anyMatch(fact -> fact != null && fact.getKey() == key);
    }

    /** 类型化依据的元素合法性：键属闭集、值非空白；{@code null} 列表合法（可空）。 */
    private static boolean isRationaleFactsValid(final List<DecisionRationaleFact> facts) {
        if (facts == null) {
            return true;
        }
        return facts.stream().allMatch(fact -> fact != null
                && fact.getKey() != null
                && StringUtils.isNotBlank(fact.getValue()));
    }

    /** 自述位的三态与内容约束：{@code null}（位缺失）/ 空集合（显式空集）合法；有值态禁 null 元素、禁重复元素。 */
    private static boolean isAttestedDataSourcesValid(final List<?> sources) {
        if (sources == null) {
            return true;
        }
        final Set<Object> seen = new HashSet<>();
        return sources.stream().allMatch(source -> source != null && seen.add(source));
    }
}
