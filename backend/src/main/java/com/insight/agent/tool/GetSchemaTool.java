package com.insight.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 返回表的列定义。模型不该靠猜字段名。 */
@Component
public class GetSchemaTool implements AgentTool {

    /** 只暴露业务表。白名单而不是黑名单：将来加新表时不会因为忘记排除而泄露内部表。 */
    private static final Set<String> BUSINESS_TABLES = Set.of(
            "region", "category", "shop", "app_user", "voucher", "voucher_order", "metric_definition");

    private final JdbcTemplate jdbc;

    public GetSchemaTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "get_schema";
    }

    @Override
    public String description() {
        return "查看数据表的字段定义。写 SQL 前先用它确认表名和列名，不要凭猜测写字段。"
                + "不传 tables 时返回所有可用表。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "tables": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "表名列表，例如 voucher_order, shop"
                    }
                  }
                }
                """;
    }

    @Override
    public ToolOutcome execute(Map<String, Object> args) {
        // schema 声明的是数组，但模型经常给单个字符串：两种都收，别让它因为参数形状报错
        Object raw = args.get("tables");
        List<String> tables = raw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : raw == null ? List.of() : List.of(String.valueOf(raw));

        String sql = """
                select table_name, column_name, data_type
                from information_schema.columns
                where table_schema = 'public'
                  and table_name in (select table_name from information_schema.tables
                                     where table_schema = 'public' and table_type = 'BASE TABLE')
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        boolean all = tables == null || tables.isEmpty();
        Map<String, List<Map<String, Object>>> byTable = rows.stream()
                .filter(r -> BUSINESS_TABLES.contains(String.valueOf(r.get("table_name"))))
                .filter(r -> all || tables.contains(String.valueOf(r.get("table_name"))))
                .collect(Collectors.groupingBy(r -> String.valueOf(r.get("table_name"))));

        StringBuilder sb = new StringBuilder();
        byTable.forEach((table, cols) -> {
            sb.append("table ").append(table).append(":\n");
            cols.forEach(c -> sb.append("  - ")
                    .append(c.get("column_name")).append(" ")
                    .append(c.get("data_type")).append("\n"));
        });
        return sb.isEmpty()
                ? ToolOutcome.empty("没有找到匹配的表，先确认表名")
                : ToolOutcome.ok(sb.toString());
    }
}
