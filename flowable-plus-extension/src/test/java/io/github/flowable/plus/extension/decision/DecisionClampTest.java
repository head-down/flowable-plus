package io.github.flowable.plus.extension.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowable.plus.core.enums.DecisionContextSource;
import io.github.flowable.plus.core.enums.DecisionEvidenceReadGuard;
import io.github.flowable.plus.core.enums.DecisionEvidenceWriteGuard;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E6 —— 出域 clamp 守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E6}）。
 *
 * <p><b>承哪些推入项</b>：ADR-0042 第 6 节「硬上限 clamp」—— 不可放大 / 降序丢弃 / 兜底拒绝 /
 * 段内永不动刀 / 出站与直提共用 clamp 不共用策略（后者由 {@link DecisionInboundProcessor} 承担）；
 * 以及模块与构建 §4 的 G2（读侧常量不得与出域 clamp 同源）与 G3a（写入侧上界 ≥ 两方向 clamp 上限之和）。</p>
 *
 * <p><b>纯值，零引擎</b>：断言对象是载荷的段结构与字节数。</p>
 */
class DecisionClampTest {

    /** 段内容填充量（字节）：四段皆约 6 KiB，整载荷约 24 KiB < 硬上限，便于用实测大小反推上限 */
    private static final int SEGMENT_FILL_BYTES = 6 * 1024;

