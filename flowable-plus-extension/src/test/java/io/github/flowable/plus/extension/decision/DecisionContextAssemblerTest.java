package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionContextSource;
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
import org.mockito.ArgumentMatchers;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E4 —— 装配器守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E4}），
 * <b>靶子②「越域出站」（不变量 I2）的主落点（承裁定）</b>。
 *
 * <p><b>承哪些推入项</b>：ADR-0042 第 6 节位点① —— 严格 fallback 链（节点级 › 应用级 › 框架级 ∅）、
 * 缺席 / 显式空集的状态机分岔、零 token 判定在装配器内、空装配是显式事实、{@code decisionDataSources}
 * 只被读一次、装配器 ↔ 管线单命令一致读。</p>
 *
 * <p><b>纯单测、不跑真引擎</b>：断言对象是<b>调用契约</b>（读一次 / 恰一次 {@code executeCommand}）与
 * 装配结果，依据落点表 §1.1 的判例 —— 引擎侧端到端一致读属管线票。</p>
 */
class DecisionContextAssemblerTest {

    private static final String TASK_ID = "task-20260927-0001";
    private static final String PROCESS_INSTANCE_ID = "process-20260927-0001";

    /** 数据源闭集的成员数（对账常量；不调枚举 {@code values()}） */
    private static final int ALL_SOURCE_COUNT = EnumSet.allOf(DecisionContextSource.class).size();

    private ManagementService managementService;
    private RuntimeService runtimeService;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        managementService = mock(ManagementService.class);
        runtimeService = mock(RuntimeService.class);
        taskService = mock(TaskService.class);

