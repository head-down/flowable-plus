package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionContextSource;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 决策上下文装配器（ADR-0042 第 6 节「位点①」）：做<b>数据源级结构性最小化</b>（框架强制、fail-closed），
 * 只装配声明的来源，产出与管线之间的单命令一致读。
 *
 * <p><b>严格 fallback 链</b>（有效数据源集 = 节点级 token 集 › 应用级默认集 › ∅）：</p>
 *
 * <ul>
 *   <li><b>缺席</b>（属性不存在）⇒ 取应用级默认集；</li>
 *   <li><b>显式空集</b>（{@code decisionDataSources=""}，含纯空白）⇒ <b>恒空集</b>、<b>不取</b>默认
 *       （显式否决）；</li>
 *   <li><b>绝无隐式全集兜底</b> —— 「没声明」永不等于「全都要」。</li>
 * </ul>
 *
 * <p><b>单命令一致读</b>：三段读（流程实例变量 / 任务本地变量 / 元数据）包在<b>一次</b>
 * {@link ManagementService#executeCommand(Command)}（同一 {@code CommandContext}）内；<b>不承诺</b>跨命令
 * 一致（管线其余步骤在流程事务之外）。</p>
 *
 * <p><b>零 token 判定在本装配器内</b>，产「<b>空装配</b>」这一显式事实（{@link DecisionAssemblyResult}
 * 承载）：管线只读 {@code decisionEnabled}（控制流），{@code decisionDataSources} <b>只被装配器读一次</b>
 * （数据流）。空装配 ⇒ 管线短路：<b>不调策略、不调 provider</b>。</p>
 *
 * <p><b>不重复校验 token 合法性</b>：主闸在部署期已保证合法（token 抄错部署当场即炸）；本装配器拿到
 * 未知 token 只可能来自「校验被关掉的部署」或运行期直改模型，故按「<b>显式不认</b>」处置并留一条
 * {@code WARN}，<b>不静默</b>把它当「未声明」—— 重复校验会造第二真相。</p>
 */
public final class DecisionContextAssembler {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionContextAssembler.class);

    /** 引擎命令入口（一致快照的唯一开启点） */
    private final ManagementService managementService;

    /** 读流程实例变量与元数据 */
    private final RuntimeService runtimeService;

    /** 读任务本地变量与元数据 */
    private final TaskService taskService;

    /** 应用级默认数据源集（节点缺席时的下取面；缺失与空集同义） */
    private final DecisionDefaultContextSources defaultContextSources;

    /**
     * 构造装配器。
     *
     * @param managementService     引擎命令入口，不得为 null
     * @param runtimeService        读流程实例侧，不得为 null
     * @param taskService           读任务侧，不得为 null
     * @param defaultContextSources 应用级默认数据源集；{@code null} 与空集同义（缺失一律按空集）
     */
    public DecisionContextAssembler(final ManagementService managementService,
                                    final RuntimeService runtimeService,
                                    final TaskService taskService,
                                    final DecisionDefaultContextSources defaultContextSources) {
        this.managementService = Objects.requireNonNull(managementService, "引擎命令入口不得为 null");
        this.runtimeService = Objects.requireNonNull(runtimeService, "流程实例侧读取面不得为 null");
        this.taskService = Objects.requireNonNull(taskService, "任务侧读取面不得为 null");
        this.defaultContextSources = defaultContextSources == null
                ? DecisionDefaultContextSources.empty()
                : defaultContextSources;
    }

    /**
     * 装配一个节点的决策上下文。
     *
     * @param nodeElement      承载节点声明的 BPMN 元素，不得为 null
     * @param taskId           锚点任务标识
     * @param processInstanceId 流程实例标识
     * @return 装配结果；有效数据源集为空时返回空装配（显式事实），且<b>不读引擎</b>
     */
    public DecisionAssemblyResult assemble(final BaseElement nodeElement,
                                           final String taskId,
                                           final String processInstanceId) {
        Objects.requireNonNull(nodeElement, "承载节点声明的元素不得为 null");
        final Set<DecisionContextSource> effectiveSources = effectiveSources(nodeElement);
        final List<DecisionContextSource> droppedSources = droppedSources(effectiveSources);
        if (effectiveSources.isEmpty()) {
            return DecisionAssemblyResult.empty(droppedSources);
        }
        final DecisionContextSnapshot snapshot = readSnapshot(taskId, processInstanceId);
        return DecisionAssemblyResult.of(buildPayload(effectiveSources, snapshot), droppedSources);
    }

    /**
     * 解出有效数据源集（严格 fallback 链：节点级 › 应用级 › ∅）。
     *
     * <p>{@code decisionDataSources} 在这<b>一次</b>读取中被消费；本方法返回后不再触碰声明面。</p>
     *
     * @param nodeElement 承载节点声明的元素
     * @return 有效数据源集；显式空集 / 应用级默认缺失时为空集
     */
    private Set<DecisionContextSource> effectiveSources(final BaseElement nodeElement) {
        final String declared = DecisionNodeDeclarationReader.declaredValue(nodeElement,
                DecisionNodeDeclaration.DECISION_DATA_SOURCES);
        if (declared == null) {
            return new LinkedHashSet<>(defaultContextSources.getSources());
        }
        final List<String> tokens = DecisionNodeDeclarationReader.splitDataSourceTokens(declared);
        if (tokens.isEmpty()) {
            return EnumSet.noneOf(DecisionContextSource.class);
        }
        final Set<DecisionContextSource> sources = EnumSet.noneOf(DecisionContextSource.class);
        for (final String token : tokens) {
            final DecisionContextSource source = DecisionNodeDeclarationReader.parseDataSourceToken(token);
            if (source == null) {
                // 现场值先取局部变量：日志参数不得携带函数调用
                final String nodeId = nodeElement.getId();
                LOG.warn("数据源 token 不在闭集内，按「显式不认」处置（不静默当「未声明」）：节点={}，token={}",
                        nodeId, token);
                continue;
            }
            sources.add(source);
        }
        return sources;
    }

    /**
     * 装配器丢弃的来源 = 有效数据源集之外的承载单元级来源（声明面最小化的基线计数，非标志）。
     *
     * @param effectiveSources 有效数据源集
     * @return 被排除的来源（按枚举声明序）
     */
    private static List<DecisionContextSource> droppedSources(final Set<DecisionContextSource> effectiveSources) {
        return EnumSet.allOf(DecisionContextSource.class).stream()
                .filter(source -> !effectiveSources.contains(source))
                .collect(Collectors.toList());
    }

    /**
     * 单次引擎命令内读一致快照（内层服务调用复用同一 {@code CommandContext}，不新开事务）。
     *
     * @param taskId            锚点任务标识
     * @param processInstanceId 流程实例标识
     * @return 四份原始上下文
     */
    private DecisionContextSnapshot readSnapshot(final String taskId, final String processInstanceId) {
        return managementService.executeCommand((Command<DecisionContextSnapshot>) commandContext -> {
            final Map<String, Object> processVariables = runtimeService.getVariables(processInstanceId);
            final Map<String, Object> taskVariables = taskService.getVariablesLocal(taskId);
            final Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            final ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            return new DecisionContextSnapshot(processVariables, taskVariables,
                    toTaskMetadata(task), toProcessInstanceMetadata(processInstance));
        });
    }

    /**
     * 按有效数据源集装配四段：声明内⇒取值（来源为空取空集合 / 全空小对象），声明外⇒{@code null}。
     *
     * @param effectiveSources 有效数据源集
     * @param snapshot         一致性快照
     * @return 决策载荷
     */
    private static DecisionPayload buildPayload(final Set<DecisionContextSource> effectiveSources,
                                                final DecisionContextSnapshot snapshot) {
        return new DecisionPayload(
                declaredMap(effectiveSources, DecisionContextSource.PROCESS_VARIABLES, snapshot.getProcessVariables()),
                declaredMap(effectiveSources, DecisionContextSource.TASK_VARIABLES, snapshot.getTaskVariables()),
                declaredSegment(effectiveSources, DecisionContextSource.TASK_METADATA, snapshot.getTaskMetadata()),
                declaredSegment(effectiveSources, DecisionContextSource.PROCESS_INSTANCE_METADATA,
                        snapshot.getProcessInstanceMetadata()));
    }

    /**
     * 变量段的装配：未声明 ⇒ {@code null}；已声明 ⇒ 原样（来源为空 ⇒ 空集合，与缺席两态可分）。
     */
    private static Map<String, Object> declaredMap(final Set<DecisionContextSource> effectiveSources,
                                                   final DecisionContextSource source,
                                                   final Map<String, Object> value) {
        if (!effectiveSources.contains(source)) {
            return null;
        }
        return value == null ? Collections.<String, Object>emptyMap() : value;
    }

    /**
     * 元数据段的装配：未声明 ⇒ {@code null}；已声明 ⇒ 原样（全空小对象是「声明但空」的一态）。
     */
    private static <T> T declaredSegment(final Set<DecisionContextSource> effectiveSources,
                                         final DecisionContextSource source,
                                         final T value) {
        return effectiveSources.contains(source) ? value : null;
    }

    /**
     * 任务元数据小对象（任务缺席 ⇒ 字段全空，仍是「已声明但空」的一态，不返回 null）。
     *
     * @param task 引擎任务，可空
     * @return 任务元数据
     */
    private static TaskMetadata toTaskMetadata(final Task task) {
        if (task == null) {
            return new TaskMetadata(null, null, null, null, null);
        }
        return new TaskMetadata(task.getId(), task.getName(), task.getTaskDefinitionKey(),
                task.getAssignee(), task.getCreateTime());
    }

    /**
     * 流程实例元数据小对象（实例缺席 ⇒ 字段全空，仍是「已声明但空」的一态，不返回 null）。
     *
     * @param processInstance 引擎流程实例，可空
     * @return 流程实例元数据
     */
    private static ProcessInstanceMetadata toProcessInstanceMetadata(final ProcessInstance processInstance) {
        if (processInstance == null) {
            return new ProcessInstanceMetadata(null, null, null, null, null);
        }
        return new ProcessInstanceMetadata(processInstance.getProcessInstanceId(),
                processInstance.getProcessDefinitionKey(), processInstance.getBusinessKey(),
                processInstance.getStartUserId(), processInstance.getStartTime());
    }
}