    /**
     * 序列化器：<b>默认配置</b>即计量口径所要求者（空段照发 {@code null}、不排序键、不减空白），
     * 与 clamp 内部对同一载荷的计量同源；extension 无 Spring 容器可注入，故此处自行构造。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("clamp 不可放大：请求上限再大，有效上限仍是框架硬上限")
    void clampNeverRaisesAppConfiguredLimit() {
        final DecisionPayload oversized = new DecisionPayload(variables(DecisionClamp.MAX_PAYLOAD_BYTES),
                null, null, null);

        // 请求上限放大十倍：框架硬上限是天花板，应用不可放大
        final DecisionPayload clamped = clamp(DecisionClamp.MAX_PAYLOAD_BYTES * 10).clampOutbound(oversized);

        assertThat(clamped.getProcessVariables())
                .as("不可放大：单段自身超限时必须被整段丢弃，而不是被放大的上限放行")
                .isNull();
        assertThat(utf8Size(clamped))
                .as("收缩后的载荷不得超过框架硬上限")
                .isLessThanOrEqualTo(DecisionClamp.MAX_PAYLOAD_BYTES);
    }

    @Test
    @DisplayName("clamp 按 dropPriority 降序整段丢弃")
    void dropsSegmentsInDescendingDropPriority() {
        final DecisionPayload all = fullPayload();
        final DecisionPayload withoutProcessVariables = new DecisionPayload(null,
                all.getTaskVariables(), all.getTaskMetadata(), all.getProcessInstanceMetadata());
        final DecisionPayload withoutVariables = new DecisionPayload(null, null,
                all.getTaskMetadata(), all.getProcessInstanceMetadata());
        final DecisionPayload onlyInstanceMetadata = new DecisionPayload(null, null, null,
                all.getProcessInstanceMetadata());
        final DecisionPayload none = new DecisionPayload(null, null, null, null);

        // 防空转：段内容必须真的贡献字节数，否则「丢到哪一段」无从分辨
        assertThat(utf8Size(all))
                .as("四段全装配的载荷必须小于硬上限（否则本测试的上限推演失真）")
                .isLessThanOrEqualTo(DecisionClamp.MAX_PAYLOAD_BYTES);
        assertThat(utf8Size(all)).isGreaterThan(utf8Size(withoutProcessVariables));
        assertThat(utf8Size(withoutProcessVariables)).isGreaterThan(utf8Size(withoutVariables));
        assertThat(utf8Size(withoutVariables)).isGreaterThan(utf8Size(onlyInstanceMetadata));
        assertThat(utf8Size(onlyInstanceMetadata)).isGreaterThan(utf8Size(none));

        // 恰丢一段 ⇒ 丢的必须是 40（流程变量）
        final DecisionPayload oneDrop = clamp(utf8Size(withoutProcessVariables)).clampOutbound(all);
        assertThat(oneDrop.getProcessVariables()).as("先丢 dropPriority 40").isNull();
        assertThat(oneDrop.getTaskVariables()).isNotNull();
        assertThat(oneDrop.getTaskMetadata()).isNotNull();
        assertThat(oneDrop.getProcessInstanceMetadata()).isNotNull();

        // 恰丢两段 ⇒ 40 › 30
        final DecisionPayload twoDrops = clamp(utf8Size(withoutVariables)).clampOutbound(all);
        assertThat(twoDrops.getProcessVariables()).isNull();
        assertThat(twoDrops.getTaskVariables()).as("次丢 dropPriority 30").isNull();
        assertThat(twoDrops.getTaskMetadata()).isNotNull();
        assertThat(twoDrops.getProcessInstanceMetadata()).isNotNull();

        // 恰丢三段 ⇒ 40 › 30 › 20
        final DecisionPayload threeDrops = clamp(utf8Size(onlyInstanceMetadata)).clampOutbound(all);
        assertThat(threeDrops.getProcessVariables()).isNull();
        assertThat(threeDrops.getTaskVariables()).isNull();
        assertThat(threeDrops.getTaskMetadata()).as("再丢 dropPriority 20").isNull();
        assertThat(threeDrops.getProcessInstanceMetadata()).as("实例元数据最后丢（10）").isNotNull();

        // 恰丢四段 ⇒ 40 › 30 › 20 › 10
        final DecisionPayload fourDrops = clamp(utf8Size(none)).clampOutbound(all);
        assertThat(fourDrops.getProcessVariables()).isNull();
        assertThat(fourDrops.getTaskVariables()).isNull();
        assertThat(fourDrops.getTaskMetadata()).isNull();
        assertThat(fourDrops.getProcessInstanceMetadata()).as("最后丢的是 dropPriority 10").isNull();

        assertThat(DecisionContextSource.PROCESS_VARIABLES.getDropPriority())
                .as("降序丢弃序依赖 dropPriority 显式契约（不依赖物理声明序）")
                .isGreaterThan(DecisionContextSource.TASK_VARIABLES.getDropPriority());
        assertThat(DecisionContextSource.TASK_VARIABLES.getDropPriority())
                .isGreaterThan(DecisionContextSource.TASK_METADATA.getDropPriority());
        assertThat(DecisionContextSource.TASK_METADATA.getDropPriority())
                .isGreaterThan(DecisionContextSource.PROCESS_INSTANCE_METADATA.getDropPriority());
    }

    @Test
    @DisplayName("丢到全空仍超 ⇒ 兜底拒绝")
    void fallsBackToRejectionWhenAllSegmentsDropped() {
        final DecisionPayload all = fullPayload();
        final int belowEmptyPayload = utf8Size(new DecisionPayload(null, null, null, null)) - 1;

        assertThatThrownBy(() -> clamp(belowEmptyPayload).clampOutbound(all))
                .as("丢到全空仍超上限 ⇒ 拒绝出域（硬闸，不由空载荷静默兜底）")
                .isInstanceOf(DecisionClampRejectedException.class);
    }

    @Test
    @DisplayName("段内永不动刀：超限段整段丢、未超限段原样留")
    void neverSplitsWithinSegment() throws Exception {
        final String oversizedValue = fill(DecisionClamp.MAX_PAYLOAD_BYTES);
        final DecisionPayload oversized = new DecisionPayload(variables(oversizedValue), null, null, null);

        final DecisionPayload clamped = clamp(DecisionClamp.MAX_PAYLOAD_BYTES).clampOutbound(oversized);

        assertThat(clamped.getProcessVariables()).as("单段自身超限 ⇒ 整段丢弃").isNull();
        assertThat(OBJECT_MAPPER.writeValueAsString(clamped))
                .as("段内永不动刀：不得留下「看似真实、实则残缺」的残片")
                .doesNotContain(fill(64));

        final String fittingValue = fill(64);
        final DecisionPayload fitting = new DecisionPayload(variables(fittingValue), null, null, null);
        final DecisionPayload untouched = clamp(DecisionClamp.MAX_PAYLOAD_BYTES).clampOutbound(fitting);

        assertThat(untouched.getProcessVariables())
                .as("上限内不做任何段内裁剪")
                .isSameAs(fitting.getProcessVariables());
        assertThat(untouched.getProcessVariables().get("k")).isEqualTo(fittingValue);
    }

    @Test
    @DisplayName("出站与直提共用 clamp、不共用策略")
    void outboundAndDirectShareClampButNotPolicy() {
        final DecisionClamp sharedClamp = clamp(DecisionClamp.MAX_PAYLOAD_BYTES);
        final DecisionInboundProcessor inboundProcessor = new DecisionInboundProcessor(sharedClamp);

        final String overLimit = fill(DecisionClamp.MAX_PAYLOAD_BYTES + 1);
        assertThat(inboundProcessor.process(overLimit))
                .as("直提的入站兜底与拉面入站走上限为硬上限的共用 clamp")
                .isNull();

        final String withinLimit = fill(DecisionClamp.MAX_PAYLOAD_BYTES);
        assertThat(inboundProcessor.process(withinLimit))
                .as("上限内原样返回（内容卫生归提交方自律）")
                .isSameAs(withinLimit);
        assertThat(sharedClamp.clampInbound(overLimit))
                .as("直提入站与拉面入站同源：同一组件、同一常量")
                .isNull();

        // 不共用策略：结构性保证 —— 入站加工既不持有也不注入 DecisionPolicy
        for (final Field field : DecisionInboundProcessor.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("入站加工不得持有策略（直提的 decisionPolicy key 无从取得）")
                    .isNotEqualTo(DecisionPolicy.class);
        }
        for (final Constructor<?> constructor : DecisionInboundProcessor.class.getDeclaredConstructors()) {
            for (final Class<?> parameterType : constructor.getParameterTypes()) {
                assertThat(parameterType)
                        .as("入站加工构造不得注入策略")
                        .isNotEqualTo(DecisionPolicy.class);
            }
        }
    }

    @Test
    @DisplayName("G2：读侧两常量与出域 clamp 常量不同源（不同常量类）")
    void readGuardIsNotTheEgressClampConstant() throws Exception {
        final Field clampLimit = DecisionClamp.class.getDeclaredField("MAX_PAYLOAD_BYTES");

        for (final String readGuardConstant : Arrays.asList("MAX_PARSE_BYTES", "MAX_NESTING_DEPTH")) {
            final Field constant = DecisionEvidenceReadGuard.class.getField(readGuardConstant);
            assertThat(constant.getDeclaringClass())
                    .as("读侧护栏常量 %s 与出域载荷上限指不同的事实，不得共用一个常量类", readGuardConstant)
                    .isNotEqualTo(clampLimit.getDeclaringClass());
        }
    }

    @Test
    @DisplayName("G3a：写入侧上界 ≥ 两方向 clamp 上限之和")
    void writeGuardCoversBothDirectionClampUpperBounds() {
        assertThat(DecisionClamp.MAX_PAYLOAD_BYTES)
                .as("出域 clamp 硬上限 = 32 KiB（两方向同一上限的唯一住所）")
                .isEqualTo(32 * 1024);
        assertThat(DecisionEvidenceWriteGuard.MAX_EVIDENCE_BYTES)
                .as("写入侧上界必须覆盖两方向 clamp 上限之和（2 × 32 KiB）")
                .isGreaterThanOrEqualTo(2 * DecisionClamp.MAX_PAYLOAD_BYTES);
    }

    // ======================== 私有支撑 ========================

    /**
     * 构造 clamp（请求上限 = 给定值）。
     *
     * @param requestedMaxBytes 请求上限
     * @return clamp
     */
    private static DecisionClamp clamp(final int requestedMaxBytes) {
        return new DecisionClamp(requestedMaxBytes, OBJECT_MAPPER);
    }

