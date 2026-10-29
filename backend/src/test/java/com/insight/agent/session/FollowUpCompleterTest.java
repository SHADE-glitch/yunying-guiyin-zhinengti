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
 * 范围按维度合并：问题里点名的维度覆盖上一轮，未点名的维度继承。
 */
class FollowUpCompleterTest {

    private final FollowUpCompleter completer = new FollowUpCompleter();

    private static SessionService.State state(int turns, String metric, Map<String, String> scope) {
        return new SessionService.State(metric, scope, UUID.randomUUID(), turns);
    }

    @Test
    @DisplayName("第一轮：不补全，原样")
    void firstTurnIsAsIs() {
        Completion c = completer.complete("为什么华东跌了", state(0, "gmv", Map.of()), Map.of(), false);
        assertThat(c.kind()).isEqualTo(Kind.AS_IS);
        assertThat(c.resolvedQuestion()).isEqualTo("为什么华东跌了");
    }

    @Test
    @DisplayName("点名了新范围与新指标：当新问题，不继承")
    void explicitScopeAndMetricIsAsIs() {
        Completion c = completer.complete("华南订单量怎么样",
                state(2, "gmv", Map.of("REGION", "华东")), Map.of("REGION", "华南"), true);
        assertThat(c.kind()).isEqualTo(Kind.AS_IS);
    }

    @Test
    @DisplayName("长问题：当新问题，猜错代价高")
    void longQuestionIsAsIs() {
        Completion c = completer.complete("我想知道最近这段时间华南地区各个品类到底发生了什么变化",
                state(2, "gmv", Map.of("REGION", "华东")), Map.of(), false);
        assertThat(c.kind()).isEqualTo(Kind.AS_IS);
    }

    @Test
    @DisplayName("「那华南呢」：继承指标，范围换成华南")
    void newScopeInheritsMetric() {
        Completion c = completer.complete("那华南呢",
                state(2, "order_count", Map.of("REGION", "华东")), Map.of("REGION", "华南"), false);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("华南").contains("订单量");
        assertThat(c.note()).contains("继承上一轮指标");
    }

    @Test
    @DisplayName("「那火锅呢」：只换品类，继承上一轮区域与指标")
    void categorySwitchKeepsRegion() {
        Completion c = completer.complete("那火锅呢",
                state(2, "gmv", Map.of("REGION", "华东")), Map.of("CATEGORY", "火锅"), false);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("华东").contains("火锅").contains("GMV");
        assertThat(c.resolvedQuestion()).doesNotContain("咖啡");
    }

    @Test
    @DisplayName("「那订单量呢」：只换指标，继承上一轮范围且不写旧指标")
    void metricSwitchKeepsScope() {
        Completion c = completer.complete("那订单量呢",
                state(2, "gmv", Map.of("REGION", "华东")), Map.of(), true);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("华东").contains("订单量");
        assertThat(c.resolvedQuestion()).doesNotContain("GMV");
        assertThat(c.note()).contains("继承上一轮范围");
    }

    @Test
    @DisplayName("「青岛呢」：只换城市，继承上一轮品类与指标")
    void citySwitchKeepsCategory() {
        Completion c = completer.complete("青岛呢",
                state(2, "gmv", Map.of("REGION", "南京", "CATEGORY", "火锅")),
                Map.of("REGION", "青岛"), false);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("青岛").contains("火锅").contains("GMV");
        assertThat(c.resolvedQuestion()).doesNotContain("南京");
    }

    @Test
    @DisplayName("同名维度覆盖：新品类替换旧品类，区域保留")
    void sameDimensionOverrides() {
        Completion c = completer.complete("那咖啡呢",
                state(2, "gmv", Map.of("REGION", "华东", "CATEGORY", "火锅")),
                Map.of("CATEGORY", "咖啡"), false);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("华东").contains("咖啡");
        assertThat(c.resolvedQuestion()).doesNotContain("火锅");
    }

    @Test
    @DisplayName("「为什么跌了」：继承上一轮范围与指标")
    void continuationInheritsScopeAndMetric() {
        Completion c = completer.complete("为什么跌了",
                state(2, "gmv", Map.of("REGION", "华东")), Map.of(), false);
        assertThat(c.kind()).isEqualTo(Kind.INHERIT);
        assertThat(c.resolvedQuestion()).contains("华东").contains("GMV");
        assertThat(c.note()).contains("范围与指标");
    }

    @Test
    @DisplayName("短而空的问题：重问，不猜")
    void vagueQuestionIsClarified() {
        Completion c = completer.complete("嗯",
                state(2, "gmv", Map.of("REGION", "华东")), Map.of(), false);
        assertThat(c.kind()).isEqualTo(Kind.CLARIFY);
        assertThat(c.note()).contains("补充指标或范围");
    }

    @Test
    @DisplayName("会话里没有范围：延续词也不能猜")
    void noPreviousScopeMeansClarify() {
        Completion c = completer.complete("为什么跌了", state(2, "gmv", Map.of()), Map.of(), false);
        assertThat(c.kind()).isEqualTo(Kind.CLARIFY);
    }
}
