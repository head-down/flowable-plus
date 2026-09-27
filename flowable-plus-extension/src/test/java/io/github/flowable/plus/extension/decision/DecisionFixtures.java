package io.github.flowable.plus.extension.decision;

import io.github.flowable.plus.core.enums.DecisionChainStage;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionFailureKind;
import io.github.flowable.plus.core.enums.DecisionOutcome;
import io.github.flowable.plus.core.enums.DecisionSubjectType;

import java.util.Collections;
import java.util.List;

/**
 * 观测面的固定 fixture（<b>测试专用类型，非测试类</b>）。
 *
 * <p>形态 = <b>Java 常量</b>；<b>不落资源文件</b> —— 本模块的测试树<b>不建</b>
 * {@code src/test/resources}，故 recorded fixture 无栖身处（「recorded 不进 v1」的结构保证）。</p>
 */
final class DecisionFixtures {

    /**
     * 载荷哨兵：只出现在<b>载荷侧</b> fixture 里。
     *
     * <p>观测面（结构化日志与指标 tag value）一律不得出现它 —— 它是「观测面禁载」的运行期可判形态：
     * 哨兵真的存在于载荷侧，故「未出现于观测面」的断言不是真空成立。</p>
     */
    static final String PAYLOAD_SENTINEL = "SENTINEL-PAYLOAD-CONTENT-MUST-NOT-BE-OBSERVED";

    /** 锚点任务标识 */
    static final String TASK_ID = "task-20260927-0001";

    /** 节点标识 */
    static final String NODE_ID = "userTask-decide";

    /** 流程实例标识 */
    static final String PROCESS_INSTANCE_ID = "process-20260927-0001";

    /** 模型标识 */
    static final String MODEL_ID = "decision-model-1";

    /** 单次尝试耗时（毫秒） */
    static final long LATENCY_MS = 128L;

    /** 入向 token 用量 */
    static final long INPUT_TOKENS = 320L;

    /** 出向 token 用量 */
    static final long OUTPUT_TOKENS = 96L;

    /** 装配器丢弃的数据源 */
    private static final List<DecisionContextSource> DROPPED_SOURCES =
            Collections.unmodifiableList(Collections.singletonList(DecisionContextSource.PROCESS_VARIABLES));

    private DecisionFixtures() {
    }

    /**
     * 载荷侧 fixture：一段含哨兵的载荷文本（观测面禁载材料的替身；本票无载荷类型，故取裸串形态）。
     *
     * @return 含 {@link #PAYLOAD_SENTINEL} 的载荷文本
     */
    static String payloadCarryingSentinel() {
        return "{\"processVariables\":{\"note\":\"" + PAYLOAD_SENTINEL + "\"}}";
    }

    /**
     * 成功路径的观测 fixture（{@code SUGGESTION_DELIVERED} 行）。
     *
     * @return 观测事实
     */
    static DecisionObservation deliveredObservation() {
        return new DecisionObservation(TASK_ID, NODE_ID, PROCESS_INSTANCE_ID,
                DecisionOutcome.SUGGESTION_PRODUCED, null, null, DecisionSeverity.INFO,
                DecisionSubjectType.AI, MODEL_ID, DecisionChainStage.PRIMARY,
                LATENCY_MS, INPUT_TOKENS, OUTPUT_TOKENS, null, null, DROPPED_SOURCES);
    }

    /**
     * 失败行的观测 fixture（{@code OUTBOUND_TIMEOUT} 行：可重试、落证据行）。
     *
     * @return 观测事实
     */
    static DecisionObservation failedObservation() {
        return new DecisionObservation(TASK_ID, NODE_ID, PROCESS_INSTANCE_ID,
                DecisionOutcome.SUGGESTION_FAILED, DecisionFailureKind.OUTBOUND_TIMEOUT, null,
                DecisionSeverity.WARN, DecisionSubjectType.AI, MODEL_ID, DecisionChainStage.PRIMARY,
                LATENCY_MS, null, null, null, null, null);
    }

    /**
     * 未物质化行的观测 fixture（{@code ANCHOR_LOST} 行：结局三字段皆 null、严重度必填）。
     *
     * @return 观测事实
     */
    static DecisionObservation unmaterializedObservation() {
        return new DecisionObservation(TASK_ID, NODE_ID, PROCESS_INSTANCE_ID,
                null, null, null, DecisionSeverity.ERROR,
                DecisionSubjectType.USER, null, null,
                null, null, null, WriteDegradedCause.ANCHOR_LOST, null, null);
    }
}
