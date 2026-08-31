package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayIssueDomainServiceTest {
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayIssueDomainService service;
    private long issueId;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "username VARCHAR(50), real_name VARCHAR(50), emp_no VARCHAR(50), email VARCHAR(100), "
                + "phone VARCHAR(50), department VARCHAR(100), status INT, remark VARCHAR(255), "
                + "creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.batchUpdate("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES (?,?,?,?)", List.of(
                new Object[]{"public-editor", "公共编辑人", "100001", 1},
                new Object[]{"platform-editor", "平台编辑人", null, 1},
                new Object[]{"deposit-editor", "存款编辑人", "300001", 1},
                new Object[]{"migration-editor", "迁移编辑人", "400001", 1}));

        ReplayIssueDomainProperties properties = new ReplayIssueDomainProperties();
        LinkedHashMap<String, ReplayIssueDomainProperties.EditorGroup> editors = new LinkedHashMap<>();
        editors.put("公共组", editorGroup("100001"));
        editors.put("平台组", editorGroup("platform-editor"));
        editors.put("存款组", editorGroup("300001"));
        editors.put("迁移组", editorGroup("400001"));
        properties.setEditors(editors);

        dao = new ReplayIssueDao(jdbc);
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "domain")),
                LocalDateTime.of(2026, 8, 31, 9, 0));
        issueId = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                50, 0, null, null, null, null, null, null)).get(0).get("id")).longValue();
        service = new ReplayIssueDomainService(dao, new SysUserDao(jdbc), properties,
                Clock.fixed(Instant.parse("2026-08-31T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void transfersByCurrentDomainPermissionAndShiftsPermissionToTargetDomain() {
        var publicOperator = new ReplayIssueOperator("public-editor", "公共编辑人");
        var platformOperator = new ReplayIssueOperator("platform-editor", "平台编辑人");

        assertEquals(List.of("公共组"), service.permissions(publicOperator).editableDomains());
        var first = service.update(issueId, "平台组", publicOperator);
        assertEquals("平台组", first.issueDomain());
        assertEquals(1, first.transferCount());

        assertThrows(ReplayIssueDomainForbiddenException.class,
                () -> service.update(issueId, "存款组", publicOperator));
        var second = service.update(issueId, "存款组", platformOperator);
        assertEquals(2, second.transferCount());
        assertEquals(List.of("平台组", "公共组"), service.transfers(issueId).items().stream()
                .map(item -> item.fromDomain()).toList());
        assertEquals(2L, dao.countHistory("key-1"));
    }

    @Test
    void sameValueIsIdempotentAndFourthRealTransferIsRejected() {
        var publicOperator = new ReplayIssueOperator("public-editor", "公共编辑人");
        var platformOperator = new ReplayIssueOperator("platform-editor", "平台编辑人");
        var depositOperator = new ReplayIssueOperator("deposit-editor", "存款编辑人");
        var migrationOperator = new ReplayIssueOperator("migration-editor", "迁移编辑人");

        assertEquals(0, service.update(issueId, "公共组", publicOperator).transferCount());
        service.update(issueId, "平台组", publicOperator);
        service.update(issueId, "存款组", platformOperator);
        service.update(issueId, "迁移组", depositOperator);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update(issueId, "贷款组", migrationOperator));
        assertEquals("已经达到 3 次转组上限，无法继续转组", error.getMessage());
    }

    @Test
    void defectRepairDateLocksTransferBeforePermissionCheck() {
        jdbc.update("UPDATE dii_replay_issue SET defect_repair_date='2026-08-31' WHERE id=?", issueId);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update(issueId, "平台组", new ReplayIssueOperator("nobody", "无权限")));

        assertEquals("问题已有缺陷修复日期，不可转组", error.getMessage());
    }

    private static ReplayIssueDomainProperties.EditorGroup editorGroup(String... identities) {
        ReplayIssueDomainProperties.EditorGroup group = new ReplayIssueDomainProperties.EditorGroup();
        group.setEmpNos(List.of(identities));
        return group;
    }
}
