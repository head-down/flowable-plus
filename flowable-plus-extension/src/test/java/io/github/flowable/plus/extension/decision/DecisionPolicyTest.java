package io.github.flowable.plus.extension.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E7 —— 出域策略契约守卫（{@code docs/impl/0042-verification-landings.md} §3.2 的 {@code E7}）。
 *
 * <p><b>承哪些推入项</b>：ADR-0042 第 6 节「出域策略契约的形态定稿」—— <b>结果位互锁</b>
 * （{@code permitted} / {@code persistable} 为 {@code true} ⇒ 对应内容位必非空）、<b>双向服务</b>
 * （同一接口、两个方法、不带方向词）、{@code DecisionProcessingRecord} <b>只承加工事实</b>
 * （「说不」住结果位）。</p>
 *
 * <p><b>纯契约，零引擎</b>：断言对象是值类型与接口形状。</p>
 */
class DecisionPolicyTest {

    @Test
    @DisplayName("结果位互锁：permitted = true ⇒ 出域载荷必非空")
    void permittedTrueImpliesNonEmptyPayload() {
        assertThatThrownBy(() -> new DecisionOutboundResult(true, null, DecisionProcessingRecord.none()))
                .as("放行却不给内容 = 凭空造出「无载荷的出域」，构造期即拒")
                .isInstanceOf(IllegalArgumentException.class);

        final DecisionPayload payload = new DecisionPayload(null, null, null, null);
        final DecisionOutboundResult allowed = new DecisionOutboundResult(true, payload,
                new DecisionProcessingRecord(true, false));
        assertThat(allowed.isPermitted()).isTrue();
        assertThat(allowed.getPayload()).as("放行 ⇒ 载荷必非空").isSameAs(payload);
        assertThat(allowed.getRecord().isRedacted()).as("加工记录随结果位传递，不与之混用").isTrue();
        assertThat(allowed.getRecord().isTruncated()).isFalse();

        final DecisionOutboundResult rejected = new DecisionOutboundResult(false, null,
                DecisionProcessingRecord.none());
        assertThat(rejected.isPermitted()).as("主动拒绝 = 合规、不计错误").isFalse();
        assertThat(rejected.getPayload()).as("拒绝时不带载荷").isNull();
    }

    @Test
    @DisplayName("结果位互锁：persistable = true ⇒ 入站内容必非空")
    void persistableTrueImpliesNonEmptyContent() {
        assertThatThrownBy(() -> new DecisionInboundResult(true, null, DecisionProcessingRecord.none()))
                .as("可落盘却无内容 ⇒ 非法态，构造期即拒")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionInboundResult(true, "", DecisionProcessingRecord.none()))
                .as("空串同判「无内容」")
                .isInstanceOf(IllegalArgumentException.class);

        final DecisionInboundResult persistable = new DecisionInboundResult(true, "{\"ok\":true}",
                new DecisionProcessingRecord(false, true));
        assertThat(persistable.isPersistable()).isTrue();
        assertThat(persistable.getRawOutput()).isEqualTo("{\"ok\":true}");
        assertThat(persistable.getRecord().isTruncated()).isTrue();

        final DecisionInboundResult restricted = new DecisionInboundResult(false, null,
                DecisionProcessingRecord.none());
        assertThat(restricted.isPersistable()).as("政策性不可落盘 = 合规拒绝、不计错误").isFalse();
        assertThat(restricted.getRawOutput()).isNull();

        // 双向服务：applyInbound 缺省实现 = 透传（有内容则可落盘且原样返回，无内容则不可落盘）
        final DecisionPolicy passthrough = new DecisionPolicy() {
            @Override
            public String key() {
                return "passthrough";
            }

            @Override
            public DecisionOutboundResult applyOutbound(final DecisionPayload payload) {
                return new DecisionOutboundResult(false, null, DecisionProcessingRecord.none());
            }
        };
        assertThat(passthrough.applyInbound("raw").isPersistable()).isTrue();
        assertThat(passthrough.applyInbound("raw").getRawOutput()).isEqualTo("raw");
        assertThat(passthrough.applyInbound("").isPersistable()).as("无内容不得报「可落盘」").isFalse();
        assertThat(passthrough.applyInbound(null).isPersistable()).isFalse();
    }

    @Test
    @DisplayName("加工记录只承加工事实：「说不」住结果位、不住记录")
    void processingRecordCarriesFactsOnlyNotVerdict() throws Exception {
        final List<String> recordFields = Arrays.stream(DecisionProcessingRecord.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toList());

        assertThat(recordFields)
                .as("加工记录字段集恰 {redacted, truncated}")
                .containsExactlyInAnyOrder("redacted", "truncated");
        assertThat(recordFields)
                .as("「说不」不得住加工记录（避免字面恒真的字段兼职）")
                .doesNotContain("permitted", "persistable");

        // 双向服务：方向住方法名与两侧结果类型（同一接口承载，不另起一套方向词类型）
        final List<String> methodNames = Arrays.stream(DecisionPolicy.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toList());
        assertThat(methodNames)
                .as("策略是「单一接口、双向两方法」")
                .contains("applyOutbound", "applyInbound");
        assertThat(DecisionPolicy.class.getMethod("applyOutbound", DecisionPayload.class).getReturnType())
                .as("出域方向入参 = DecisionPayload、出参 = DecisionOutboundResult")
                .isEqualTo(DecisionOutboundResult.class);
        assertThat(DecisionPolicy.class.getMethod("applyInbound", String.class).getReturnType())
                .as("入站方向入参 = 裸 String（不套壳）、出参 = DecisionInboundResult")
                .isEqualTo(DecisionInboundResult.class);
        assertThat(DecisionPolicy.class.getMethod("key").getReturnType())
                .as("策略与决策目标同型：bean + 唯一 key()（节点按 key 引用、部署期校验）")
                .isEqualTo(String.class);
    }
}
