package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 指标守卫只做一件事：问的不是受支持指标（gmv / order_count）就拒绝，别拿另一个结论回答。
 *
 * <p>两个方向都要测：该拒的拒（问退款率不能返回 GMV 归因），该放的放
 * （正常的受支持指标问题不能被误拦，否则整个功能等于下线）。
 */
class AttributionMetricGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"为什么华东 GMV 跌了", "华东销售额为什么下降", "为什么成交额跌了", "交易额怎么掉了"})
    @DisplayName("问的是 GMV 口径：放行")
    void gmvQuestionPasses(String question) {
        assertThatCode(() -> AttributionMetricGuard.check(question)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"为什么退款率跌了", "华东客单价为什么涨了", "为什么核销率下降"})
    @DisplayName("问的是不支持的指标：拒绝，并把指标名带出去")
    void otherMetricIsRejected(String question) {
        assertThatThrownBy(() -> AttributionMetricGuard.check(question))
                .isInstanceOf(UnsupportedMetricException.class)
                .hasMessageContaining("只支持 GMV");
    }

    @Test
    @DisplayName("同时提到 GMV 和别的指标：按 GMV 处理，不拦")
    void gmvWinsOverOtherMetric() {
        // "GMV 跌了，退款率是不是原因" 这种问法主语仍是 GMV，归因口径对得上
        assertThatCode(() -> AttributionMetricGuard.check("GMV 跌了，退款率有影响吗"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空问题：交给后面的解析器处理，守卫不越权")
    void blankQuestionIsNotGuardsBusiness() {
        assertThatCode(() -> AttributionMetricGuard.check(null)).doesNotThrowAnyException();
        assertThatCode(() -> AttributionMetricGuard.check("  ")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("大小写不敏感：GMV / gmv / Gmv 都是同一个口径")
    void gmvMatchIsCaseInsensitive() {
        assertThatCode(() -> AttributionMetricGuard.check("为什么 gmv 跌了")).doesNotThrowAnyException();
    }
}
