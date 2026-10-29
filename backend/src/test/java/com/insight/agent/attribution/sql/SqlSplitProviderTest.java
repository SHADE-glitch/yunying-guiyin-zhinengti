package com.insight.agent.attribution.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insight.agent.attribution.AttributionConfig;
import com.insight.agent.attribution.AttributionEngine;
import com.insight.agent.attribution.AttributionOutcome;
import com.insight.agent.attribution.AttributionPorts.ProbeResult;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.SplitProvider;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.AttributionResult;
import com.insight.agent.attribution.ContributionMath;
import com.insight.agent.attribution.ContributionMath.Item;
import com.insight.agent.attribution.Dimension;
import com.insight.agent.attribution.Evidence;
import com.insight.agent.attribution.QueryBudget;
import com.insight.agent.attribution.QueryBudgetExhausted;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 归因查询层的行为测试。
 *
 * <p>用假执行器制造真实数据库很难稳定复现的分支：超时、被拒、截断、预算耗尽。
 * 真实 SQL 的正确性由 {@link AttributionSqlIntegrationTest} 在真 PG 上验证，
 * 两者分工明确：这里测"拿到各种结果时怎么反应"，那里测"SQL 本身对不对"。
 */
class SqlSplitProviderTest {

    private static final Instant AS_OF = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("根口径：一条查询同时取本期和基期，证据带指纹")
    void rootTotalsUsesOneQueryAndCarriesEvidence() {
        FakeExecutor executor = new FakeExecutor()
                .respond(List.of(row("x", "850.00", "1000.00")));
        QueryBudget budget = new QueryBudget(16);
        SqlSplitProvider provider = provider(executor, props(), budget);

        Totals totals = provider.rootTotals();

        assertThat(totals.current()).isEqualByComparingTo("850.00");
        assertThat(totals.baseline()).isEqualByComparingTo("1000.00");
        assertThat(totals.delta()).isEqualByComparingTo("-150.00");
        assertThat(executor.sqls).hasSize(1);
        assertThat(executor.sqls.get(0)).contains("voucher_order");
        assertThat(budget.used()).isEqualTo(1);

        Evidence evidence = totals.evidence();
        assertThat(evidence).isNotNull();
        assertThat(evidence.queryHash()).hasSize(64).isEqualTo(Evidence.hash(executor.sqls.get(0)));
        assertThat(evidence.stepId()).isNull();
    }

    @Test
    @DisplayName("区域按大区归组：城市级 region_id 向上汇总，否则问华东时华东不出现")
    void probeGroupsRegionsByTopLevelRegion() {
        FakeExecutor executor = new FakeExecutor().respond(List.of(
                row("华东", "1200.00", "1400.00"),
                row("华南", "900.00", "900.00")));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.REGION, Scope.root());

