package io.github.flowable.plus.starter;

import io.github.flowable.plus.core.domain.PlusTask;
import io.github.flowable.plus.core.enums.ApprovalAction;
import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionRationaleFactKey;
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
import io.github.flowable.plus.core.spi.AutoApprovalRule;
import io.github.flowable.plus.core.spi.ProcessEventListener;
import io.github.flowable.plus.core.vo.DecisionRationaleFact;
import io.github.flowable.plus.extension.decision.DecisionOutboundResult;
import io.github.flowable.plus.extension.decision.DecisionPayload;
import io.github.flowable.plus.extension.decision.DecisionPolicy;
import io.github.flowable.plus.extension.decision.DecisionProcessingRecord;
import io.github.flowable.plus.extension.decision.DecisionProvider;
import io.github.flowable.plus.extension.decision.DecisionProviderRequest;
import io.github.flowable.plus.extension.decision.DecisionProviderResponse;
import io.github.flowable.plus.extension.decision.DecisionTarget;
import org.flowable.engine.impl.db.DbIdGenerator;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S5 两路径隔离探针的应用侧 stub —— <b>不带任何 stereotype 注解</b>（非 {@code @Configuration} /
 * 非 {@code @TestConfiguration}），仅经探针测试类的 {@code @Import} 与程序化 boot 的
 * {@code .sources()} 显式注册（lite 配置类形态）。
 *
 * <p><b>为何不能带 stereotype（实现期一手事实，如实登记）</b>：集成测试应用类对 starter 包做了
 * {@code @ComponentScan}，带 stereotype 的测试配置类会被<b>每一个</b> TestContext 管理的上下文
 * 收进（{@code @TestComponent} 不豁免该扫描）—— 本类的 {@link ProbeAutoApprovalRule} 是行为性
 * stub（对任意首任务返回固定意见），一旦漏进其它测试类的上下文，老路径会在不该触发的测试里
 * 自动提交（一手实测：{@code ProcessLifecycleIntegrationTest} 与 {@code S4} 被打红）。
 * 无 stereotype ⇒ 组件扫描不可见 ⇒ 本类的 Bean 恰好只存在于探针的三个上下文。</p>
 *
 * <p><b>三态同款注册</b>（{@code docs/impl/0042-two-path-isolation-probe.md} §2.3 的唯一差异纪律）：
 * 主闸不随全局开关消失 ⇒ 关态 / 开态下 fixture 的 {@code fp:decisionPolicy} / {@code fp:decisionTarget}
 * 必须能解析到注册表里的 key；把 stub 归入应用侧、三态逐一同款，正是这条约束的形态解。三态的唯一差异 =
 * 两个决策自动配置类是否参与 + {@code enabled} 的取值。</p>
 *
 * <p><b>测试类型（命名宪章 §1.1 判例）</b>：仅受 §2.B-T1 绝对禁词约束，不登记词根行。</p>
 */
public class DecisionTwoPathProbeTestConfiguration {

    /**
     * 三态同款属性：关掉异步事件发布 —— 面⑤ 的「逐位置等值」要求既有回调的录制顺序确定，
     * 而 {@code AsyncEventPublisher} 只把事件丢进线程池、顺序不是框架性质（故不进对拍面）。
     * 注解与程序化 boot 共用本常量，避免「三态同款」这条不变量靠两处人工同步。
     */
    public static final String EVENT_ASYNC_OFF = "flowable.plus.event.async=false";

    @Bean
    ProbeAutoApprovalRule probeAutoApprovalRule() {
        return new ProbeAutoApprovalRule();
    }

    @Bean
    ProbeDecisionTarget probeDecisionTarget() {
        return new ProbeDecisionTarget();
    }

    @Bean
    ProbeDecisionPolicy probeDecisionPolicy() {
        return new ProbeDecisionPolicy();
    }

