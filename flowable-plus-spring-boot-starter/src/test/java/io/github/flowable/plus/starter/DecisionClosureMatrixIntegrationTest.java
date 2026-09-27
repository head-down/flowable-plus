package io.github.flowable.plus.starter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import io.github.flowable.plus.extension.decision.DecisionNodeDeclarationValidator;
import io.github.flowable.plus.extension.decision.SuggestionSubmission;
import io.github.flowable.plus.extension.decision.SuggestionSubmissionService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ProcessValidatorImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S4 —— 三层关闭矩阵的 Spring 装配侧（ADR-0042 第 11 节第 1–3 条；{@code module-and-build} §2.4 附 /
 * §2.5 / §3）。断言名形态与 {@code docs/impl/0042-verification-landings.md} §4 的 S4 行逐字一致。
 *
 * <p>三个相互独立的上下文（沿用 {@code S5} 形态先例 —— 单类多上下文，零类序依赖）：</p>
 *
 * <ul>
 *   <li><b>全局关</b>（{@code @SpringBootTest}，{@code enabled} 默认 {@code false}）—— 独立 H2 库，
 *       承载全局关三断言 + G5 / B1 / B2 + validator 叠加式注册的装配断言；</li>
 *   <li><b>开态</b>（程序化 boot，{@code enabled=true}，auto-deploy 矩阵 BPMN）—— 承载节点声明 /
 *       推面两断言（正控：启用节点拉面真的触发并落证据行）；</li>
 *   <li><b>超限</b>（{@code ApplicationContextRunner} + mock 引擎服务）—— 承载超限收口 + WARN。</li>
 * </ul>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:decisionClosureMatrix;DB_CLOSE_DELAY=-1")
@Import({SharedTestConfiguration.class, DecisionMatrixTestConfiguration.class})
class DecisionClosureMatrixIntegrationTest extends AbstractIntegrationTest {

    private static final String MATRIX_RESOURCE = "bpmn-decision/test-decision-matrix.bpmn20.xml";
    private static final String PROCESS_KEY = "testDecisionMatrix";

