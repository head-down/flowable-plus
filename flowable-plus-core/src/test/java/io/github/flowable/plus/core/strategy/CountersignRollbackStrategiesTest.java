package io.github.flowable.plus.core.strategy;

import io.github.flowable.plus.core.model.MultiInstanceDetector;
import io.github.flowable.plus.core.model.NodeFinder;
import io.github.flowable.plus.core.vo.RollbackResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CountersignRollbackStrategies#resolveRedirectOutcome} 共享助手单测（ADR-0041）。
 *
 * <p>覆盖三态语义 + null name fallback + messageBuilder 回调契约。
 * 本类只钉共享助手骨架；消费方（getJumpableNodes / AutoRedirect 策略）行为
 * 由各自集成测试覆盖。</p>
 */
public class CountersignRollbackStrategiesTest {

    private static final String PROCESS_DEF_ID = "leave:1:abc";
    private static final String PROCESS_INST_ID = "pi-001";
    private static final String MI_ACTIVITY_ID = "csTask";
    private static final String PREDECESSOR_ID = "task1";

    private NodeFinder mockNodeFinder;
    private MultiInstanceDetector mockMultiInstanceDetector;

    /** 预览态措辞 lambda（与 TaskExecutionWorkflow.getJumpableNodes 一致） */
    private static final BiFunction<String, String, String> PREVIEW_MESSAGE_BUILDER =
            (targetDisplay, predecessorDisplay) ->
                    targetDisplay + "（系统将重定向至: " + predecessorDisplay + "）";

    /** 执行态措辞 lambda（与 AutoRedirectCountersignRollbackStrategy 一致） */
    private static final BiFunction<String, String, String> EXECUTE_MESSAGE_BUILDER =
            (targetDisplay, predecessorDisplay) -> String.format(
                    "选择的审批人节点 [%s] 在本次流程中为多人会签，"
                            + "系统已自动重定向至前置准备节点: [%s]",
                    targetDisplay, predecessorDisplay);

    @BeforeEach
    void setUp() {
        mockNodeFinder = mock(NodeFinder.class);
        mockMultiInstanceDetector = mock(MultiInstanceDetector.class);
    }

    // ======================== 三态语义 ========================

    @Test
    void testNonRuntimeMultiInstanceReturnsDirect() {
        // 模型非 MI 或全局历史数 <= 1 → 短路 direct(targetActivityId)
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).thenReturn(false);

        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, PREVIEW_MESSAGE_BUILDER);

        assertThat(result).isNotNull();
        assertThat(result.getTargetActivityId()).isEqualTo(MI_ACTIVITY_ID);
        assertThat(result.getRedirectMessage()).isNull();
        assertThat(result.getNewAssigneeList()).isNull();
    }

    @Test
    void testRuntimeMultiInstanceWithPredecessorReturnsRedirect() {
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).thenReturn(true);
        // 有唯一前置单例节点
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn("前置准备节点");

        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, PREVIEW_MESSAGE_BUILDER);

        assertThat(result).isNotNull();
        assertThat(result.getTargetActivityId()).isEqualTo(PREDECESSOR_ID);
        // 预览态措辞被 lambda 拼装
        assertThat(result.getRedirectMessage())
                .isEqualTo("会签节点（系统将重定向至: 前置准备节点）");
        assertThat(result.getNewAssigneeList()).isNull();
    }

    @Test
    void testRuntimeMultiInstanceWithoutPredecessorReturnsNull() {
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).thenReturn(true);
        // 无前置节点
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.emptyList());

        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, PREVIEW_MESSAGE_BUILDER);

        // 无前置 → null（消费方各自处理：预览态 continue / 执行态 throw）
        assertThat(result).isNull();
    }

    @Test
    void testRuntimeMultiInstanceWithMultiplePredecessorsReturnsNull() {
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).thenReturn(true);
        // 多个前置节点（均非 MI）→ 不唯一 → null
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "taskA"))
                .thenReturn(false);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "taskB"))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Arrays.asList("taskA", "taskB"));

        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, PREVIEW_MESSAGE_BUILDER);

        assertThat(result).isNull();
    }

    @Test
    void testRuntimeMultiInstanceWithAllMiPredecessorsReturnsNull() {
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).thenReturn(true);
        // 前置全是 MI 节点 → 过滤后空 → null
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, "miPred"))
                .thenReturn(true);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList("miPred"));

        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, PREVIEW_MESSAGE_BUILDER);

        assertThat(result).isNull();
    }

    // ======================== null name fallback 单点（助手内统一兜底） ========================

    @Test
    void testNullNodeNameFallsBackToIdInMessageBuilder() {
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).thenReturn(true);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));
        // nodeName 都为 null → 助手内 fallback 到 ID
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID)).thenReturn(null);
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, PREDECESSOR_ID)).thenReturn(null);

        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, PREVIEW_MESSAGE_BUILDER);

        assertThat(result).isNotNull();
        // messageBuilder 拿到的是 ID 兜底后的字符串，不是 "null"
        assertThat(result.getRedirectMessage())
                .isEqualTo(MI_ACTIVITY_ID + "（系统将重定向至: " + PREDECESSOR_ID + "）");
    }

    @Test
    void testExecuteModeMessageBuilderProducesExecuteToneWording() {
        when(mockMultiInstanceDetector.isRuntimeMultiInstanceNode(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID)).thenReturn(true);
        when(mockMultiInstanceDetector.isMultiInstanceNode(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn(false);
        when(mockNodeFinder.findPreviousNodes(PROCESS_DEF_ID, MI_ACTIVITY_ID, PROCESS_INST_ID))
                .thenReturn(Collections.singletonList(PREDECESSOR_ID));
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, MI_ACTIVITY_ID))
                .thenReturn("会签节点");
        when(mockNodeFinder.getNodeName(PROCESS_DEF_ID, PREDECESSOR_ID))
                .thenReturn("前置准备节点");

        // 执行态措辞 lambda（与 AutoRedirect 策略一致）
        RollbackResult result = CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, EXECUTE_MESSAGE_BUILDER);

        assertThat(result).isNotNull();
        assertThat(result.getRedirectMessage())
                .contains("会签节点")
                .contains("多人会签")
                .contains("自动重定向")
                .contains("前置准备节点");
    }

    // ======================== 构造参数 null 校验 ========================

    @Test
    void testNullMultiInstanceDetectorRejected() {
        assertThatThrownBy(() -> CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, null, PREVIEW_MESSAGE_BUILDER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MultiInstanceDetector");
    }

    @Test
    void testNullNodeFinderRejected() {
        assertThatThrownBy(() -> CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                null, mockMultiInstanceDetector, PREVIEW_MESSAGE_BUILDER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NodeFinder");
    }

    @Test
    void testNullMessageBuilderRejected() {
        assertThatThrownBy(() -> CountersignRollbackStrategies.resolveRedirectOutcome(
                PROCESS_DEF_ID, PROCESS_INST_ID, MI_ACTIVITY_ID,
                mockNodeFinder, mockMultiInstanceDetector, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageBuilder");
    }
}
