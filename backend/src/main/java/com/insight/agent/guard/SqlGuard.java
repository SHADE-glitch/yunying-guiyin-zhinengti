package com.insight.agent.guard;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SQL 护栏第一层：语法白名单。
 *
 * <p>只允许单条 SELECT / WITH。写操作不是"不推荐"，是"不允许"——数据库账号本身也是只读的，
 * 这里是第二道防线。第三道是行数上限，第四道是执行超时。
 */
@Component
public class SqlGuard {

    private static final Set<String> FORBIDDEN = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "copy", "call", "do", "merge", "vacuum", "analyze");

    private static final Pattern WORD = Pattern.compile("[a-zA-Z_]+");

    /** 按整词找 LIMIT：不依赖前后空格，换行和多个空白都能认出来。 */
    private static final Pattern LIMIT = Pattern.compile("\\blimit\\b");

    public record Verdict(boolean allowed, String sql, String reason) {
        public static Verdict ok(String sql) {
            return new Verdict(true, sql, null);
        }

        public static Verdict reject(String reason) {
            return new Verdict(false, null, reason);
        }
    }

    public Verdict check(String raw, int maxRows) {
        if (raw == null || raw.isBlank()) {
            return Verdict.reject("SQL 为空");
        }
        String sql = raw.strip();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        if (sql.contains(";")) {
            return Verdict.reject("不允许多条语句");
        }

        String head = sql.toLowerCase(Locale.ROOT);
        if (!head.startsWith("select") && !head.startsWith("with")) {
            return Verdict.reject("只允许 SELECT 查询");
        }

        // 注释可能藏起后续语句，直接拒绝
        if (sql.contains("--") || sql.contains("/*")) {
            return Verdict.reject("不允许 SQL 注释");
        }

        for (String word : words(sql)) {
            if (FORBIDDEN.contains(word)) {
                return Verdict.reject("包含禁止的关键字: " + word);
            }
        }

        if (!LIMIT.matcher(head).find()) {
            sql = sql + " limit " + maxRows;
        }
        return Verdict.ok(sql);
    }

    private static List<String> words(String sql) {
        return WORD.matcher(sql).results()
                .map(m -> m.group().toLowerCase(Locale.ROOT))
                .toList();
    }
}
