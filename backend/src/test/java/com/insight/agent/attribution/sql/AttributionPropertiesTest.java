package com.insight.agent.attribution.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insight.agent.attribution.AttributionConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttributionPropertiesTest {

    @Test
    @DisplayName("默认值合法，且能转成状态机配置")
    void defaultsAreValid() {
        AttributionProperties props = new AttributionProperties();
        props.validate();

        AttributionConfig config = props.toConfig();
        assertThat(config.maxDepth()).isEqualTo(2);
        assertThat(config.maxProbes()).isEqualTo(8);
        assertThat(config.threshold()).isEqualByComparingTo("0.6");
        assertThat(config.topN()).isEqualTo(10);
    }

    @Test
    @DisplayName("非法阈值在启动校验时就失败，不留到运行时")
    void rejectsIllegalThreshold() {
        AttributionProperties props = new AttributionProperties();
        props.setThreshold(BigDecimal.ZERO);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    @DisplayName("层数有上限：每多一层都是一次全量分组查询，不能随便调")
    void rejectsTooDeepDrill() {
        AttributionProperties props = new AttributionProperties();
        props.setMaxDepth(9);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-depth");
    }

    @Test
    @DisplayName("容差必须小于 1：否则完备性校验永远通过，等于没有校验")
    void rejectsToleranceOfOne() {
        AttributionProperties props = new AttributionProperties();
        props.setTolerance(BigDecimal.ONE);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    @DisplayName("不支持的指标启动就拒绝")
    void rejectsUnsupportedMetric() {
        AttributionProperties props = new AttributionProperties();
        props.setMetric("refund_rate");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refund_rate");
    }
}
