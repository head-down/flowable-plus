package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionPolicyReason;
import io.github.flowable.plus.core.event.TaskCreatedEvent;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.lang3.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E13 —— 拉管线的闸门链与降级兜底（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E13}）。
 *
 * <p><b>承哪些推入项</b>：{@code #36} 闸门链次序（{@code InOrder} 逐阶段 + 回调内零引擎命令 / 零网络 /
 * 零状态写入）+ 空装配短路 + 运行暂停（{@code NO_SUGGESTION_BY_POLICY} / {@code SUSPENDED}）+
 * 发起前锚点可见性检查（有界等待超时 ⇒ 不触发、无记录、不新增第四态）+ 专属有界池（池满 ⇒
 * 日志 + 独立计数、不落证据行）+ {@code catch (Exception)} 绝不 rethrow + 事件面关闭不 fail-fast。</p>
 *
 * <p><b>装配器用真件</b>（{@code DecisionContextAssembler} 是 final 的、且它的一致性读本身就是要件），
 * 引擎三服务（{@code ManagementService} / {@code RuntimeService} / {@code TaskService}）以 mock 隔离：
 * 这样「回调内零引擎命令」与「池线程内的阶段次序」都由<b>同一组</b>可观测替身承担。</p>
 */
class DecisionPipelineTest {

    /** 锚点任务标识（同时是拉面幂等键：一元锚点） */
    private static final String TASK_ID = DecisionFixtures.TASK_ID;

    /** 流程实例标识 */
    private static final String PROCESS_INSTANCE_ID = DecisionFixtures.PROCESS_INSTANCE_ID;

    /** 节点标识（= 引擎的 taskDefinitionKey） */
    private static final String NODE_ID = DecisionFixtures.NODE_ID;

    /** 声明引用的出域策略 key */
    private static final String POLICY_KEY = "policy-under-test";

    /** 声明引用的决策目标 key */
    private static final String TARGET_KEY = "target-under-test";

    /** 数值入参（本类取测试自定值，<b>不复制</b> starter 的默认值 —— 防第二处真相） */
    private static final long TOTAL_BUDGET_MS = 60_000L;

    /** 尝试次数上限（本类默认不重试，重试面另以自定值驱动） */
    private static final int MAX_ATTEMPTS = 3;

    /** 退避初值（0 ⇒ 无等待，测试确定性） */
    private static final long BACKOFF_INITIAL_MS = 0L;

    /** 退避倍率 */
    private static final double BACKOFF_MULTIPLIER = 2.0d;

    /** 退避上限 */
    private static final long BACKOFF_MAX_MS = 0L;

    /** 超限载荷的字节规模（> 框架硬上限，使 clamp 丢到全空仍超 ⇒ 兜底拒绝） */
    private static final int OVERSIZED_BYTES = DecisionClamp.MAX_PAYLOAD_BYTES + 1_000;

    private TaskService taskService;
    private RuntimeService runtimeService;
    private ManagementService managementService;
    private Task anchor;
    private DecisionPolicy policy;
    private DecisionProvider provider;
    private DecisionRuntimeControl runtimeControl;
    private SuggestionSubmissionService submissionService;
    private List<DecisionObservation> observations;
    private DecisionPipeline pipeline;

    @BeforeEach
    void setUp() {
        anchor = mock(Task.class);
        when(anchor.getId()).thenReturn(TASK_ID);
        when(anchor.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);
        when(anchor.getTaskDefinitionKey()).thenReturn(NODE_ID);

        final TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskQuery.taskId(anyString())).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(anchor);
        taskService = mock(TaskService.class);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);

        final ProcessInstanceQuery processInstanceQuery = mock(ProcessInstanceQuery.class);
        when(processInstanceQuery.processInstanceId(anyString())).thenReturn(processInstanceQuery);
        when(processInstanceQuery.singleResult()).thenReturn(mock(ProcessInstance.class));
        final Map<String, Object> variables = new LinkedHashMap<>();
        runtimeService = mock(RuntimeService.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);
        when(runtimeService.getVariables(anyString())).thenReturn(variables);
        when(taskService.getVariablesLocal(anyString())).thenReturn(new LinkedHashMap<>());

        managementService = mock(ManagementService.class);
        when(managementService.executeCommand(anyCommand()))
                .thenAnswer(invocation -> ((Command<?>) invocation.getArgument(0)).execute(null));

        policy = mock(DecisionPolicy.class);
        when(policy.key()).thenReturn(POLICY_KEY);
        when(policy.applyOutbound(any())).thenReturn(permitted(payload()));
        when(policy.applyInbound(anyString())).thenAnswer(invocation -> {
            // 缺省透传：有内容 ⇒ 可落盘且原样返回；无内容 ⇒ 不可落盘（与 DecisionPolicy 的缺省实现同型；
            // 返回值入参容忍空白，避免 Mockito 重打桩时以匹配器缺省值触发守卫）
            final String raw = invocation.getArgument(0);
            return new DecisionInboundResult(StringUtils.isNotEmpty(raw), raw, DecisionProcessingRecord.none());
        });
        provider = mock(DecisionProvider.class);
        when(provider.send(any())).thenReturn(producedResponse());
        runtimeControl = mock(DecisionRuntimeControl.class);
        submissionService = mock(SuggestionSubmissionService.class);

        observations = new ArrayList<>();
        pipeline = newPipeline(MAX_ATTEMPTS, BACKOFF_INITIAL_MS);
    }

    // ======================== 八条具名断言 ========================

    @Test
    @DisplayName("闸门链次序逐阶段成立：回调（开关 → 声明 → 入队）→ 锚点 → 暂停 → 装配 → 策略 → Provider → 提交")
    void stageOrderMatchesDeclaredChain() {
        final BaseElement nodeElement = declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true");
        final List<Runnable> enqueued = new ArrayList<>();
        final DecisionTaskCreatedListener listener = listener(Runnable -> enqueued.add(Runnable),
                Collections.singletonMap(NODE_ID, nodeElement), true, true);

        listener.onTaskCreated(event());
        assertThat(enqueued)
                .as("回调内两格（全局开关 → 节点声明）后即入队：一次叫号恰好一个池任务")
                .hasSize(1);

        enqueued.get(0).run();

        final InOrder poolOrder = inOrder(taskService, runtimeControl, managementService, policy, provider,
                submissionService);
        poolOrder.verify(taskService).createTaskQuery();
        poolOrder.verify(runtimeControl).isPaused();
        poolOrder.verify(managementService).executeCommand(anyCommand());
        poolOrder.verify(policy).applyOutbound(any());
        poolOrder.verify(provider).send(any());
        poolOrder.verify(submissionService).submit(any());

        assertThat(observations).as("成功路径的观测产生点在管线").hasSize(1);
        assertThat(observations.get(0).getOutcome()).isEqualTo(DecisionOutcome.SUGGESTION_PRODUCED);
    }

    @Test
    @DisplayName("回调内零引擎命令 / 零网络 / 零状态写入：只读门控 + 入队")
    void callbackPerformsNoEngineCommandNoNetworkNoStateWrite() {
        final BaseElement nodeElement = declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true");
        final List<Runnable> enqueued = new ArrayList<>();
        final DecisionTaskCreatedListener listener = listener(Runnable -> enqueued.add(Runnable),
                Collections.singletonMap(NODE_ID, nodeElement), true, true);

        listener.onTaskCreated(event());

        assertThat(enqueued).as("回调只入队，池任务尚未执行").hasSize(1);
        verifyNoInteractions(taskService, runtimeService, managementService, provider, policy, submissionService);
        assertThat(observations).as("回调内不产出任何观测（未触发类为零观测）").isEmpty();
    }

    @Test
    @DisplayName("空装配短路：零 token ⇒ 不调策略、不调 provider，落 NO_SOURCE_DECLARED")
    void emptyAssemblyShortCircuitsPipeline() throws IOException {
        // 显式空集（decisionDataSources=""）⇒ 装配器返「空装配」这一显式事实
        pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID, declaring("", POLICY_KEY, TARGET_KEY, "true"));

        verifyNoInteractions(policy);
        verifyNoInteractions(provider);
        verifyNoInteractions(submissionService);
        assertThat(singleObservation().getOutcome()).isEqualTo(DecisionOutcome.NO_SUGGESTION_BY_POLICY);
        assertThat(singleObservation().getPolicyReason()).isEqualTo(DecisionPolicyReason.NO_SOURCE_DECLARED);
        assertThat(singleObservation().getFailureKind()).as("按政策未产出是结论、不是异常").isNull();

        final JsonNode row = DecisionFixtures.evidenceJson(capturedRows(taskService).get(0));
        assertThat(DecisionFixtures.enumValue(row.get("policyReason"), DecisionPolicyReason.class))
                .as("空装配与策略拒绝必须独立承载")
                .isEqualTo(DecisionPolicyReason.NO_SOURCE_DECLARED);
        assertThat(row.get("inputSnapshot").isNull() && row.get("rawOutput").isNull())
                .as("C 列的载荷字段由矩阵钉死为 null")
                .isTrue();
    }

    @Test
    @DisplayName("运行暂停：落按政策未产出 + SUSPENDED，不计错误、不触装配与 Provider")
    void pauseYieldsPolicyReasonSuspended() throws IOException {
        when(runtimeControl.isPaused()).thenReturn(true);

        pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID, declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true"));

        verify(managementService, never()).executeCommand(anyCommand());
        verifyNoInteractions(policy);
        verifyNoInteractions(provider);
        assertThat(singleObservation().getOutcome()).isEqualTo(DecisionOutcome.NO_SUGGESTION_BY_POLICY);
        assertThat(singleObservation().getPolicyReason()).isEqualTo(DecisionPolicyReason.SUSPENDED);
        assertThat(singleObservation().getFailureKind()).as("暂停不计错误").isNull();

        final JsonNode row = DecisionFixtures.evidenceJson(capturedRows(taskService).get(0));
        assertThat(DecisionFixtures.enumValue(row.get("policyReason"), DecisionPolicyReason.class))
                .isEqualTo(DecisionPolicyReason.SUSPENDED);
    }

    @Test
    @DisplayName("锚点不可见（含可见性等待超时）⇒ 不触发、无记录、不新增第四态")
    void anchorInvisibilityDoesNotTriggerAndLeavesNoRecord() {
        when(taskService.createTaskQuery().taskId(anyString()).singleResult()).thenReturn(null);

        pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID, declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true"));

        assertThat(observations).as("未触发类产出零观测（不新增第四叶子态）").isEmpty();
        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
        verify(managementService, never()).executeCommand(anyCommand());
        verifyNoInteractions(provider);
        verifyNoInteractions(submissionService);
    }

    @Test
    @DisplayName("池满：日志 + 独立计数（OVERLOADED）、不落证据行、不计错误")
    void poolSaturationLeavesNoEvidenceRowButLogsAndCounts() {
        final Executor saturated = runnable -> {
            throw new RejectedExecutionException("专属线程池已满");
        };
        final DecisionTaskCreatedListener listener = listener(saturated,
                Collections.singletonMap(NODE_ID, declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true")),
                true, true);

        listener.onTaskCreated(event());

        assertThat(singleObservation().getPolicyReason()).as("池满 ⇒ OVERLOADED").isEqualTo(
                DecisionPolicyReason.OVERLOADED);
        assertThat(singleObservation().getOutcome()).isEqualTo(DecisionOutcome.NO_SUGGESTION_BY_POLICY);
        assertThat(singleObservation().getFailureKind()).as("过载保护是机制按设计工作，不计错误").isNull();
        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("绝不 rethrow：装配 / 策略 / clamp / Provider / 位点 / 写入任一处异常都不外抛")
    void neverRethrowsOnAnyException() {
        final String dataSources = "TASK_METADATA";

        // ① 装配器抛异常（引擎读失败）⇒ 归 ASSEMBLY_FAILED / INTERNAL_ERROR
        when(runtimeService.getVariables(anyString())).thenThrow(new IllegalStateException("引擎读失败"));
        assertThatCode(() -> pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID,
                declaring(dataSources, POLICY_KEY, TARGET_KEY, "true"))).doesNotThrowAnyException();
        assertThat(singleObservation().getFailureKind()).isEqualTo(DecisionFailureKind.INTERNAL_ERROR);
        nextScenario();

        // ② 策略抛异常 ⇒ 系统故障、计错误
        doThrow(new IllegalStateException("策略内部错误")).when(policy).applyOutbound(any());
        assertThatCode(() -> pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID,
                declaring(dataSources, POLICY_KEY, TARGET_KEY, "true"))).doesNotThrowAnyException();
        assertThat(singleObservation().getFailureKind()).isEqualTo(DecisionFailureKind.INTERNAL_ERROR);
        nextScenario();
        doReturn(permitted(payload())).when(policy).applyOutbound(any());

        // ③ 出域 clamp 兜底拒绝（丢到全空仍超限）
        doReturn(permitted(oversizedPayload())).when(policy).applyOutbound(any());
        assertThatCode(() -> pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID,
                declaring(dataSources, POLICY_KEY, TARGET_KEY, "true"))).doesNotThrowAnyException();
        assertThat(singleObservation().getFailureKind()).isEqualTo(DecisionFailureKind.INTERNAL_ERROR);
        nextScenario();
        doReturn(permitted(payload())).when(policy).applyOutbound(any());

        // ④ Provider 缝抛异常
        doThrow(new IllegalStateException("缝内部错误")).when(provider).send(any());
        assertThatCode(() -> pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID,
                declaring(dataSources, POLICY_KEY, TARGET_KEY, "true"))).doesNotThrowAnyException();
        nextScenario();
        doReturn(producedResponse()).when(provider).send(any());

        // ⑤ 位点服务抛准入异常（观测与失败行由位点服务产出 ⇒ 管线只接住）
        doThrow(new SuggestionAdmissionException(SuggestionAdmissionReason.ACTION_NOT_AVAILABLE_FOR_TASK, TASK_ID))
                .when(submissionService).submit(any());
        assertThatCode(() -> pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID,
                declaring(dataSources, POLICY_KEY, TARGET_KEY, "true"))).doesNotThrowAnyException();
        nextScenario();

        // ⑥ 位点服务上抛最后防御（写入器构造期守卫）⇒ 同样只接住
        doThrow(new IllegalArgumentException("写入器拒绝")).when(submissionService).submit(any());
        assertThatCode(() -> pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID,
                declaring(dataSources, POLICY_KEY, TARGET_KEY, "true"))).doesNotThrowAnyException();
        nextScenario();

        // ⑦ 自造的证据行写不下去（写入期降级：日志 + 指标、不成行、不上抛）
        doThrow(new RuntimeException("引擎拒绝写入")).when(taskService)
                .addComment(anyString(), anyString(), anyString(), anyString());
        assertThatCode(() -> pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID,
                declaring("", POLICY_KEY, TARGET_KEY, "true"))).doesNotThrowAnyException();
        assertThat(singleObservation().getOutcome())
                .as("写入期降级不产生失败结局（三结局字段皆 null）").isNull();
        assertThat(singleObservation().getWriteDegradedCause()).isNotNull();
    }

    @Test
    @DisplayName("事件面关闭不 fail-fast：构造期只落 WARN，监听器照常可装配")
    void closedEventChannelDoesNotFailFast() {
        final BaseElement nodeElement = declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true");
        final Map<String, BaseElement> index = Collections.singletonMap(NODE_ID, nodeElement);

        assertThatCode(() -> listener(Runnable::run, index, true, false)).doesNotThrowAnyException();
        assertThatCode(() -> listener(Runnable::run, index, false, false).onTaskCreated(event()))
                .doesNotThrowAnyException();
    }

    // ======================== 分支补齐：重试 / 未声明 / 显式禁用 / 非法字面量 ========================

    @Test
    @DisplayName("可重试失败在总预算内同步重试并复用同一次推导的键：失败整体只落一行")
    void retryableFailureIsRetriedSynchronouslyWithSamePayload() {
        when(provider.send(any()))
                .thenReturn(DecisionProviderResponse.failed(DecisionFailureKind.OUTBOUND_TIMEOUT))
                .thenReturn(producedResponse());

        pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID, declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true"));

        final ArgumentCaptor<DecisionProviderRequest> outboundCalls =
                ArgumentCaptor.forClass(DecisionProviderRequest.class);
        verify(provider, org.mockito.Mockito.times(2)).send(outboundCalls.capture());
        final List<DecisionProviderRequest> requests = outboundCalls.getAllValues();
        assertThat(requests).as("可重试失败 ⇒ 同一次管线执行内恰好两次尝试").hasSize(2);
        assertThat(requests.get(1).getPayload())
                .as("重试不重新生成载荷：两次尝试携带同一份（同引用）载荷")
                .isSameAs(requests.get(0).getPayload());
        assertThat(requests.get(1).getTarget())
                .as("重试复用同一决策目标（键不重新推导）")
                .isSameAs(requests.get(0).getTarget());

        final ArgumentCaptor<SuggestionSubmission> submissions =
                ArgumentCaptor.forClass(SuggestionSubmission.class);
        verify(submissionService).submit(submissions.capture());
        assertThat(submissions.getValue().getIdempotencyKey())
                .as("拉面键 = 一元锚点 taskId：同一次执行内推导一次、重试复用")
                .isEqualTo(TASK_ID);

        assertThat(observations)
                .as("整次决策一行：最终结局为产出（中间尝试只落日志）")
                .hasSize(1);
        assertThat(observations.get(0).getOutcome()).isEqualTo(DecisionOutcome.SUGGESTION_PRODUCED);
    }

    @Test
    @DisplayName("不可重试的失败不重试：凭据失效只尝试一次并落 401/403 归类")
    void nonRetryableFailureIsNotRetried() throws IOException {
        when(provider.send(any()))
                .thenReturn(DecisionProviderResponse.failed(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID));

        pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID, declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true"));

        verify(provider, org.mockito.Mockito.times(1)).send(any());
        assertThat(singleObservation().getFailureKind())
                .isEqualTo(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID);
        final JsonNode row = DecisionFixtures.evidenceJson(capturedRows(taskService).get(0));
        assertThat(DecisionFixtures.enumValue(row.get("failureKind"), DecisionFailureKind.class))
                .isEqualTo(DecisionFailureKind.OUTBOUND_CREDENTIAL_INVALID);
        assertThat(row.get("provider").asText())
                .as("拉面失败行必带 provider（出处组双射在失败行上成立）")
                .isEqualTo(TARGET_KEY);
    }

    @Test
    @DisplayName("节点未声明 / 显式禁用：回调即短路，零观测、零落行")
    void undeclaredOrDisabledNodeIsInertAtCallback() {
        final List<Runnable> enqueued = new ArrayList<>();
        final Executor capture = runnable -> enqueued.add(runnable);

        // 未声明：索引未命中（冷启动未命中同判 —— 登记过的已知边界）
        listener(capture, Collections.emptyMap(), true, true)
                .onTaskCreated(event());
        // 显式禁用：声明为 false
        listener(capture, Collections.singletonMap(NODE_ID,
                declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "false")), true, true)
                .onTaskCreated(event());
        // 全局开关关闭：未激活
        listener(capture, Collections.singletonMap(NODE_ID,
                declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true")), false, true)
                .onTaskCreated(event());

        assertThat(enqueued).as("三类都不入队").isEmpty();
        assertThat(observations).as("三类皆产出零观测").isEmpty();
        verifyNoInteractions(taskService, provider);
    }

    @Test
    @DisplayName("声明字面量非法：显式降级（留痕后跳过），不静默当「未声明」")
    void illegalEnabledLiteralDegradesExplicitly() {
        final List<Runnable> enqueued = new ArrayList<>();
        listener(runnable -> enqueued.add(runnable), Collections.singletonMap(NODE_ID,
                declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "TRUE")), true, true)
                .onTaskCreated(event());

        assertThat(enqueued).as("非法字面量不得被读成启用").isEmpty();
        assertThat(observations).as("跳过不发观测（降级的可见面是 WARN 日志）").isEmpty();
    }

    @Test
    @DisplayName("入站两降级先分类型：政策性不可落盘取 RESTRICTED（不计错误）；超限归 INBOUND_PROCESSING_FAILED")
    void inboundDegradationsAreTypedSeparately() throws IOException {
        // ① 策略判政策性不可落盘 ⇒ RESTRICTED、failureKind = null（不计错误）
        when(policy.applyInbound(anyString()))
                .thenReturn(new DecisionInboundResult(false, null, DecisionProcessingRecord.none()));
        pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID, declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true"));

        assertThat(singleObservation().getOutcome()).isEqualTo(DecisionOutcome.SUGGESTION_PRODUCED);
        assertThat(singleObservation().getFailureKind()).as("政策性不可落盘不计错误").isNull();
        JsonNode row = DecisionFixtures.evidenceJson(capturedRows(taskService).get(0));
        assertThat(DecisionFixtures.enumValue(row.get("inboundCompleteness"),
                io.github.flowable.plus.core.enums.DecisionCompleteness.class))
                .as("政策性不可落盘取 RESTRICTED（与入站加工失败分类型）")
                .isEqualTo(io.github.flowable.plus.core.enums.DecisionCompleteness.RESTRICTED);
        assertThat(row.get("rawOutput").isNull()).as("RESTRICTED ⇒ 载荷字段恒空").isTrue();
        clearInvocations(taskService);
        observations.clear();

        // ② 入站载荷超限（clamp 返回 null）⇒ INBOUND_PROCESSING_FAILED（产出态唯一破例，计错误）
        doReturn(new DecisionInboundResult(true,
                StringUtils.repeat('x', DecisionClamp.MAX_PAYLOAD_BYTES + 1), DecisionProcessingRecord.none()))
                .when(policy).applyInbound(anyString());
        pipeline.pull(TASK_ID, PROCESS_INSTANCE_ID, declaring("TASK_METADATA", POLICY_KEY, TARGET_KEY, "true"));

        assertThat(singleObservation().getOutcome()).isEqualTo(DecisionOutcome.SUGGESTION_PRODUCED);
        assertThat(singleObservation().getFailureKind())
                .isEqualTo(DecisionFailureKind.INBOUND_PROCESSING_FAILED);
        row = DecisionFixtures.evidenceJson(capturedRows(taskService).get(0));
        assertThat(row.get("rawOutput").isNull()).as("超限 ⇒ 载荷字段恒空").isTrue();
        assertThat(DecisionFixtures.enumValue(row.get("inboundCompleteness"),
                io.github.flowable.plus.core.enums.DecisionCompleteness.class))
                .isEqualTo(io.github.flowable.plus.core.enums.DecisionCompleteness.NO_PAYLOAD);
    }

    // ======================== 驱动辅助 ========================

    /** 构造管线（数值可调，供重试面用自定值）。 */
    private DecisionPipeline newPipeline(final int maxAttempts, final long backoffInitialMs) {
        final DecisionTarget target = new DecisionTarget() {

            @Override
            public String key() {
                return TARGET_KEY;
            }

            @Override
            public String url() {
                return "https://decision.example.invalid/v1/suggest";
            }
        };
        return new DecisionPipeline(
                new DecisionContextAssembler(managementService, runtimeService, taskService,
                        DecisionDefaultContextSources.empty()),
                taskService, runtimeService,
                Collections.singletonList(target), Collections.singletonList(policy),
                provider, runtimeControl, submissionService,
                new DecisionObservationEmitter(null, Collections.singletonList(observations::add)),
                TOTAL_BUDGET_MS, maxAttempts, backoffInitialMs, BACKOFF_MULTIPLIER, BACKOFF_MAX_MS);
    }

    /** 构造监听器（可换执行器 / 索引 / 两个开关）。 */
    private DecisionTaskCreatedListener listener(final Executor executor,
                                                 final Map<String, BaseElement> declaredNodeElements,
                                                 final boolean enabled,
                                                 final boolean eventChannelEnabled) {
        return new DecisionTaskCreatedListener(enabled, eventChannelEnabled, executor, pipeline,
                declaredNodeElements,
                new DecisionObservationEmitter(null, Collections.singletonList(observations::add)));
    }

    /** 到点事件（字段取固定 fixture）。 */
    private static TaskCreatedEvent event() {
        return TaskCreatedEvent.of(TASK_ID, PROCESS_INSTANCE_ID, "决策任务", NODE_ID, null, new Date());
    }

    /** 承载节点声明的元素桩（四个属性的声明原值按入参）。 */
    private static BaseElement declaring(final String dataSources,
                                         final String policyKey,
                                         final String targetKey,
                                         final String enabled) {
        final BaseElement element = mock(BaseElement.class);
        when(element.getId()).thenReturn(NODE_ID);
        when(element.getAttributeValue(DecisionNodeDeclaration.NAMESPACE_URI,
                DecisionNodeDeclaration.DECISION_DATA_SOURCES)).thenReturn(dataSources);
        when(element.getAttributeValue(DecisionNodeDeclaration.NAMESPACE_URI,
                DecisionNodeDeclaration.DECISION_POLICY)).thenReturn(policyKey);
        when(element.getAttributeValue(DecisionNodeDeclaration.NAMESPACE_URI,
                DecisionNodeDeclaration.DECISION_TARGET)).thenReturn(targetKey);
        when(element.getAttributeValue(DecisionNodeDeclaration.NAMESPACE_URI,
                DecisionNodeDeclaration.DECISION_ENABLED)).thenReturn(enabled);
        return element;
    }

    /** 四段全声明的载荷。 */
    private static DecisionPayload payload() {
        return new DecisionPayload(Collections.singletonMap("amount", 1), Collections.emptyMap(),
                new TaskMetadata(TASK_ID, "决策任务", NODE_ID, null, null),
                new ProcessInstanceMetadata(PROCESS_INSTANCE_ID, "definition-key-static", null, null, null));
    }

    /** 单段即超限的载荷（使 clamp 丢到全空仍超 ⇒ 兜底拒绝）。 */
    private static DecisionPayload oversizedPayload() {
        final StringBuilder oversized = new StringBuilder(OVERSIZED_BYTES);
        for (int index = 0; index < OVERSIZED_BYTES; index++) {
            oversized.append('x');
        }
        return new DecisionPayload(null, null, null,
                new ProcessInstanceMetadata(PROCESS_INSTANCE_ID, "definition-key-static",
                        oversized.toString(), null, null));
    }

    /** 策略放行的出域结果。 */
    private static DecisionOutboundResult permitted(final DecisionPayload payload) {
        return new DecisionOutboundResult(true, payload, DecisionProcessingRecord.none());
    }

    /** 固定产出响应（默认方言的成功形态）。 */
    private static DecisionProviderResponse producedResponse() {
        final List<DecisionRationaleFact> facts = new ArrayList<>();
        facts.add(new DecisionRationaleFact(
                io.github.flowable.plus.core.enums.DecisionRationaleFactKey.SCORE, "0.91"));
        return DecisionProviderResponse.builder()
                .declined(Boolean.FALSE)
                .suggestedAction(ApprovalAction.AGREE)
                .actionSummary("stub 建议同意")
                .rationaleFacts(facts)
                .rationaleNarrative("stub 依据：分值高于阈值")
                .modelId(DecisionFixtures.MODEL_ID)
                .provider(TARGET_KEY)
                .chainStage(DecisionChainStage.PRIMARY)
                .degraded(Boolean.FALSE)
                .rawOutput(StubDecisionTransport.PRODUCED_FIXTURE)
                .inputTokens(128L)
                .outputTokens(32L)
                .build();
    }

    /** 断言本段恰一条观测并交回。 */
    private DecisionObservation singleObservation() {
        assertThat(observations).as("本段应当恰好发一条观测").hasSize(1);
        return observations.get(0);
    }

    /** 本段落下的证据行（整行文本）。 */
    private static List<String> capturedRows(final TaskService tasks) {
        final ArgumentCaptor<String> rows = ArgumentCaptor.forClass(String.class);
        verify(tasks, atLeast(0)).addComment(anyString(), anyString(), anyString(), rows.capture());
        return rows.getAllValues();
    }

    /** 场景之间清场：替身调用留痕与观测清零（异常场景互不串味）。 */
    private void nextScenario() {
        clearInvocations(taskService, runtimeService, managementService, runtimeControl, policy, provider,
                submissionService);
        observations.clear();
    }

    /** 类型化参数匹配器（泛型命令方法的 verify 用）。 */
    private static Command<DecisionContextSnapshot> anyCommand() {
        return org.mockito.ArgumentMatchers.<Command<DecisionContextSnapshot>>any();
    }
}
