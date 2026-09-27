package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.event.DefaultEventPublisher;
import io.github.flowable.plus.core.event.EventBus;
import io.github.flowable.plus.core.event.ProcessEndedEvent;
import io.github.flowable.plus.core.event.ProcessInvalidatedEvent;
import io.github.flowable.plus.core.event.ProcessStartedEvent;
import io.github.flowable.plus.core.event.TaskCompletedEvent;
import io.github.flowable.plus.core.event.TaskCreatedEvent;
import io.github.flowable.plus.core.event.TaskDelegatedEvent;
import io.github.flowable.plus.core.event.TaskJumpedEvent;
import io.github.flowable.plus.core.event.TaskRejectedEvent;
import io.github.flowable.plus.core.event.TaskTransferredEvent;
import io.github.flowable.plus.core.event.TaskWithdrawnEvent;
import io.github.flowable.plus.core.model.CountersignRoundResolver;
import io.github.flowable.plus.core.model.DefaultBpmnModelCache;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.domain.PlusProcessInstance;
import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import io.github.flowable.plus.core.spi.ProcessEventListener;
import io.github.flowable.plus.core.spi.UserContext;
import io.github.flowable.plus.core.strategy.CountersignRollbackStrategy;
import io.github.flowable.plus.core.support.DefaultActionInferenceStrategy;
import io.github.flowable.plus.core.support.ProcessEndDetector;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.CountersignSubRecord;
import io.github.flowable.plus.core.workflow.CounterSignWorkflow;
import io.github.flowable.plus.core.workflow.FlowableExecutionTreeHelper;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E20 —— 无感等价对拍（探索工作区落点文件 §3.2 的 {@code E20}，靶子⑦主落点，I4；ADR-0042 第 11 节第 6 条 (c)）。
 *
 * <p><b>内部形态的唯一住所 = 探索工作区 {@code docs/impl/0042-equivalence-harness.md}</b>：三态
 * （关态 / 参照态 / 开态正向对照）· 参照装配 = 同一测试内两个引擎实例、除机制组件注册与否外完全同构 ·
 * 对拍 = {@code getApprovalHistory} 全字段（含 {@code decisionEvidences}）· 动态字段按出现序规范化 ·
 * fixture 代码内构造、覆盖会签 + 回退 + 作废 / 删除实例、至少一个已声明节点 · 防空转两条并立。</p>
 *
 * <p><b>驱动入口 = 框架受控入口</b>（不是直接引擎 API）：拉面的 {@code TaskCreatedEvent} 发射点在
 * 受控入口内，直接调 {@code runtimeService} / {@code taskService} 机制根本不会被触达 ⇒ 两态必然
 * 逐字段相等、断言恒真而无证据力（防恒真的理由见形态文件 §1.2）。</p>
 *
 * <p><b>七条具名断言</b>（形态文件 §8 清单）：面② 全字段对拍 · 面① 运行时与历史状态 · 面③ 写操作
 * 返回与异常 · 面⑤ 监听器回调序列 · 开态正向对照（甲）· 装配集恒等（乙）· 归类守卫（防「新增字段
 * 未归类」，形态文件 §3.2 的订正语义）。<b>面④ 事务语义不在本类视域</b>（standalone 无 Spring 事务
 * 代理，边界已推 {@code S5}）。</p>
 *
 * <p><b>构造期取值全部为测试自定值</b>：超时 / 预算 / 池尺寸等不复制任何框架默认（防第二处真相，
 * 形态文件 §2.4）。三台引擎各占<b>独立内存库</b>，互不见对方的部署与实例。</p>
 */
class DecisionDisabledEquivalenceTest {

    private static final String PROCESS_KEY = "equivalenceProcess";

    private static final String RESOURCE_NAME = "equivalence.bpmn20.xml";

    /** 发起人 / 复核节点办理人 */
    private static final String USER_INITIATOR = "equ-alice";

    /** 会签参与人（BPMN 集合变量；三处命名一致见 ADR-0026） */
    private static final List<String> ASSIGNEES = Arrays.asList("equ-bob", "equ-carol", "equ-dave");

    private static final String BUSINESS_KEY = "equ-biz-0001";

    private static final String NODE_INITIATOR = "initiator";

    private static final String NODE_COUNTERSIGN = "countersign";

    private static final String NODE_REVIEW = "review";

    /** 已注册的决策目标 key（stub Bean 的 key；与节点声明一致） */
    private static final String TARGET_KEY = "stubDecisionTarget";

    /** 已注册的出域策略 key（stub Bean 的 key；与节点声明一致） */
    private static final String POLICY_KEY = "stubDecisionPolicy";

    /** 开态证据落行的有界等待上限（测试自定值，不入框架默认） */
    private static final long EVIDENCE_WAIT_SECONDS = 10L;

    /** 作废理由（deleteReason 非常规值的现场值） */
    private static final String INVALIDATE_REASON = "作废-验证收口";

    // ======================== 三态（@BeforeAll 一次装配并各跑一段受控序列，断言方法只做各自面的比较） ========================

    private static Assembly offState;

    private static Assembly referenceState;

