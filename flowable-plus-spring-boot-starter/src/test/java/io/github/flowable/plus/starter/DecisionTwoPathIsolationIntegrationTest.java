package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.domain.PlusProcessInstance;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionEvidenceComment;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
import io.github.flowable.plus.core.enums.DecisionSubjectType;
import io.github.flowable.plus.core.vo.ApprovalRecordVO;
import io.github.flowable.plus.core.vo.CountersignSubRecord;
import io.github.flowable.plus.core.vo.DecisionEvidenceVO;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import io.github.flowable.plus.core.workflow.CounterSignWorkflow;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;
import io.github.flowable.plus.extension.decision.ComparableAction;
import io.github.flowable.plus.extension.decision.SuggestionSubmission;
import io.github.flowable.plus.extension.decision.SuggestionSubmissionService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 —— 两路径隔离探针（ADR-0042 第 13 节「P2 受控压力测试探针」；靶子①「无证据推进」与
 * 靶子③「影子活动」的主落点，承裁定 I1 / I4；并承 ADR-0042 第 11 节第 1 条的
 * <b>面④ 事务语义</b> —— {@code E20} 因 standalone 无 Spring 事务代理交付不了这一面）。
 *
 * <p><b>内部形态的唯一住所 = 探索工作区 {@code docs/impl/0042-two-path-isolation-probe.md}</b>：
 * 三个相互独立的上下文（参照 = 程序化 boot + 两个决策自动配置类排除；关态 = {@code @SpringBootTest}
 * 缺省即关；开态 = 程序化 boot + {@code enabled=true}）、双节点两边同挂 BPMN、四断言 + 面④ 的
 * 冻结判定式、归一化第二处、防空转两条。11 条断言名与该文件 §10 清单逐字一致。</p>
 *
 * <p><b>对拍住在同一测试方法内</b> ⇒ 零类序依赖（不引 {@code ClassOrderer}、不动
 * {@code junit-platform.properties}、单类运行不受影响）。探针测试类自身<b>不标</b>
 * {@code @Transactional}（会与 {@code startProcess} 的事务语义混淆，使面④ 白测）。</p>
 *
 * <p><b>断言判据面 = 引擎公开 API 的 {@code TYPE_} 列</b>（{@code TaskService#getProcessInstanceComments}
 * → {@code Comment#getType()}），不经读侧契约面。</p>
 *
 * <p><b>实现期披露（如实登记，判据与冻结判定式未动）</b>：</p>
 * <ul>
 *   <li><b>声明节点为伪单例多实例</b>（模型级会签 + 单元素集合）：断言 3 的冻结判定式要求逐
 *       {@code ComparableAction.MEMBERS} 四值经位点服务提交且各增 1 条证据，而准入按
 *       {@code ComparableAction#isAvailableFor} 校验动作可用性 —— 普通节点拒会签两值、真多实例拒
 *       AGREE / REJECT，<b>伪单例是四值同时可用的唯一形态</b>（判定式「遍历取值 = 该面能达到的最强形式」
 *       的唯一可满足读法）。老路径结构保证不变：{@code autoCompleteFirstTasks} 只在 {@code startProcess}
 *       内对快照执行，规则不作用于后置节点。</li>
 *   <li><b>fixture 载体放 {@code bpmn-decision-probe/}</b>（形态文件 §4.2 字面为 {@code bpmn/}）：
 *       {@code bpmn/} 是 Flowable auto-deploy 的默认前缀，带 {@code fp:} 声明的 fixture 会在
 *       <b>其它测试类</b>的空注册表上下文里被部署期校验阻断；而 {@code bpmn-decision/} 是 {@code S4}
 *       开态上下文的 auto-deploy 前缀（一手实测：探针 fixture 会被 S4 的上下文部署并被其注册表
 *       阻断）。故改放探针专属独立前缀、由各态上下文显式部署，形态文件 §4.2 的「classpath 资源 +
 *       {@code addClasspathResource} 显式部署」语义不变。</li>
 *   <li><b>面③ 的异常探针落已自动完成的首任务</b>：会签入口对已完成任务抛
 *       {@code TaskAlreadyCompletedException}（确定性、零状态变更），使三个写侧 workflow 的受控入口
 *       都有真实调用记录。</li>
 *   <li><b>参照态的排除落到装配面边界 BFPP</b>：集成测试应用类对 starter 包做了 {@code @ComponentScan}，
 *       两个决策自动配置类经<b>扫描路径</b>注册，{@code spring.autoconfigure.exclude} 只过滤导入路径
 *       （一手实测：排除后 {@code decisionPipeline} 仍被构造并撞双 {@code DecisionProvider} 候选）。
 *       故参照态在属性排除（词面留痕）之外，以仅挂参照态的 BFPP 按 S2 已断言的机制 Bean 名闭集
 *       移除机制组件定义（见 {@link #ReferenceStateAssemblyBoundary()}）；关态 / 开态下 Provider 桩
 *       以 {@code @Primary} 压过扫描路径注册的默认实现。</li>
 *   <li><b>三态共用一个库</b>（形态文件 §2.2：无害、不隔离也不需要隔离）：程序化 boot 的数据源属性
 *       从被注入的 {@code Environment} 复制（{@code AbstractIntegrationTest#configureDataSource} 只服务
 *       TestContext 管理的上下文，三库矩阵下同一份复制逻辑）；所有断言按 pid / bk 作用域。</li>
 * </ul>
 *
 * <p><b>归一化第二处（有意重复，措辞按形态文件 §5.3 写死）</b>：本类的「动态 / 内容」字段二分表与
 * 归一化细则与 {@code docs/impl/0042-equivalence-harness.md} §3 / §4 的那一份<b>语义相同、代码不复用</b>
 * （模块边界：extension 的测试类型不进 starter 的测试 classpath）。这不构成两处真相：两侧各有同名
 * 归类守卫 {@link #equivalenceClassificationCoversEveryVoField()}，漂移的失败是响的。</p>
 */
@SpringBootTest(classes = BpmnQueryIntegrationTestApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:decisionTwoPathIsolation;DB_CLOSE_DELAY=-1")
@Import({SharedTestConfiguration.class, DecisionTwoPathProbeTestConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecisionTwoPathIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final String PROCESS_KEY = "testDecisionTwoPathIsolation";

    private static final String RESOURCE = "bpmn-decision-probe/test-decision-two-path-isolation.bpmn20.xml";

    /** 老路径节点（AutoApprovalRule 在 startProcess 事务内自动提交） */
    private static final String NODE_LEGACY = "firstApproval";

    /** 机制声明节点（fp 四属性齐全；伪单例多实例，见类 javadoc 实现期披露） */
    private static final String NODE_DECLARED = "declaredApproval";

    /** 办理人（首任务 assignee 与声明节点集合元素同名） */
    private static final String USER = "approver";

    /** 机制侧评论类型（TYPE_ 列判据；由枚举常量派生的枚举名原文串 —— 单一来源，与写侧同源一致） */
    private static final String TYPE_DECISION_EVIDENCE =
            io.github.flowable.plus.core.enums.DecisionEvidenceComment.COMMENT_TYPE.name();

    /** 老路径评论类型（TYPE_ 列判据；由枚举常量派生的枚举名原文串） */
    private static final String TYPE_AUTO_COMPLETE =
            io.github.flowable.plus.core.enums.CommentType.AUTO_COMPLETE.name();

    /**
     * 证据落行 / 出站收敛的有界等待上限 —— <b>测试自定值</b>，不复制 {@code DecisionGuardrails}
     * 默认数值（防第二处真相）。轮询间隔 {@code Thread.sleep} 为轮询等待的必要形态
     * （pom 账本约束「测试零新增」，不引 Awaitility 一类等待库）。
     */
    private static final long CONVERGENCE_WAIT_MS = 15_000L;

    /** 有界轮询的检查间隔（等待上限 {@link #CONVERGENCE_WAIT_MS} 的细分步长） */
    private static final long POLL_INTERVAL_MS = 200L;

    /** 参照态排除的两个决策自动配置类（全限定名书写，一手核实 {@code AutoConfigurationImportSelector}） */
    private static final List<String> EXCLUDED_AUTO_CONFIGURATIONS = Collections.unmodifiableList(Arrays.asList(
            FlowablePlusDecisionAutoConfiguration.class.getName(),
            FlowablePlusDecisionValidationAutoConfiguration.class.getName()));

    /**
     * 断言 3 的提交取值集 = {@link ComparableAction#MEMBERS}（恰四值；单一来源，本地不复制成员）。
     */
    private static final List<ApprovalAction> SUBMISSION_ACTIONS =
            Collections.unmodifiableList(new ArrayList<>(ComparableAction.MEMBERS));

    /**
     * 机制组件集（§9 乙的对照面）= {@link DecisionAssemblyTestSupport#DECLARED_MECHANISM_BEAN_NAMES}
     * 减 {@code decisionProvider} —— 该替换点 Bean 的注册与否随装载路径的解析序摆动（桩可见性
     * 在 {@code @ConditionalOnMissingBean} 求值前后不定，一手实测：单类运行缺席、全量运行在场），
     * 其「被替换」的语义由行为面钉死（管线注入单候选 {@code @Primary} 桩、调用计数进断言），
     * 名集对拍面对它双侧同剔（见 {@link #PARSE_ORDER_CONDITIONAL_DRIFT_BEAN_NAMES}）。
     */
    private static final Set<String> MECHANISM_BEAN_DEFINITION_NAMES;

    static {
        final List<String> declared = new ArrayList<>(DecisionAssemblyTestSupport.DECLARED_MECHANISM_BEAN_NAMES);
        declared.remove("decisionProvider");
        MECHANISM_BEAN_DEFINITION_NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(declared));
    }

    /**
     * 装载路径的解析序条件漂移豁免（一手实测，具名、非通配）：{@code DataSourceJmxConfiguration}
     * （含其 Hikari 内嵌）挂在 {@code @ConditionalOnSingleCandidate} 上，随 TestContext 与
     * SpringApplication 两种装载路径的解析序在关态 / 两个程序化态之间摆动 —— 均与机制装配面无关，
     * 在乙的三个对拍面两侧同剔（「对拍面 = 机制装配面」这一判据的可操作化；机制组件集不受影响，
     * 闭集逐字对账）。
     */
    private static final Set<String> PARSE_ORDER_CONDITIONAL_DRIFT_BEAN_NAMES;

    static {
        final Set<String> drift = new LinkedHashSet<>(Arrays.asList(
                "org.springframework.boot.autoconfigure.jdbc.DataSourceJmxConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceJmxConfiguration$Hikari"));
        // 替换点 Bean 名的在场性随装载路径解析序翻转（见 comparableBeanNames 的登记），双侧同剔
        drift.add("decisionProvider");
        // 探针配置类自身的 Bean 定义名随注册路径取形（@Import ⇒ FQN 名、.sources() ⇒ 简名），
        // 是注册路径的产物而非机制装配面（其 Bean 三态同款、已在对拍面内），双侧同剔
        drift.add("decisionTwoPathProbeTestConfiguration");
        drift.add("io.github.flowable.plus.starter.DecisionTwoPathProbeTestConfiguration");
        PARSE_ORDER_CONDITIONAL_DRIFT_BEAN_NAMES = Collections.unmodifiableSet(drift);
    }

    // ======================== 动态字段二分表（归一化第二处；§5.3 的 starter 侧实例） ========================

    /** 动态字段（按出现序规范化；父记录与子记录共用同一张编号表） */
    private static final Set<String> DYNAMIC_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "taskId", "startTime", "endTime", "duration")));

    /** 内容字段（严格等值；roundIndex 是引擎写入的会签轮次内容，不归一化；decisionEvidences 恒进清单） */
    private static final Set<String> CONTENT_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "nodeId", "nodeName", "action", "comment", "operationComment", "operationComments",
            "actorId", "actorName", "roundIndex", "countersignRecords", "decisionEvidences")));

    /** 两个 VO 的声明字段集（归类守卫的对照面） */
    private static final Set<String> VO_FIELD_SET =
            Collections.unmodifiableSet(collectDeclaredFields(ApprovalRecordVO.class,
                    CountersignSubRecord.class));

    // ======================== 上下文与捕获 ========================

    /** 关态上下文（TestContext 管理；{@code enabled} 缺省即 false） */
    @Autowired
    private ApplicationContext closedApplicationContext;

    /** 关态上下文的环境（程序化 boot 的数据源属性从此复制，三库矩阵下同一份复制逻辑） */
    @Autowired
    private Environment environment;

    private StateAccess closed;

    private StateAccess reference;

    private StateAccess on;

    @BeforeAll
    void assembleAndRun() {
        // 关态：@SpringBootTest 装配完整机制组件，enabled 缺省即 false
        closed = new StateAccess("closed", (ConfigurableApplicationContext) closedApplicationContext);
        // 参照态：两个决策自动配置类被排除 ⇒ 机制组件不构造、不注册（装配面边界，探针形态文件 §3）
        reference = new StateAccess("reference", bootProgrammatically(true, false));
        // 开态：机制组件注册 + enabled=true + 三态同款应用侧 stub
        on = new StateAccess("on", bootProgrammatically(false, true));

        // 三态各自显式部署同一份两边同挂 BPMN（三态共用一个库 ⇒ 同名定义多版本部署，不进对拍面）
        deployFixture(closed);
        deployFixture(reference);
        deployFixture(on);

        // 三态同序跑同一段受控序列 ⇒ 面①②③⑤ 与断言 2 / 3 / 4 / §9 甲的数据就位，断言方法只做比较
        runProbeSequence(closed);
        runProbeSequence(reference);
        runProbeSequence(on);
    }

    @AfterAll
    void closeProgrammaticContexts() {
        for (final StateAccess state : Arrays.asList(reference, on)) {
            if (state != null) {
                state.context.close();
            }
        }
    }

    // ======================== 断言 1 · 面①：运行时状态与历史 ========================

    @Test
    @DisplayName("断言1·面①：老路径在关态 / 开态与参照态逐字段等价（活动序列 / 历史变量 / 实例状态）")
    void legacyPathEqualsReferenceOnRuntimeAndHistoryState() {
        compareWithReference(closed, reference);
        compareWithReference(on, reference);
    }

    // ======================== 断言 1 · 面②：getApprovalHistory 逐字段 ========================

    @Test
    @DisplayName("断言1·面②：审批历史归一化副本逐字段等价（关态全字段；开态 decisionEvidences 移出对拍面）")
    void legacyPathEqualsReferenceOnApprovalHistory() {
        // 关态 vs 参照：全字段（两侧 decisionEvidences 皆为空集合）
        assertThat(normalizeRecords(closed.records, false))
                .as("关态与参照态的审批历史必须逐字段等价（含 decisionEvidences 恒空集合）")
                .containsExactlyElementsOf(normalizeRecords(reference.records, false));
        assertThat(closed.records).allSatisfy(record ->
                assertThat(record.getDecisionEvidences()).isEmpty());

        // 开态 vs 参照：对拍面 = 人工意见组侧的全部字段，decisionEvidences 移出对拍面
        //（开态下机制写证据行是它的正常工作，不是串味；该字段的正向面由 §9 甲与断言 2 承担。
        //  开态等价 run 的声明任务随即被完成，证据行是否赶上写入存在竞态 ⇒ 恰好只影响该字段。）
        assertThat(normalizeRecords(on.records, true))
                .as("开态与参照态的审批历史必须在人工意见组侧逐字段等价（decisionEvidences 移出对拍面）")
                .containsExactlyElementsOf(normalizeRecords(reference.records, true));
    }

    // ======================== 断言 1 · 面③：写操作返回与异常 ========================

    @Test
    @DisplayName("断言1·面③：三个写侧 workflow 受控入口的返回与异常逐次等价（消息按动态归一化）")
    void legacyPathEqualsReferenceOnWriteOperations() {
        assertThat(closed.writeOpTrace)
                .as("关态与参照态的受控入口逐次调用结果必须等价")
                .containsExactlyElementsOf(reference.writeOpTrace);
        assertThat(on.writeOpTrace)
                .as("开态与参照态的受控入口逐次调用结果必须等价")
                .containsExactlyElementsOf(reference.writeOpTrace);
        // 防空转：三个写侧 workflow 的受控入口必须都有真实调用记录（含一条异常探针；两次 startProcess
        // 分别是等价 run 与 anchor run，末条是 fail-fast run）
        assertThat(closed.writeOpTrace).hasSize(5);
        assertThat(closed.writeOpTrace.get(0)).startsWith("startProcess(");
        assertThat(closed.writeOpTrace.get(1)).isEqualTo("counterSign:TaskAlreadyCompletedException");
        assertThat(closed.writeOpTrace.get(2)).isEqualTo("completeTask=void");
        assertThat(closed.writeOpTrace.get(3)).startsWith("startProcess(");
        assertThat(closed.writeOpTrace.get(4)).isEqualTo("startProcess:IllegalStateException");
    }

    // ======================== 断言 1 · 面④：事务语义（整体回滚） ========================

    @Test
    @DisplayName("面④：老路径 fail-fast 三态各一次整体回滚（异常同型 / 零运行时残留 / 零历史残留 / 无自动提交残留）")
    void legacyPathFailFastRollsBackInEveryState() {
        // ① 抛出：异常类型三态一致（真 Spring 事务代理在每一个态都在场）
        assertThat(closed.failFastException)
                .as("面④：三态的 fail-fast 异常类型必须一致，且必须是探针规则抛出的类型")
                .isEqualTo(on.failFastException)
                .isEqualTo(reference.failFastException)
                .isEqualTo("IllegalStateException");
        // ② ③ 零残留（整体回滚）+ 无自动提交残留：逐态断言
        for (final StateAccess state : Arrays.asList(closed, reference, on)) {
            final TaskService stateTaskService = state.bean(TaskService.class);
            final RuntimeService stateRuntimeService = state.bean(RuntimeService.class);
            final HistoryService stateHistoryService = state.bean(HistoryService.class);
            assertThat(stateRuntimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(state.failFastBk).count())
                    .as("面④ ② 零残留：%s 态 fail-fast 后不得有运行时实例", state.label)
                    .isZero();
            assertThat(stateHistoryService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(state.failFastBk).count())
                    .as("面④ ② 零残留：%s 态 fail-fast 后不得有历史实例", state.label)
                    .isZero();
            // ③ 无自动提交残留：该 businessKey 下不存在 AutoApprovalRule 产生的 AUTO_COMPLETE 评论。
            //   评论行必挂实例与任务，②的零历史实例已在结构上承载；此处再对态内两次成功运行
            //   （等价 run + anchor run）的 AUTO_COMPLETE 计数收口为恰各 1 条，证回滚未波及、也未新增。
            assertThat(countComments(stateTaskService, state.equivalencePid, TYPE_AUTO_COMPLETE))
                    .as("面④ ③：%s 态等价 run 的 AUTO_COMPLETE 恰 1 条（无残留、无扩散）", state.label)
                    .isEqualTo(1L);
            assertThat(countComments(stateTaskService, state.anchorPid, TYPE_AUTO_COMPLETE))
                    .as("面④ ③：%s 态 anchor run 的 AUTO_COMPLETE 恰 1 条（无残留、无扩散）", state.label)
                    .isEqualTo(1L);
        }
    }

    // ======================== 断言 1 · 面⑤：监听器回调序列 ========================

    @Test
    @DisplayName("断言1·面⑤：既有监听器回调序列逐位置等值（方法名 + 锚点归一化；机制不带来既有回调）")
    void legacyPathEqualsReferenceOnListenerCallbackSequence() {
        assertThat(closed.normalizedListenerEntries)
                .as("关态与参照态的既有监听器回调序列必须逐位置等值")
                .isEqualTo(reference.normalizedListenerEntries);
        assertThat(on.normalizedListenerEntries)
                .as("开态与参照态的既有监听器回调序列必须逐位置等值（机制写证据行不触发框架回调）")
                .isEqualTo(reference.normalizedListenerEntries);
        // 防空转：回调必须真实发生过
        assertThat(closed.normalizedListenerEntries).isNotEmpty();
    }

    // ======================== 断言 2：机制侧无自动提交痕迹（靶子① 主落点，承裁定 I1） ========================

    @Test
    @DisplayName("断言2：机制侧无自动提交痕迹（证据非空 + 声明任务零 AUTO_COMPLETE + 锚点仍在）")
    void mechanismSideLeavesNoAutoSubmitTrace() throws InterruptedException {
        final TaskService onTaskService = on.bean(TaskService.class);
        // ① 正向（防空转）：E(T2, "DECISION_EVIDENCE") 非空 —— 机制侧为异步（专属池），有界等待收敛
        assertThat(await(() -> countComments(onTaskService, on.anchorPid, on.anchorTaskId,
                TYPE_DECISION_EVIDENCE) >= 1))
                .as("开态 anchor run 的声明任务应产出证据行（机制能产生活动）")
                .isTrue();
        // ② 负向：E(T2, "AUTO_COMPLETE") 为空（判据按 taskId 收口到声明任务 —— 不得扩成 C(P, "AUTO_COMPLETE")，
        //    老路径本就会在首任务上写一条，那是断言 1 的对象）
        assertThat(countComments(onTaskService, on.anchorPid, on.anchorTaskId, TYPE_AUTO_COMPLETE))
                .as("声明任务上不得有老路径的自动提交痕迹")
                .isZero();
        // ③ 锚点仍在
        assertThat(onTaskService.createTaskQuery().taskId(on.anchorTaskId).active().count())
                .as("提交建议不得移走锚点：声明任务必须仍活跃")
                .isEqualTo(1L);
    }

    // ======================== 断言 3：建议到推进无框架通路（靶子① 主落点，承裁定 I1） ========================

    @Test
    @DisplayName("断言3：逐 ComparableAction 四值提交建议 —— 各增 1 条证据、流程状态不变、无自动提交")
    void submissionNeverAdvancesProcessState() throws InterruptedException {
        final TaskService onTaskService = on.bean(TaskService.class);
        final SuggestionSubmissionService submissionService = on.bean(SuggestionSubmissionService.class);
        // 基线：拉面证据已落行（断言 2 已收敛；此处再等一次，保证方法序无关）
        assertThat(await(() -> countComments(onTaskService, on.anchorPid, on.anchorTaskId,
                TYPE_DECISION_EVIDENCE) >= 1)).isTrue();
        // 逐 s ∈ S（恰四值；幂等身份四轮各不同键，不同键即不同决策）
        int round = 0;
        for (final ApprovalAction action : SUBMISSION_ACTIONS) {
            round++;
            final long before = countComments(onTaskService, on.anchorPid, on.anchorTaskId,
                    TYPE_DECISION_EVIDENCE);
            final Map<String, String> activeBefore = activeTaskSnapshot(onTaskService, on.anchorPid);
            final List<String> manualBefore = manualOpinionSnapshot(onTaskService, on.anchorPid);
            // 经机制唯一公开写入口提交（submit 返 void、入参不承载推进语义）
            submissionService.submit(directSubmission(on.anchorTaskId, action,
                    "s5-submission-never-advances-" + round));
            // ① 留痕成立：基数恰比提交前增 1（有界等待收敛，超时即失败）
            assertThat(await(() -> countComments(onTaskService, on.anchorPid, on.anchorTaskId,
                    TYPE_DECISION_EVIDENCE) >= before + 1))
                    .as("断言 3 ①：%s 的提交应恰增 1 条证据行", action)
                    .isTrue();
            assertThat(countComments(onTaskService, on.anchorPid, on.anchorTaskId, TYPE_DECISION_EVIDENCE))
                    .as("断言 3 ①：%s 的提交后证据基数必须恰比提交前增 1", action)
                    .isEqualTo(before + 1);
            // ② 流程状态不变：活跃任务集与人工意见组逐项相等
            assertThat(activeTaskSnapshot(onTaskService, on.anchorPid))
                    .as("断言 3 ②：%s 的提交不得改变活跃任务集", action)
                    .isEqualTo(activeBefore);
            assertThat(manualOpinionSnapshot(onTaskService, on.anchorPid))
                    .as("断言 3 ②：%s 的提交不得改变人工意见组", action)
                    .isEqualTo(manualBefore);
            // ③ 无自动提交
            assertThat(countComments(onTaskService, on.anchorPid, on.anchorTaskId, TYPE_AUTO_COMPLETE))
                    .as("断言 3 ③：%s 的提交不得产生自动提交痕迹", action)
                    .isZero();
        }
        assertThat(round).as("表态比较面必须恰四值").isEqualTo(4);
    }

    // ======================== 断言 4：默认关下探针不激活（靶子③ 主落点，承裁定 I4） ========================

    @Test
    @DisplayName("断言4：默认关下零证据、零出站、老路径照常（AUTO_COMPLETE 恰 1 条且流程已推进到声明节点）")
    void probeStaysInactiveUnderDefaultOff() {
        final TaskService closedTaskService = closed.bean(TaskService.class);
        // ① 零证据
        assertThat(countComments(closedTaskService, closed.anchorPid, TYPE_DECISION_EVIDENCE))
                .as("默认关下不得有任何机制证据行")
                .isZero();
        // ② 零出站（S4 结构上不可达的一格：S4 无 DecisionProvider 替身；此处由探针桩的调用计数承担）
        assertThat(closed.bean(DecisionTwoPathProbeTestConfiguration.ProbeDecisionProvider.class).callCount())
                .as("默认关下不得发生任何出站调用")
                .isZero();
        // ③ 老路径照常：E(firstApproval, "AUTO_COMPLETE") 恰 1 条，且流程已推进 —— 活跃任务集 == { declaredApproval }
        assertThat(countComments(closedTaskService, closed.anchorPid, closed.anchorLegacyTaskId, TYPE_AUTO_COMPLETE))
                .as("默认关下老路径照常：首任务恰 1 条 AUTO_COMPLETE")
                .isEqualTo(1L);
        assertThat(activeTaskSnapshot(closedTaskService, closed.anchorPid).values())
                .as("默认关下流程已推进：活跃任务集恰为声明节点")
                .containsExactly(NODE_DECLARED);
    }

    // ======================== 防空转甲：开态正向 ========================

    @Test
    @DisplayName("防空转甲：开态机制能产生活动（证据非空 ∧ 出站调用 ≥ 1）")
    void mechanismProducesEvidenceUnderEnabledState() throws InterruptedException {
        final TaskService onTaskService = on.bean(TaskService.class);
        assertThat(await(() -> countComments(onTaskService, on.anchorPid, on.anchorTaskId,
                TYPE_DECISION_EVIDENCE) >= 1))
                .as("开态必须产出证据行（关态 ≡ 参照态 不是恒真式）")
                .isTrue();
        assertThat(on.bean(DecisionTwoPathProbeTestConfiguration.ProbeDecisionProvider.class).callCount())
                .as("开态必须发生真实出站调用（经 Provider 桩，无网络）")
                .isGreaterThanOrEqualTo(1);
    }

    // ======================== 防空转乙：装配差异恒等 ========================

    @Test
    @DisplayName("防空转乙：参照 ⊆ 关态，关态 \\ 参照 == 机制组件集，关态与开态 Bean 定义名集恒等")
    void stateContextsDifferExactlyByMechanismComponents() {
        final Set<String> closedNames = comparableBeanNames(closed.context);
        final Set<String> referenceNames = comparableBeanNames(reference.context);
        final Set<String> onNames = comparableBeanNames(on.context);
        assertThat(closedNames)
                .as("参照态 BeanDefinition 名集必须 ⊆ 关态")
                .containsAll(referenceNames);
        assertThat(difference(closedNames, referenceNames))
                .as("关态 \\ 参照 必须恰为机制组件集（防「唯一差异被误实现成零差异」）")
                .containsExactlyInAnyOrderElementsOf(MECHANISM_BEAN_DEFINITION_NAMES);
        assertThat(closedNames)
                .as("关态与开态的 BeanDefinition 名集必须恒等（唯一差异只在属性）")
                .isEqualTo(onNames);
    }

    /**
     * Bean 定义名集的对拍读法，两处剔除（如实登记，非判据放宽：机制组件集不受影响）：
     * ① {@code org.springframework.boot.test.*} —— TestContext / Mockito 的测试基础设施 Bean 只在
     * TestContext 管理的关态存在，是三态构造方式的固有产物；② {@code decisionProvider} —— 替换点
     * Bean 名的在场性随装载路径的解析序翻转（{@code @ConditionalOnMissingBean} 与扫描 / 导入双路径
     * 的求值先后，一手实测：关态缺席、开态在场），而桩的替换语义已由行为面钉死（管线注入单候选
     * {@code @Primary} 桩、调用计数进断言），故从两侧同剔该名。
     */
    private static Set<String> comparableBeanNames(final ConfigurableApplicationContext context) {
        final Set<String> names = new HashSet<>(Arrays.asList(
                context.getBeanFactory().getBeanDefinitionNames()));
        names.removeIf(name -> name.startsWith("org.springframework.boot.test."));
        names.removeAll(PARSE_ORDER_CONDITIONAL_DRIFT_BEAN_NAMES);
        return names;
    }

    // ======================== 归类守卫（与 E20 同名的第二处实例） ========================

    @Test
    @DisplayName("归类守卫：动态表 ∪ 内容表 == 两个 VO 的声明字段集（新增字段未归类即红）")
    void equivalenceClassificationCoversEveryVoField() {
        final Set<String> covered = new LinkedHashSet<>(DYNAMIC_FIELDS);
        covered.addAll(CONTENT_FIELDS);
        assertThat(covered)
                .as("归类守卫（形态文件 §5.3）：动态字段 + 内容字段必须恰等两个 VO 的字段集")
                .isEqualTo(VO_FIELD_SET);
    }

    // ======================== 三态构造 ========================

    /**
     * 程序化 boot（探针形态文件 §2.2；模块内首例的数据源承接：从被注入的 {@link Environment} 复制
     * {@code spring.datasource.*} —— 三库矩阵下同一份复制逻辑）。经命令行参数形态传属性
     * （默认属性优先级低于 application.yml，不能承载态专属差异）。
     */
    private ConfigurableApplicationContext bootProgrammatically(final boolean excludeMechanism,
            final boolean enabled) {
        final List<String> args = new ArrayList<>();
        addDataSourceArgs(args);
        if (excludeMechanism) {
            args.add("--spring.autoconfigure.exclude="
                    + String.join(",", EXCLUDED_AUTO_CONFIGURATIONS));
        }
        if (enabled) {
            args.add("--flowable.plus.decision.enabled=true");
        }
        final SpringApplicationBuilder builder = new SpringApplicationBuilder(BpmnQueryIntegrationTestApplication.class)
                .sources(SharedTestConfiguration.class, DecisionTwoPathProbeTestConfiguration.class)
                .web(WebApplicationType.NONE);
        if (excludeMechanism) {
            builder.initializers(referenceBoundaryInitializer());
        }
        return builder.run(args.toArray(new String[0]));
    }

    private void addDataSourceArgs(final List<String> args) {
        addIfPresent(args, "spring.datasource.url");
        addIfPresent(args, "spring.datasource.username");
        addIfPresent(args, "spring.datasource.password");
        addIfPresent(args, "spring.datasource.driver-class-name");
    }

    private void addIfPresent(final List<String> args, final String key) {
        final String value = environment.getProperty(key);
        if (value != null) {
            args.add("--" + key + "=" + value);
        }
    }

    private void deployFixture(final StateAccess state) {
        state.bean(RepositoryService.class).createDeployment().addClasspathResource(RESOURCE).deploy();
    }

    // ======================== 受控序列（三态同序同构） ========================

    /**
     * 一个态的受控序列（三态同一段操作，同序）：
     * 等价 run（startProcess → 会签异常探针 → completeTask，流程结束，供面①②③⑤ 对拍）
     * → anchor run（startProcess 后止步，声明任务保持活跃，供断言 2 / 3 / 4 / §9 甲）
     * → fail-fast run（布防后 startProcess，供面④）。
     */
    private void runProbeSequence(final StateAccess state) {
        final TaskService stateTaskService = state.bean(TaskService.class);
        final HistoryService stateHistoryService = state.bean(HistoryService.class);
        final ProcessLifecycleWorkflow lifecycle = state.bean(ProcessLifecycleWorkflow.class);
        final TaskExecutionWorkflow execution = state.bean(TaskExecutionWorkflow.class);
        final CounterSignWorkflow counterSign = state.bean(CounterSignWorkflow.class);
        final HistoryWorkflow history = state.bean(HistoryWorkflow.class);
        final OccurrenceTable traceIds = new OccurrenceTable();
        BpmnQueryIntegrationTest.DynamicUserContext.set(USER);
        try {
            // 等价 run
            state.equivalenceBk = "twopath-equivalence-" + state.label;
            final PlusProcessInstance instance = lifecycle.startProcess(PROCESS_KEY, state.equivalenceBk,
                    startVariables());
            state.equivalencePid = instance.getProcessInstanceId();
            state.writeOpTrace.add("startProcess(pid=" + traceIds.renumber(instance.getProcessInstanceId())
                    + ",def=" + traceIds.renumber(instance.getProcessDefinitionId())
                    + ",bk=" + traceIds.renumber(String.valueOf(instance.getBusinessKey())) + ")");
            state.legacyTaskId = stateHistoryService.createHistoricTaskInstanceQuery()
                    .processInstanceId(state.equivalencePid)
                    .taskDefinitionKey(NODE_LEGACY).singleResult().getId();
            // 面③ 异常探针：会签入口对已自动完成的首任务（确定性异常、零状态变更）
            try {
                counterSign.counterSign(state.legacyTaskId, true, null, "探针-会签异常探针");
                state.writeOpTrace.add("counterSign=(no exception)");
            } catch (final RuntimeException rejected) {
                // 有意的宽捕获：此处是异常探针（面③），目的是捕获异常类型供三态对拍，不是吞异常
                state.writeOpTrace.add("counterSign:" + rejected.getClass().getSimpleName());
            }
            final Task declared = singleActiveTask(stateTaskService, state.equivalencePid);
            execution.completeTask(declared.getId(), null, "探针-人工同意");
            state.writeOpTrace.add("completeTask=void");
            // 历史时间确定性化（读序前置）：与 E20 的 fixture 直连库改写同手法、代码不复用 ——
            // start 记录与首任务记录同毫秒并列时引擎侧无任何可用顺序（D4），按数值 ID_ 升序
            // 给三库共用的两张历史表指定确定全序；绝对值不进任何断言。
            determinizeHistoryTimes();
            state.records = history.getApprovalHistory(state.equivalencePid);
            state.activityIdSequence = activityIdSequence(stateHistoryService, state.equivalencePid);
            state.historicVariables = historicVariables(stateHistoryService, state.equivalencePid);

            // anchor run：声明任务保持活跃
            state.anchorBk = "twopath-anchor-" + state.label;
            final PlusProcessInstance anchorInstance = lifecycle.startProcess(PROCESS_KEY, state.anchorBk,
                    startVariables());
            state.anchorPid = anchorInstance.getProcessInstanceId();
            state.writeOpTrace.add("startProcess(pid=" + traceIds.renumber(anchorInstance.getProcessInstanceId())
                    + ",def=" + traceIds.renumber(anchorInstance.getProcessDefinitionId())
                    + ",bk=" + traceIds.renumber(String.valueOf(anchorInstance.getBusinessKey())) + ")");
            final Task anchorTask = singleActiveTask(stateTaskService, state.anchorPid);
            state.anchorTaskId = anchorTask.getId();
            state.anchorLegacyTaskId = stateHistoryService.createHistoricTaskInstanceQuery()
                    .processInstanceId(state.anchorPid)
                    .taskDefinitionKey(NODE_LEGACY).singleResult().getId();
            state.anchorActiveTaskNodeIds = activeTaskSnapshot(stateTaskService, state.anchorPid).values()
                    .stream().collect(Collectors.toCollection(LinkedHashSet::new));

            // fail-fast run：布防后 startProcess（面④）
            state.bean(DecisionTwoPathProbeTestConfiguration.ProbeAutoApprovalRule.class).armFailure();
            state.failFastBk = "twopath-failfast-" + state.label;
            try {
                lifecycle.startProcess(PROCESS_KEY, state.failFastBk, startVariables());
                state.failFastException = "(no exception)";
            } catch (final RuntimeException rejected) {
                // 有意的宽捕获：此处是异常探针（面④），目的是捕获异常类型供三态对拍，不是吞异常
                state.failFastException = rejected.getClass().getSimpleName();
            }
            state.writeOpTrace.add("startProcess:" + state.failFastException);

            // 录制序列归一化快照（面⑤；三条 run 全部完成后固化）
            state.normalizedListenerEntries =
                    state.bean(DecisionTwoPathProbeTestConfiguration.ProbeRecordingListener.class)
                            .normalizedEntries();
        } catch (final RuntimeException broken) {
            throw new AssertionError("受控操作序列在 " + state.label + " 态必须可完整执行（否则对拍面不成立）",
                    broken);
        } finally {
            BpmnQueryIntegrationTest.DynamicUserContext.CURRENT_USER.remove();
        }
    }

    /** 一次 startProcess 的变量（声明节点集合单元素 = 伪单例；一个内容变量进历史变量对拍面） */
    private static Map<String, Object> startVariables() {
        final Map<String, Object> variables = new HashMap<>();
        variables.put("declaredAssignees", new ArrayList<>(Collections.singletonList(USER)));
        variables.put("probeReason", "两路径隔离探针");
        return variables;
    }

    // ======================== 对拍（面①） ========================

    private void compareWithReference(final StateAccess side, final StateAccess referenceState) {
        for (final StateAccess state : Arrays.asList(side, referenceState)) {
            final HistoricProcessInstance instance = state.bean(HistoryService.class)
                    .createHistoricProcessInstanceQuery()
                    .processInstanceId(state.equivalencePid).singleResult();
            assertThat(instance.getDeleteReason())
                    .as("面①：等价 run 正常结束，%s 态的 deleteReason 必须为 null", state.label)
                    .isNull();
            assertThat(instance.getEndTime())
                    .as("面①：等价 run 在 %s 态同为已结束", state.label)
                    .isNotNull();
            assertThat(instance.getStartUserId())
                    .as("面①：发起人同为受控用户（内容字段）")
                    .isEqualTo(USER);
            // 运行时状态：等价 run 结束后两侧都无运行时实例
            assertThat(state.bean(RuntimeService.class).createProcessInstanceQuery()
                    .processInstanceId(state.equivalencePid).count())
                    .as("面①：等价 run 结束后 %s 态不得有运行时实例", state.label)
                    .isZero();
        }
        assertThat(side.activityIdSequence)
                .as("面①：历史活动与任务的节点 id 序列逐位置等值")
                .isEqualTo(referenceState.activityIdSequence);
        assertThat(side.historicVariables)
                .as("面①：历史变量集等值")
                .isEqualTo(referenceState.historicVariables);
        assertThat(side.anchorActiveTaskNodeIds)
                .as("面①：anchor run 的活跃任务集（节点 id）等值且恰为声明节点")
                .isEqualTo(referenceState.anchorActiveTaskNodeIds)
                .containsExactly(NODE_DECLARED);
    }

    // ======================== 引擎公开 API 读数（TYPE_ 列判据，不经读侧契约面） ========================

    /** C(P) 内 taskId == 给定任务且 TYPE_ == 给定类型的评论数（E(T2, type) 的基数） */
    private static long countComments(final TaskService taskService, final String processInstanceId,
            final String taskId, final String typeName) {
        return taskService.getProcessInstanceComments(processInstanceId).stream()
                .filter(comment -> typeName.equals(comment.getType()))
                .filter(comment -> taskId.equals(comment.getTaskId()))
                .count();
    }

    /** C(P, type) 的基数（不带 taskId 收口） */
    private static long countComments(final TaskService taskService, final String processInstanceId,
            final String typeName) {
        return taskService.getProcessInstanceComments(processInstanceId).stream()
                .filter(comment -> typeName.equals(comment.getType()))
                .count();
    }

    /** 活跃任务集快照 {taskId → nodeId}（断言 3 ② 的对拍面之一） */
    private static Map<String, String> activeTaskSnapshot(final TaskService taskService,
            final String processInstanceId) {
        return taskService.createTaskQuery().processInstanceId(processInstanceId).active().list().stream()
                .collect(Collectors.toMap(Task::getId, Task::getTaskDefinitionKey,
                        (first, second) -> first, LinkedHashMap::new));
    }

    /**
     * 人工意见组快照（断言 3 ② 的另一对拍面）：业务意见 + 操作注释组的评论集合，
     * 逐条取 (getTaskId(), getType(), getFullMessage())。机械化：排除机制证据组
     * （{@code DECISION_EVIDENCE}，随提交按设计增长、不得进该对拍面）后的全部评论；
     * 引擎侧无序（D4），快照排序后比较。
     */
    private static List<String> manualOpinionSnapshot(final TaskService taskService,
            final String processInstanceId) {
        final List<String> entries = taskService.getProcessInstanceComments(processInstanceId).stream()
                .filter(comment -> !DecisionEvidenceComment.COMMENT_TYPE.name().equals(comment.getType()))
                .map(comment -> comment.getTaskId() + "|" + comment.getType() + "|" + comment.getFullMessage())
                .collect(Collectors.toList());
        Collections.sort(entries);
        return entries;
    }

    private static Task singleActiveTask(final TaskService taskService, final String processInstanceId) {
        final Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().singleResult();
        assertThat(task).as("受控序列前提：声明节点恰有一张活跃任务").isNotNull();
        return task;
    }

    /** 历史活动与任务的节点 id 序列（同毫秒并列由数值 ID_ 兜底 —— D4 漂移的既有哲学） */
    private static List<String> activityIdSequence(final HistoryService historyService,
            final String processInstanceId) {
        final List<HistoricActivityInstance> activities = new ArrayList<>(historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).list());
        activities.sort(Comparator
                .comparing(HistoricActivityInstance::getStartTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(activity -> numericId(activity.getId())));
        return activities.stream()
                .map(HistoricActivityInstance::getActivityId)
                .collect(Collectors.toList());
    }

    /** 数值 ID（DbIdGenerator 数值串；不可解析时按字典序兜底，仅影响排序稳定性、不影响内容比较） */
    private static String numericId(final String rawId) {
        return rawId == null ? "" : String.format("%020d", parseOrMin(rawId));
    }

    private static long parseOrMin(final String rawId) {
        try {
            return Long.parseLong(rawId);
        } catch (final NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static Map<String, Object> historicVariables(final HistoryService historyService,
            final String processInstanceId) {
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).list().stream()
                .collect(Collectors.toMap(HistoricVariableInstance::getVariableName,
                        HistoricVariableInstance::getValue, (first, second) -> first, LinkedHashMap::new));
    }

    // ======================== 历史时间确定性化（fixture 直连库改写；E20 同手法、代码不复用） ========================

    /**
     * 三库共用的两张历史表（{@code ACT_HI_ACTINST} / {@code ACT_HI_TASKINST}）按<b>数值 {@code ID_}
     * 升序（= 写入序）</b>重排 {@code START_TIME_} / {@code END_TIME_}（保持 null 形态；END = START + 500）。
     * 同毫秒并列在引擎侧无任何可用顺序（D4），不钉死则读侧记录顺序不稳；绝对值不进任何断言。
     * 数据源属性与程序化 boot 同源（从 {@link Environment} 复制），三库矩阵下同一份逻辑，
     * {@code ORDER BY} 的 CAST 目标类型随库方言取值（H2 / PostgreSQL 用 BIGINT，MySQL 用 SIGNED）。
     */
    private void determinizeHistoryTimes() {
        final String url = environment.getProperty("spring.datasource.url");
        final String username = environment.getProperty("spring.datasource.username");
        final String password = environment.getProperty("spring.datasource.password");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, username, password)) {
            // 两表合并、按全局数值 ID 排序共享同一序数序列：ID_ 全局唯一且数值单调（= 写入序），
            // 记录面跨表取时间（start 活动行 vs 任务实例行），独立改写会让跨表时间不可比
            final List<HistoryRow> rows = new ArrayList<>();
            rows.addAll(collectStartEndTimes(connection, "ACT_HI_ACTINST"));
            rows.addAll(collectStartEndTimes(connection, "ACT_HI_TASKINST"));
            rows.sort(Comparator.comparingLong(HistoryRow::numericId));
            rewriteStartEndTimes(connection, rows);
        } catch (final java.sql.SQLException broken) {
            throw new IllegalStateException("历史时间的确定性化改写失败", broken);
        }
    }

    /** 一行历史实例的改写输入（表名 / 数值 ID / 原 END_TIME_ 毫秒值，-1 = 无结束时间） */
    private static final class HistoryRow {

        final String table;
        final long numericId;
        final long endTimeMs;

        HistoryRow(final String table, final long numericId, final long endTimeMs) {
            this.table = table;
            this.numericId = numericId;
            this.endTimeMs = endTimeMs;
        }

        long numericId() {
            return numericId;
        }
    }

    /**
     * 单表收集改写输入（engine 默认 {@code DbIdGenerator} 前提下 {@code ID_} 恒为数值串；
     * 解析失败属结构性意外，按 IllegalStateException 上抛 —— 静默降级会让改写失效且不响）。
     */
    private static List<HistoryRow> collectStartEndTimes(final java.sql.Connection connection,
            final String table) throws java.sql.SQLException {
        final List<HistoryRow> rows = new ArrayList<>();
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery(
                     "SELECT ID_, END_TIME_ FROM " + table)) {
            while (resultSet.next()) {
                final String id = resultSet.getString(1);
                final java.sql.Timestamp end = resultSet.getTimestamp(2);
                rows.add(new HistoryRow(table, Long.parseLong(id),
                        end == null ? -1L : end.getTime()));
            }
        }
        return rows;
    }

    /** 按全局序改写两表（保持 null 形态；END = START + 500）；改写必须命中全部行（构造期守卫）。 */
    private static void rewriteStartEndTimes(final java.sql.Connection connection,
            final List<HistoryRow> rows) throws java.sql.SQLException {
        final long base = 1_000_000_000L;
        long updated = 0L;
        for (int rank = 0; rank < rows.size(); rank++) {
            final HistoryRow row = rows.get(rank);
            final long start = base + rank * 1_000L;
            try (java.sql.PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + row.table + " SET START_TIME_ = ?, END_TIME_ = ? WHERE ID_ = ?")) {
                update.setTimestamp(1, new java.sql.Timestamp(start));
                if (row.endTimeMs < 0) {
                    update.setNull(2, java.sql.Types.TIMESTAMP);
                } else {
                    update.setTimestamp(2, new java.sql.Timestamp(start + 500L));
                }
                // ID_ 列为 VARCHAR：按串绑定
                update.setString(3, String.valueOf(row.numericId));
                updated += update.executeUpdate();
            }
        }
        if (updated != rows.size()) {
            throw new IllegalStateException("历史时间改写未命中全部行：updated=" + updated
                    + ", rows=" + rows.size());
        }
    }

    /** 合法直提提交（三次前置齐 + 依据面 BASIS_CODE ≥ 1 + 文本兜底；出处组与 modelId 全空 = 直提双射） */
    private static SuggestionSubmission directSubmission(final String taskId, final ApprovalAction action,
            final String idempotencyKey) {
        return SuggestionSubmission.builder()
                .taskId(taskId)
                .suggestedAction(action)
                .idempotencyKey(idempotencyKey)
                .subjectType(DecisionSubjectType.USER)
                .subjectId(USER)
                .actionSummary("探针直提建议摘要：" + idempotencyKey)
                .rationaleFacts(Collections.singletonList(
                        new DecisionRationaleFact(DecisionRationaleFactKey.BASIS_CODE, "BASIS-S5")))
                .rationaleNarrative("探针直提依据文本兜底")
                .build();
    }

    /** 有界轮询等待（收敛条件驱动；轮询间隔的 sleep 是轮询等待的必要形态，见常量 javadoc） */
    private static boolean await(final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + CONVERGENCE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return condition.getAsBoolean();
    }

    // ======================== 归一化（形态文件 §3 / §4 的第二处实现，语义相同、代码不复用） ========================

    /**
     * 归一化副本：{@code taskId} 按出现序重编号（父记录与子记录共用同一张编号表）；
     * {@code startTime} / {@code endTime} 归一化为出现序向量（以序数刻在 {@code Date} 上）；
     * {@code duration} 只比「同为 null / 同为非 null」。其余字段（内容）严格等值。
     * {@code maskDecisionEvidences} 为真时该字段移出对拍面（开态 vs 参照；掩码为下限）。
     */
    private static List<ApprovalRecordVO> normalizeRecords(final List<ApprovalRecordVO> records,
            final boolean maskDecisionEvidences) {
        final Map<String, String> taskIds = new HashMap<>();
        final Map<Long, Long> times = new HashMap<>();
        final Function<Date, Date> ordinal = (Date value) -> value == null ? null
                : new Date(times.computeIfAbsent(value.getTime(), key -> (long) times.size()));
        final Function<String, String> renumber = (String taskId) -> taskId == null ? null
                : taskIds.computeIfAbsent(taskId, key -> "T" + taskIds.size());
        final Function<List<DecisionEvidenceVO>, List<DecisionEvidenceVO>> evidence =
                value -> maskDecisionEvidences ? null : value;

        final List<ApprovalRecordVO> normalized = new ArrayList<>(records.size());
        for (final ApprovalRecordVO record : records) {
            final List<CountersignSubRecord> subRecords = record.getCountersignRecords();
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
                            evidence.apply(subRecord.getDecisionEvidences())));
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
                    evidence.apply(record.getDecisionEvidences())));
        }
        return normalized;
    }

    private static Set<String> collectDeclaredFields(final Class<?>... types) {
        return Arrays.stream(types)
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> difference(final Set<String> left, final Set<String> right) {
        final Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    // ======================== 内部件 ========================

    /** 一个态的全部产物（上下文 + 受控序列捕获） */
    private static final class StateAccess {
        final String label;
        final ConfigurableApplicationContext context;
        final List<String> writeOpTrace = new ArrayList<>();
        String equivalenceBk;
        String equivalencePid;
        String legacyTaskId;
        String anchorBk;
        String anchorPid;
        String anchorTaskId;
        String anchorLegacyTaskId;
        String failFastBk;
        String failFastException;
        Set<String> anchorActiveTaskNodeIds;
        List<ApprovalRecordVO> records;
        List<String> activityIdSequence;
        Map<String, Object> historicVariables;
        List<String> normalizedListenerEntries;

        StateAccess(final String label, final ConfigurableApplicationContext context) {
            this.label = label;
            this.context = context;
        }

        <T> T bean(final Class<T> type) {
            return context.getBean(type);
        }
    }

    /**
     * 参照态的装配面边界（仅挂参照态 boot；经 {@code ApplicationContextInitializer} +
     * {@code addBeanFactoryPostProcessor} 注入，<b>不是</b> Bean —— 实现期必需，如实登记）：
     * 集成测试应用类对 starter 包做了 {@code @ComponentScan}，两个决策自动配置类经<b>扫描路径</b>注册，
     * {@code spring.autoconfigure.exclude} 只过滤自动配置<b>导入</b>路径（一手实测：排除后
     * {@code decisionPipeline} 仍被构造并撞双 {@code DecisionProvider} 候选）。<b>且</b>静态嵌套
     * {@code @TestConfiguration} 同样会被该扫描收进<b>所有</b>上下文（一手实测：嵌套配置类形态泄漏进
     * 关态 / 开态，把机制组件从被测态也移除了）。故参照态边界以初始化器形态在刷新前注册 BFPP，
     * 在任何单例实例化之前按 {@link DecisionAssemblyTestSupport#DECLARED_MECHANISM_BEAN_NAMES}
     * （S2 已断言的机械闭集）移除机制组件的 Bean 定义，使「机制组件不构造、不注册」在扫描路径下成立。
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> referenceBoundaryInitializer() {
        return context -> context.addBeanFactoryPostProcessor(beanFactory -> {
            final BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
            for (final String name : DecisionAssemblyTestSupport.DECLARED_MECHANISM_BEAN_NAMES) {
                if (registry.containsBeanDefinition(name)) {
                    registry.removeBeanDefinition(name);
                }
            }
        });
    }

    /** 出现序重编号表（面③ trace 的 pid / def / bk 归动态重编号；各态一张，归一化后跨态同形） */
    private static final class OccurrenceTable {
        private final Map<String, String> ids = new HashMap<>();

        String renumber(final String raw) {
            return raw == null ? "-" : ids.computeIfAbsent(raw, key -> "X" + ids.size());
        }
    }
}
