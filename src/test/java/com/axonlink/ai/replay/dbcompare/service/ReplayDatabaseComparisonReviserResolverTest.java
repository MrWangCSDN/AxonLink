package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDatabaseComparisonReviserResolverTest {

    private ReplayDatabaseComparisonReviserResolver resolver;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseComparisonServiceTest.createUsers(jdbc);
        jdbc.batchUpdate("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES (?,?,?,?)",
                List.of(
                        new Object[]{"c-zhangs", "张三", "10001", 1},
                        new Object[]{"c-lisi1", "李四", "10002", 1},
                        new Object[]{"c-lisi2", "李四", "10003", 1},
                        new Object[]{"c-disabled", "停用用户", "10004", 0}));
        resolver = new ReplayDatabaseComparisonReviserResolver(new SysUserDao(jdbc));
    }

    @Test
    void acceptsBlankNameAndParenthesizedDisplay() {
        assertTrue(resolver.resolve(" ").blank());
        assertNull(resolver.resolve(" ").user());
        assertEquals("c-zhangs", resolver.resolve("张三").user().getUsername());
        assertEquals("张三（c-zhangs）", resolver.resolve("张三（c-zhangs）").displayName());
        assertEquals("张三（c-zhangs）", resolver.resolve("张三(c-zhangs)").displayName());
    }

    @Test
    void treatsPlainTextOnlyAsARealName() {
        assertEquals("人员不存在或已停用", resolver.resolve("c-zhangs").reason());
        assertEquals("人员不存在或已停用", resolver.resolve("10001").reason());
    }

    @Test
    void reportsUnknownInactiveMismatchedAndDuplicateNames() {
        assertEquals("人员不存在或已停用", resolver.resolve("王不存在").reason());
        assertEquals("人员不存在或已停用", resolver.resolve("c-disabled").reason());
        assertEquals("姓名与账号不匹配", resolver.resolve("王五（c-zhangs）").reason());
        ReplayDatabaseComparisonReviserResolver.Resolution duplicate = resolver.resolve("李四");
        assertFalse(duplicate.valid());
        assertEquals("姓名不唯一，可选账号：c-lisi1、c-lisi2", duplicate.reason());
    }
}
