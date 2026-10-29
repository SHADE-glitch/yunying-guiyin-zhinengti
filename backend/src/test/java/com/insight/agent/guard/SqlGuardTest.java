package com.insight.agent.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL 护栏第一层的直接测试。
 *
 * <p>这一层是"第二道防线"（第一道是只读账号），所以测试只断言现有契约，不改策略：
 * 宁可错杀（字符串字面量里的 drop 也会被拒），不可放过。
 */
class SqlGuardTest {

    private static final int MAX_ROWS = 100;

    private final SqlGuard guard = new SqlGuard();

    @Test
    @DisplayName("写语句直接拒绝，大小写不敏感")
    void rejectsWriteStatements() {
        SqlGuard.Verdict lower = guard.check("delete from voucher_order", MAX_ROWS);
        SqlGuard.Verdict upper = guard.check("UPDATE voucher_order SET amount = 0", MAX_ROWS);

        // 第一道判断是开头：写语句根本进不到关键字扫描
        assertThat(lower.allowed()).isFalse();
        assertThat(lower.reason()).contains("只允许 SELECT");
        assertThat(upper.allowed()).isFalse();
        assertThat(upper.reason()).contains("只允许 SELECT");
        assertThat(lower.sql()).isNull();
    }

    @Test
    @DisplayName("藏在 with 里的写语句被关键字扫描拦下")
    void rejectsWriteHiddenInsideCte() {
        SqlGuard.Verdict verdict = guard.check(
                "with x as (delete from voucher_order returning id) select * from x", MAX_ROWS);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("delete");
    }

    @Test
    @DisplayName("多条语句拒绝，哪怕第二条是查询")
    void rejectsMultipleStatements() {
        assertThat(guard.check("select 1; select 2", MAX_ROWS).reason()).contains("多条语句");
        assertThat(guard.check("select 1; delete from t", MAX_ROWS).reason()).contains("多条语句");
    }

    @Test
    @DisplayName("注释拒绝：它能把后续语句藏起来")
    void rejectsComments() {
        assertThat(guard.check("select * from voucher_order -- 只看华东", MAX_ROWS).reason())
                .contains("注释");
        assertThat(guard.check("select /* 1 */ 1", MAX_ROWS).reason()).contains("注释");
    }

    @Test
    @DisplayName("空输入拒绝，不把 null 传给执行器")
    void rejectsBlankInput() {
        assertThat(guard.check(null, MAX_ROWS).reason()).contains("为空");
        assertThat(guard.check("", MAX_ROWS).reason()).contains("为空");
        assertThat(guard.check("   \n\t ", MAX_ROWS).reason()).contains("为空");
    }

    @Test
    @DisplayName("不是 select/with 开头就拒绝，包括只读的 explain")
    void rejectsNonSelectHeads() {
        assertThat(guard.check("pragma table_info('x')", MAX_ROWS).reason()).contains("只允许 SELECT");
        assertThat(guard.check("explain select 1", MAX_ROWS).reason()).contains("只允许 SELECT");
        assertThat(guard.check("(select 1)", MAX_ROWS).reason()).contains("只允许 SELECT");
    }

    @Test
    @DisplayName("没有 limit 时补上行数上限")
    void appendsLimitWhenMissing() {
        SqlGuard.Verdict verdict = guard.check("select * from voucher_order", MAX_ROWS);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.sql()).isEqualTo("select * from voucher_order limit " + MAX_ROWS);
    }

    @Test
    @DisplayName("已有 limit 就不重复追加")
    void keepsExistingLimit() {
        SqlGuard.Verdict verdict = guard.check("select * from voucher_order limit 5", MAX_ROWS);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.sql()).isEqualTo("select * from voucher_order limit 5");
        assertThat(verdict.sql()).doesNotContain("limit 5 limit");
    }

    @Test
    @DisplayName("LIMIT 前是换行或多个空白时也不重复追加")
    void recognisesLimitWithoutSurroundingSpaces() {
        SqlGuard.Verdict newline = guard.check("select * from voucher_order\nlimit 5", MAX_ROWS);
        SqlGuard.Verdict spaces = guard.check("select * from voucher_order   limit 5", MAX_ROWS);
        SqlGuard.Verdict tab = guard.check("select * from voucher_order\tlimit 5", MAX_ROWS);

        assertThat(newline.sql()).isEqualTo("select * from voucher_order\nlimit 5");
        assertThat(spaces.sql()).isEqualTo("select * from voucher_order   limit 5");
        assertThat(tab.sql()).isEqualTo("select * from voucher_order\tlimit 5");
        assertThat(newline.sql()).doesNotContain("limit 5 limit");
    }

    @Test
    @DisplayName("大写 LIMIT 同样识别")
    void recognisesUppercaseLimit() {
        SqlGuard.Verdict verdict = guard.check("select * from voucher_order LIMIT 5", MAX_ROWS);

        assertThat(verdict.sql()).isEqualTo("select * from voucher_order LIMIT 5");
    }

    @Test
    @DisplayName("列名里含 limit 不算已经限制行数")
    void identifierContainingLimitIsNotALimitClause() {
        SqlGuard.Verdict verdict = guard.check("select limit_flag from voucher_order", MAX_ROWS);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.sql()).isEqualTo("select limit_flag from voucher_order limit " + MAX_ROWS);
    }

    @Test
    @DisplayName("结尾的分号被去掉，不算多条语句")
    void stripsTrailingSemicolon() {
        SqlGuard.Verdict verdict = guard.check("select 1;", MAX_ROWS);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.sql()).isEqualTo("select 1 limit " + MAX_ROWS);
    }

    @Test
    @DisplayName("with 开头的 CTE 允许")
    void allowsCte() {
        SqlGuard.Verdict verdict = guard.check(
                "with base as (select 1 as x) select * from base", MAX_ROWS);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.sql()).endsWith(" limit " + MAX_ROWS);
    }

    @Test
    @DisplayName("禁止词按整词匹配：created_at 不会被当成 create")
    void doesNotFlagIdentifiers() {
        SqlGuard.Verdict verdict = guard.check(
                "select created_at, updated_at, analyze_flag from voucher_order", MAX_ROWS);

        assertThat(verdict.allowed()).isTrue();
    }

    @Test
    @DisplayName("已知取舍：字符串字面量里的禁止词也会被拒（宁可错杀）")
    void forbidsKeywordInsideStringLiteral() {
        // 这不是漏洞，是刻意的保守方向：不为了少一次误拒去写 SQL 词法分析
        assertThat(guard.check("select * from voucher_order where note = 'drop'", MAX_ROWS).reason())
                .contains("drop");
    }

    @Test
    @DisplayName("前后空白被裁掉")
    void trimsOuterWhitespace() {
        SqlGuard.Verdict verdict = guard.check("  \n select 1 \n  ", MAX_ROWS);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.sql()).startsWith("select 1");
    }
}
