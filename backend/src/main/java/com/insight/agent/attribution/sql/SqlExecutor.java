package com.insight.agent.attribution.sql;

import java.util.List;
import java.util.Map;

/**
 * 归因查询的执行入口。
 *
 * <p>抽成接口是为了让 provider 的分组/折叠/预算逻辑能在没有数据库的情况下单测：
 * 假实现能精确制造空结果、超时、被拒、截断这些分支。
 * 真实实现只有 {@link JdbcSqlExecutor} 一个，护栏挂在它里面。
 */
public interface SqlExecutor {

    List<Map<String, Object>> query(String sql, List<Object> params, int timeoutSeconds, int maxRows);
}
