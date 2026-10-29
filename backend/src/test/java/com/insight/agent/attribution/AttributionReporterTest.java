package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Scored;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 证据约束的机械校验：模型写什么不重要，重要的是没有证据的那条进不了 facts。
 */
class AttributionReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Totals ROOT = new Totals(d("71192.45"), d("80253.25"));
    private static final List<AttributionReporter.Level> LEVELS = List.of(
            new AttributionReporter.Level(1, Dimension.CATEGORY, Scope.root().with(Dimension.REGION, "华东"),
                    List.of(new Scored("火锅", d("9860.72"), d("18706.18"), d("-8845.46"), d("-0.9762")),
                            new Scored("电影", d("8883.09"), d("11088.82"), d("-2205.73"), d("-0.2434"))),
                    d("-9060.80"), true));
    private static final AttributionResult RESULT = new AttributionResult(
            AttributionOutcome.MULTI_FACTOR, List.of(), 2, 0, false,
            "已定位到 火锅，但它内部的变化分散在多个子项，没有单项达到阈值");

    @Test
    @DisplayName("有证据的事实留下，编造的降级到 uncertainty")
    void ungroundedFactIsDemoted() {
        LlmClient llm = findings("""
                {"facts":["火锅贡献 -0.9762","某品牌全线崩盘 300%"],
                 "hypothesis":["可能是周边竞品活动分流"],
                 "uncertainty":["缺少竞品数据"]}
                """);

        Findings result = new AttributionReporter(llm, MAPPER, "sys")
                .report("为什么华东跌了", Scope.root().with(Dimension.REGION, "华东"), ROOT, LEVELS, RESULT, false);

        assertThat(result.facts()).containsExactly("火锅贡献 -0.9762");
        assertThat(result.uncertainty())
                .anySatisfy(text -> assertThat(text).contains("无证据支撑").contains("300%"))
                .anySatisfy(text -> assertThat(text).contains("缺少竞品数据"));
        assertThat(result.hypothesis()).containsExactly("可能是周边竞品活动分流");
    }

    @Test
    @DisplayName("说反的进 uncertainty，因果的进 hypothesis，都不是 facts")
    void contradictedAndCausalFactsAreSplit() {
        LlmClient llm = findings("""
                {"facts":["火锅上涨 8845.46","火锅下跌 8845.46，因为门店数量减少","火锅贡献 -0.9762"],
                 "hypothesis":[],
                 "uncertainty":[]}
                """);

        Findings result = new AttributionReporter(llm, MAPPER, "sys")
                .report("为什么华东跌了", Scope.root().with(Dimension.REGION, "华东"), ROOT, LEVELS, RESULT, false);

        assertThat(result.facts()).containsExactly("火锅贡献 -0.9762");
        assertThat(result.uncertainty())
                .anySatisfy(text -> assertThat(text).contains("方向与证据相反").contains("火锅上涨 8845.46"));
        assertThat(result.hypothesis())
                .anySatisfy(text -> assertThat(text).contains("因果表述").contains("门店数量减少"));
    }

    @Test
    @DisplayName("模型挂掉：只复述计算结果，不生成原因推测")
    void modelFailureFallsBackToNumbers() {
        LlmClient llm = (messages, tools) -> {
            throw new IllegalStateException("model down");
        };

        Findings result = new AttributionReporter(llm, MAPPER, "sys")
                .report("为什么华东跌了", Scope.root(), ROOT, LEVELS, RESULT, true);

        assertThat(result.hypothesis()).isEmpty();
        assertThat(result.uncertainty()).anySatisfy(text -> assertThat(text).contains("模型不可用"));
        assertThat(result.facts()).anySatisfy(text -> assertThat(text).contains("火锅"));
    }

    @Test
    @DisplayName("数据不完整：一次模型都不调，只说明已有数据和差额")
    void dataIncompleteNeverCallsModel() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient llm = (messages, tools) -> {
            calls.incrementAndGet();
            return new LlmResponse("", List.of(), 0, 0);
        };
        AttributionResult incomplete = new AttributionResult(
                AttributionOutcome.DATA_INCOMPLETE, List.of(), 1, 0, false,
                "分组汇总与父节点总量偏差过大，无法给出可靠归因");

        Findings result = new AttributionReporter(llm, MAPPER, "sys")
                .report("为什么华东跌了", Scope.root().with(Dimension.REGION, "华东"), ROOT, LEVELS, incomplete, false);

        assertThat(calls).hasValue(0);
        assertThat(result.hypothesis()).isEmpty();
        assertThat(result.facts()).anySatisfy(text -> assertThat(text).contains("差额"));
        assertThat(result.uncertainty())
                .anySatisfy(text -> assertThat(text).contains("无法可靠确定主要变化来源"));
    }

    @Test
    @DisplayName("模型没按格式调工具：同样降级，不解析自由文本")
    void missingToolCallFallsBack() {
        LlmClient llm = (messages, tools) -> new LlmResponse("火锅跌了", List.of(), 0, 0);

        Findings result = new AttributionReporter(llm, MAPPER, "sys")
                .report("为什么华东跌了", Scope.root(), ROOT, LEVELS, RESULT, false);

        assertThat(result.uncertainty()).anySatisfy(text -> assertThat(text).contains("未按格式返回"));
    }

    private static LlmClient findings(String args) {
        return (messages, tools) -> new LlmResponse("",
                List.of(new ToolCallReq("c1", tools.get(0).name(), args)), 0, 0);
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }
}