    private static Assembly onState;

    @BeforeAll
    static void assembleAndRun() {
        // 关态：机制组件已构造并注册，全局开关取构造期定值 false
        offState = assemble(MechanismMode.REGISTERED_OFF);
        // 参照态：机制组件根本不构造、不注册
        referenceState = assemble(MechanismMode.ABSENT);
        // 开态：机制组件注册 + 开关 true + stub 决策目标 / 策略 / Provider / Transport（正向对照）
        onState = assemble(MechanismMode.ON);

        offState.records = runFullSequence(offState);
        referenceState.records = runFullSequence(referenceState);
        runOnStateSequence(onState);
    }

    @AfterAll
    static void stopEngines() {
        offState.engine.close();
        referenceState.engine.close();
        onState.engine.close();
    }

    // ======================== 面②：getApprovalHistory 全字段对拍 ========================

    @Test
    @DisplayName("面②：全局关 ⇄ 机制不参与，getApprovalHistory 全字段逐字段等价（归一化副本 equals）")
    void globalOffEqualsMechanismAbsentFieldByField() {
        // 归一化副本按整个列表 equals：ApprovalRecordVO 的 equals 由 Lombok 全字段生成，
        // 新增内容字段自动纳入比较（比较面 = 全字段是结构事实，漏字段在结构上不可能）。
        assertThat(normalizeRecords(offState.records))
                .as("关态与参照态的审批历史必须逐字段等价（含 decisionEvidences 恒空集合）")
                .containsExactlyElementsOf(normalizeRecords(referenceState.records));
        // decisionEvidences 必须进清单且关态恒为空集合（软回退，不是 null）
        assertThat(offState.records).allSatisfy(record ->
                assertThat(record.getDecisionEvidences()).isEmpty());
    }

    // ======================== 面①：运行时状态与结束态历史 ========================

    @Test
    @DisplayName("面①：活动实例集 / 历史实例状态与 deleteReason / 历史变量集 / 节点 id 序列逐项等值")
    void globalOffEqualsMechanismAbsentOnRuntimeAndHistoryState() {
        for (final Assembly side : Arrays.asList(offState, referenceState)) {
            HistoryService historyService = side.engine.getHistoryService();
            String pid = side.run.processInstanceId;

            HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(pid).singleResult();
            assertThat(instance.getDeleteReason())
                    .as("作废 / 删除实例是 deleteReason 非常规值的唯一路径，两侧必须同为该现场值")
                    .isEqualTo(INVALIDATE_REASON);
            assertThat(instance.getEndTime()).as("结束态两侧同为已结束").isNotNull();
            assertThat(instance.getStartUserId()).isEqualTo(USER_INITIATOR);
            assertThat(instance.getBusinessKey()).isEqualTo(BUSINESS_KEY);

            // 节点 id 序列（按开始时间升序、逐位置）
            side.run.activityIdSequence = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(pid)
                    .orderByHistoricActivityInstanceStartTime().asc()
                    .list()
                    .stream()
                    .map(HistoricActivityInstance::getActivityId)
                    .collect(Collectors.toList());

            // 历史变量集：名字与内容（集合变量按内容等值）
            Map<String, Object> variables = new LinkedHashMap<>();
            for (final HistoricVariableInstance variable : historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(pid).list()) {
                variables.put(variable.getVariableName(), variable.getValue());
            }
            side.run.variablesByName = variables;

            // 运行时状态：作废后两侧都无运行时实例
            assertThat(side.engine.getRuntimeService().createProcessInstanceQuery().count())
                    .as("作废后运行时实例两侧同为零").isZero();
        }
        assertThat(offState.run.activityIdSequence)
                .as("历史活动与任务的节点 id 序列逐位置等值")
                .isEqualTo(referenceState.run.activityIdSequence);
        assertThat(offState.run.variablesByName)
                .as("历史变量集等值")
                .isEqualTo(referenceState.run.variablesByName);
    }

    // ======================== 面③：写操作返回值与异常 ========================

    @Test
    @DisplayName("面③：受控入口每次调用的返回与异常逐次逐位置等值（消息按动态归一化）")
    void globalOffEqualsMechanismAbsentOnWriteOperations() {
        assertThat(offState.run.writeOpTrace)
                .as("三个写侧 workflow 的受控入口逐次调用结果必须等价")
                .containsExactlyElementsOf(referenceState.run.writeOpTrace);
        // 异常路径（作废后再作废）：两侧抛同一类型
        assertThat(offState.run.secondInvalidationException)
                .isEqualTo(referenceState.run.secondInvalidationException);
        assertThat(offState.run.secondInvalidationException)
                .as("作废 / 删除实例的异常路径必须真实发生（实例已结束 ⇒ 已结束无法作废）")
                .isEqualTo("TaskAlreadyCompletedException");
    }

    // ======================== 面⑤：既有 ProcessEventListener 回调序列 ========================

