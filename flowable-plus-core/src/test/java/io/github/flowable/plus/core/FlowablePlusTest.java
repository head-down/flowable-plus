package io.github.flowable.plus.core;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FlowablePlus 入口类测试：验证构造注入。
 */
public class FlowablePlusTest {

    @Test
    public void testConstructorInjectsProcessEngine() {
        ProcessEngine mockEngine = org.mockito.Mockito.mock(ProcessEngine.class);
        FlowablePlus flowablePlus = new FlowablePlus(mockEngine);

        assertThat(flowablePlus.getProcessEngine()).isSameAs(mockEngine);
    }

    @Test
    public void testConstructorRejectsNull() {
        assertThatThrownBy(() -> new FlowablePlus(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ProcessEngine 不可为 null");
    }
}
