package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.event.EventBus;
import io.github.flowable.plus.core.event.TaskCreatedEvent;
import io.github.flowable.plus.core.spi.ProcessEventListener;
import io.github.flowable.plus.extension.decision.DecisionObservationEmitter;
import io.github.flowable.plus.extension.decision.DecisionPipeline;
import io.github.flowable.plus.extension.decision.DecisionTaskCreatedListener;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 到点信号订阅的懒初始化适配器（starter <b>包内</b>实现细节）。
 *
 * <p><b>为何需要懒初始化</b>：flowable 的 classpath 自动部署发生在 {@code SmartLifecycle#start()}
 * （全部单例 Bean 创建完<b>之后</b>）—— 任何 Bean 构造期预热都会得到空索引；而
 * {@link DecisionTaskCreatedListener} 的构造缝对声明面索引做<b>防御性拷贝</b>，此后索引内容对它
 * 冻结。故本适配器把「预热 + 构建真身」推迟到<b>首次到点事件</b>：彼时部署必然已完成，一次预热
 * 即覆盖全部已部署定义，冷启动节点不丢失（预热入口自选 = 首次到点事件，比「启动期一次性」覆盖
 * 面更完整）。</p>
 *
 * <p><b>扫描域</b> = 每个 key 的<b>最新版本</b>流程定义（拉面回调只对新实例触发）内、本机制 URI 下
 * 带启用声明的 UserTask（含嵌套子流程，与主闸部署期扫描域一致）；跨流程定义的 {@code nodeId}
 * 碰撞按「剔除 + WARN」fail-closed（{@link DecisionDeclaredNodeIndex}）。</p>
 *
 * <p>本类自身<b>零门控逻辑</b>：门控（全局开关 → 节点声明）全部在真身
 * {@link DecisionTaskCreatedListener} 内，不做第二份规则。</p>
 */
final class DecisionTaskCreatedListenerAdapter implements ProcessEventListener {

    private static final Logger log = LoggerFactory.getLogger(DecisionTaskCreatedListenerAdapter.class);

    private final ObjectProvider<FlowablePlusDecisionProperties> propertiesProvider;
    private final ObjectProvider<EventBus> eventBusProvider;
    private final ObjectProvider<ThreadPoolExecutor> executorProvider;
    private final ObjectProvider<DecisionPipeline> pipelineProvider;
    private final ObjectProvider<DecisionDeclaredNodeIndex> indexProvider;
    private final ObjectProvider<DecisionObservationEmitter> emitterProvider;
    private final ObjectProvider<RepositoryService> repositoryServiceProvider;

    /** 真身（首次到点事件时构建；null = 初始化失败，永久 fail-closed） */
    private volatile DecisionTaskCreatedListener delegate;

    private final Object initLock = new Object();

    DecisionTaskCreatedListenerAdapter(
            final ObjectProvider<FlowablePlusDecisionProperties> propertiesProvider,
            final ObjectProvider<EventBus> eventBusProvider,
            final ObjectProvider<ThreadPoolExecutor> executorProvider,
            final ObjectProvider<DecisionPipeline> pipelineProvider,
            final ObjectProvider<DecisionDeclaredNodeIndex> indexProvider,
            final ObjectProvider<DecisionObservationEmitter> emitterProvider,
            final ObjectProvider<RepositoryService> repositoryServiceProvider) {
        this.propertiesProvider = propertiesProvider;
        this.eventBusProvider = eventBusProvider;
        this.executorProvider = executorProvider;
        this.pipelineProvider = pipelineProvider;
        this.indexProvider = indexProvider;
        this.emitterProvider = emitterProvider;
        this.repositoryServiceProvider = repositoryServiceProvider;
    }

    @Override
    public void onTaskCreated(final TaskCreatedEvent event) {
        DecisionTaskCreatedListener target = delegate;
        if (target == null) {
            synchronized (initLock) {
                target = delegate;
                if (target == null) {
                    delegate = target = init();
                }
            }
        }
        if (target != null) {
            target.onTaskCreated(event);
        }
    }

    /** 预热索引并构建真身；初始化失败按 fail-closed 处置（拉面不触发，落 WARN）。 */
    private DecisionTaskCreatedListener init() {
        try {
            final RepositoryService repositoryService = repositoryServiceProvider.getIfAvailable();
            final DecisionDeclaredNodeIndex index = indexProvider.getIfAvailable();
            if (repositoryService == null || index == null) {
                log.warn("决策装配依赖缺席，拉面按 fail-closed 处置（不触发）");
                return null;
            }
            final Map<String, BaseElement> declared = new LinkedHashMap<>();
            for (final ProcessDefinition definition : repositoryService.createProcessDefinitionQuery()
                    .latestVersion().list()) {
                final BpmnModel model = repositoryService.getBpmnModel(definition.getId());
                model.getProcesses().forEach(
                        process -> DecisionDeclaredNodeIndex.collectDeclaredUserTasks(process, declared));
            }
            index.merge(declared);
            /*
             * eventChannelEnabled = EventBus#isEnabled()（构造期定值，事件面开关的收口口径）。此刻
             * （首次到点事件）EventBus Bean 必然已构建完毕 —— 事件正是经它发布的 —— 故此处解析
             * ObjectProvider 不会回到 Bean 构造期（那里直接依赖 EventBus 会成环：
             * eventPublisher 收集本监听器 → 本监听器 → eventBus → eventPublisher）。
             */
            final EventBus eventBus = eventBusProvider.getIfAvailable();
            return new DecisionTaskCreatedListener(
                    propertiesProvider.getIfAvailable().isEnabled(),
                    eventBus != null && eventBus.isEnabled(),
                    executorProvider.getIfAvailable(),
                    pipelineProvider.getIfAvailable(),
                    index.snapshot(),
                    emitterProvider.getIfAvailable());
        } catch (final RuntimeException initFailure) {
            // 初始化失败不 fail-fast：拉面整体退到 fail-closed（不触发、零观测），与事件面关闭同型
            log.warn("决策声明面索引预热失败，拉面按 fail-closed 处置（不触发）", initFailure);
            return null;
        }
    }
}