    @Test
    @DisplayName("面⑤：既有监听器回调序列逐位置等值（方法名 + 内容字段，动态字段归一化）")
    void globalOffEqualsMechanismAbsentOnListenerCallbackSequence() {
        assertThat(offState.recording.normalizedEntries())
                .as("既有监听器的回调方法名序列与事件内容必须逐位置等值")
                .isEqualTo(referenceState.recording.normalizedEntries());
        // 回调必须真实发生过（防空转的辅助面：序列非空）
        assertThat(offState.recording.normalizedEntries()).isNotEmpty();
    }

    // ======================== 防空转甲：开态正向对照 ========================

    @Test
    @DisplayName("防空转甲：开态机制活动非零（decisionEvidences 非空 + 有出站调用记录）")
    void mechanismActiveProducesNonZeroActivity() {
        ApprovalRecordVO reviewRecord = onState.records.stream()
                .filter(record -> onState.run.reviewTaskId.equals(record.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("复核节点的审批记录必须存在"));
        assertThat(reviewRecord.getDecisionEvidences())
                .as("开态必须产出机制痕迹：声明节点的证据组非空（关态 ≡ 参照态 不是恒真式）")
                .isNotEmpty();
        assertThat(onState.transport.getCallCount())
                .as("开态必须发生真实出站调用（经决策目标的默认方言、stub 传输）")
                .isGreaterThanOrEqualTo(1);
        // 附带（近零成本）：录制 core 的 TaskCreatedEvent 计数，证「任务就绪确实发生过」
        assertThat(onState.recording.countByMethod("onTaskCreated"))
                .as("受控入口确实发射了到点信号（辅助面，单独不构成防空转）")
                .isGreaterThanOrEqualTo(4);
    }

    // ======================== 防空转乙：装配集恒等 ========================

    @Test
    @DisplayName("防空转乙：关态装配集 == 参照态装配集 ∪ 机制组件集（两侧唯一差异确实是机制组件）")
    void globalOffAssemblySetEqualsReferencePlusMechanismComponents() {
        Set<String> expected = new LinkedHashSet<>(referenceState.builtComponents);
        expected.addAll(MECHANISM_COMPONENTS);
        assertThat(offState.builtComponents)
                .as("关态装配集必须恒等于参照态装配集加机制组件集（防「唯一差异被误实现成零差异」）")
                .isEqualTo(expected);
    }

    // ======================== 归类守卫：防「新增字段未归类」 ========================

    @Test
    @DisplayName("归类守卫：归一化表 ∪ 内容表 == 两个 VO 的声明字段集（新增字段未归类即红）")
    void equivalenceClassificationCoversEveryVoField() {
        Set<String> covered = new LinkedHashSet<>(DYNAMIC_FIELDS);
        covered.addAll(CONTENT_FIELDS);
        assertThat(covered)
                .as("归类守卫（形态文件 §3.2 订正语义）：动态字段 + 内容字段必须恰等两个 VO 的字段集")
                .isEqualTo(VO_FIELD_SET);
    }

    // ======================== 装配 ========================

    /** 机制组件三态（形态文件 §1.1） */
    private enum MechanismMode {
        /** 关态：机制组件已构造并注册，全局开关构造期定值 false */
        REGISTERED_OFF,
        /** 参照态：机制组件根本不构造、不注册 */
        ABSENT,
        /** 开态：注册 + 开关 true + stub 决策目标 / 策略 / Provider / Transport */
        ON
    }

    /** 机制组件集（乙的对照面；helper 构造哪个组件就登记哪个类简单名） */
    private static final Set<String> MECHANISM_COMPONENTS = new LinkedHashSet<>(Arrays.asList(
            "DecisionNodeDeclarationValidator",
            "DecisionTaskCreatedListener",
            "DecisionPipeline",
            "DecisionContextAssembler",
            "DefaultSuggestionSubmissionService",
            "DefaultDecisionProvider",
            "StubDecisionTransport",
            "DefaultDecisionRuntimeControl",
            "DecisionObservationEmitter",
            "DecisionExclusiveExecutor"));

    /** 动态字段（按出现序规范化；父记录与子记录共用同一张编号表） */
    private static final Set<String> DYNAMIC_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "taskId", "startTime", "endTime", "duration"));

    /** 内容字段（严格等值；roundIndex 是引擎写入的会签轮次内容，不归一化） */
    private static final Set<String> CONTENT_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "nodeId", "nodeName", "action", "comment", "operationComment", "operationComments",
            "actorId", "actorName", "roundIndex", "countersignRecords", "decisionEvidences"));

    /** 两个 VO 的声明字段集（归类守卫的对照面） */
    private static final Set<String> VO_FIELD_SET = collectDeclaredFields(ApprovalRecordVO.class,
            CountersignSubRecord.class);

    private static Set<String> collectDeclaredFields(Class<?>... types) {
        Set<String> names = new LinkedHashSet<>();
        for (final Class<?> type : types) {
            for (final Field field : type.getDeclaredFields()) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /** 可变身份上下文（会签三票各自以办理人身份执行；UserContext 是应用供给面） */
    private static final class MutableUserContext implements UserContext {
        private String currentUserId = USER_INITIATOR;

        @Override
        public String getCurrentUserId() {
            return currentUserId;
        }
    }

    /** 录制既有监听器回调的 listener（面⑤的录制面） */
    private static final class RecordingListener implements ProcessEventListener {

        /** 一条回调的原始形态：方法名 / 锚点 id（任务或实例）/ 事件时刻 / 内容位（多实例拆分的并列组排序键） */
        private final List<String[]> entries = new ArrayList<>();

        @Override
        public void onProcessStarted(ProcessStartedEvent event) {
            record("onProcessStarted", event.getProcessInstanceId(), event.getEventTime(), null);
        }

        @Override
        public void onTaskCreated(TaskCreatedEvent event) {
            // 内容位取 assignee（内容字段，两侧同值）：多实例拆分在一毫秒内创建多任务，
            // 「同毫秒本无客观先后」，其到达序由引擎查询的并列序承载 —— 归一化时并列组按内容排序。
            record("onTaskCreated", event.getTaskId(), event.getEventTime(), event.getAssignee());
        }

        @Override
        public void onTaskCompleted(TaskCompletedEvent event) {
            record("onTaskCompleted", event.getTaskId(), event.getEventTime(), null);
        }

        @Override
        public void onTaskRejected(TaskRejectedEvent event) {
            record("onTaskRejected", event.getTaskId(), event.getEventTime(), null);
        }

        @Override
        public void onTaskWithdrawn(TaskWithdrawnEvent event) {
            record("onTaskWithdrawn", event.getTaskId(), event.getEventTime(), null);
        }

        @Override
        public void onTaskTransferred(TaskTransferredEvent event) {
            record("onTaskTransferred", event.getTaskId(), event.getEventTime(), null);
        }

        @Override
        public void onTaskJumped(TaskJumpedEvent event) {
            record("onTaskJumped", event.getTaskId(), event.getEventTime(), null);
        }

        @Override
        public void onTaskDelegated(TaskDelegatedEvent event) {
            record("onTaskDelegated", event.getTaskId(), event.getEventTime(), null);
        }

        @Override
        public void onProcessInvalidated(ProcessInvalidatedEvent event) {
            record("onProcessInvalidated", event.getProcessInstanceId(), event.getEventTime(), null);
        }

        @Override
        public void onProcessEnded(ProcessEndedEvent event) {
            record("onProcessEnded", event.getProcessInstanceId(), event.getEventTime(), null);
        }

        private void record(String methodName, String anchorId, java.util.Date eventTime, String contentTag) {
            entries.add(new String[]{methodName,
                    anchorId,
                    String.valueOf(eventTime == null ? null : eventTime.getTime()),
                    contentTag});
        }

        int countByMethod(String methodName) {
            return (int) entries.stream().filter(entry -> entry[0].equals(methodName)).count();
        }

        /**
         * 归一化：锚点 id 按出现序重编号；{@code getEventTime()} 按「归动态」口径<b>整体退出对拍面</b>
         * —— 事件时刻的绝对值与并列模式都是引擎时钟产物，毫秒边界在任何一次运行里都会摆动，其序
         * 已由序列位置承载。<b>连续同名运行</b>（一次操作内的多实例拆分，或相邻三次投票的同名回调）
         * 为并列组 —— 组内按内容位排序（同毫秒并列无客观先后，内容等值面才是契约面）；组内无内容位
         * 的条目（各操作至多一条）由稳定排序保持跨操作出现序。
         */
        List<String> normalizedEntries() {
            Map<String, String> idTable = new HashMap<>();
            List<String> normalized = new ArrayList<>();
            int index = 0;
            while (index < entries.size()) {
                String[] head = entries.get(index);
                int groupEnd = index + 1;
                while (groupEnd < entries.size()
                        && entries.get(groupEnd)[0].equals(head[0])) {
                    groupEnd++;
                }
                List<String[]> group = new ArrayList<>(entries.subList(index, groupEnd));
                group.sort(Comparator.comparing(entry -> entry[3] == null ? "" : entry[3]));
                for (final String[] entry : group) {
                    String id = entry[1] == null ? "-"
                            : idTable.computeIfAbsent(entry[1], key -> "X" + idTable.size());
                    normalized.add(entry[0] + "|" + id);
                }
                index = groupEnd;
            }
            return normalized;
        }
    }

    /** 一侧装配的全部产物（引擎 + 受控入口 + 组件名单 + 运行捕获） */
    private static final class Assembly {
        final ProcessEngine engine;
        final String databaseName;
        MutableUserContext userContext;
        final ProcessLifecycleWorkflow lifecycle;
        final TaskExecutionWorkflow execution;
        final CounterSignWorkflow counterSign;
        final HistoryWorkflow history;
        final RecordingListener recording;
        final StubDecisionTransport transport;
        final Set<String> builtComponents = new LinkedHashSet<>();
        final RunCapture run = new RunCapture();
        List<ApprovalRecordVO> records;

        Assembly(ProcessEngine engine, String databaseName, ProcessLifecycleWorkflow lifecycle,
                 TaskExecutionWorkflow execution, CounterSignWorkflow counterSign,
                 HistoryWorkflow history, RecordingListener recording,
                 StubDecisionTransport transport) {
            this.engine = engine;
            this.databaseName = databaseName;
            this.lifecycle = lifecycle;
            this.execution = execution;
            this.counterSign = counterSign;
            this.history = history;
            this.recording = recording;
            this.transport = transport;
        }
    }

    /** 一次受控序列运行的捕获（面①③⑤的对照面） */
    private static final class RunCapture {
        final List<String> writeOpTrace = new ArrayList<>();
        String processInstanceId;
        String reviewTaskId;
        String secondInvalidationException;
        List<String> activityIdSequence;
        Map<String, Object> variablesByName;
    }

    /**
     * 组装一侧（同一 helper、唯一差异一个模式位 —— 形态文件 §2.2 纪律 1）。
     */
    private static Assembly assemble(MechanismMode mode) {
        boolean mechanismPresent = mode != MechanismMode.ABSENT;
        boolean enabled = mode == MechanismMode.ON;
        Set<String> built = new LinkedHashSet<>();

        ProcessEngine engine;
        if (mechanismPresent) {
            // 关态 / 开态：主闸以「自建默认 ProcessValidator + 叠加 ValidatorSet」等价替换（形态文件 §2.5）
            engine = ExtensionTestEngine.buildIsolated(new DecisionNodeDeclarationValidator(
                            Collections.singleton(TARGET_KEY), Collections.singleton(POLICY_KEY)),
                    null, mode == MechanismMode.ON ? "equivalence-on" : "equivalence-off");
            built.add("DecisionNodeDeclarationValidator");
        } else {
            engine = ExtensionTestEngine.buildIsolated(null, null, "equivalence-reference");
        }
        TaskService taskService = engine.getTaskService();
        RuntimeService runtimeService = engine.getRuntimeService();
        HistoryService historyService = engine.getHistoryService();
        ManagementService managementService = engine.getManagementService();
        IdentityService identityService = engine.getIdentityService();

        deployEquivalenceBpmn(engine);

        // core 侧装配（两态同构）
        DefaultBpmnModelCache bpmnModelCache = new DefaultBpmnModelCache(engine.getRepositoryService());
        built.add("DefaultBpmnModelCache");
        MultiInstanceDetector multiInstanceDetector =
                new MultiInstanceDetector(bpmnModelCache, taskService, historyService);
        built.add("MultiInstanceDetector");
        NodeFinder nodeFinder = mock(NodeFinder.class);
        when(nodeFinder.findInitiatorNode(anyString())).thenReturn(NODE_INITIATOR);
        built.add("NodeFinder");
        ExecutionTreeHelper executionTreeHelper = new FlowableExecutionTreeHelper(managementService);
        built.add("FlowableExecutionTreeHelper");

        RecordingListener recordingListener = new RecordingListener();
        built.add("RecordingListener");

        MutableUserContext userContext = new MutableUserContext();
        EventBus eventBus;
        StubDecisionTransport transport = null;
        if (mechanismPresent) {
            DecisionObservationEmitter observationEmitter = new DecisionObservationEmitter(null, null);
            built.add("DecisionObservationEmitter");
            DefaultDecisionRuntimeControl runtimeControl = new DefaultDecisionRuntimeControl();
            built.add("DefaultDecisionRuntimeControl");
            transport = new StubDecisionTransport(StubDecisionTransport.PRODUCED_FIXTURE);
            built.add("StubDecisionTransport");
            DefaultDecisionProvider provider = new DefaultDecisionProvider(transport, null);
            built.add("DefaultDecisionProvider");
            DecisionContextAssembler assembler =
                    new DecisionContextAssembler(managementService, runtimeService, taskService, null);
            built.add("DecisionContextAssembler");
            DefaultSuggestionSubmissionService submissionService =
                    new DefaultSuggestionSubmissionService(multiInstanceDetector, taskService,
                            runtimeService, observationEmitter, enabled);
            built.add("DefaultSuggestionSubmissionService");
            // 测试自定值：预算 / 尝试 / 退避 / 池尺寸（不复制框架默认，防第二处真相）
            DecisionPipeline pipeline = new DecisionPipeline(assembler, taskService, runtimeService,
                    Collections.singletonList(stubTarget()), Collections.singletonList(stubPolicy()),
                    provider, runtimeControl, submissionService, observationEmitter,
                    2_000L, 2, 1L, 2.0d, 10L);
            built.add("DecisionPipeline");
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "equivalence-decision-pool");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            built.add("DecisionExclusiveExecutor");
            DecisionTaskCreatedListener decisionListener = new DecisionTaskCreatedListener(enabled, true,
                    executor, pipeline, declaredNodeIndex(engine), observationEmitter);
            built.add("DecisionTaskCreatedListener");
            eventBus = new EventBus(new DefaultEventPublisher(Arrays.asList(recordingListener, decisionListener)));
            built.add("DefaultEventPublisher");
        } else {
            eventBus = new EventBus(new DefaultEventPublisher(Collections.singletonList(recordingListener)));
            built.add("DefaultEventPublisher");
        }
        built.add("EventBus");

        ProcessLifecycleWorkflow lifecycle = new ProcessLifecycleWorkflow(userContext,
                taskService, historyService, runtimeService, identityService, nodeFinder,
                Collections.emptyList(), eventBus);
        built.add("ProcessLifecycleWorkflow");
        TaskExecutionWorkflow execution = new TaskExecutionWorkflow(userContext, taskService, historyService,
                runtimeService, nodeFinder, multiInstanceDetector, executionTreeHelper, eventBus,
                new ProcessEndDetector(runtimeService, historyService, eventBus),
                mock(CountersignRollbackStrategy.class));
        built.add("TaskExecutionWorkflow");
        CounterSignWorkflow counterSign = new CounterSignWorkflow(userContext, taskService, historyService,
                runtimeService, multiInstanceDetector, nodeFinder, Collections.emptyList(), eventBus,
                new ProcessEndDetector(runtimeService, historyService, eventBus),
                new CountersignRoundResolver(historyService, taskService));
        built.add("CounterSignWorkflow");
        HistoryWorkflow history = new HistoryWorkflow(historyService, taskService, bpmnModelCache,
                multiInstanceDetector, userId -> userId, new DefaultActionInferenceStrategy(),
                new CountersignRoundResolver(historyService, taskService));
        built.add("HistoryWorkflow");

        Assembly assembly = new Assembly(engine,
                mode == MechanismMode.ON ? "equivalence-on"
                        : mode == MechanismMode.REGISTERED_OFF ? "equivalence-off"
                        : "equivalence-reference",
                lifecycle, execution, counterSign, history, recordingListener, transport);
        assembly.userContext = userContext;
        assembly.builtComponents.addAll(built);
        return assembly;
    }

    private static DecisionTarget stubTarget() {
        return new DecisionTarget() {
            @Override
            public String key() {
                return TARGET_KEY;
            }

            @Override
            public String url() {
                return "http://equivalence.stub.invalid/decision";
            }
        };
    }

    private static DecisionPolicy stubPolicy() {
        return new DecisionPolicy() {
            @Override
            public String key() {
                return POLICY_KEY;
            }

            @Override
            public DecisionOutboundResult applyOutbound(DecisionPayload payload) {
                return new DecisionOutboundResult(true, payload, DecisionProcessingRecord.none());
            }
        };
    }

    /** 声明面索引（装配面预热的等价替换：从已部署模型取声明节点元素） */
    private static Map<String, BaseElement> declaredNodeIndex(ProcessEngine engine) {
        String definitionId = engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).latestVersion().singleResult().getId();
        BpmnModel model = engine.getRepositoryService().getBpmnModel(definitionId);
        FlowElement review = model.getMainProcess().getFlowElement(NODE_REVIEW);
        Map<String, BaseElement> index = new HashMap<>();
        index.put(NODE_REVIEW, review);
        return index;
    }

    // ======================== 受控操作序列 ========================

    /**
     * 关态 / 参照态的同一份操作序列（形态文件 §6.2：会签 + 回退 + 作废 / 删除实例；声明节点 = 复核）。
     * 每次调用的返回与异常记入 {@code writeOpTrace}（面③），序列末读审批历史。
     */
    private static List<ApprovalRecordVO> runFullSequence(Assembly side) {
        RunCapture capture = side.run;
        try {
            PlusProcessInstance instance = side.lifecycle.startProcess(PROCESS_KEY, BUSINESS_KEY,
                    new HashMap<>(Collections.singletonMap("assignees", new ArrayList<>(ASSIGNEES))));
            capture.processInstanceId = instance.getProcessInstanceId();
            capture.writeOpTrace.add("startProcess(" + instance.getProcessDefinitionId()
                    + "," + instance.getBusinessKey() + ")");

            String initiatorTaskId = singleActiveTaskId(side, capture.processInstanceId);
            side.execution.completeTask(initiatorTaskId, null, "同意-发起");
            capture.writeOpTrace.add("completeTask=void");

            voteCountersign(side, capture);

            String reviewTaskId = singleActiveTaskId(side, capture.processInstanceId);
            capture.reviewTaskId = reviewTaskId;
            side.execution.rejectTaskToInitiator(reviewTaskId, "驳回至发起人");
            capture.writeOpTrace.add("rejectTaskToInitiator=void");

            side.lifecycle.invalidateProcess(capture.processInstanceId, INVALIDATE_REASON);
            capture.writeOpTrace.add("invalidateProcess=void");

            try {
                side.lifecycle.invalidateProcess(capture.processInstanceId, INVALIDATE_REASON);
                capture.secondInvalidationException = "(no exception)";
            } catch (RuntimeException rejected) {
                // 有意的宽捕获：此处是异常探针（面③），目的是捕获「再作废已结束实例」的异常类型
                // 供两态对拍，不是吞异常 —— 类型取 simpleName 后即写入对拍清单。
                capture.secondInvalidationException = rejected.getClass().getSimpleName();
            }
        } catch (RuntimeException broken) {
            throw new AssertionError("受控操作序列在关态 / 参照态必须可完整执行（否则对拍面不成立）", broken);
        }
        determinizeHistoryTimes(side);
        return side.history.getApprovalHistory(capture.processInstanceId);
    }

    /** 会签三票：各自以办理人身份（UserContext 是应用供给面，测试内切换）。 */
    private static void voteCountersign(Assembly side, RunCapture capture) {
        for (final String assignee : ASSIGNEES) {
            String taskId = activeTaskIdForAssignee(side, capture.processInstanceId, assignee);
            side.userContext.currentUserId = assignee;
            side.counterSign.counterSign(taskId, true, null, "同意-会签");
            capture.writeOpTrace.add("counterSign=void");
        }
        side.userContext.currentUserId = USER_INITIATOR;
    }

    /**
     * 开态序列：只走到声明节点就绪并等待证据落行（后续操作会移走锚点，与拉取线程竞态）。
     */
    private static void runOnStateSequence(Assembly side) {
        RunCapture capture = side.run;
        try {
            PlusProcessInstance instance = side.lifecycle.startProcess(PROCESS_KEY, BUSINESS_KEY,
                    new HashMap<>(Collections.singletonMap("assignees", new ArrayList<>(ASSIGNEES))));
            capture.processInstanceId = instance.getProcessInstanceId();
            String initiatorTaskId = singleActiveTaskId(side, capture.processInstanceId);
            side.execution.completeTask(initiatorTaskId, null, "同意-发起");
            voteCountersign(side, capture);
            capture.reviewTaskId = singleActiveTaskId(side, capture.processInstanceId);
        } catch (RuntimeException broken) {
            throw new AssertionError("开态受控序列必须可完整执行", broken);
        }
        waitForEvidenceRow(side, capture.reviewTaskId);
        side.records = side.history.getApprovalHistory(capture.processInstanceId);
    }

    /** 有界等待机制证据落行（拉面在专属池线程上异步执行）。 */
    private static void waitForEvidenceRow(Assembly side, String reviewTaskId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EVIDENCE_WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!side.engine.getTaskService()
                    .getTaskComments(reviewTaskId,
                            io.github.flowable.plus.core.enums.DecisionEvidenceComment.COMMENT_TYPE.name())
                    .isEmpty()) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("有界等待超时：开态的证据行未在 " + EVIDENCE_WAIT_SECONDS + "s 内落行");
    }

    /**
     * 历史时间的确定性化（fixture 侧）：同一操作内创建的多行（多实例拆分、发起事件与其任务）在
     * {@code ACT_HI_*} 里共享同一毫秒，而「同毫秒本无客观先后」（排序在两台引擎的库上不保证一致）。
     * 按<b>数值 {@code ID_} 升序（= 写入序）</b>给活动实例与任务实例的开始 / 结束时间指定确定的全序
     * —— 与本机制读侧 tie-break 的既有哲学一致；绝对值不进任何断言（对拍只看相对序与内容）。
     */
    private static void determinizeHistoryTimes(Assembly side) {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                ExtensionTestEngine.isolatedJdbcUrl(side.databaseName), "sa", "")) {
            rewriteStartEndTimesById(connection, "ACT_HI_ACTINST");
            rewriteStartEndTimesById(connection, "ACT_HI_TASKINST");
        } catch (java.sql.SQLException broken) {
            throw new IllegalStateException("历史时间的确定性化改写失败", broken);
        }
    }

    /** 单表：按数值 {@code ID_} 升序重排 START_TIME_ / END_TIME_（保持 null 形态；END = START + 500）。 */
    private static void rewriteStartEndTimesById(java.sql.Connection connection, String table)
            throws java.sql.SQLException {
        List<long[]> rows = new ArrayList<>();
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery(
                     "SELECT ID_, END_TIME_ FROM " + table + " ORDER BY CAST(ID_ AS BIGINT)")) {
            while (resultSet.next()) {
                String id = resultSet.getString(1);
                java.sql.Timestamp end = resultSet.getTimestamp(2);
                // parseLong 的前提由本 fixture 保证：E20 的引擎未替换 IdGenerator，ID_ 恒为 DbIdGenerator 数值串
                rows.add(new long[]{Long.parseLong(id), end == null ? -1L : end.getTime()});
            }
        }
        long base = 1_000_000_000L;
        try (java.sql.PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET START_TIME_ = ?, END_TIME_ = ? WHERE ID_ = ?")) {
            for (int rank = 0; rank < rows.size(); rank++) {
                long[] row = rows.get(rank);
                long start = base + rank * 1_000L;
                update.setTimestamp(1, new java.sql.Timestamp(start));
                if (row[1] < 0) {
                    update.setNull(2, java.sql.Types.TIMESTAMP);
                } else {
                    update.setTimestamp(2, new java.sql.Timestamp(start + 500L));
                }
                update.setLong(3, row[0]);
                update.executeUpdate();
            }
        }
    }

    private static String singleActiveTaskId(Assembly side, String processInstanceId) {
        return side.engine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult()
                .getId();
    }

    /** 会签阶段的票任务按办理人取（多实例节点同时存在多张活跃任务）。 */
    private static String activeTaskIdForAssignee(Assembly side, String processInstanceId, String assignee) {
        return side.engine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .taskAssignee(assignee)
                .singleResult()
                .getId();
    }

    // ======================== 归一化（形态文件 §3 / §4 的单一实现，各面对拍共用） ========================

    /**
     * 归一化副本：{@code taskId} 按出现序重编号（父记录与子记录共用同一张编号表）；
     * {@code startTime} / {@code endTime} 归一化为出现序向量（以序数刻在 {@code Date} 上）；
     * {@code duration} 只比「同为 null / 同为非 null」。其余字段（内容）严格等值。
     */
    private static List<ApprovalRecordVO> normalizeRecords(List<ApprovalRecordVO> records) {
        Map<String, String> taskIds = new HashMap<>();
        Map<Long, Long> times = new HashMap<>();
        Function<Date, Date> ordinal = (Date value) -> value == null ? null
                : new Date(times.computeIfAbsent(value.getTime(), key -> (long) times.size()));
        Function<String, String> renumber = (String taskId) -> taskId == null ? null
                : taskIds.computeIfAbsent(taskId, key -> "T" + taskIds.size());

        List<ApprovalRecordVO> normalized = new ArrayList<>(records.size());
        for (final ApprovalRecordVO record : records) {
            List<CountersignSubRecord> subRecords = record.getCountersignRecords();
            List<CountersignSubRecord> normalizedSubRecords = null;
            if (subRecords != null) {
                normalizedSubRecords = new ArrayList<>(subRecords.size());
                for (final CountersignSubRecord subRecord : subRecords) {
                    normalizedSubRecords.add(new CountersignSubRecord(
                            renumber.apply(subRecord.getTaskId()),
                            subRecord.getNodeId(),
                            subRecord.getNodeName(),
                            subRecord.getAction(),
                            subRecord.getActorId(),
                            subRecord.getActorName(),
                            subRecord.getComment(),
                            subRecord.getOperationComment(),
                            subRecord.getOperationComments(),
                            ordinal.apply(subRecord.getStartTime()),
                            ordinal.apply(subRecord.getEndTime()),
                            subRecord.getDuration() == null ? null : 0L,
                            subRecord.getRoundIndex(),
                            subRecord.getDecisionEvidences()));
                }
            }
            normalized.add(new ApprovalRecordVO(
                    renumber.apply(record.getTaskId()),
                    record.getNodeId(),
                    record.getNodeName(),
                    record.getAction(),
                    record.getActorId(),
                    record.getActorName(),
                    record.getComment(),
                    record.getOperationComment(),
                    record.getOperationComments(),
                    ordinal.apply(record.getStartTime()),
                    ordinal.apply(record.getEndTime()),
                    record.getDuration() == null ? null : 0L,
                    normalizedSubRecords,
                    record.getDecisionEvidences()));
        }
        return normalized;
    }

    // ======================== BPMN fixture（代码内构造；至少一个已声明节点） ========================

    private static void deployEquivalenceBpmn(ProcessEngine engine) {
        String declaration = String.join(" ",
                mechanismAttribute(DecisionNodeDeclaration.DECISION_ENABLED, "true"),
                mechanismAttribute(DecisionNodeDeclaration.DECISION_DATA_SOURCES,
                        "PROCESS_VARIABLES,TASK_VARIABLES"),
                mechanismAttribute(DecisionNodeDeclaration.DECISION_TARGET, TARGET_KEY),
                mechanismAttribute(DecisionNodeDeclaration.DECISION_POLICY, POLICY_KEY));
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:flowable=\"http://flowable.org/bpmn\""
                + " xmlns:" + DecisionNodeDeclaration.NAMESPACE_PREFIX
                + "=\"" + DecisionNodeDeclaration.NAMESPACE_URI + "\""
                + " targetNamespace=\"http://flowable.plus/extension/test\">"
                + "<process id=\"" + PROCESS_KEY + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\" flowable:initiator=\"initiator\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"" + NODE_INITIATOR + "\"/>"
                + "<userTask id=\"" + NODE_INITIATOR + "\" name=\"发起\" flowable:assignee=\"${initiator}\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"" + NODE_INITIATOR + "\" targetRef=\"" + NODE_COUNTERSIGN + "\"/>"
                + "<userTask id=\"" + NODE_COUNTERSIGN + "\" name=\"会签\" flowable:assignee=\"${assignee}\">"
                + "<multiInstanceLoopCharacteristics isSequential=\"false\""
                + " flowable:collection=\"assignees\" flowable:elementVariable=\"assignee\"/>"
                + "</userTask>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"" + NODE_COUNTERSIGN + "\" targetRef=\"" + NODE_REVIEW + "\"/>"
                + "<userTask id=\"" + NODE_REVIEW + "\" name=\"复核\" flowable:assignee=\"${initiator}\" "
                + declaration + "/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"" + NODE_REVIEW + "\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/>"
                + "</process>"
                + "</definitions>";
        engine.getRepositoryService().createDeployment().addString(RESOURCE_NAME, xml).deploy();
    }

    private static String mechanismAttribute(String attributeName, String value) {
        return String.format("%s:%s=\"%s\"", DecisionNodeDeclaration.NAMESPACE_PREFIX, attributeName, value);
    }
}
