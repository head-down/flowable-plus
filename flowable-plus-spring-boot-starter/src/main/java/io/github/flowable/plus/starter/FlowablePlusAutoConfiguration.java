package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.BpmnFormDataHelper;
import io.github.flowable.plus.core.BpmnModelCache;
import io.github.flowable.plus.core.CounterSignWorkflow;
import io.github.flowable.plus.core.DefaultBpmnModelCache;
import io.github.flowable.plus.core.DefaultNodeFinder;
import io.github.flowable.plus.core.FlowableHistoricRepository;
import io.github.flowable.plus.core.FlowablePlus;
import io.github.flowable.plus.core.FlowableRuntimeProcessRepository;
import io.github.flowable.plus.core.FlowableTaskRepository;
import io.github.flowable.plus.core.HistoricRepository;
import io.github.flowable.plus.core.NodeFinder;
import io.github.flowable.plus.core.ProcessQueryWorkflow;
import io.github.flowable.plus.core.RuntimeProcessRepository;
import io.github.flowable.plus.core.TaskQueryModule;
import io.github.flowable.plus.core.TaskRepository;
import io.github.flowable.plus.core.TaskWorkflow;
import io.github.flowable.plus.core.UserTaskApproverResolver;
import io.github.flowable.plus.core.VOAssembler;
import io.github.flowable.plus.core.spi.ApproverResolver;
import io.github.flowable.plus.core.spi.CounterSignCallback;
import io.github.flowable.plus.core.spi.GroupResolver;
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
@EnableConfigurationProperties(FlowablePlusCounterSignProperties.class)
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
     * @param bpmnModelCache     BPMN 模型缓存
     * @param historicRepository 历史数据仓储
     * @param processEngine      Flowable 流程引擎
     * @return DefaultNodeFinder 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NodeFinder nodeFinder(BpmnModelCache bpmnModelCache, HistoricRepository historicRepository,
                                  ProcessEngine processEngine) {
        ExpressionManager expressionManager = ((ProcessEngineConfigurationImpl) processEngine
                .getProcessEngineConfiguration()).getExpressionManager();
        return new DefaultNodeFinder(bpmnModelCache, historicRepository, expressionManager);
    }

    /**
     * 注册 Flowable TaskRepository Bean。
     *
     * <p>将 Flowable 的 TaskService 链式查询 API 封装为简洁的单方法调用接口。</p>
     *
     * @param processEngine Flowable 流程引擎
     * @return FlowableTaskRepository 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskRepository taskRepository(ProcessEngine processEngine) {
        return new FlowableTaskRepository(processEngine.getTaskService());
    }

    /**
     * 注册 Flowable HistoricRepository Bean。
     *
     * <p>将 Flowable 的 HistoryService 链式查询 API 封装为简洁的单方法调用接口。</p>
     *
     * @param processEngine Flowable 流程引擎
     * @return FlowableHistoricRepository 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public HistoricRepository historicRepository(ProcessEngine processEngine) {
        return new FlowableHistoricRepository(processEngine.getHistoryService());
    }

    /**
     * 注册 TaskWorkflow Bean。
     *
     * <p>封装常规审批任务的推进、驳回、撤回、撤销逻辑。
     * 实现 {@link io.github.flowable.plus.core.TaskOperations}、
     * {@link io.github.flowable.plus.core.RejectionOperations}、
     * {@link io.github.flowable.plus.core.ProcessLifecycle} 接口。</p>
     *
     * @param userContext        用户上下文
     * @param taskRepository     任务仓储
     * @param historicRepository 历史数据仓储
     * @param nodeFinder         BPMN 节点遍历策略
     * @param bpmnModelCache     BPMN 模型缓存
     * @param processEngine      Flowable 流程引擎
     * @return TaskWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskWorkflow taskWorkflow(UserContext userContext, TaskRepository taskRepository,
                                     HistoricRepository historicRepository, NodeFinder nodeFinder,
                                     BpmnModelCache bpmnModelCache, ProcessEngine processEngine) {
        return new TaskWorkflow(userContext, taskRepository, historicRepository,
                processEngine.getRuntimeService(), processEngine.getIdentityService(),
                nodeFinder, bpmnModelCache);
    }

    /**
     * 注册 CounterSignWorkflow Bean。
     *
     * <p>封装多实例审批任务的投票与人员管理逻辑。
     * 实现 {@link io.github.flowable.plus.core.CounterSignOperations} 接口。
     * 当 {@code flowable.plus.counter-sign.enabled=false} 时回调列表为空。</p>
     *
     * @param userContext           用户上下文
     * @param taskRepository        任务仓储
     * @param historicRepository    历史数据仓储
     * @param nodeFinder            BPMN 节点遍历策略
     * @param bpmnModelCache        BPMN 模型缓存
     * @param processEngine         Flowable 流程引擎
     * @param counterSignCallbacks  会签回调列表（可选）
     * @param counterSignProps     会签配置属性
     * @return CounterSignWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CounterSignWorkflow counterSignWorkflow(UserContext userContext, TaskRepository taskRepository,
                                                   HistoricRepository historicRepository, NodeFinder nodeFinder,
                                                   BpmnModelCache bpmnModelCache, ProcessEngine processEngine,
                                                   @Autowired(required = false) List<CounterSignCallback> counterSignCallbacks,
                                                   FlowablePlusCounterSignProperties counterSignProps) {
        List<CounterSignCallback> callbacks = counterSignProps.isEnabled() && counterSignCallbacks != null
                ? counterSignCallbacks : Collections.emptyList();
        return new CounterSignWorkflow(userContext, taskRepository, historicRepository,
                processEngine.getRuntimeService(), bpmnModelCache, nodeFinder, callbacks);
    }

    /**
     * 注册 ApproverResolver Bean。
     *
     * <p>默认实现从 UserTask 的 assignee、candidateUsers 和
     * candidateGroups 中提取审批人。应用可通过声明同名 Bean 覆盖。</p>
     *
     * @param groupResolver 候选组解析器（可选）
     * @return UserTaskApproverResolver 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ApproverResolver approverResolver(@Autowired(required = false) GroupResolver groupResolver) {
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
     * 注册 FlowablePlus Bean。
     *
     * <p>当 {@code flowable.plus.enabled=true}（默认）时生效，
     * 且允许用户通过自定义同类型 Bean 覆盖。
     * 待办/已办查询委托给 TaskQueryModule，节点预览逻辑内聚于本模块。</p>
     *
     * @param taskQueryModule     待办/已办查询模块
     * @param runtimeService      Flowable 运行时服务
     * @param repositoryService   Flowable 仓储服务
     * @param taskService         Flowable 任务服务
     * @param nodeFinder          BPMN 节点遍历策略
     * @param bpmnModelCache      BPMN 模型缓存
     * @param approverResolver    审批人解析策略
     * @param bpmnFormDataHelper  BPMN 扩展属性解析工具
     * @return FlowablePlus 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "flowable.plus.enabled", havingValue = "true", matchIfMissing = true)
    public FlowablePlus flowablePlus(TaskQueryModule taskQueryModule,
                                     RuntimeService runtimeService,
                                     RepositoryService repositoryService,
                                     TaskService taskService,
                                     NodeFinder nodeFinder,
                                     BpmnModelCache bpmnModelCache,
                                     ApproverResolver approverResolver,
                                     BpmnFormDataHelper bpmnFormDataHelper) {
        return new FlowablePlus(taskQueryModule, runtimeService, repositoryService, taskService,
                nodeFinder, bpmnModelCache, approverResolver, bpmnFormDataHelper);
    }

    /**
     * 注册 RuntimeProcessRepository Bean。
     *
     * <p>封装 RuntimeService 的进程实例查询，为 {@link ProcessQueryWorkflow}
     * 提供一致的仓储接缝。</p>
     *
     * @param processEngine Flowable 流程引擎
     * @return FlowableRuntimeProcessRepository 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RuntimeProcessRepository runtimeProcessRepository(ProcessEngine processEngine) {
        return new FlowableRuntimeProcessRepository(processEngine.getRuntimeService());
    }

    /**
     * 注册 ProcessQueryWorkflow Bean。
     *
     * <p>封装批量流程实例摘要查询逻辑，通过 {@link RuntimeProcessRepository}、
     * {@link TaskRepository} 和 {@link HistoricRepository} 接缝访问流程数据。</p>
     *
     * @param runtimeProcessRepository 运行时实例仓储
     * @param taskRepository           任务仓储
     * @param historicRepository       历史数据仓储
     * @param bpmnModelCache           BPMN 模型缓存
     * @return ProcessQueryWorkflow 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessQueryWorkflow processQueryWorkflow(RuntimeProcessRepository runtimeProcessRepository,
                                                      TaskRepository taskRepository,
                                                      HistoricRepository historicRepository,
                                                      BpmnModelCache bpmnModelCache) {
        return new ProcessQueryWorkflow(runtimeProcessRepository, taskRepository, historicRepository, bpmnModelCache);
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
