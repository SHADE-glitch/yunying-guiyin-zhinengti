package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标路由与守卫：受支持指标（gmv / order_count）互相放行、按问题选；
 * 不支持的指标（退款率等）拒绝。方向刻意不对称：宁可漏判，不可误拒。
 */
class MetricRoutingTest {

    @Test
    @DisplayName("问题点名哪个可加指标，就路由到哪个；没点名或都点名用默认")
    void routesByQuestionWords() {
        assertThat(MetricRouter.route("为什么华东订单量跌了", MetricRouter.GMV))
                .isEqualTo(MetricRouter.ORDER_COUNT);
        assertThat(MetricRouter.route("为什么华东GMV跌了", MetricRouter.ORDER_COUNT))
                .isEqualTo(MetricRouter.GMV);
        assertThat(MetricRouter.route("华东为什么跌了", MetricRouter.ORDER_COUNT))
                .isEqualTo(MetricRouter.ORDER_COUNT);
        assertThat(MetricRouter.route("GMV 和订单量都在跌", MetricRouter.GMV))
                .isEqualTo(MetricRouter.GMV);
    }

    @Test
    @DisplayName("受支持指标之间互相放行")
    void guardAllowsBothSupportedMetrics() {
        assertThatCode(() -> AttributionMetricGuard.check("为什么订单量跌了", MetricRouter.GMV))
                .doesNotThrowAnyException();
        assertThatCode(() -> AttributionMetricGuard.check("为什么GMV跌了", MetricRouter.ORDER_COUNT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不支持的指标：拒绝，且不因同时出现受支持词而放行退款率本身")
    void guardRejectsUnsupportedMetrics() {
        assertThatThrownBy(() -> AttributionMetricGuard.check("为什么退款率跌了", MetricRouter.GMV))
                .isInstanceOf(UnsupportedMetricException.class);
        assertThatThrownBy(() -> AttributionMetricGuard.check("为什么客单价跌了", MetricRouter.ORDER_COUNT))
                .isInstanceOf(UnsupportedMetricException.class);
    }

    @Test
    @DisplayName("既有行为不回退：同时出现 GMV 与其它指标时，按 GMV 放行")
    void mixedQuestionStillPassesOnSupportedWord() {
        assertThatCode(() -> AttributionMetricGuard.check("GMV 跌了，退款率有影响吗", MetricRouter.GMV))
                .doesNotThrowAnyException();
    }
}
