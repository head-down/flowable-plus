package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.support.BpmnFormDataHelper;
import io.github.flowable.plus.core.support.PreviousNodeAuthorizer;
import io.github.flowable.plus.core.support.ProcessEndDetector;
import io.github.flowable.plus.core.model.BpmnModelCache;
import io.github.flowable.plus.core.workflow.CounterSignWorkflow;
import io.github.flowable.plus.core.workflow.DiagramWorkflow;
import io.github.flowable.plus.core.workflow.FlowableExecutionTreeHelper;
import io.github.flowable.plus.core.workflow.HistoryWorkflow;
import io.github.flowable.plus.core.model.DefaultBpmnModelCache;
import io.github.flowable.plus.core.model.DefaultNodeFinder;
import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.workflow.NodePreviewWorkflow;
import io.github.flowable.plus.core.workflow.ProcessQueryWorkflow;
import io.github.flowable.plus.core.workflow.TaskQueryModule;
import io.github.flowable.plus.core.workflow.ProcessLifecycleWorkflow;
import io.github.flowable.plus.core.workflow.TaskExecutionWorkflow;
import io.github.flowable.plus.core.support.UserTaskApproverResolver;
import io.github.flowable.plus.core.support.VOAssembler;
import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.event.DefaultEventPublisher;
import io.github.flowable.plus.core.event.EventPublisher;
import io.github.flowable.plus.core.spi.AutoApprovalRule;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.ExecutionTreeHelper;
import io.github.flowable.plus.core.spi.GroupResolver;
import io.github.flowable.plus.core.spi.IdentityResolver;
import io.github.flowable.plus.core.spi.ProcessEventListener;

import io.github.flowable.plus.core.spi.UserContext;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;

/**
 * Flowable Plus auto-configuration.
 *
 * <p>当 classpath 上存在 {@link ProcessEngine} 时自动激活，
 * 注册 {@link FlowablePlus} Bean 作为工作流增强的统一入口。</p>
 *
 * <p>可通过 {@code flowable.plus.enabled=false} 禁用。</p>
 *
 * @author flowable-plus
 */
