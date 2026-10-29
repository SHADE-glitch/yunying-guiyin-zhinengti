package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Scored;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事实校验的四类反例，以及它明确做不到的事。
 *
 * <p>这组测试的重点不是"模型会不会编"，而是"编了之后拦不拦得住"。凡是拦不住的，这里都写成
 * 用例并标出来，免得以后把"数字都命中"当成"这句话对"。
 */
class FactLedgerTest {

    private static final Totals ROOT = new Totals(d("71192.45"), d("80253.25"));
    private static final List<AttributionReporter.Level> LEVELS = List.of(
            new AttributionReporter.Level(1, Dimension.CATEGORY, Scope.root().with(Dimension.REGION, "华东"),
                    List.of(new Scored("火锅", d("9860.72"), d("18706.18"), d("-8845.46"), d("-0.9762")),
                            new Scored("电影", d("8883.09"), d("11088.82"), d("-2205.73"), d("-0.2434"))),
                    d("-9060.80"), true));

    private static final FactLedger LEDGER = new FactLedger(ROOT, LEVELS);

    private static FactLedger.Verdict verdict(String fact) {
        return LEDGER.check(fact).verdict();
    }

    @Test
    @DisplayName("反例一：方向说反——数字都对，但主体在跌却写上涨")
    void reversedDirectionIsContradicted() {
        assertThat(verdict("火锅上涨 8845.46")).isEqualTo(FactLedger.Verdict.CONTRADICTED);
        assertThat(LEDGER.check("火锅上涨 8845.46").reason()).contains("方向与证据相反");
    }

    @Test
    @DisplayName("反例二：本期和基期写反")
    void swappedCurrentAndBaselineIsContradicted() {
        FactLedger.Check check = LEDGER.check("火锅本期 18706.18，基期 9860.72");

        assertThat(check.verdict()).isEqualTo(FactLedger.Verdict.CONTRADICTED);
        assertThat(check.reason()).contains("本期与基期数值写反");
    }

    @Test
    @DisplayName("反例三：把子项数值说成整体")
    void childValueAttributedToParentIsContradicted() {
        FactLedger.Check check = LEDGER.check("整体本期 9860.72，基期 18706.18");

        assertThat(check.verdict()).isEqualTo(FactLedger.Verdict.CONTRADICTED);
        assertThat(check.reason()).contains("子项数值当成了整体");
    }

    @Test
    @DisplayName("反例四：facts 里写因果解释——不是事实，移入推测")
    void causalClaimIsNotAFact() {
        assertThat(verdict("火锅下跌 8845.46，因为门店数量减少")).isEqualTo(FactLedger.Verdict.CAUSAL);
        assertThat(verdict("火锅下跌导致整体下滑")).isEqualTo(FactLedger.Verdict.CAUSAL);
    }

    @Test
    @DisplayName("没下因果断言的句子不能被当成编造因果")
    void hedgedSentenceIsNotCausal() {
        // "原因尚不能确定"里虽然有"原因"，但它恰恰是在说不知道，不是因果断言
        assertThat(verdict("原因尚不能确定")).isEqualTo(FactLedger.Verdict.UNSUPPORTED);
        assertThat(verdict("缺少门店级数据，无法判断原因")).isEqualTo(FactLedger.Verdict.UNSUPPORTED);
    }

    @Test
    @DisplayName("数字和方向都对的事实留下")
    void supportedFactPasses() {
        assertThat(verdict("火锅变化 -8845.46，贡献 -0.9762")).isEqualTo(FactLedger.Verdict.SUPPORTED);
        assertThat(verdict("火锅本期下滑 47.3%")).isEqualTo(FactLedger.Verdict.SUPPORTED);
    }

    @Test
    @DisplayName("方向按最近的主体判：一句话里两个主体各说各的，不互相污染")
    void directionUsesNearestSubject() {
        assertThat(verdict("电影下跌 2205.73，火锅下跌 8845.46")).isEqualTo(FactLedger.Verdict.SUPPORTED);
        assertThat(verdict("电影上涨 2205.73，火锅下跌 8845.46")).isEqualTo(FactLedger.Verdict.CONTRADICTED);
    }

    @Test
    @DisplayName("已知缺口：没有本期/基期标签时，子项数值冒充整体拦不住")
    void childValueWithoutRoleLabelsIsNotCaught() {
        // 8845.46 是火锅的变化，不是整体的；这句话语义是错的，但没有任何结构能定位到错在哪。
        // 记在这里是为了不把"数字都命中"误当成"校验通过"。
        assertThat(verdict("整体变化 8845.46")).isEqualTo(FactLedger.Verdict.SUPPORTED);
    }

    @Test
    @DisplayName("已知缺口：一句话里混了多个主体时不做数值位置判定")
    void multipleSubjectsSkipRoleCheck() {
        // 判不准就不判：误降级会让人以为校验过了
        assertThat(verdict("本期火锅 9860.72，电影 8883.09")).isEqualTo(FactLedger.Verdict.SUPPORTED);
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }
}
