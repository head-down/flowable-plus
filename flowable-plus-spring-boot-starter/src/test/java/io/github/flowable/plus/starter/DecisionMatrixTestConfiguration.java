package io.github.flowable.plus.starter;

import io.github.flowable.plus.extension.decision.DecisionOutboundResult;
import io.github.flowable.plus.extension.decision.DecisionPayload;
import io.github.flowable.plus.extension.decision.DecisionPolicy;
import io.github.flowable.plus.extension.decision.DecisionProcessingRecord;
import io.github.flowable.plus.extension.decision.DecisionTarget;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 三层关闭矩阵集成测试的注册面 fixture（{@code @TestConfiguration}，不参与组件扫描）。
 *
 * <p>提供恰一个决策目标（key = {@code testTarget}，出站地址指向本机不可达端口 —— 默认方言不会
 * 被该 fixture 触达）与恰一个出域策略（key = {@code testPolicy}，全放行、零加工）。</p>
 */
@TestConfiguration
public class DecisionMatrixTestConfiguration {

    @Bean
    DecisionTarget testDecisionTarget() {
        return new DecisionTarget() {
            @Override
            public String key() {
                return "testTarget";
            }

            @Override
            public String url() {
                return "http://127.0.0.1:1/unreachable";
            }
        };
    }

    @Bean
    DecisionPolicy testDecisionPolicy() {
        return new DecisionPolicy() {
            @Override
            public String key() {
                return "testPolicy";
            }

            @Override
            public DecisionOutboundResult applyOutbound(final DecisionPayload payload) {
                return new DecisionOutboundResult(true, payload, DecisionProcessingRecord.none());
            }
        };
    }
}
