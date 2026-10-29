package com.insight.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 看几行真实数据，帮助模型理解枚举值（比如 status 到底是哪几个值）。 */
@Component
public class SampleDataTool implements AgentTool {

    private static final Set<String> ALLOWED = Set.of(
            "region", "category", "shop", "app_user", "voucher", "voucher_order", "metric_definition");

    private final JdbcTemplate jdbc;

    public SampleDataTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sample_data";
    }

    @Override
    public String description() {
        return "查看某张表的前几行样例数据，用来确认字段取值。例如 status 有哪些值。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "表名"},
                    "limit": {"type": "integer", "description": "行数，默认 5，最大 10"}
                  },
                  "required": ["table"]
                }
                """;
    }

    @Override
    public ToolOutcome execute(Map<String, Object> args) {
        String table = String.valueOf(args.get("table"));
        if (!ALLOWED.contains(table)) {
            return ToolOutcome.rejected("不允许访问表: " + table + "，可用表见 get_schema");
        }
        // 模型给的 limit 可能是字符串、负数或超大值：夹到 1..10，避免直接抛错或拖库
        int limit = 5;
        Object raw = args.get("limit");
        if (raw instanceof Number number) {
            limit = number.intValue();
        } else if (raw instanceof String text && text.strip().matches("\\d+")) {
            limit = Integer.parseInt(text.strip());
        }
        limit = Math.max(1, Math.min(10, limit));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from " + table + " limit " + limit);
        return rows.isEmpty() ? ToolOutcome.empty("表 " + table + " 没有数据") : ToolOutcome.ok(rows.toString());
    }
}
