package com.axonlink.ai.replay.dbcompare.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseComparisonScriptConfigurationDaoTest {
    private JdbcTemplate jdbc;
    private ReplayDatabaseComparisonScriptConfigurationDao dao;
    private final LocalDateTime at = LocalDateTime.of(2026, 11, 24, 10, 0);

    @BeforeEach void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V95__replay_named_script_configuration.sql"), new ClassPathResource(
                "db/daoindex/V97__replay_script_config_datasource.sql")).execute(jdbc.getDataSource());
        dao = new ReplayDatabaseComparisonScriptConfigurationDao(jdbc);
    }

    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    private ReplayDatabaseComparisonScriptConfigurationDao.WriteConfiguration data(String name, String key) {
        return new ReplayDatabaseComparisonScriptConfigurationDao.WriteConfiguration(name, key,
                "{\"tables\":[]}", "config.sql", new byte[]{1, 2, 3}, "a".repeat(64),
                123, 2, 8, "operator", at, null, null);
    }

    @Test void persistsIndependentConfigurationsAndListsTheirOwnRevisions() {
        long first = dao.insert(data("方案一", "方案一"));
        long second = dao.insert(data("方案二", "方案二"));
        assertNotEquals(first, second);
        var saved = dao.find(first);
        assertEquals("方案一", saved.name());
        assertEquals(1, saved.revision());
        assertArrayEquals(new byte[]{1, 2, 3}, saved.scriptContentGzip());
        assertEquals(2, dao.list().size());
        assertEquals(second, dao.list().get(0).id());
        assertEquals(8, dao.list().get(0).fieldCount());
        assertNull(dao.find(Long.MAX_VALUE));
    }

    @Test void optimisticUpdateRejectsStaleRevisionWithoutOverwritingTheWinner() {
        long id = dao.insert(data("方案一", "方案一"));
        assertEquals(1, dao.update(id, 1, data("新方案", "新方案")));
        assertEquals(0, dao.update(id, 1, data("过期编辑", "过期编辑")));
        assertEquals(2, dao.find(id).revision());
        assertEquals("新方案", dao.find(id).name());
    }

    @Test void duplicateNormalizedNamesCannotCreateOrOverwriteConfigurations() {
        long first = dao.insert(data("First", "first"));
        long second = dao.insert(data("Second", "second"));
        assertThrows(DuplicateKeyException.class, () -> dao.insert(data("FIRST", "first")));
        assertThrows(DuplicateKeyException.class, () -> dao.update(second, 1, data("FIRST", "first")));
        assertEquals("First", dao.find(first).name());
        assertEquals("Second", dao.find(second).name());
        assertEquals(1, dao.find(second).revision());
    }
}