    /** G5：属性类字段集恒等十二键（顶层 7 字段展开后恰为冻结的十二键） */
    private static final Set<String> TOP_LEVEL_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "enabled", "outboundConnectTimeout", "outboundReadTimeout", "totalBudget",
            "maxAttempts", "backoff", "executor")));
    private static final Set<String> BACKOFF_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "initial", "multiplier", "max")));
    private static final Set<String> EXECUTOR_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "coreSize", "maxSize", "queueCapacity", "threadNamePrefix")));

    /** B1：Guardrails 常量名闭集（恰十个数值键，不含 enabled 与 thread-name-prefix） */
    private static final Set<String> GUARDRAIL_CONSTANTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "OUTBOUND_CONNECT_TIMEOUT_MS", "OUTBOUND_READ_TIMEOUT_MS", "TOTAL_BUDGET_MS",
            "MAX_ATTEMPTS", "BACKOFF_INITIAL_MS", "BACKOFF_MULTIPLIER", "BACKOFF_MAX_MS",
            "EXECUTOR_CORE_SIZE", "EXECUTOR_MAX_SIZE", "EXECUTOR_QUEUE_CAPACITY")));

    /**
     * 机制 Bean 名闭集（全局关态仍注册 —— 全局开关是运行期门控而非装配条件）。
     *
     * <p>取 {@link DecisionAssemblyTestSupport#DECLARED_MECHANISM_BEAN_NAMES} 的真子集：恒等断言
     * （S2）里的两个配置类自身 Bean 与属性 Bean 在此处按类型断言（见
     * {@link #mechanismBeansRemainRegisteredWhenGlobalSwitchIsOff()}），名字面不含机制关键词。</p>
     */
    private static final List<String> MECHANISM_BEAN_NAMES;

    static {
        final List<String> declared = new ArrayList<>(DecisionAssemblyTestSupport.DECLARED_MECHANISM_BEAN_NAMES);
        declared.remove("flowablePlusDecisionAutoConfiguration");
        declared.remove("flowablePlusDecisionValidationAutoConfiguration");
        declared.remove("flowable.plus.decision-io.github.flowable.plus.starter.FlowablePlusDecisionProperties");
        MECHANISM_BEAN_NAMES = Collections.unmodifiableList(declared);
    }

    // ======================== 开态上下文（程序化 boot，全部开态断言共用） ========================

    private static final AtomicReference<ConfigurableApplicationContext> ON_CONTEXT = new AtomicReference<>();

    private static ConfigurableApplicationContext onContext() {
        synchronized (ON_CONTEXT) {
            if (ON_CONTEXT.get() == null) {
                final ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        BpmnQueryIntegrationTestApplication.class)
                        .sources(SharedTestConfiguration.class, DecisionMatrixTestConfiguration.class)
                        .web(WebApplicationType.NONE)
                        .run("--spring.datasource.url=jdbc:h2:mem:decisionClosureMatrixOn;DB_CLOSE_DELAY=-1",
                                "--spring.datasource.driver-class-name=org.h2.Driver",
                                "--spring.datasource.username=sa",
                                "--flowable.plus.decision.enabled=true",
                                "--flowable.process-definition-location-prefix=classpath:/bpmn-decision/");
                ON_CONTEXT.set(context);
            }
            return ON_CONTEXT.get();
        }
    }

    @AfterAll
    static void closeOnContext() {
        final ConfigurableApplicationContext context = ON_CONTEXT.getAndSet(null);
        if (context != null) {
            context.close();
        }
    }

    // ======================== 上下文 1：全局关（enabled 默认 false） ========================

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private SuggestionSubmissionService suggestionSubmissionService;

    @Autowired
    private io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow processLifecycleWorkflow;

    @Autowired
    private io.github.flowable.plus.core.workflow.TaskExecutionWorkflow taskExecutionWorkflow;

    @Test
    void globalOffLeavesExistingBehaviourUnchanged() {
        deployMatrix();
        final String processInstanceId = startInstance();
        final Task taskOn = taskByDefinitionKey(processInstanceId, "taskOn");
        // 既有行为：业务意见照常落、任务照常推进（走 core 门面 —— 事件链正常发射）
        BpmnQueryIntegrationTest.DynamicUserContext.set("approver");
        try {
            taskExecutionWorkflow.completeTask(taskOn.getId(), null, "正常审批意见，与本机制无关");
        } finally {
            BpmnQueryIntegrationTest.DynamicUserContext.CURRENT_USER.remove();
        }
        // 关闭态：既有轨迹在、本机制零痕迹（不留记录、非失败；拉面收不到回调也不留痕）
        final long decisionRows = decisionRowCount(processInstanceId);
        assertThat(taskService.getProcessInstanceComments(processInstanceId))
                .anyMatch(comment -> comment.getFullMessage() != null
                        && comment.getFullMessage().contains("正常审批意见"));
        assertThat(decisionRows).isZero();
        assertThat(taskByDefinitionKey(processInstanceId, "taskOff")).isNotNull();
    }

    @Test
    void globalOffSubmissionIsANoOpNotAFailure() {
        deployMatrix();
        final String processInstanceId = startInstance();
        final Task taskOn = taskByDefinitionKey(processInstanceId, "taskOn");
        final long before = decisionRowCount(processInstanceId);
        // 全局关：推面提交 = no-op 且非失败（不落记录、不抛准入异常）
        assertThatCode(() -> suggestionSubmissionService.submit(
                directSubmission(taskOn.getId(), "s4-globaloff-noop"))).doesNotThrowAnyException();
        assertThat(decisionRowCount(processInstanceId)).isEqualTo(before);
    }

    @Test
    void mechanismBeansRemainRegisteredWhenGlobalSwitchIsOff() {
        // 全局开关是运行期门控、非装配条件：机制 Bean（含 validator 与复核装配）一律在场
        for (final String beanName : MECHANISM_BEAN_NAMES) {
            assertThat(applicationContext.containsBean(beanName))
                    .as("机制 Bean 全局关态仍注册：%s", beanName)
                    .isTrue();
        }
        assertThat(applicationContext.getBean(FlowablePlusDecisionProperties.class)).isNotNull();
    }

    @Test
    void guardThresholdsAreNotConfigurable() {
        // G5：属性类字段集恒等十二键，三个护栏阈值不出现在配置面
        assertThat(declaredFieldNames(FlowablePlusDecisionProperties.class))
                .containsExactlyInAnyOrderElementsOf(TOP_LEVEL_FIELDS);
        assertThat(declaredFieldNames(FlowablePlusDecisionProperties.BackoffProperties.class))
                .containsExactlyInAnyOrderElementsOf(BACKOFF_FIELDS);
        assertThat(declaredFieldNames(FlowablePlusDecisionProperties.ExecutorProperties.class))
                .containsExactlyInAnyOrderElementsOf(EXECUTOR_FIELDS);
    }

    @Test
    void guardrailsCoversExactlyTheNumericKeys() {
        // B1：常量集恰等十个数值键（不含 enabled 与 thread-name-prefix）
        assertThat(staticFieldNames(DecisionGuardrails.class))
                .containsExactlyInAnyOrderElementsOf(GUARDRAIL_CONSTANTS);
    }

    @Test
    void guardrailsConstantsEqualPropertyFieldDefaults() {
        // B2：反射对账 —— 属性类每个数值字段的初始值 == 对应常量（单一数值承载位，不重复写数字）
        final FlowablePlusDecisionProperties properties = new FlowablePlusDecisionProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getOutboundConnectTimeout()).isEqualTo(DecisionGuardrails.OUTBOUND_CONNECT_TIMEOUT_MS);
        assertThat(properties.getOutboundReadTimeout()).isEqualTo(DecisionGuardrails.OUTBOUND_READ_TIMEOUT_MS);
        assertThat(properties.getTotalBudget()).isEqualTo(DecisionGuardrails.TOTAL_BUDGET_MS);
        assertThat(properties.getMaxAttempts()).isEqualTo(DecisionGuardrails.MAX_ATTEMPTS);
        assertThat(properties.getBackoff().getInitial()).isEqualTo(DecisionGuardrails.BACKOFF_INITIAL_MS);
        assertThat(properties.getBackoff().getMultiplier()).isEqualTo(DecisionGuardrails.BACKOFF_MULTIPLIER);
        assertThat(properties.getBackoff().getMax()).isEqualTo(DecisionGuardrails.BACKOFF_MAX_MS);
        assertThat(properties.getExecutor().getCoreSize()).isEqualTo(DecisionGuardrails.EXECUTOR_CORE_SIZE);
        assertThat(properties.getExecutor().getMaxSize()).isEqualTo(DecisionGuardrails.EXECUTOR_MAX_SIZE);
        assertThat(properties.getExecutor().getQueueCapacity()).isEqualTo(DecisionGuardrails.EXECUTOR_QUEUE_CAPACITY);
        assertThat(properties.getExecutor().getThreadNamePrefix()).isEqualTo("flowable-plus-decision-");
    }

    @Test
    void decisionValidatorIsAdditiveToEngineDefaults() {
        // 主闸叠加式注册（补齐断言，module-and-build §2.5）：引擎 26 个内置 validator 的组原样保留，
        // 本机制组具名 flowable-plus-decision 叠加其后 —— 不得整体替换
        final ProcessEngineConfigurationImpl configuration =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        final ProcessValidator configured = configuration.getProcessValidator();
        assertThat(configured).isInstanceOf(ProcessValidatorImpl.class);
        final Set<String> configuredSetNames = validatorSetNames(configured);
        final ProcessValidator reference = new ProcessValidatorFactory().createDefaultProcessValidator();
        assertThat(configuredSetNames)
                .containsAll(validatorSetNames(reference))
                .contains(DecisionNodeDeclarationValidator.VALIDATOR_SET_NAME);
    }

    // ======================== 上下文 2：超限（runner + mock 引擎服务） ========================

    @Test
    void overLimitConfigIsClampedWithStartupWarn() {
        // 直接用 logback 的 ListAppender 捕获启动期 WARN：SLF4J 门面无等价捕获物（测试期日志探针豁免）
        final Logger configLogger = (Logger) LoggerFactory.getLogger(FlowablePlusDecisionAutoConfiguration.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        configLogger.addAppender(appender);
        try {
            DecisionAssemblyTestSupport.baseRunner()
                    .withPropertyValues(
                            "flowable.plus.decision.outbound-connect-timeout=99999",
                            "flowable.plus.decision.outbound-read-timeout=99999",
                            "flowable.plus.decision.total-budget=999999",
                            "flowable.plus.decision.max-attempts=99",
                            "flowable.plus.decision.backoff.initial=99999",
                            "flowable.plus.decision.backoff.multiplier=9.9",
                            "flowable.plus.decision.backoff.max=99999",
                            "flowable.plus.decision.executor.core-size=99",
                            "flowable.plus.decision.executor.max-size=99",
                            "flowable.plus.decision.executor.queue-capacity=99")
                    .run(context -> {
                        // 不 fail-fast：超限静默收口
                        assertThat(context).hasNotFailed();
                        final ThreadPoolExecutor executor =
                                context.getBean("decisionExecutor", ThreadPoolExecutor.class);
                        assertThat(executor.getCorePoolSize()).isEqualTo(DecisionGuardrails.EXECUTOR_CORE_SIZE);
                        assertThat(executor.getMaximumPoolSize()).isEqualTo(DecisionGuardrails.EXECUTOR_MAX_SIZE);
                        assertThat(executor.getQueue().remainingCapacity())
                                .isEqualTo(DecisionGuardrails.EXECUTOR_QUEUE_CAPACITY);
                        // 每个被收口的键恰好一条启动期 WARN（十个数值键全部超限）
                        final long clampWarns = appender.list.stream()
                                .filter(event -> event.getLevel() == Level.WARN)
                                .filter(event -> event.getFormattedMessage().contains("已收口"))
                                .count();
                        assertThat(clampWarns).isEqualTo(10);
                    });
        } finally {
            configLogger.detachAppender(appender);
        }
    }

    // ======================== 上下文 3：开态（程序化 boot + auto-deploy 矩阵 BPMN） ========================

    @Test
    void nodeOffGatesPullAndEgressOnly() throws Exception {
        final ConfigurableApplicationContext context = onContext();
        final TaskService onTaskService = context.getBean(TaskService.class);
        final io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow lifecycle =
                context.getBean(io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow.class);
        final io.github.flowable.plus.core.workflow.TaskExecutionWorkflow execution =
                context.getBean(io.github.flowable.plus.core.workflow.TaskExecutionWorkflow.class);
        final SuggestionSubmissionService onSubmissionService = context.getBean(SuggestionSubmissionService.class);
        BpmnQueryIntegrationTest.DynamicUserContext.set("approver");
        try {
            // 正控：启用节点的拉面真的触发并落证据行（节点未声明数据源 ⇒ NO_SOURCE_DECLARED 行）
            final String processInstanceId =
                    lifecycle.startProcess(PROCESS_KEY, null, null).getProcessInstanceId();
            assertThat(await(() -> decisionRowCount(onTaskService, processInstanceId) >= 1, 15_000L))
                    .as("正控：启用节点 taskOn 的拉面应产出证据行")
                    .isTrue();
            final long rowsAfterPull = decisionRowCount(onTaskService, processInstanceId);
            // 推进到显式禁用节点 taskOff：索引命中 + parseEnabled(false) ⇒ 不入队、零痕迹
            final Task taskOn = taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskOn");
            execution.completeTask(taskOn.getId(), null, null);
            assertThat(await(() -> taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskOff") != null,
                    10_000L)).isTrue();
            // 缺席断言的观测窗：若回归使拉面误触发，管线会在窗口内产出证据行（正控路径实测 ~2s）
            Thread.sleep(3_000L);
            assertThat(decisionRowCount(onTaskService, processInstanceId))
                    .as("节点关只门控拉面：taskOff 不触发、不留记录")
                    .isEqualTo(rowsAfterPull);
            // 推面无节点级开关：节点关的节点上推面照常留痕
            final Task taskOff = taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskOff");
            onSubmissionService.submit(directSubmission(taskOff.getId(), "s4-nodeoff-push"));
            assertThat(await(() -> decisionRowCount(onTaskService, processInstanceId) >= rowsAfterPull + 1, 10_000L))
                    .as("节点关不门控推面：submit 应在 taskOff 上留痕")
                    .isTrue();
        } finally {
            BpmnQueryIntegrationTest.DynamicUserContext.CURRENT_USER.remove();
        }
    }

    @Test
    void pushSideHasNoNodeSwitch() throws Exception {
        final ConfigurableApplicationContext context = onContext();
        final TaskService onTaskService = context.getBean(TaskService.class);
        final SuggestionSubmissionService onSubmissionService = context.getBean(SuggestionSubmissionService.class);
        BpmnQueryIntegrationTest.DynamicUserContext.set("approver");
        try {
            // 未声明节点上不存在任何节点级开关可挡推面：submit 照常物质化
            final String processInstanceId = startViaFacade(context, PROCESS_KEY);
            completeUntilTaskPlain(context, onTaskService, processInstanceId);
            final Task taskPlain = taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskPlain");
            onSubmissionService.submit(directSubmission(taskPlain.getId(), "s4-plain-push"));
            assertThat(await(() -> decisionRowCount(onTaskService, processInstanceId) >= 1, 10_000L))
                    .as("推面无节点开关：未声明节点的直提应留痕")
                    .isTrue();
        } finally {
            BpmnQueryIntegrationTest.DynamicUserContext.CURRENT_USER.remove();
        }
    }

    @Test
    void undeclaredNodeIsNotEquivalentToZeroEvidence() throws Exception {
        final ConfigurableApplicationContext context = onContext();
        final TaskService onTaskService = context.getBean(TaskService.class);
        final SuggestionSubmissionService onSubmissionService = context.getBean(SuggestionSubmissionService.class);
        BpmnQueryIntegrationTest.DynamicUserContext.set("approver");
        try {
            final String processInstanceId = startViaFacade(context, PROCESS_KEY);
            completeUntilTaskPlain(context, onTaskService, processInstanceId);
            final long rowsBeforePush = decisionRowCount(onTaskService, processInstanceId);
            final Task taskPlain = taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskPlain");
            // 「节点未声明」≠「该节点零决策痕迹」：外部直提照常留痕
            onSubmissionService.submit(directSubmission(taskPlain.getId(), "s4-undeclared-trace"));
            assertThat(await(() -> decisionRowCount(onTaskService, processInstanceId) >= rowsBeforePush + 1, 10_000L))
                    .as("未声明节点的直提应留下决策痕迹")
                    .isTrue();
        } finally {
            BpmnQueryIntegrationTest.DynamicUserContext.CURRENT_USER.remove();
        }
    }

    // ======================== 内部件 ========================

    /** 部署矩阵 BPMN（全局关上下文内显式部署；注册面 Bean 在场 ⇒ 声明合法、部署成功） */
    private void deployMatrix() {
        repositoryService.createDeployment().addClasspathResource(MATRIX_RESOURCE).deploy();
    }

    private String startInstance() {
        // 走 core 门面发起（事件链照常发射；全局关下拉面被 stage 1 挡住、零痕迹）
        BpmnQueryIntegrationTest.DynamicUserContext.set("approver");
        try {
            return processLifecycleWorkflow.startProcess(PROCESS_KEY, null, null).getProcessInstanceId();
        } finally {
            BpmnQueryIntegrationTest.DynamicUserContext.CURRENT_USER.remove();
        }
    }

    private static String startViaFacade(final ConfigurableApplicationContext context, final String processKey) {
        return context.getBean(io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow.class)
                .startProcess(processKey, null, null).getProcessInstanceId();
    }

    private Task taskByDefinitionKey(final String processInstanceId, final String taskDefinitionKey) {
        return taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey).singleResult();
    }

    private static Task taskByDefinitionKeyOf(final TaskService taskService, final String processInstanceId,
                                              final String taskDefinitionKey) {
        return taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey).singleResult();
    }

    /** 推进到未声明节点 taskPlain（沿 taskOn → taskOff 两次完成；taskOn 的拉面行属正控、不影响断言面） */
    private static void completeUntilTaskPlain(final ConfigurableApplicationContext context,
                                               final TaskService onTaskService, final String processInstanceId)
            throws InterruptedException {
        final io.github.flowable.plus.core.workflow.TaskExecutionWorkflow execution =
                context.getBean(io.github.flowable.plus.core.workflow.TaskExecutionWorkflow.class);
        assertThat(await(() -> taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskOn") != null, 10_000L))
                .isTrue();
        execution.completeTask(
                taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskOn").getId(), null, null);
        assertThat(await(() -> taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskOff") != null, 10_000L))
                .isTrue();
        execution.completeTask(
                taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskOff").getId(), null, null);
        assertThat(await(() -> taskByDefinitionKeyOf(onTaskService, processInstanceId, "taskPlain") != null, 10_000L))
                .isTrue();
    }

    /** 合法直提提交（三次前置齐 + 依据面 BASIS_CODE ≥ 1 + 文本兜底；出处组与 modelId 全空 = 直提双射） */
    private static SuggestionSubmission directSubmission(final String taskId, final String idempotencyKey) {
        return SuggestionSubmission.builder()
                .taskId(taskId)
                .suggestedAction(ApprovalAction.AGREE)
                .idempotencyKey(idempotencyKey)
                .subjectType(DecisionSubjectType.USER)
                .subjectId("approver")
                .actionSummary("集成测试直提建议摘要")
                .rationaleNarrative("集成测试直提依据文本兜底")
                .rationaleFacts(Collections.singletonList(
                        new DecisionRationaleFact(DecisionRationaleFactKey.BASIS_CODE, "BASIS-S4")))
                .build();
    }

    private long decisionRowCount(final String processInstanceId) {
        return decisionRowCount(taskService, processInstanceId);
    }

    private static long decisionRowCount(final TaskService taskService, final String processInstanceId) {
        return taskService.getProcessInstanceComments(processInstanceId).stream()
                .map(Comment::getFullMessage)
                .filter(DecisionEvidenceComment::hasMarker)
                .count();
    }

    private static Set<String> declaredFieldNames(final Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> staticFieldNames(final Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> validatorSetNames(final ProcessValidator validator) {
        return ((ProcessValidatorImpl) validator).getValidatorSets().stream()
                .map(org.flowable.validation.validator.ValidatorSet::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 有界轮询等待（收敛条件驱动）。
     *
     * <p>轮询间隔 {@code Thread.sleep} 为轮询等待的必要形态：本票 pom 账本约束「测试零新增」，
     * 不能引入 Awaitility 一类等待库（java-libraries 建议已评、按账本约束不采纳）。</p>
     */
    private static boolean await(final BooleanSupplier condition, final long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200L);
        }
        return condition.getAsBoolean();
    }
}
