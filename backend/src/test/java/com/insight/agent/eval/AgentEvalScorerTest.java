package com.insight.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.insight.agent.eval.AgentEvalScorer.Kind;
import com.insight.agent.eval.AgentEvalScorer.Report;
import com.insight.agent.eval.AgentEvalScorer.Scored;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 打分逻辑的单测。
 *
 * <p>评测服务本身要真调模型，进不了 CI；能进 CI 的只有这里——所以判定的边界必须钉死，
 * 否则"跑出 100%"可能只是打分太松。
 */
class AgentEvalScorerTest {

    private static Scored attribution(String expected, String actual, List<String> expectedPath,
                                      List<String> actualPath, String expectedLeaf) {
        return new Scored("q", Kind.ATTRIBUTION, expected, actual,
                AgentEvalScorer.outcomeMatches(expected, actual),
                AgentEvalScorer.pathMatches(expectedPath, actualPath),
                AgentEvalScorer.leafMatches(expectedLeaf, actualPath), true, 3, 5, 3000, false);
    }

    @Test
    @DisplayName("结局不一致即失败，哪怕路径蒙对了")
    void outcomeMismatchFails() {
        Scored scored = attribution("MULTI_FACTOR", "NO_SIGNIFICANT_CHANGE",
                List.of("CATEGORY=火锅"), List.of("CATEGORY=火锅"), "火锅");

        assertThat(scored.passed()).isFalse();
        assertThat(scored.pathMatch()).isTrue();
    }

    @Test
    @DisplayName("路径按前缀比：多下钻一层仍算对，走错分支算错")
    void pathIsPrefixMatch() {
        assertThat(AgentEvalScorer.pathMatches(List.of("CATEGORY=火锅"),
                List.of("CATEGORY=火锅", "SHOP=商户016"))).isTrue();
        assertThat(AgentEvalScorer.pathMatches(List.of("CATEGORY=火锅"),
                List.of("REGION=华南", "CATEGORY=火锅"))).isFalse();
        assertThat(AgentEvalScorer.pathMatches(List.of("CATEGORY=火锅"), List.of())).isFalse();
    }

    @Test
    @DisplayName("没写期望路径时只看结局，不看路径")
    void emptyExpectedPathIsNotChecked() {
        assertThat(AgentEvalScorer.pathMatches(null, List.of())).isTrue();
        assertThat(AgentEvalScorer.pathMatches(List.of(), List.of("CATEGORY=火锅"))).isTrue();
    }

    @Test
    @DisplayName("期望叶子落在路径最后一节")
    void leafIsLastNode() {
        assertThat(AgentEvalScorer.leafMatches("火锅", List.of("CATEGORY=火锅"))).isTrue();
        assertThat(AgentEvalScorer.leafMatches("咖啡", List.of("CATEGORY=火锅"))).isFalse();
        assertThat(AgentEvalScorer.leafMatches("火锅", List.of())).isFalse();
        assertThat(AgentEvalScorer.leafMatches(null, List.of())).isTrue();
    }

    @Test
    @DisplayName("汇总：分开统计归因与查数，失败项原样带出")
    void summarizesBothKinds() {
        List<Scored> scored = List.of(
                attribution("MULTI_FACTOR", "MULTI_FACTOR", List.of("CATEGORY=火锅"),
                        List.of("CATEGORY=火锅"), "火锅"),
                attribution("MULTI_FACTOR", "NO_SIGNIFICANT_CHANGE", null, List.of(), null),
                new Scored("退款率怎么算", Kind.LOOKUP, "SUCCEEDED", "SUCCEEDED",
                        true, true, true, true, 3, 0, 1200, false),
                new Scored("查一下", Kind.LOOKUP, "SUCCEEDED", "FAILED(MAX_STEPS)",
                        false, true, true, true, 6, 0, 9000, false));

        Report report = AgentEvalScorer.summarize(scored);

        assertThat(report.attribution().cases()).isEqualTo(2);
        assertThat(report.attribution().passed()).isEqualTo(1);
        assertThat(report.attribution().outcomeAccuracy()).isEqualTo(0.5);
        assertThat(report.lookup().cases()).isEqualTo(2);
        assertThat(report.lookup().passRate()).isEqualTo(0.5);
        assertThat(report.failures()).hasSize(2);
        // (3000 + 3000 + 1200 + 9000) / 4
        assertThat(report.efficiency().avgTokens()).isEqualTo(4050.0);
    }

    @Test
    @DisplayName("查数题答非所问也算失败：调了工具不等于答对了")
    void lookupNeedsTheRightAnswer() {
        assertThat(AgentEvalScorer.answerContains("退款订单数", "退款率 = 退款订单数 ÷ 已支付订单数")).isTrue();
        assertThat(AgentEvalScorer.answerContains("退款订单数", "这个要看具体口径，建议咨询运营")).isFalse();
        // 留空表示不校验答案内容
        assertThat(AgentEvalScorer.answerContains(null, "随便答")).isTrue();
        assertThat(AgentEvalScorer.answerContains("退款订单数", null)).isFalse();
    }

    @Test
    @DisplayName("空集不除零")
    void emptySetIsSafe() {
        Report report = AgentEvalScorer.summarize(List.of());

        assertThat(report.attribution().passRate()).isZero();
        assertThat(report.lookup().passRate()).isZero();
        assertThat(report.efficiency().avgSteps()).isZero();
        assertThat(report.failures()).isEmpty();
    }
}