    /**
     * 出站缝的整体替换点（E19 的 stub 契约）：返回固定建议、不发起网络；调用计数供
     * 断言 4 ②（默认关零出站）与 §9 甲（开态出站非零）读取。
     *
     * <p><b>响应体 = 默认方言的平铺 JSON（与 {@code rawOutput} 同源一致）</b>：管线的入站加工
     * {@code policy.applyInbound(response.getRawOutput())} 以响应体原文为入参，出处的
     * {@code chainStage} / {@code degraded} 从入站内容侧成立 —— 桩若只给解析字段、不给
     * {@code rawOutput}，写入器会以出处组半填拒绝（一手实测：归 INTERNAL_ERROR、证据行不落）。
     * 解析字段与响应体由本桩手工保持一致（默认方言字段名与证据 VO 同源）。</p>
     *
     * <p><b>{@code @Primary} 是实现期必需（如实登记）</b>：两个决策自动配置类经扫描与导入双路径
     * 注册，其 {@code @ConditionalOnMissingBean} 的求值时点随装载路径在桩注册之前 / 之后摆动，
     * 默认实现可能照常注册；无 {@code @Primary} 时 {@code decisionPipeline} 的注入会撞双候选。
     * 消费面（管线）经 {@code @Primary} 解析到桩，替换语义在行为面成立。</p>
     */
    @Bean
    @Primary
    ProbeDecisionProvider probeDecisionProvider() {
        return new ProbeDecisionProvider();
    }

    @Bean
    ProbeRecordingListener probeRecordingListener() {
        return new ProbeRecordingListener();
    }

    /**
     * 恢复引擎默认的 {@link DbIdGenerator}（三态同款；应用侧引擎配置）。<b>实现期一手事实</b>：
     * Flowable Spring Boot 路径的默认 {@code IdGenerator} 产出 <b>UUID 串</b>（与 standalone 默认的
     * 数值 {@code ID_} 不同），历史时间的确定性化改写与读侧「同毫秒按数值 {@code ID_} 兜底」
     * （D4 漂移的既有哲学）都建立在数值 {@code ID_} 上 —— 桩恢复它，三态一致生效。
     */
    @Bean
    EngineConfigurationConfigurer<SpringProcessEngineConfiguration> probeIdGeneratorConfigurer() {
        return configuration -> configuration.setIdGenerator(new DbIdGenerator());
    }

    /**
     * 老路径规则：{@code evaluate} 返回固定意见串；{@link #armFailure()} 后抛异常
     * （承探针形态文件 §5.4 的 fail-fast 场景）。自动提交的快照隔离保证该规则只作用于
     * {@code startProcess} 时刻的活跃任务快照（首任务），后置节点不级联。
     */
    static class ProbeAutoApprovalRule implements AutoApprovalRule {

        private final AtomicBoolean armed = new AtomicBoolean(false);

        /** 布防：下一次 {@code evaluate} 抛异常，触发 {@code startProcess} 整体回滚（面④ 的注入点）。 */
        void armFailure() {
            armed.set(true);
        }

        @Override
        public String evaluate(final PlusTask task, final Map<String, Object> variables) {
            if (armed.get()) {
                throw new IllegalStateException("探针规则已布防：startProcess 必须整体回滚");
            }
            return "探针-自动通过";
        }
    }

    /** 决策目标接线单元（key 与 fixture 声明一致；出站地址指向本机不可达端口，默认方言不触达）。 */
    static class ProbeDecisionTarget implements DecisionTarget {

        @Override
        public String key() {
            return "probeTarget";
        }

        @Override
        public String url() {
            return "http://127.0.0.1:1/unreachable";
        }
    }

    /** 出域策略（全放行、零加工）。 */
    static class ProbeDecisionPolicy implements DecisionPolicy {

        @Override
        public String key() {
            return "probePolicy";
        }

        @Override
        public DecisionOutboundResult applyOutbound(final DecisionPayload payload) {
            return new DecisionOutboundResult(true, payload, DecisionProcessingRecord.none());
        }
    }

    /** 固定响应体（默认方言平铺 JSON；与解析字段保持一致，供入站加工以 {@code rawOutput} 为入参）。 */
    private static final String PROBE_RESPONSE_BODY =
            "{\"suggestedAction\":\"AGREE\","
                    + "\"actionSummary\":\"探针建议摘要\","
                    + "\"rationaleFacts\":[{\"key\":\"BASIS_CODE\",\"value\":\"BASIS-S5\"}],"
                    + "\"rationaleNarrative\":\"探针建议依据文本兜底\","
                    + "\"modelId\":\"probe-model\","
                    + "\"chainStage\":\"PRIMARY\"}";

    static class ProbeDecisionProvider implements DecisionProvider {

        private final AtomicInteger callCount = new AtomicInteger();

        int callCount() {
            return callCount.get();
        }

