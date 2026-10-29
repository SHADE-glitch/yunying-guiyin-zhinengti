package com.insight.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.guard.SqlGuard;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 唯一能真正打到数据库的工具，所以护栏都挂在这里。 */
@Component
public class RunSqlTool implements AgentTool {

    private final JdbcTemplate jdbc;
    private final SqlGuard guard;
    private final ObjectMapper mapper;
    private final int timeoutSeconds;
    private final int maxRows;

    public RunSqlTool(JdbcTemplate jdbc,
                      SqlGuard guard,
                      ObjectMapper mapper,
                      @Value("${agent.sql-timeout-seconds:10}") int timeoutSeconds,
                      @Value("${agent.max-rows:200}") int maxRows) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.mapper = mapper;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRows = maxRows;
    }

    @Override
    public String name() {
        return "run_sql";
    }

    @Override
    public String description() {
        return "执行一条只读 SELECT 查询并返回 JSON 结果。只允许 SELECT/WITH，自动加行数上限。"
                + "写操作会被拒绝。查询金额时注意 status 口径，先查 search_knowledge 确认定义。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "sql": {"type": "string", "description": "单条 SELECT 语句"}
                  },
                  "required": ["sql"]
                }
                """;
    }

    @Override
    public ToolOutcome execute(Map<String, Object> args) throws Exception {
        String raw = String.valueOf(args.get("sql"));
        SqlGuard.Verdict verdict = guard.check(raw, maxRows);
        if (!verdict.allowed()) {
            // 不抛异常：把拒绝原因回灌给模型，让它自己改。这是 Reflection 的入口。
            return ToolOutcome.rejected(verdict.reason());
        }

        List<Map<String, Object>> rows = jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) con -> {
            try (Statement st = con.createStatement()) {
                st.setQueryTimeout(timeoutSeconds);
                st.setMaxRows(maxRows);
                try (ResultSet rs = st.executeQuery(verdict.sql())) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            Object v = rs.getObject(i);
                            row.put(md.getColumnLabel(i), v == null ? null : String.valueOf(v));
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });

        if (rows == null || rows.isEmpty()) {
            return ToolOutcome.empty("查询没有返回任何行，检查过滤条件是否过严或时间窗是否写错");
        }
        return ToolOutcome.ok(mapper.writeValueAsString(rows));
    }
}