    /**
     * 四段全装配的载荷（各段约 {@value #SEGMENT_FILL_BYTES} 字节）。
     *
     * @return 载荷
     */
    private static DecisionPayload fullPayload() {
        return new DecisionPayload(variables(SEGMENT_FILL_BYTES), variables(SEGMENT_FILL_BYTES),
                taskMetadata(SEGMENT_FILL_BYTES), processInstanceMetadata(SEGMENT_FILL_BYTES));
    }

    /**
     * 变量段（值长度为给定字节数的 ASCII 串）。
     *
     * @param valueBytes 值长度（字节）
     * @return 变量段
     */
    private static Map<String, Object> variables(final int valueBytes) {
        return variables(fill(valueBytes));
    }

    /**
     * 变量段（给定值）。
     *
     * @param value 值
     * @return 变量段
     */
    private static Map<String, Object> variables(final String value) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("k", value);
        return map;
    }

    /**
     * 任务元数据（{@code assignee} 长度为给定字节数）。
     *
     * @param valueBytes 值长度（字节）
     * @return 任务元数据
     */
    private static TaskMetadata taskMetadata(final int valueBytes) {
        return new TaskMetadata("task-1", "name", "node-1", fill(valueBytes), null);
    }

    /**
     * 流程实例元数据（{@code businessKey} 长度为给定字节数）。
     *
     * @param valueBytes 值长度（字节）
     * @return 流程实例元数据
     */
    private static ProcessInstanceMetadata processInstanceMetadata(final int valueBytes) {
        return new ProcessInstanceMetadata("pi-1", "pd-key", fill(valueBytes), "starter", null);
    }

    /**
     * 生成给定长度的 ASCII 串（UTF-8 下逐字符一字节）。
     *
     * @param bytes 长度
     * @return 串
     */
    private static String fill(final int bytes) {
        return StringUtils.repeat('x', bytes);
    }

    /**
     * 载荷的序列化 UTF-8 字节数（与 clamp 内部计量口径同源）。
     *
     * @param payload 载荷
     * @return 字节数
     */
    private static int utf8Size(final DecisionPayload payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("载荷序列化失败", e);
        }
    }
}