        @Override
        public DecisionProviderResponse send(final DecisionProviderRequest request) {
            callCount.incrementAndGet();
            return DecisionProviderResponse.builder()
                    .suggestedAction(ApprovalAction.AGREE)
                    .actionSummary("探针建议摘要")
                    .rationaleFacts(Collections.singletonList(
                            new DecisionRationaleFact(DecisionRationaleFactKey.BASIS_CODE, "BASIS-S5")))
                    .rationaleNarrative("探针建议依据文本兜底")
                    .chainStage(DecisionChainStage.PRIMARY)
                    .modelId("probe-model")
                    .rawOutput(PROBE_RESPONSE_BODY)
                    .build();
        }
    }

    /**
     * 录制既有监听器回调的 listener（面⑤的录制面；三态同款注册，经 {@code EventBus} 的
     * {@code List<ProcessEventListener>} 收集）。录制只记本监听器自己的回调，机制侧监听器
     * （关态 / 开态的装配面注册）不进录制面。
     */
    /** 一条回调的录制条目（方法名 / 锚点 id / 内容位；事件时刻归动态、整体退出对拍面，不录制） */
    static final class CallbackEntry {

        final String methodName;
        final String anchorId;
        final String contentTag;

        CallbackEntry(final String methodName, final String anchorId, final String contentTag) {
            this.methodName = methodName;
            this.anchorId = anchorId;
            this.contentTag = contentTag;
        }
    }

    static class ProbeRecordingListener implements ProcessEventListener {

        private final List<CallbackEntry> entries = new ArrayList<>();

        @Override
        public void onProcessStarted(final ProcessStartedEvent event) {
            record("onProcessStarted", event.getProcessInstanceId(), null);
        }

        @Override
        public void onTaskCreated(final TaskCreatedEvent event) {
            record("onTaskCreated", event.getTaskId(), event.getAssignee());
        }

        @Override
        public void onTaskCompleted(final TaskCompletedEvent event) {
            record("onTaskCompleted", event.getTaskId(), null);
        }

        @Override
        public void onTaskRejected(final TaskRejectedEvent event) {
            record("onTaskRejected", event.getTaskId(), null);
        }

        @Override
        public void onTaskWithdrawn(final TaskWithdrawnEvent event) {
            record("onTaskWithdrawn", event.getTaskId(), null);
        }

        @Override
        public void onTaskTransferred(final TaskTransferredEvent event) {
            record("onTaskTransferred", event.getTaskId(), null);
        }

        @Override
        public void onTaskJumped(final TaskJumpedEvent event) {
            record("onTaskJumped", event.getTaskId(), null);
        }

        @Override
        public void onTaskDelegated(final TaskDelegatedEvent event) {
            record("onTaskDelegated", event.getTaskId(), null);
        }

        @Override
        public void onProcessInvalidated(final ProcessInvalidatedEvent event) {
            record("onProcessInvalidated", event.getProcessInstanceId(), null);
        }

        @Override
        public void onProcessEnded(final ProcessEndedEvent event) {
            record("onProcessEnded", event.getProcessInstanceId(), null);
        }

        private void record(final String methodName, final String anchorId, final String contentTag) {
            entries.add(new CallbackEntry(methodName, anchorId, contentTag));
        }

        /**
         * 归一化（面⑤；与 extension 侧 {@code E20} 语义相同、代码不复用 —— 模块边界）：
         * 锚点 id 按出现序重编号；{@code getEventTime()} 归动态、整体退出对拍面；
         * 连续同名运行为并列组，组内按内容位排序。本探针 fixture 无多实例拆分，并列组按稳定排序保持出现序。
         */
        List<String> normalizedEntries() {
            final Map<String, String> idTable = new HashMap<>();
            final List<String> normalized = new ArrayList<>();
            int index = 0;
            while (index < entries.size()) {
                final CallbackEntry head = entries.get(index);
                int groupEnd = index + 1;
                while (groupEnd < entries.size()
                        && entries.get(groupEnd).methodName.equals(head.methodName)) {
                    groupEnd++;
                }
                final List<CallbackEntry> group = new ArrayList<>(entries.subList(index, groupEnd));
                group.sort(Comparator.comparing(entry -> entry.contentTag == null ? "" : entry.contentTag));
                for (final CallbackEntry entry : group) {
                    final String id = entry.anchorId == null ? "-"
                            : idTable.computeIfAbsent(entry.anchorId, key -> "X" + idTable.size());
                    normalized.add(entry.methodName + "|" + id);
                }
                index = groupEnd;
            }
            return normalized;
        }
    }
}
