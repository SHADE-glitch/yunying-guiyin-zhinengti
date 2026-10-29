package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.insight.agent.attribution.AttributionPorts.DimensionProposer;
import com.insight.agent.attribution.AttributionPorts.ProbeResult;
import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.SplitProvider;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Item;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttributionEngineTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Item item(String name, String current, String baseline) {
        return new Item(name, d(current), d(baseline));
    }

    private static AttributionConfig cfg(int maxDepth, int maxProbes) {
        return new AttributionConfig(maxDepth, maxProbes, d("0.6"), d("0.03"), d("100"),
                d("0.05"), 3, 2, 10);
    }

    /** 按脚本依次返回维度；null 表示模型这次给不出合法维度。 */
    static class ScriptedProposer implements DimensionProposer {
        // ArrayDeque 不允许 null 元素，而脚本里要用 null 表示"模型这次给不出维度"
        private final Deque<Dimension> script = new java.util.LinkedList<>();
        private Dimension fallback;

        ScriptedProposer(Dimension... dims) {
            for (Dimension dim : dims) {
                script.add(dim);
            }
        }

        ScriptedProposer repeating(Dimension dim) {
            this.fallback = dim;
            return this;
        }

        @Override
        public Dimension propose(ProposalContext context) {
            return script.isEmpty() ? fallback : script.poll();
        }
    }

    static class FakeProvider implements SplitProvider {
        final Totals root;
        final Map<Dimension, List<Item>> data = new EnumMap<>(Dimension.class);
        final Map<Dimension, Integer> failFirst = new EnumMap<>(Dimension.class);
        final Map<Dimension, Integer> calls = new EnumMap<>(Dimension.class);

        FakeProvider(Totals root) {
            this.root = root;
        }

        FakeProvider with(Dimension dim, List<Item> items) {
            data.put(dim, items);
            return this;
        }

        FakeProvider failingFirst(Dimension dim, int times) {
            failFirst.put(dim, times);
            return this;
        }

        @Override
        public Totals rootTotals() {
            return root;
        }

        @Override
        public ProbeResult probe(Dimension dim, Scope scope) {
            int n = calls.merge(dim, 1, Integer::sum);
            Integer fail = failFirst.get(dim);
            if (fail != null && n <= fail) {
                return ProbeResult.failed("statement rejected");
            }
            List<Item> items = data.get(dim);
            if (items == null) {
                return ProbeResult.failed("no data");
            }
            if (items.size() <= 1) {
                return ProbeResult.noInformation();
            }
            return ProbeResult.ok(items);
        }
    }

    /** 根 1000→850，区域里苏州跌 120、杭州跌 30、上海持平。 */
    private static FakeProvider happyPath() {
        FakeProvider provider = new FakeProvider(new Totals(d("850"), d("1000")));
        provider.with(Dimension.REGION, List.of(
                item("苏州", "280", "400"),
                item("杭州", "270", "300"),
                item("上海", "300", "300")));
        provider.with(Dimension.CATEGORY, List.of(
                item("火锅", "90", "200"),
                item("烧烤", "190", "200")));
        return provider;
    }

    @Test
    @DisplayName("两层下钻：区域定位苏州，品类定位火锅")
    void drillsTwoLevels() {
        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION, Dimension.CATEGORY), happyPath());

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.path()).hasSize(2);
        assertThat(result.path().get(0).value()).isEqualTo("苏州");
        assertThat(result.path().get(0).contribution()).isEqualByComparingTo("-0.8");
        assertThat(result.path().get(1).value()).isEqualTo("火锅");
        assertThat(result.probes()).isEqualTo(2);
    }

    @Test
    @DisplayName("T16 达到深度上限即停，不再下钻")
    void stopsAtMaxDepth() {
        AttributionResult result = new AttributionEngine(cfg(1, 8))
                .run(new ScriptedProposer(Dimension.REGION, Dimension.CATEGORY), happyPath());

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.path()).hasSize(1);
    }

    @Test
    @DisplayName("门店已是叶子维度：未到 maxDepth 也在主因处收敛，不再问提议器")
    void stopsAtLeafDimension() {
        FakeProvider provider = happyPath();
        provider.with(Dimension.SHOP, List.of(
                item("商户015", "280", "400"),
                item("商户016", "270", "300")));

        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.SHOP), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.path()).hasSize(1);
        assertThat(result.path().get(0).dimension()).isEqualTo(Dimension.SHOP);
        assertThat(result.path().get(0).value()).isEqualTo("商户015");
        assertThat(result.invalidProposals()).isZero();
    }

    @Test
    @DisplayName("变化不显著时直接结束，一次探针都不跑")
    void skipsWhenChangeIsSmall() {
        FakeProvider provider = new FakeProvider(new Totals(d("1010"), d("1000")));
        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.NO_SIGNIFICANT_CHANGE);
        assertThat(result.probes()).isZero();
        assertThat(provider.calls).isEmpty();
    }

    @Test
    @DisplayName("没有单项达到阈值时判为多因素")
    void reportsMultiFactor() {
        FakeProvider provider = new FakeProvider(new Totals(d("900"), d("1000")));
        provider.with(Dimension.REGION, List.of(
                item("A", "270", "300"),
                item("B", "270", "300"),
                item("C", "360", "400")));

        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.MULTI_FACTOR);
    }

    @Test
    @DisplayName("分组只覆盖部分总量时拒绝归因")
    void rejectsIncompletePartition() {
        FakeProvider provider = new FakeProvider(new Totals(d("900"), d("1000")));
        provider.with(Dimension.REGION, List.of(
                item("A", "340", "400"),
                item("B", "380", "400")));

        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DATA_INCOMPLETE);
    }

    @Test
    @DisplayName("选中子项自身变化不足 3% 时不再下钻")
    void stopsWhenChildIsFlat() {
        // 根跌 3.4% 过门槛；苏州贡献 61.8% 是最大同向项，但它自身只跌 2.1%
        FakeProvider provider = new FakeProvider(new Totals(d("966"), d("1000")));
        provider.with(Dimension.REGION, List.of(
                item("苏州", "959", "980"),
                item("其他城市", "7", "20")));

        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION, Dimension.CATEGORY), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.INSUFFICIENT_EVIDENCE);
        assertThat(result.path()).hasSize(1);
    }

    @Test
    @DisplayName("T11 重复提议同一维度计入无效提议")
    void duplicateProposalCountsAsInvalid() {
        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION).repeating(Dimension.REGION), happyPath());

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.INVALID_PROPOSAL_LIMIT);
        assertThat(result.path()).hasSize(1);
        assertThat(result.invalidProposals()).isEqualTo(3);
    }

    @Test
    @DisplayName("T23 连续三次给不出合法维度就熔断，且不消耗探针预算")
    void invalidProposalsBurnNoProbeBudget() {
        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer().repeating(null), happyPath());

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.INVALID_PROPOSAL_LIMIT);
        assertThat(result.probes()).isZero();
    }

    @Test
    @DisplayName("T24 一次成功提议会把连续无效计数清零")
    void successResetsInvalidStreak() {
        // 两次无效 → 一次成功 → 再三次无效才熔断
        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(null, null, Dimension.REGION, null, null, null, null, null), happyPath());

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.INVALID_PROPOSAL_LIMIT);
        assertThat(result.path()).hasSize(1);
        assertThat(result.invalidProposals()).isEqualTo(5);
    }

    @Test
    @DisplayName("T25 同一维度连续失败两次后标记耗尽，再提议算无效")
    void exhaustedDimensionCountsAsInvalid() {
        FakeProvider provider = happyPath().failingFirst(Dimension.REGION, 2);

        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION, Dimension.REGION, Dimension.REGION,
                        Dimension.REGION, Dimension.REGION), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.INVALID_PROPOSAL_LIMIT);
        assertThat(result.probes()).isEqualTo(2);
    }

    @Test
    @DisplayName("T13 探针失败不终止，换维度继续")
    void probeFailureDoesNotAbort() {
        FakeProvider provider = happyPath().failingFirst(Dimension.REGION, 1);

        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.REGION, Dimension.REGION, Dimension.CATEGORY), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.probes()).isEqualTo(3);
    }

    /** 像一个守规矩的模型：只看上下文，挑第一个还能用的维度，并记下每次看到什么。 */
    static class ContextAwareProposer implements DimensionProposer {
        final List<ProposalContext> seen = new ArrayList<>();

        @Override
        public Dimension propose(ProposalContext context) {
            seen.add(context);
            for (Dimension dimension : Dimension.values()) {
                if (!context.used().contains(dimension) && !context.exhausted().contains(dimension)) {
                    return dimension;
                }
            }
            return null;
        }
    }

    @Test
    @DisplayName("P0-2 探测失败的维度会被标记耗尽并传给提议器，不再反复提议到熔断")
    void exhaustedDimensionIsNotReproposed() {
        // 区域连续失败 2 次 → 标记耗尽；品类仍然可用且能与根口径对上
        FakeProvider provider = new FakeProvider(new Totals(d("850"), d("1000")))
                .failingFirst(Dimension.REGION, 2);
        provider.with(Dimension.CATEGORY, List.of(
                item("火锅", "90", "200"),
                item("烧烤", "190", "200"),
                item("其他", "570", "600")));
        ContextAwareProposer proposer = new ContextAwareProposer();

        AttributionResult result = new AttributionEngine(cfg(1, 8)).run(proposer, provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.leaf().value()).isEqualTo("火锅");
        // 关键：没有因为反复提议一个废维度而熔断
        assertThat(result.invalidProposals()).isZero();
        assertThat(provider.calls.get(Dimension.REGION)).isEqualTo(2);
        // 失败上限达到之后，提议器立刻看到 REGION 已耗尽，于是换成了品类
        assertThat(proposer.seen.get(2).exhausted()).containsExactly(Dimension.REGION);
        assertThat(proposer.seen.get(2).used()).isEmpty();
    }

    @Test
    @DisplayName("T15 探针预算用尽即停")
    void stopsWhenProbeBudgetExhausted() {
        FakeProvider provider = happyPath()
                .failingFirst(Dimension.REGION, 99)
                .failingFirst(Dimension.CATEGORY, 99)
                .failingFirst(Dimension.SHOP, 99);

        AttributionResult result = new AttributionEngine(cfg(2, 2))
                .run(new ScriptedProposer(Dimension.REGION, Dimension.CATEGORY, Dimension.SHOP), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.BUDGET_EXHAUSTED);
        assertThat(result.probes()).isEqualTo(2);
    }

    @Test
    @DisplayName("单值维度不消耗探针预算，也不算成功")
    void singleValueDimensionBurnsNoBudget() {
        FakeProvider provider = happyPath().with(Dimension.SHOP, List.of(item("only", "1", "1")));

        AttributionResult result = new AttributionEngine(cfg(2, 8))
                .run(new ScriptedProposer(Dimension.SHOP, Dimension.REGION, Dimension.CATEGORY), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.probes()).isEqualTo(2);
    }

    @Test
    @DisplayName("T21-T22 高基数折叠后由聚合桶当选时，结论只能停在集合层面")
    void aggregateCanBeDominant() {
        List<Item> shops = new ArrayList<>();
        shops.add(item("big", "100", "200"));
        for (int i = 1; i <= 100; i++) {
            shops.add(item("s" + i, "5", "10"));
        }
        FakeProvider provider = new FakeProvider(new Totals(d("600"), d("1200")))
                .with(Dimension.SHOP, shops);

        AttributionResult result = new AttributionEngine(cfg(1, 8))
                .run(new ScriptedProposer(Dimension.SHOP), provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.leaf().value()).isEqualTo("其他");
        assertThat(result.dominantIsAggregate()).isTrue();
    }
}