@Configuration
@ConditionalOnClass(name = "org.flowable.engine.ProcessEngine")
@EnableConfigurationProperties({FlowablePlusCounterSignProperties.class, FlowablePlusEventProperties.class})
public class FlowablePlusAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlowablePlusAutoConfiguration.class);

    /**
     * 启动时输出自动配置激活日志，便于排查配置是否生效。
     */
    @PostConstruct
    void logActivation() {
        log.info("FlowablePlus auto-configuration activated");
    }

    /**
     * 注册默认 BpmnModelCache Bean。
     *
     * <p>基于 ConcurrentHashMap，BPMN 模型部署后不可变，永不过期。
     * 应用可通过声明同名 Bean 替换缓存策略（如 LRU、TTL 等）。</p>
     *
     * @param processEngine Flowable 流程引擎
     * @return DefaultBpmnModelCache 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public BpmnModelCache bpmnModelCache(ProcessEngine processEngine) {
        return new DefaultBpmnModelCache(processEngine.getRepositoryService());
    }

    /**
     * 注册默认 NodeFinder Bean。
     *
     * <p>应用可通过声明同名 Bean 替换默认的 BPMN 节点遍历策略。</p>
     *
     * @param bpmnModelCache  BPMN 模型缓存
     * @param historyService  Flowable 历史服务
     * @param processEngine   Flowable 流程引擎
     * @return DefaultNodeFinder 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NodeFinder nodeFinder(BpmnModelCache bpmnModelCache, HistoryService historyService,
                                  ProcessEngine processEngine) {
        ExpressionManager expressionManager = ((ProcessEngineConfigurationImpl) processEngine
                .getProcessEngineConfiguration()).getExpressionManager();
        return new DefaultNodeFinder(bpmnModelCache, historyService, expressionManager);
    }

    /**
     * 注册 ExecutionTreeHelper Bean。
     *
     * <p>默认实现基于 Flowable 内部 API，封装并行网关分支剥离与清理操作。
     * 应用可通过声明同名 Bean 替换为其他 Flowable 版本的适配实现。</p>
     *
     * @param processEngine Flowable 流程引擎
     * @return FlowableExecutionTreeHelper 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ExecutionTreeHelper executionTreeHelper(ProcessEngine processEngine) {
        return new FlowableExecutionTreeHelper(processEngine.getManagementService());
    }

    /**
     * 事件发布线程池 Bean。
     *
     * <p>仅在异步事件发布特性开启时注册（默认开启）。
     * 线程命名前缀为 flowable-plus-event-，便于排查。
     * 使用 {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} 防止事件丢失。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "flowable.plus.event.async", havingValue = "true", matchIfMissing = true)
    public ThreadPoolTaskExecutor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("flowable-plus-event-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 注册 EventPublisher Bean。
     *
     * <p>当 {@code flowable.plus.event.enabled=true}（默认）时生效，
     * 收集所有 {@link ProcessEventListener} Bean，构建同步发布器。
     * 当 {@code flowable.plus.event.async=true} 时以 AsyncEventPublisher 装饰。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "flowable.plus.event.enabled", havingValue = "true", matchIfMissing = true)
    public EventPublisher eventPublisher(
            @Autowired(required = false) List<ProcessEventListener> eventListeners,
            @Autowired(required = false) ThreadPoolTaskExecutor eventExecutor,
            FlowablePlusEventProperties eventProperties) {

        List<ProcessEventListener> listeners = eventListeners != null ? eventListeners : Collections.emptyList();
        DefaultEventPublisher syncPublisher = new DefaultEventPublisher(listeners);

        if (eventProperties.isAsync() && eventExecutor != null) {
            return new AsyncEventPublisher(syncPublisher, eventExecutor);
        }
        return syncPublisher;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessEndDetector processEndDetector(RuntimeService runtimeService,
                                                   HistoryService historyService,
                                                   @Autowired(required = false) EventPublisher eventPublisher) {
        return new ProcessEndDetector(runtimeService, historyService, eventPublisher);
    }

    /**
     * 注册 PreviousNodeAuthorizer Bean。
     *
     * <p>封装"上一节点审批人身份校验"的流水线查询逻辑，
     * 供 TaskExecutionWorkflow 和 CounterSignWorkflow
     * 复用，消除内联权限代码重复。</p>
     *
     * @param taskService    Flowable 任务服务
     * @param historyService Flowable 历史服务
     * @param nodeFinder      BPMN 节点遍历器
     * @return PreviousNodeAuthorizer 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public PreviousNodeAuthorizer previousNodeAuthorizer(TaskService taskService,
                                                           HistoryService historyService,
                                                           NodeFinder nodeFinder) {
        return new PreviousNodeAuthorizer(taskService, historyService, nodeFinder);
    }

    /**
     * 注册 ProcessLifecycleWorkflow Bean。
     *
     * <p>封装流程发起与撤销逻辑，包含自动提交能力。
     * 实现 {@link io.github.flowable.plus.core.api.ProcessLifecycleOperations} 接口。
     * {@code autoApprovalRules} 为可选参数，无注册 Bean 时行为不变。</p>
     *
     * @param userContext             用户上下文
     * @param taskService             Flowable 任务服务
     * @param historyService          Flowable 历史服务
     * @param nodeFinder              BPMN 节点遍历策略
     * @param processEngine           Flowable 流程引擎
     * @param autoApprovalRules       自动提交规则列表（可选）
     * @return ProcessLifecycleWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessLifecycleWorkflow processLifecycleWorkflow(UserContext userContext, TaskService taskService,
                                                              HistoryService historyService, NodeFinder nodeFinder,
                                                              ProcessEngine processEngine,
                                                              @Autowired(required = false) List<AutoApprovalRule> autoApprovalRules,
                                                              @Autowired(required = false) EventPublisher eventPublisher) {
        return new ProcessLifecycleWorkflow(userContext, taskService, historyService,
                processEngine.getRuntimeService(), processEngine.getIdentityService(),
                nodeFinder, autoApprovalRules, eventPublisher);
    }

    /**
     * 注册 TaskExecutionWorkflow Bean。
     *
     * <p>封装常规审批任务的推进、驳回、撤回、跳转、转办和认领逻辑。
     * 实现 {@link io.github.flowable.plus.core.api.TaskExecutionOperations} 接口。</p>
     *
     * @param userContext             用户上下文
     * @param taskService             Flowable 任务服务
     * @param historyService          Flowable 历史服务
     * @param nodeFinder              BPMN 节点遍历策略
     * @param multiInstanceDetector   多实例检测模块
     * @param processEngine           Flowable 流程引擎
     * @param executionTreeHelper     执行树操作辅助
     * @return TaskExecutionWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskExecutionWorkflow taskExecutionWorkflow(UserContext userContext, TaskService taskService,
                                                        HistoryService historyService, NodeFinder nodeFinder,
                                                        MultiInstanceDetector multiInstanceDetector,
                                                        ProcessEngine processEngine,
                                                        ExecutionTreeHelper executionTreeHelper,
                                                        @Autowired(required = false) EventPublisher eventPublisher,
                                                        ProcessEndDetector processEndDetector,
                                                        PreviousNodeAuthorizer previousNodeAuthorizer) {
        return new TaskExecutionWorkflow(userContext, taskService, historyService,
                processEngine.getRuntimeService(), nodeFinder, multiInstanceDetector,
                executionTreeHelper, eventPublisher, processEndDetector, previousNodeAuthorizer);
    }

    /**
     * 注册 CounterSignWorkflow Bean。
     *
     * <p>封装多实例审批任务的投票与人员管理逻辑。
     * 实现 {@link io.github.flowable.plus.core.api.CounterSignOperations} 接口。
     * 当 {@code flowable.plus.counter-sign.enabled=false} 时回调列表为空。</p>
     *
     * @param userContext            用户上下文
     * @param taskService            Flowable 任务服务
     * @param historyService         Flowable 历史服务
     * @param nodeFinder              BPMN 节点遍历策略
     * @param multiInstanceDetector   多实例检测模块
     * @param processEngine           Flowable 流程引擎
     * @param counterSignCallbacks    会签回调列表（可选）
     * @param counterSignProps        会签配置属性
     * @return CounterSignWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CounterSignWorkflow counterSignWorkflow(UserContext userContext, TaskService taskService,
                                                   HistoryService historyService, NodeFinder nodeFinder,
                                                   MultiInstanceDetector multiInstanceDetector,
                                                   ProcessEngine processEngine,
                                                   @Autowired(required = false) List<CounterSignCallback> counterSignCallbacks,
                                                   FlowablePlusCounterSignProperties counterSignProps,
                                                   @Autowired(required = false) EventPublisher eventPublisher,
                                                   ProcessEndDetector processEndDetector,
                                                   PreviousNodeAuthorizer previousNodeAuthorizer) {
        List<CounterSignCallback> callbacks = counterSignProps.isEnabled() && counterSignCallbacks != null
                ? counterSignCallbacks : Collections.emptyList();
        return new CounterSignWorkflow(userContext, taskService, historyService,
                processEngine.getRuntimeService(), multiInstanceDetector, nodeFinder, callbacks,
                eventPublisher, processEndDetector, previousNodeAuthorizer);
    }

    /**
     * 注册 GroupResolver 默认 Bean。
     *
     * <p>基于 Flowable IdentityService 查询组成员列表。
     * 应用可通过声明同名 Bean 替换为自定义组织架构服务。</p>
     *
     * @param identityService Flowable 身份认证服务
     * @return IdentityGroupResolver 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public GroupResolver groupResolver(IdentityService identityService) {
        return new IdentityGroupResolver(identityService);
    }


    /**
     * 注册 IdentityResolver 默认 Bean。
     *
     * <p>兜底实现为 userId→userId，直接返回用户 ID 作为名称。
     * 应用可通过声明同名 Bean 替换为自定义身份服务。</p>
     *
     * @return IdentityResolver 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public IdentityResolver identityResolver() {
        return userId -> userId;
    }

    /**
     * 注册 ApproverResolver Bean。
     *
     * <p>默认实现从 UserTask 的 assignee、candidateUsers 和
     * candidateGroups 中提取审批人。应用可通过声明同名 Bean 覆盖。</p>
     *
     * @param groupResolver 候选组解析器
     * @return UserTaskApproverResolver 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ApproverResolver approverResolver(GroupResolver groupResolver) {
        return new UserTaskApproverResolver(groupResolver);
    }

    /**
     * 注册 BpmnFormDataHelper Bean。
     *
     * <p>从 BPMN FlowElement 的扩展属性中提取自定义 formData。
     * 应用可通过声明同名 Bean 覆盖。</p>
     *
     * @return BpmnFormDataHelper 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public BpmnFormDataHelper bpmnFormDataHelper() {
        return new BpmnFormDataHelper();
    }

    /**
     * 注册 VOAssembler Bean。
     *
     * <p>将 Flowable 原生任务对象转换为框架 VO，内建流程信息补全缓存。
     * 应用可通过声明同名 Bean 覆盖。</p>
     *
     * @param repositoryService Flowable 仓储服务
     * @param historyService    Flowable 历史服务
     * @return VOAssembler 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public VOAssembler voAssembler(RepositoryService repositoryService, HistoryService historyService) {
        return new VOAssembler(repositoryService, historyService);
    }

    /**
     * 注册 TaskQueryModule Bean。
     *
     * <p>封装待办/已办查询逻辑，通过 VOAssembler 完成 VO 转换。
     * 应用可通过声明同名 Bean 覆盖。</p>
     *
     * @param taskService     Flowable 任务服务
     * @param historyService  Flowable 历史服务
     * @param identityService Flowable 身份认证服务
     * @param voAssembler     VO 转换模块
     * @return TaskQueryModule 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskQueryModule taskQueryModule(TaskService taskService, HistoryService historyService,
                                            IdentityService identityService, VOAssembler voAssembler) {
        return new TaskQueryModule(taskService, historyService, identityService, voAssembler);
    }

    /**
     * 注册 NodePreviewWorkflow Bean。
     *
     * <p>封装流程定义起始节点预览、运行时任务下游节点预测等逻辑。
     * 应用可通过声明同名 Bean 替换节点预览实现。</p>
     *
     * @param repositoryService  Flowable 仓储服务
     * @param bpmnModelCache     BPMN 模型缓存
     * @param nodeFinder         BPMN 节点遍历策略
     * @param approverResolver   审批人解析策略
     * @param taskService        Flowable 任务服务
     * @param runtimeService     Flowable 运行时服务
     * @param bpmnFormDataHelper BPMN 扩展属性解析工具
     * @return NodePreviewWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NodePreviewWorkflow nodePreviewWorkflow(RepositoryService repositoryService,
                                                    BpmnModelCache bpmnModelCache,
                                                    NodeFinder nodeFinder,
                                                    ApproverResolver approverResolver,
                                                    TaskService taskService,
                                                    RuntimeService runtimeService,
                                                    BpmnFormDataHelper bpmnFormDataHelper) {
        return new NodePreviewWorkflow(repositoryService, bpmnModelCache, nodeFinder,
                approverResolver, taskService, runtimeService, bpmnFormDataHelper);
    }

    /**
     * 注册 DiagramWorkflow Bean。
     *
     * <p>封装流程图生成逻辑，包含节点状态分类和 SVG 渲染。
     * 应用可通过声明同名 Bean 替换默认实现。</p>
     *
     * @param historyService  Flowable 历史服务
     * @param bpmnModelCache  BPMN 模型缓存
     * @return DiagramWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public DiagramWorkflow diagramWorkflow(HistoryService historyService, BpmnModelCache bpmnModelCache) {
        return new DiagramWorkflow(historyService, bpmnModelCache);
    }

    /**
     * 注册 HistoryWorkflow Bean。
     *
     * <p>封装审批历史查询逻辑，实现 ADR-0009 三级 Comment→Action 推断策略。
     * 应用可通过声明同名 Bean 替换默认实现。</p>
     *
     * @param historyService         Flowable 历史服务
     * @param taskService            Flowable 任务服务
     * @param bpmnModelCache         BPMN 模型缓存
     * @param multiInstanceDetector  多实例检测模块
     * @param identityResolver       身份解析器
     * @return HistoryWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public HistoryWorkflow historyWorkflow(HistoryService historyService,
                                            TaskService taskService,
                                            BpmnModelCache bpmnModelCache,
                                            MultiInstanceDetector multiInstanceDetector,
                                            IdentityResolver identityResolver) {
        return new HistoryWorkflow(historyService, taskService, bpmnModelCache,
                multiInstanceDetector, identityResolver);
    }

    /**
     * 注册 FlowablePlus Bean。
     *
     * <p>当 {@code flowable.plus.enabled=true}（默认）时生效。
     * 待办/已办查询委托给 TaskQueryModule，节点预览委托给 NodePreviewWorkflow，
     * 流程追踪委托给 ProcessQueryWorkflow，流程图委托给 DiagramWorkflow，
     * 审批历史委托给 HistoryWorkflow。</p>
     *
     * @param taskQueryModule      待办/已办查询模块
     * @param processQueryWorkflow 流程追踪模块
     * @param nodePreviewWorkflow  节点预览模块
     * @param diagramWorkflow      流程图生成模块
     * @param historyWorkflow      审批历史查询模块
     * @return FlowablePlus 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "flowable.plus.enabled", havingValue = "true", matchIfMissing = true)
    public FlowablePlus flowablePlus(TaskQueryModule taskQueryModule,
                                     ProcessQueryWorkflow processQueryWorkflow,
                                     NodePreviewWorkflow nodePreviewWorkflow,
                                     DiagramWorkflow diagramWorkflow,
                                     HistoryWorkflow historyWorkflow) {
        return new FlowablePlus(taskQueryModule, processQueryWorkflow, nodePreviewWorkflow,
                diagramWorkflow, historyWorkflow);
    }

    /**
     * 注册 MultiInstanceDetector Bean。
     *
     * <p>从 BPMN 模型缓存中提取的多实例检测逻辑独立模块。
     * 应用可通过声明同名 Bean 覆盖。</p>
     *
     * @param bpmnModelCache BPMN 模型缓存
     * @return MultiInstanceDetector 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public MultiInstanceDetector multiInstanceDetector(BpmnModelCache bpmnModelCache) {
        return new MultiInstanceDetector(bpmnModelCache);
    }

    /**
     * 注册 ProcessQueryWorkflow Bean。
     *
     * <p>封装批量流程实例摘要查询与审批轨迹查询。</p>
     *
     * @param runtimeService         Flowable 运行时服务
     * @param taskService            Flowable 任务服务
     * @param historyService         Flowable 历史服务
     * @param multiInstanceDetector  多实例检测模块
     * @return ProcessQueryWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessQueryWorkflow processQueryWorkflow(RuntimeService runtimeService,
                                                      TaskService taskService,
                                                      HistoryService historyService,
                                                      MultiInstanceDetector multiInstanceDetector) {
        return new ProcessQueryWorkflow(runtimeService, taskService, historyService, multiInstanceDetector);
    }

    /**
     * Spring Security 环境的 UserContext Bean，从认证上下文读取当前用户。
     *
     * <p>仅在 classpath 上存在 Spring Security 时生效。
     * 应用可通过声明同名 Bean 覆盖此实现。</p>
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.security.core.Authentication")
    @ConditionalOnMissingBean
    public UserContext securityContextUserContext() {
        return new SecurityContextUserContext();
    }

    /**
     * 非 Spring Security 环境的 UserContext 兜底 Bean。
     *
     * <p>当 classpath 上不存在 Spring Security 且未自定义 UserContext 时生效。
     * 从系统属性 {@code flowable.plus.user-id} 读取当前用户 ID，
     * 未设置时返回 {@code "system"}。生产环境建议注入认证框架对应的实现。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public UserContext systemPropertyUserContext() {
        return new SystemPropertyUserContext();
    }
}
