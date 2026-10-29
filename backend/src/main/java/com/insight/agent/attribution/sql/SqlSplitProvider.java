package com.insight.agent.attribution.sql;

import com.insight.agent.attribution.AttributionPorts.ProbeResult;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.SplitProvider;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Item;
import com.insight.agent.attribution.Dimension;
import com.insight.agent.attribution.Evidence;
import com.insight.agent.attribution.QueryBudget;
import com.insight.agent.attribution.QueryBudgetExhausted;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 版拆分器：给状态机提供"根节点总量"和"某个范围内按某维度分组"。
 *
 * <p>三个刻意的取舍：
 *
 * <p>1. **区域按大区归组，品类按二级品类归组。** 订单表里的 region_id 是城市级，
 * 直接按它分组会得到 16 个城市，问"华东为什么跌"时华东根本不出现。大区只有 4 个，
 * 一次拆完。品类反过来：一级只有 4 个，拆了还得再钻一层才能到"火锅"，
 * 而 maxDepth 只有 2；按二级品类拆，第一层就能落到可行动的结论上。
 *
 * <p>2. **scope 里的名字在查询前解析成 id，并缓存。** 用子查询在 SQL 里解析也能做到，
 * 但名字重复时会静默合并多个实体。先解析、要求唯一，歧义会变成明确的失败而不是错误答案。
 * 缓存保证一次归因里同一维度的解析只打一次库。
 *
 * <p>3. **行数触顶直接判失败。** 门店分组必须拿全量才能把"其他"算对；
 * 静默截断会让完备性校验通过（少算的部分也少了），得出一个看起来合理但错的主因。
 */
public class SqlSplitProvider implements SplitProvider {

    private static final String STATUS_FILTER = "o.status in ('PAID', 'USED', 'REFUNDED')";

    private final SqlExecutor executor;
    private final AttributionProperties props;
    private final QueryBudget budget;
    private final QueryWindow window;
    private final Scope rootScope;
    private final String valueExpr;
    private final Map<Dimension, Map<String, Long>> resolvedIds = new EnumMap<>(Dimension.class);

    public SqlSplitProvider(SqlExecutor executor, AttributionProperties props, QueryBudget budget) {
        this(executor, props, budget, QueryWindow.of(props.getAsOf(), props.getWindowDays()));
    }

    public SqlSplitProvider(SqlExecutor executor, AttributionProperties props, QueryBudget budget,
                            QueryWindow window) {
        this(executor, props, budget, window, Scope.root());
    }

    /**
     * @param rootScope 本次归因的根范围。问"为什么华东跌了"时根就是华东，
     *                  不是全平台——大盘只跌 2.8%，从平台出发会被显著性门槛挡掉，
     *                  然后回答"没跌"，而用户问的是一个确实跌了 7% 的局部。
     */
    public SqlSplitProvider(SqlExecutor executor, AttributionProperties props, QueryBudget budget,
                            QueryWindow window, Scope rootScope) {
        this(executor, props, budget, window, rootScope, props.getMetric());
    }

    /**
     * @param rootScope 本次归因的根范围。问"为什么华东跌了"时根就是华东，
     *                  不是全平台——大盘只跌 2.8%，从平台出发会被显著性门槛挡掉，
     *                  然后回答"没跌"，而用户问的是一个确实跌了 7% 的局部。
     * @param metric    本次归因的指标（由问题路由决定），只通过 valueExpression 进入。
     */
    public SqlSplitProvider(SqlExecutor executor, AttributionProperties props, QueryBudget budget,
                            QueryWindow window, Scope rootScope, String metric) {
        this.executor = executor;
        this.props = props;
        this.budget = budget;
        this.window = window;
        this.rootScope = rootScope;
        this.valueExpr = valueExpression(metric);
    }