        // 单命令内的四段读：桩住查询链与变量读（命令体在 thenAnswer 里真被执行）
        final TaskQuery taskQuery = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(anyString())).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(mock(Task.class));

        final ProcessInstanceQuery processInstanceQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);
        when(processInstanceQuery.processInstanceId(anyString())).thenReturn(processInstanceQuery);
        when(processInstanceQuery.singleResult()).thenReturn(mock(ProcessInstance.class));

        when(runtimeService.getVariables(anyString())).thenReturn(new LinkedHashMap<>());
        when(taskService.getVariablesLocal(anyString())).thenReturn(new LinkedHashMap<>());

        when(managementService.executeCommand(anyCommand()))
                .thenAnswer(invocation -> ((Command<?>) invocation.getArgument(0)).execute(null));
    }

    @Test
    @DisplayName("有效数据源集走严格 fallback 链：节点级 › 应用级 › 框架级 ∅")
    void effectiveSourcesFollowNodeThenAppThenFrameworkChain() {
        // 节点级 › 应用级：节点声明赢
        final DecisionAssemblyResult nodeWins = assembler(defaults(DecisionContextSource.TASK_VARIABLES))
                .assemble(declaring("TASK_METADATA"), TASK_ID, PROCESS_INSTANCE_ID);
        assertThat(nodeWins.isEmptyAssembly()).isFalse();
        assertThat(nodeWins.getPayload().getTaskMetadata()).as("节点级声明赢应用级默认").isNotNull();
        assertThat(nodeWins.getPayload().getTaskVariables()).as("应用级默认被节点声明顶掉").isNull();

        // 缺席 ⇒ 应用级默认
        final DecisionAssemblyResult absentNode = assembler(defaults(DecisionContextSource.PROCESS_INSTANCE_METADATA))
                .assemble(declaring(null), TASK_ID, PROCESS_INSTANCE_ID);
        assertThat(absentNode.getPayload().getProcessInstanceMetadata()).as("缺席取应用级默认").isNotNull();
        assertThat(absentNode.getPayload().getTaskMetadata()).isNull();

        // 应用级也空 ⇒ 框架级 ∅
        final DecisionAssemblyResult frameworkEmpty = assembler(DecisionDefaultContextSources.empty())
                .assemble(declaring(null), TASK_ID, PROCESS_INSTANCE_ID);
        assertThat(frameworkEmpty.isEmptyAssembly()).as("fallback 链尽头 = 框架级 ∅").isTrue();
        assertThat(frameworkEmpty.getDroppedContextSources())
                .as("框架级 ∅ 时四段全在「装配器丢弃」面")
                .hasSize(ALL_SOURCE_COUNT);
    }

    @Test
    @DisplayName("显式空集恒 NO_SOURCE_DECLARED：不取应用级默认、也不读引擎")
    void explicitEmptySetAlwaysYieldsNoSourceDeclared() {
        final DecisionAssemblyResult emptyLiteral = assembler(defaults(DecisionContextSource.TASK_VARIABLES))
                .assemble(declaring(""), TASK_ID, PROCESS_INSTANCE_ID);
        assertThat(emptyLiteral.isEmptyAssembly()).as("显式空集（decisionDataSources=\"\"）⇒ 恒空装配").isTrue();
        assertThat(emptyLiteral.getPayload()).isNull();
        assertThat(emptyLiteral.getDroppedContextSources())
                .as("显式否决：应用级默认不得被下取")
                .hasSize(ALL_SOURCE_COUNT);

        final DecisionAssemblyResult blankLiteral = assembler(defaults(DecisionContextSource.TASK_VARIABLES))
                .assemble(declaring("   "), TASK_ID, PROCESS_INSTANCE_ID);
        assertThat(blankLiteral.isEmptyAssembly()).as("纯空白同判显式空集").isTrue();
    }

    @Test
    @DisplayName("声明缺席时取应用级默认数据源集")
    void absentDeclarationTakesAppDefault() {
        final DecisionAssemblyResult result = assembler(defaults(DecisionContextSource.PROCESS_INSTANCE_METADATA))
                .assemble(declaring(null), TASK_ID, PROCESS_INSTANCE_ID);

        assertThat(result.isEmptyAssembly()).isFalse();
        assertThat(result.getPayload().getProcessInstanceMetadata()).as("取应用级默认").isNotNull();
        assertThat(result.getPayload().getProcessVariables()).as("只装配声明的来源").isNull();
        assertThat(result.getPayload().getTaskVariables()).isNull();
    }

    @Test
    @DisplayName("空装配是显式事实：不得由「四段全 null」反推")
    void emptyAssemblyIsExplicitNotInferredFromNullSegments() {
        final DecisionAssemblyResult empty = DecisionAssemblyResult.empty(Collections.emptyList());
        assertThat(empty.isEmptyAssembly()).isTrue();
        assertThat(empty.getPayload()).isNull();

        final DecisionPayload allNullSegments = new DecisionPayload(null, null, null, null);
        final DecisionAssemblyResult nonEmpty = DecisionAssemblyResult.of(allNullSegments, Collections.emptyList());
        assertThat(nonEmpty.isEmptyAssembly())
                .as("「四段全 null」不得被读成空装配：null 的语义是「该段未声明」")
                .isFalse();
        assertThat(nonEmpty.getPayload()).isSameAs(allNullSegments);

        // 装配器路径同样把空装配落成显式事实（不由载荷反推）
        final DecisionAssemblyResult assembled = assembler(DecisionDefaultContextSources.empty())
                .assemble(declaring(""), TASK_ID, PROCESS_INSTANCE_ID);
        assertThat(assembled.isEmptyAssembly()).isTrue();
        assertThat(assembled.getPayload()).isNull();
    }

    @Test
    @DisplayName("零 token 判定在装配器内：产空装配且零引擎命令")
    void zeroTokenDecisionHappensInsideAssembler() {
        final DecisionAssemblyResult result = assembler(defaults(DecisionContextSource.TASK_VARIABLES))
                .assemble(declaring(""), TASK_ID, PROCESS_INSTANCE_ID);

        assertThat(result.isEmptyAssembly()).as("零 token ⇒ 空装配（装配器判定，管线只据此短路）").isTrue();
        verify(managementService, never()).executeCommand(anyCommand());
    }

    @Test
    @DisplayName("decisionDataSources 只被装配器读一次")
    void readsDataSourcesExactlyOnce() {
        final BaseElement node = declaring("TASK_METADATA");
        assembler(defaults(DecisionContextSource.PROCESS_VARIABLES)).assemble(node, TASK_ID, PROCESS_INSTANCE_ID);

        verify(node, times(1)).getAttributeValue(
                DecisionNodeDeclaration.NAMESPACE_URI, DecisionNodeDeclaration.DECISION_DATA_SOURCES);
    }

    @Test
    @DisplayName("装配器 ↔ 管线：非空装配恰一次 executeCommand，空装配零次")
    void snapshotUsesSingleExecuteCommand() {
        assembler(defaults(DecisionContextSource.TASK_METADATA))
                .assemble(declaring("TASK_METADATA"), TASK_ID, PROCESS_INSTANCE_ID);
        verify(managementService, times(1)).executeCommand(anyCommand());

        clearInvocations(managementService);
        assembler(defaults(DecisionContextSource.TASK_METADATA))
                .assemble(declaring(""), TASK_ID, PROCESS_INSTANCE_ID);
        verify(managementService, never()).executeCommand(anyCommand());
    }

    @Test
    @DisplayName("应用级默认缺失 / 空集一律按空集：绝无隐式全集兜底")
    void absentOrEmptyDefaultSourcesBehaveAsEmptySet() {
        assertThat(assembler(null).assemble(declaring(null), TASK_ID, PROCESS_INSTANCE_ID).isEmptyAssembly())
                .as("应用级默认缺失（Bean 不存在）⇒ 空集")
                .isTrue();
        assertThat(assembler(new DecisionDefaultContextSources(Collections.<DecisionContextSource>emptySet()))
                .assemble(declaring(null), TASK_ID, PROCESS_INSTANCE_ID).isEmptyAssembly())
                .as("应用级默认显式空集 ⇒ 空集")
                .isTrue();
        assertThat(assembler(DecisionDefaultContextSources.empty())
                .assemble(declaring(null), TASK_ID, PROCESS_INSTANCE_ID).isEmptyAssembly())
                .as("空形态的默认集 ⇒ 空集")
                .isTrue();

        final DecisionAssemblyResult result = assembler(null)
                .assemble(declaring(null), TASK_ID, PROCESS_INSTANCE_ID);
        assertThat(result.getDroppedContextSources())
                .as("禁隐式全集兜底：不得退化成「四段全装配」")
                .hasSize(ALL_SOURCE_COUNT);
    }

    // ======================== 私有支撑 ========================

    /**
     * 命令匹配器（显式泛型以固定 {@code executeCommand} 的类型推断）。
     *
     * @return 任意引擎命令的匹配器
     */
    private static Command<DecisionContextSnapshot> anyCommand() {
        return ArgumentMatchers.<Command<DecisionContextSnapshot>>any();
    }

    /**
     * 构造装配器。
     *
     * @param defaultSources 应用级默认数据源集；{@code null} 表示缺失
     * @return 装配器
     */
    private DecisionContextAssembler assembler(final DecisionDefaultContextSources defaultSources) {
        return new DecisionContextAssembler(managementService, runtimeService, taskService, defaultSources);
    }

    /**
     * 应用级默认集（给定成员）。
     *
     * @param sources 默认来源
     * @return 默认集
     */
    private static DecisionDefaultContextSources defaults(final DecisionContextSource... sources) {
        final Set<DecisionContextSource> set = new LinkedHashSet<>();
        Collections.addAll(set, sources);
        return new DecisionDefaultContextSources(set);
    }

    /**
     * 承载节点声明的元素桩：{@code decisionDataSources} 的声明原值 = {@code rawValue}（{@code null} = 缺席）。
     *
     * @param rawValue 声明原值
     * @return 元素桩
     */
    private static BaseElement declaring(final String rawValue) {
        final BaseElement element = mock(BaseElement.class);
        when(element.getId()).thenReturn("userTask-decide");
        when(element.getAttributeValue(DecisionNodeDeclaration.NAMESPACE_URI,
                DecisionNodeDeclaration.DECISION_DATA_SOURCES)).thenReturn(rawValue);
        return element;
    }
}