        assertThat(result.status()).isEqualTo(ProbeResult.Status.OK);
        assertThat(result.items()).extracting(Item::value).containsExactly("华东", "华南");
        assertThat(result.items().get(0).delta()).isEqualByComparingTo("-200.00");
        assertThat(executor.sqls.get(0)).contains("coalesce(r.parent_id, r.id)");
        assertThat(executor.sqls.get(0)).contains("group by topr.id, topr.name");
    }

    @Test
    @DisplayName("父级 scope 继承：下钻到品类时同时带区域条件，且区域名解析成 id")
    void probeInheritsParentScope() {
        FakeExecutor executor = new FakeExecutor()
                .respond(List.of(idRow(1L)))
                .respond(List.of(row("火锅", "500.00", "900.00"), row("烧烤", "300.00", "320.00")));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.CATEGORY, Scope.root().with(Dimension.REGION, "华东"));

        assertThat(result.status()).isEqualTo(ProbeResult.Status.OK);
        assertThat(executor.sqls).hasSize(2);
        assertThat(executor.sqls.get(0)).isEqualTo("select id from region where name = ?");

        String groupSql = executor.sqls.get(1);
        assertThat(groupSql).contains("o.region_id in (select id from region where id = ? or parent_id = ?)");
        assertThat(groupSql).contains("group by c.id, c.name");
        // 前 6 个参数是四个时间窗，之后是 scope 解析出的 id
        assertThat(executor.params.get(1).subList(6, 8)).containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("scope 名称只解析一次：同一范围反复探测不重复查库")
    void scopeNameResolvedOnceAcrossProbes() {
        FakeExecutor executor = new FakeExecutor()
                .respond(List.of(idRow(1L)))
                .respond(List.of(row("火锅", "500.00", "900.00")))
                .respond(List.of(row("火锅", "500.00", "900.00")));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));
        Scope scope = Scope.root().with(Dimension.REGION, "华东");

        provider.probe(Dimension.CATEGORY, scope);
        provider.probe(Dimension.SHOP, scope);

        assertThat(executor.sqls).hasSize(3);
        assertThat(executor.sqls.get(0)).startsWith("select id from region");
    }

    @Test
    @DisplayName("门店 Top-10 + 其他：折叠后变化量总和不变，剩余门店全部进其他")
    void shopProbeKeepsAllGroupsThenFoldPreservesTotal() {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalDelta = BigDecimal.ZERO;
        for (int i = 1; i <= 15; i++) {
            BigDecimal cur = new BigDecimal(100 - i * 5);
            BigDecimal base = new BigDecimal(100);
            rows.add(row("门店" + i, cur.toPlainString(), base.toPlainString()));
            totalDelta = totalDelta.add(cur.subtract(base));
        }
        FakeExecutor executor = new FakeExecutor().respond(rows);
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.SHOP, Scope.root());
        assertThat(result.items()).hasSize(15);

        List<Item> folded = ContributionMath.foldTopN(result.items(), 10, AttributionEngine.AGGREGATE_LABEL);

        assertThat(folded).hasSize(11);
        assertThat(folded.get(10).value()).isEqualTo(AttributionEngine.AGGREGATE_LABEL);
        BigDecimal foldedDelta = folded.stream()
                .map(Item::delta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(foldedDelta).isEqualByComparingTo(totalDelta);
    }

    @Test
    @DisplayName("空结果：判失败并说明是过滤条件问题，不能当成没变化")
    void emptyResultFails() {
        FakeExecutor executor = new FakeExecutor().respond(List.of());
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.CATEGORY, Scope.root());

        assertThat(result.status()).isEqualTo(ProbeResult.Status.FAILED);
        assertThat(result.error()).contains("空结果");
    }

    @Test
    @DisplayName("查询异常：失败原因带回，供模型或日志定位")
    void sqlFailureIsReported() {
        FakeExecutor executor = new FakeExecutor().respondWith(new SqlExecutionException(
                SqlExecutionException.Kind.FAILED, "relation \"voucher_order\" does not exist"));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.CATEGORY, Scope.root());

        assertThat(result.status()).isEqualTo(ProbeResult.Status.FAILED);
        assertThat(result.error()).contains("查询失败").contains("does not exist");
    }

    @Test
    @DisplayName("被护栏拒绝：区分于执行失败，说明是 SQL 本身不合法")
    void rejectedSqlIsReported() {
        FakeExecutor executor = new FakeExecutor().respondWith(new SqlExecutionException(
                SqlExecutionException.Kind.REJECTED, "包含禁止的关键字: delete"));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.CATEGORY, Scope.root());

        assertThat(result.status()).isEqualTo(ProbeResult.Status.FAILED);
        assertThat(result.error()).contains("护栏");
    }

    @Test
    @DisplayName("超时：单独报超时，不和语法错误混在一起")
    void timeoutIsReported() {
        FakeExecutor executor = new FakeExecutor().respondWith(new SqlExecutionException(
                SqlExecutionException.Kind.TIMEOUT, "查询超时"));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.CATEGORY, Scope.root());

        assertThat(result.status()).isEqualTo(ProbeResult.Status.FAILED);
        assertThat(result.error()).contains("超时");
    }

    @Test
    @DisplayName("结果触顶：判失败，不能静默截断后算完备性")
    void truncatedResultFails() {
        AttributionProperties props = props();
        props.setProbeMaxRows(3);
        FakeExecutor executor = new FakeExecutor().respond(List.of(
                row("A", "10.00", "20.00"), row("B", "10.00", "20.00"), row("C", "10.00", "20.00")));
        SqlSplitProvider provider = provider(executor, props, new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.SHOP, Scope.root());

        assertThat(result.status()).isEqualTo(ProbeResult.Status.FAILED);
        assertThat(result.error()).contains("截断");
    }

    @Test
    @DisplayName("预算是硬上限：额度用完就不再打库，抛专用异常而不是普通失败")
    void budgetIsHardLimit() {
        FakeExecutor executor = new FakeExecutor()
                .respondWith(new SqlExecutionException(SqlExecutionException.Kind.FAILED, "boom"))
                .respondWith(new SqlExecutionException(SqlExecutionException.Kind.FAILED, "boom"))
                .respondWith(new SqlExecutionException(SqlExecutionException.Kind.FAILED, "boom"));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(2));

        provider.probe(Dimension.CATEGORY, Scope.root());
        provider.probe(Dimension.REGION, Scope.root());

        assertThatThrownBy(() -> provider.probe(Dimension.SHOP, Scope.root()))
                .isInstanceOf(QueryBudgetExhausted.class);
        assertThat(executor.sqls).hasSize(2);
    }

    @Test
    @DisplayName("预算耗尽终止整个归因，不换维度继续试")
    void budgetExhaustionStopsEngine() {
        SplitProvider provider = new SplitProvider() {
            @Override
            public Totals rootTotals() {
                return new Totals(new BigDecimal("850"), new BigDecimal("1000"));
            }

            @Override
            public ProbeResult probe(Dimension dimension, Scope scope) {
                throw new QueryBudgetExhausted("查询预算用尽（上限 16 次），停止归因");
            }
        };

        AttributionResult result = new AttributionEngine(AttributionConfig.defaults())
                .run(context -> Dimension.REGION, provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.BUDGET_EXHAUSTED);
        assertThat(result.message()).contains("预算");
        assertThat(result.path()).isEmpty();
    }

    @Test
    @DisplayName("scope 值不存在：明确失败，不是返回空分组")
    void unknownScopeValueFails() {
        FakeExecutor executor = new FakeExecutor().respond(List.of());
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.CATEGORY, Scope.root().with(Dimension.REGION, "华中"));

        assertThat(result.status()).isEqualTo(ProbeResult.Status.FAILED);
        assertThat(result.error()).contains("不存在").contains("华中");
    }

    @Test
    @DisplayName("scope 值不唯一：拒绝下钻，不能随便挑一个")
    void ambiguousScopeNameFails() {
        FakeExecutor executor = new FakeExecutor().respond(List.of(idRow(1L), idRow(2L)));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        ProbeResult result = provider.probe(Dimension.CATEGORY, Scope.root().with(Dimension.REGION, "华东"));

        assertThat(result.status()).isEqualTo(ProbeResult.Status.FAILED);
        assertThat(result.error()).contains("不唯一");
    }

    @Test
    @DisplayName("不支持的指标在构造期就失败，不留到运行时")
    void unsupportedMetricRejectedAtConstruction() {
        AttributionProperties props = props();
        props.setMetric("refund_rate");

        assertThatThrownBy(() -> provider(new FakeExecutor(), props, new QueryBudget(16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refund_rate");
    }

    @Test
    @DisplayName("分组汇总对不上父节点：判数据不完整，不给主因")
    void groupSumMismatchYieldsDataIncomplete() {
        FakeExecutor executor = new FakeExecutor()
                .respond(List.of(row("x", "850.00", "1000.00")))
                .respond(List.of(row("华东", "500.00", "600.00"), row("华南", "350.00", "380.00")));
        SqlSplitProvider provider = provider(executor, props(), new QueryBudget(16));

        AttributionResult result = new AttributionEngine(AttributionConfig.defaults())
                .run(context -> Dimension.REGION, provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DATA_INCOMPLETE);
        assertThat(result.path()).isEmpty();
    }

    private static SqlSplitProvider provider(FakeExecutor executor, AttributionProperties props,
                                             QueryBudget budget) {
        return new SqlSplitProvider(executor, props, budget, QueryWindow.of(AS_OF, props.getWindowDays()));
    }

    private static AttributionProperties props() {
        AttributionProperties props = new AttributionProperties();
        props.setAsOf(AS_OF);
        return props;
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static Map<String, Object> row(String label, String cur, String base) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("cur", d(cur));
        row.put("base", d(base));
        return row;
    }

    private static Map<String, Object> idRow(long id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        return row;
    }

    /** 假执行器：按预置顺序返回结果或抛异常，并记录每次提交的 SQL 与参数。 */
    private static final class FakeExecutor implements SqlExecutor {

        private final List<String> sqls = new ArrayList<>();
        private final List<List<Object>> params = new ArrayList<>();
        private final Deque<Object> responses = new ArrayDeque<>();

        FakeExecutor respond(List<Map<String, Object>> rows) {
            responses.addLast(rows);
            return this;
        }

        FakeExecutor respondWith(RuntimeException error) {
            responses.addLast(error);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> query(String sql, List<Object> bound, int timeoutSeconds, int maxRows) {
            sqls.add(sql);
            params.add(List.copyOf(bound));
            if (responses.isEmpty()) {
                throw new IllegalStateException("没有预置响应: " + sql);
            }
            Object next = responses.removeFirst();
            if (next instanceof RuntimeException error) {
                throw error;
            }
            return (List<Map<String, Object>>) next;
        }
    }
}