    @Override
    public Totals rootTotals() {
        String sql = "select coalesce(" + valueExpr
                + " filter (where o.created_at >= ? and o.created_at < ?), 0) as cur,\n"
                + "       coalesce(" + valueExpr
                + " filter (where o.created_at >= ? and o.created_at < ?), 0) as base\n"
                + "  from voucher_order o\n"
                + " where " + STATUS_FILTER + "\n"
                + "   and o.created_at >= ? and o.created_at < ?";
        List<Object> params = new ArrayList<>(List.of(
                ts(window.currentStart()), ts(window.currentEnd()),
                ts(window.baselineStart()), ts(window.baselineEnd()),
                ts(window.baselineStart()), ts(window.currentEnd())));
        sql = appendFilters(sql, params, rootScope);

        List<Map<String, Object>> rows = run(sql, params);
        Evidence evidence = Evidence.of(sql, rows.size());
        if (rows.isEmpty()) {
            return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, evidence);
        }
        Map<String, Object> row = rows.get(0);
        return new Totals(dec(row.get("cur")), dec(row.get("base")), evidence);
    }

    @Override
    public ProbeResult probe(Dimension dimension, Scope scope) {
        try {
            GroupPlan plan = plan(dimension);
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder();
            sql.append("select ").append(plan.labelExpr()).append(" as label,\n")
                    .append("       coalesce(").append(valueExpr)
                    .append(" filter (where o.created_at >= ? and o.created_at < ?), 0) as cur,\n")
                    .append("       coalesce(").append(valueExpr)
                    .append(" filter (where o.created_at >= ? and o.created_at < ?), 0) as base\n")
                    .append("  from voucher_order o\n")
                    .append("  ").append(plan.joins()).append("\n")
                    .append(" where ").append(STATUS_FILTER).append("\n")
                    .append("   and o.created_at >= ? and o.created_at < ?\n");
            params.add(ts(window.currentStart()));
            params.add(ts(window.currentEnd()));
            params.add(ts(window.baselineStart()));
            params.add(ts(window.baselineEnd()));
            params.add(ts(window.baselineStart()));
            params.add(ts(window.currentEnd()));

            String withScope = appendFilters(sql.toString(), params, effective(scope));
            sql = new StringBuilder(withScope);
            sql.append(" group by ").append(plan.groupExpr()).append(", ").append(plan.labelExpr()).append("\n")
                    .append(" order by label");

            String text = sql.toString();
            List<Map<String, Object>> rows = run(text, params);
            Evidence evidence = Evidence.of(text, rows.size());

            if (rows.isEmpty()) {
                return ProbeResult.failed("空结果：该范围内没有订单，过滤条件可能过严", evidence);
            }
            if (rows.size() >= props.getProbeMaxRows()) {
                return ProbeResult.failed("结果被行数上限截断（" + props.getProbeMaxRows()
                        + " 行），分组不完整，不能用于归因", evidence);
            }

            List<Item> items = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                items.add(new Item(label(row), dec(row.get("cur")), dec(row.get("base"))));
            }
            return ProbeResult.ok(List.copyOf(items), evidence);
        } catch (QueryBudgetExhausted e) {
            throw e;
        } catch (SqlExecutionException e) {
            return ProbeResult.failed(describe(e));
        } catch (ScopeResolutionException e) {
            return ProbeResult.failed(e.getMessage());
        }
    }

    /** 根范围 + 已下钻范围。同一维度以更具体的一方为准。 */
    private Scope effective(Scope scope) {
        if (rootScope.filters().isEmpty()) {
            return scope;
        }
        Map<Dimension, String> merged = new LinkedHashMap<>(rootScope.filters());
        merged.putAll(scope.filters());
        return new Scope(Map.copyOf(merged));
    }

    private String appendFilters(String base, List<Object> params, Scope scope) {
        StringBuilder sql = new StringBuilder(base);
        for (Map.Entry<Dimension, String> entry : orderedFilters(scope)) {
            sql.append("   and ").append(predicate(entry.getKey())).append("\n");
            params.addAll(predicateParams(entry.getKey(), entry.getValue()));
        }
        return sql.toString();
    }

    /**
     * 唯一能打到数据库的地方。先扣预算再执行，所以"实际执行次数 ≤ 预算上限"是结构保证：
     * 任何绕过这里直接调 executor 的代码路径都拿不到额度。
     */
    private List<Map<String, Object>> run(String sql, List<Object> params) {
        if (!budget.tryAcquire()) {
            throw new QueryBudgetExhausted("查询预算用尽（上限 " + budget.limit() + " 次），停止归因");
        }
        return executor.query(sql, params, props.getTimeoutSeconds(), props.getProbeMaxRows());
    }

    private long resolve(Dimension dimension, String name) {
        Map<String, Long> cache = resolvedIds.computeIfAbsent(dimension, d -> new HashMap<>());
        Long cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        String sql = "select id from " + table(dimension) + " where name = ?";
        List<Map<String, Object>> rows = run(sql, List.of(name));
        if (rows.isEmpty()) {
            throw new ScopeResolutionException(
                    "范围内不存在名为 " + name + " 的" + dimension.label() + "，无法下钻");
        }
        if (rows.size() > 1) {
            throw new ScopeResolutionException(
                    dimension.label() + "名称不唯一：" + name + "，无法确定下钻范围");
        }
        Object raw = rows.get(0).get("id");
        long id = ((Number) raw).longValue();
        cache.put(name, id);
        return id;
    }

    private static String valueExpression(String metric) {
        return switch (metric) {
            case "gmv" -> "sum(o.pay_amount)";
            // 订单量和 GMV 一样可加，能走同一套分解数学；比率类指标（退款率等）
            // 不满足可加分解，问的人再多也不能进这里
            case "order_count" -> "count(*)";
            default -> throw new IllegalArgumentException(
                    "不支持的归因指标: " + metric + "（目前支持 gmv, order_count）");
        };
    }

    private static String table(Dimension dimension) {
        return switch (dimension) {
            case REGION -> "region";
            case CATEGORY -> "category";
            case SHOP -> "shop";
        };
    }

    private static String predicate(Dimension dimension) {
        return switch (dimension) {
            case REGION -> "o.region_id in (select id from region where id = ? or parent_id = ?)";
            case CATEGORY -> "o.category_id in (select id from category where id = ? or parent_id = ?)";
            case SHOP -> "o.shop_id = ?";
        };
    }

    private List<Object> predicateParams(Dimension dimension, String value) {
        long id = resolve(dimension, value);
        return dimension == Dimension.SHOP ? List.of(id) : List.of(id, id);
    }

    private static GroupPlan plan(Dimension dimension) {
        return switch (dimension) {
            case REGION -> new GroupPlan(
                    "join region r on r.id = o.region_id\n"
                            + "  join region topr on topr.id = coalesce(r.parent_id, r.id)",
                    "topr.id", "topr.name");
            case CATEGORY -> new GroupPlan(
                    "join category c on c.id = o.category_id",
                    "c.id", "c.name");
            case SHOP -> new GroupPlan(
                    "join shop sh on sh.id = o.shop_id",
                    "sh.id", "sh.name");
        };
    }

    /** 过滤条件的顺序会影响 SQL 文本和证据指纹，按维度名排序保证同一输入生成同一份证据。 */
    private static List<Map.Entry<Dimension, String>> orderedFilters(Scope scope) {
        return scope.filters().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .toList();
    }

    private static String label(Map<String, Object> row) {
        Object raw = row.get("label");
        return raw == null ? "" : String.valueOf(raw);
    }

    private static BigDecimal dec(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal value) {
            return value;
        }
        return new BigDecimal(String.valueOf(raw));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static String describe(SqlExecutionException e) {
        return switch (e.kind()) {
            case REJECTED -> "SQL 被护栏拒绝：" + e.getMessage();
            case TIMEOUT -> "查询超时：" + e.getMessage();
            case FAILED -> "查询失败：" + e.getMessage();
        };
    }

    private record GroupPlan(String joins, String groupExpr, String labelExpr) {
    }

    /** scope 里的值解析不出来。属于"这个范围本身有问题"，不是查询挂了。 */
    private static class ScopeResolutionException extends RuntimeException {

        ScopeResolutionException(String message) {
            super(message);
        }
    }
}
