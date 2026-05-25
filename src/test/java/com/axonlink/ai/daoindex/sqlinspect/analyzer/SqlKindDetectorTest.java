package com.axonlink.ai.daoindex.sqlinspect.analyzer;

// 静态导入 SqlKind 枚举常量，断言时少写一层前缀
import static com.axonlink.ai.daoindex.sqlinspect.analyzer.SqlPredicateAnalyzer.SqlKind;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SqlKindDetector} 单元测试。
 *
 * <p>覆盖：各语句类型 + 前导块注释/行注释 + 大小写不敏感 + 占位符 + 边界（null/空/无法识别）。
 * 纯静态方法，无需 Spring 上下文，跑得快。
 */
@DisplayName("SqlKindDetector —— 首关键字 SQL 类型判定")
class SqlKindDetectorTest {

    @Test
    @DisplayName("SELECT 普通查询 → SELECT")
    void select() {
        assertEquals(SqlKind.SELECT,
                SqlKindDetector.detect("SELECT id, name FROM t_user WHERE id = ?"));
    }

    @Test
    @DisplayName("WITH（CTE）当作 SELECT")
    void withCte() {
        assertEquals(SqlKind.SELECT,
                SqlKindDetector.detect("WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @Test
    @DisplayName("UPDATE → UPDATE")
    void update() {
        assertEquals(SqlKind.UPDATE,
                SqlKindDetector.detect("UPDATE t_acct SET bal = bal + 1 WHERE acct_no = ?"));
    }

    @Test
    @DisplayName("DELETE → DELETE")
    void delete() {
        assertEquals(SqlKind.DELETE,
                SqlKindDetector.detect("DELETE FROM t_log WHERE created_at < ?"));
    }

    @Test
    @DisplayName("INSERT ... VALUES → INSERT_VALUES")
    void insertValues() {
        assertEquals(SqlKind.INSERT_VALUES,
                SqlKindDetector.detect("INSERT INTO t_user (id, name) VALUES (?, ?)"));
    }

    @Test
    @DisplayName("INSERT ... SELECT（无 VALUES 关键字）→ INSERT_SELECT")
    void insertSelect() {
        assertEquals(SqlKind.INSERT_SELECT,
                SqlKindDetector.detect("INSERT INTO t_bak (id) SELECT id FROM t_user WHERE id = ?"));
    }

    @Test
    @DisplayName("小写 select 也能识别（大小写不敏感）")
    void lowerCase() {
        assertEquals(SqlKind.SELECT,
                SqlKindDetector.detect("select * from t_user"));
    }

    @Test
    @DisplayName("混合大小写 InSeRt ... vAlUeS → INSERT_VALUES")
    void mixedCaseInsertValues() {
        assertEquals(SqlKind.INSERT_VALUES,
                SqlKindDetector.detect("InSeRt InTo t_user (id) vAlUeS (?)"));
    }

    @Test
    @DisplayName("前导块注释 /* ... */ 不影响判定")
    void leadingBlockComment() {
        assertEquals(SqlKind.SELECT,
                SqlKindDetector.detect("/* mapper: UserDao.findById */ SELECT * FROM t_user WHERE id=?"));
    }

    @Test
    @DisplayName("前导行注释 -- ... 不影响判定")
    void leadingLineComment() {
        String sql = "-- 这是业务说明\n-- 另一行注释\nUPDATE t_user SET name=? WHERE id=?";
        assertEquals(SqlKind.UPDATE, SqlKindDetector.detect(sql));
    }

    @Test
    @DisplayName("块注释 + 行注释 + 多余空白混合前缀")
    void mixedLeadingNoise() {
        String sql = "  /* hint */\n  -- log\n   \t DELETE FROM t WHERE a=?";
        assertEquals(SqlKind.DELETE, SqlKindDetector.detect(sql));
    }

    @Test
    @DisplayName("块注释里出现 select 字样，不应误判（仍取注释后的真正首词）")
    void commentContainingKeyword() {
        String sql = "/* old: select ... deprecated */ DELETE FROM t WHERE a=?";
        assertEquals(SqlKind.DELETE, SqlKindDetector.detect(sql));
    }

    @Test
    @DisplayName("银行模板占位符 #xxx# 不影响首关键字判定")
    void sunlinePlaceholder() {
        assertEquals(SqlKind.SELECT,
                SqlKindDetector.detect("SELECT * FROM t_acct WHERE acct_no = #acctNo# AND st = ${st}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t ", "/* only comment */", "-- only line comment"})
    @DisplayName("null / 空 / 纯注释 / 纯空白 → UNKNOWN")
    void unknownEdgeCases(String sql) {
        assertEquals(SqlKind.UNKNOWN, SqlKindDetector.detect(sql));
    }

    @Test
    @DisplayName("非 DML（MERGE / TRUNCATE / CALL）→ UNKNOWN")
    void nonDml() {
        assertEquals(SqlKind.UNKNOWN, SqlKindDetector.detect("TRUNCATE TABLE t_user"));
        assertEquals(SqlKind.UNKNOWN, SqlKindDetector.detect("MERGE INTO t USING s ON (t.id=s.id)"));
        assertEquals(SqlKind.UNKNOWN, SqlKindDetector.detect("CALL my_proc(?)"));
    }
}
