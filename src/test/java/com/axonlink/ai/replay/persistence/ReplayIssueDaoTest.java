package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueFilterOptions;
import com.axonlink.ai.replay.dto.ReplayIssueQuery;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueDaoTest {

    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 8, 4, 10, 0);
    private static final ReplayIssueQuery ALL = new ReplayIssueQuery(50, 0, null, null, null, null, null);

    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
    }

    @Test
    void replaceFailureKeepsPreviousSnapshot() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")), IMPORTED_AT);
        ReplayIssueRow bad = ReplayIssueTestFixtures.row(null, false, 2, "6209", "bad");

        assertThrows(DataAccessException.class,
                () -> dao.replaceAll(List.of(bad), IMPORTED_AT.plusDays(1)));

        assertEquals("old", dao.list(ALL).get(0).get("issue_description"));
    }

    @Test
    void listCombinesGroupSandboxLevelTypeAndKeyword() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "6208", "CCBS响应不一致"),
                ReplayIssueTestFixtures.row("贷款组", true, 2, "6208", "沙箱数据缺失"),
                ReplayIssueTestFixtures.row("存款组", false, 3, "1001", "CCBS响应不一致")), IMPORTED_AT);

        ReplayIssueQuery query = new ReplayIssueQuery(50, 0, "贷款组", false,
                "交易级", "数据差异", "CCBS");

        assertEquals(1, dao.count(query));
        assertEquals("6208", dao.list(query).get(0).get("transaction_code"));
    }

    @Test
    void listClampsPageBoundsAndUsesStableGroupSandboxRowAndIdOrder() {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", true, 2, "T-2", "two"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "F-2", "false-second"),
                ReplayIssueTestFixtures.row("贷款组", false, 1, "F-1", "false-first"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "F-2b", "false-second-by-id")), IMPORTED_AT);

        List<Map<String, Object>> rows = dao.list(new ReplayIssueQuery(999, -20, null, null, null, null, null));

        assertEquals(4, rows.size());
        assertIterableEquals(List.of("F-1", "F-2", "F-2b", "T-2"),
                rows.stream().map(row -> (String) row.get("transaction_code")).toList());
    }

    @Test
    void listDefaultsZeroLimitToFiftyRows() {
        List<ReplayIssueRow> rows = new ArrayList<>();
        for (int rowOrder = 1; rowOrder <= 51; rowOrder++) {
            rows.add(ReplayIssueTestFixtures.row("公共组", false, rowOrder,
                    "T-" + rowOrder, "issue-" + rowOrder));
        }
        dao.replaceAll(rows, IMPORTED_AT);

        assertEquals(50, dao.list(new ReplayIssueQuery(0, 0, null, null, null, null, null)).size());
    }

    @Test
    void optionsExcludeBlankValuesAndStatsDescribeCurrentSnapshot() {
        ReplayIssueRow first = ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "first");
        ReplayIssueRow second = new ReplayIssueRow(null, "沙箱-贷款组", "贷款组", true, 2,
                "贷款组", "2", "BATCH-2", "6209", "交易6209", "字段级", "", "字段", "second",
                "", "环境问题", "", "", "", "", "", "002", "", "", "1", "issue-2", "key-2",
                "0", "", "", null);
        ReplayIssueRow blankOptions = new ReplayIssueRow(null, "贷款组", "贷款组", false, 3,
                "贷款组", "3", "BATCH-3", "6210", "交易6210", "", "", "字段", "third",
                "", "", "", "", "", "", "", "003", "", "", "1", "issue-3", "key-3", "0", "", "", null);
        dao.replaceAll(List.of(first, second, blankOptions), IMPORTED_AT);

        ReplayIssueFilterOptions options = dao.options();
        Map<String, Object> stats = dao.stats();

        assertIterableEquals(List.of("公共组", "贷款组"), options.groups());
        assertIterableEquals(List.of("交易级", "字段级"), options.issueLevels());
        assertIterableEquals(List.of("数据差异", "环境问题"), options.issueTypes());
        assertEquals(3L, stats.get("total"));
        assertEquals(2L, stats.get("groupCount"));
        assertEquals(1L, stats.get("sandboxCount"));
        assertEquals(IMPORTED_AT, stats.get("importedAt"));
    }
}
