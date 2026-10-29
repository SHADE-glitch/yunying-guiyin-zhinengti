package com.insight.agent.attribution.sql;

import com.insight.agent.guard.SqlGuard;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 归因查询的真实执行器。和 {@code RunSqlTool} 共用同一套 {@link SqlGuard}：
 * 语法白名单、只读、行数上限、执行超时四层都在，不是另起一套。
 *
 * <p>区别只有一点：这里用 PreparedStatement + 参数绑定，因为过滤条件是值（"华东"、"火锅"），
 * 拼接字符串会把注入面重新打开。模型走的那条路是整条 SQL 交给模型写，只能做语法层校验；
 * 归因这条路的 SQL 由代码生成，所以可以做到参数化。
 */
@Component
public class JdbcSqlExecutor implements SqlExecutor {

    private final JdbcTemplate jdbc;
    private final SqlGuard guard;

    public JdbcSqlExecutor(JdbcTemplate jdbc, SqlGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    @Override
    public List<Map<String, Object>> query(String sql, List<Object> params, int timeoutSeconds, int maxRows) {
        SqlGuard.Verdict verdict = guard.check(sql, maxRows);
        if (!verdict.allowed()) {
            throw new SqlExecutionException(SqlExecutionException.Kind.REJECTED, verdict.reason());
        }

        try {
            List<Map<String, Object>> rows = jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) con -> {
                try (PreparedStatement ps = con.prepareStatement(verdict.sql())) {
                    ps.setQueryTimeout(timeoutSeconds);
                    ps.setMaxRows(maxRows);
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int cols = md.getColumnCount();
                        List<Map<String, Object>> out = new ArrayList<>();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= cols; i++) {
                                row.put(md.getColumnLabel(i), rs.getObject(i));
                            }
                            out.add(row);
                        }
                        return out;
                    }
                }
            });
            return rows == null ? List.of() : rows;
        } catch (DataAccessException e) {
            throw classify(e);
        }
    }

    /** PG 的 statement_timeout 抛的是 SQLState 57014（query_canceled），不是 SQLTimeoutException。 */
    private static SqlExecutionException classify(DataAccessException e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof SQLTimeoutException) {
                return new SqlExecutionException(SqlExecutionException.Kind.TIMEOUT, "查询超时", e);
            }
            if (cursor instanceof SQLException sql && "57014".equals(sql.getSQLState())) {
                return new SqlExecutionException(SqlExecutionException.Kind.TIMEOUT, "查询超时", e);
            }
            cursor = cursor.getCause();
        }
        return new SqlExecutionException(SqlExecutionException.Kind.FAILED,
                e.getMostSpecificCause().getMessage(), e);
    }
}
