package com.insight.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.insight.agent.session.FollowUpCompleter.Completion;
import com.insight.agent.session.FollowUpCompleter.Kind;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 补全规则：只在高置信时继承，判不准就重问。
 * 每一条都是一个"补错就会答成另一个问题"的边界。
 */
class FollowUpCompleterTest {

    private final FollowUpCompleter completer = new FollowUpCompleter();

    private static SessionService.State state(int turns, String metric, Map<String, String> scope) {
        return new SessionService.State(metric, scope, UUID.randomUUID(), turns);
    }

    @Test
    @DisplayName("第一轮：不补全，原样")
    void firstTurnIsAsIs() {
        Completion c = completer.complete("为什么华东跌了", state(0, "gmv", Map.of()), false, false);
        assertThat(c.kind()).isEqualTo(Kind.AS_IS);
        assertThat(c.resolvedQuestion()).isEqualTo("为什么华东跌了");
    }

    @Test
    @DisplayName("点名了指标：当新问题，不继承范围")
    void explicitMetricIsAsIs() {
        Completion c = completer.complete("华南订单量怎么样", state(2, "gmv", Map.of("REGION", "华东")), true, true);
        assertThat(c.kind()).isEqualTo(Kind.AS_IS);
    }

    @Test
    @DisplayName("长问题：当新问题，猜错代价高")
    void longQuestionIsAsIs() {
        Completion c = completer.complete("我想知道最近这段时间华南地区各个品类到底发生了什么变化",
                state(2, "gmv", Map.of("REGION", "华东")), false, false);
        assertThat(c.kind()).isEqualTo(Kind.AS_IS);
    }

    @Test
    @DisplayName("「那华南呢」：继承指标，范围换成华南")
    void newScopeInheritsMetric() {
        Completion c = completer.complete("那华南呢", state(2, "order_count", Map.of("REGION", "华东")), true, false);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("华南").contains("订单量");
        assertThat(c.note()).contains("继承上一轮指标");
    }

    @Test
    @DisplayName("「为什么跌了」：继承上一轮范围与指标")
    void continuationInheritsScopeAndMetric() {
        Completion c = completer.complete("为什么跌了", state(2, "gmv", Map.of("REGION", "华东")), false, false);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("华东").contains("GMV");
        assertThat(c.note()).contains("范围与指标");
    }

    @Test
    @DisplayName("短而空的问题：重问，不猜")
    void vagueQuestionIsClarified() {
        Completion c = completer.complete("嗯", state(2, "gmv", Map.of("REGION", "华东")), false, false);
        assertThat(c.kind()).isEqualTo(Kind.CLARIFY);
        assertThat(c.note()).contains("补充指标或范围");
    }

    @Test
    @DisplayName("会话里没有范围：延续词也不能猜")
    void noPreviousScopeMeansClarify() {
        Completion c = completer.complete("为什么跌了", state(2, "gmv", Map.of()), false, false);
        assertThat(c.kind()).isEqualTo(Kind.CLARIFY);
    }
}
