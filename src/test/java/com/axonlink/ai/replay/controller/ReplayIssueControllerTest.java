package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayCoverageDetailRow;
import com.axonlink.ai.replay.dto.ReplayCoverageSummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyRowType;
import com.axonlink.ai.replay.dto.ReplayDailySummaryRow;
import com.axonlink.ai.replay.dto.ReplayDailyWorkbookData;
import com.axonlink.ai.replay.dto.ReplayDailyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailView;
import com.axonlink.ai.replay.dto.ReplayDailyReportMailSendRequest;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOption;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOptionPage;
import com.axonlink.ai.replay.dto.ReplayReportMailBodyPreview;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailSendRequest;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportMailView;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportOptions;
import com.axonlink.ai.replay.dto.ReplayWeeklyReportSnapshot;
import com.axonlink.ai.replay.dto.ReplayInterfaceComparisonRow;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayIssueCompletionStatsDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.replay.service.ReplayIssueEditService;
import com.axonlink.ai.replay.service.ReplayIssueExcelParser;
import com.axonlink.ai.replay.service.ReplayIssueFullRefreshExcelParser;
import com.axonlink.ai.replay.service.ReplayIssueFullRefreshService;
import com.axonlink.ai.replay.service.ReplayIssueImportGate;
import com.axonlink.ai.replay.service.ReplayIssueImportService;
import com.axonlink.ai.replay.service.ReplayIssueMailService;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator;
import com.axonlink.ai.replay.service.ReplayDailyReportWorkbookWriter;
import com.axonlink.ai.replay.service.ReplayIssueDailyReportService;
import com.axonlink.ai.replay.service.ReplayDailyReportMailService;
import com.axonlink.ai.replay.service.ReplayReportMailAttachmentService;
import com.axonlink.ai.replay.service.ReplayReportMailBodyService;
import com.axonlink.ai.replay.service.ReplayReportMailBodyMetricExtractor;
import com.axonlink.ai.replay.service.ReplayWeeklyReportMailService;
import com.axonlink.ai.replay.service.ReplayWeeklyReportService;
import com.axonlink.ai.replay.persistence.ReplayIssueWeeklyTaskDao;
import com.axonlink.ai.replay.service.ReplayIssueWeeklyTaskService;
import com.axonlink.ai.replay.service.ReplayIssueReviewProperties;
import com.axonlink.ai.replay.service.ReplayIssueReviewService;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateProperties;
import com.axonlink.ai.replay.service.ReplayIssuePlanDateService;
import com.axonlink.ai.replay.service.ReplayIssueDomainProperties;
import com.axonlink.ai.replay.service.ReplayIssueDomainService;
import com.axonlink.ai.replay.service.ReplayIssueCompletionStatsService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayIssueControllerTest {

    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayIssueImportService importService;
    private ReplayIssueImportGate importGate;
    private DaoIndexAnalysisProperties properties;
    private UserPrincipalResolver.Resolved resolvedUser;
    private ReplayDailyDataDao dailyDataDao;
    private ReplayIssueDailyReportService dailyReportService;
    private ReplayDailyReportMailService dailyReportMailService;
    private ReplayWeeklyReportService weeklyReportService;
    private ReplayWeeklyReportMailService weeklyReportMailService;
    private ReplayReportMailBodyService reportMailBodyService;
    private ReplayIssueController controller;

    @Test
    void previewsReportMailBodyFromSelectedGeneratedReports() throws Exception {
        mvc.perform(post("/api/ai/parallel-replay/issues/report-mail/body-preview")
                        .contentType("application/json")
                        .content("""
                                {
                                  "currentReport": {
                                    "period": "DAILY",
                                    "endBatchNo": "RPT20260916-01"
                                  },
                                  "generatedReports": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value(containsString("查询交易总交易")));
    }

    @Test
    void returnsConflictWhenReportMailBodyMetricsAreUnavailable() throws Exception {
        when(reportMailBodyService.preview(any())).thenThrow(
                new ReplayReportMailBodyMetricExtractor.BodyMetricUnavailableException(
                        "RPT20260916-01", "覆盖汇总合计不一致"));

        mvc.perform(post("/api/ai/parallel-replay/issues/report-mail/body-preview")
                        .contentType("application/json")
                        .content("""
                                {
                                  "currentReport": {
                                    "period": "DAILY",
                                    "endBatchNo": "RPT20260916-01"
                                  },
                                  "generatedReports": []
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("覆盖汇总合计不一致")));
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128), real_name VARCHAR(128), emp_no VARCHAR(64), email VARCHAR(128), phone VARCHAR(64), department VARCHAR(128), status INT, remark VARCHAR(255), creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.update("INSERT INTO ccbs_ai_sys_user (username, real_name, emp_no, status) VALUES (?,?,?,?)", "sunhy1", "孙海英", "100001", 1);
        jdbc.update("INSERT INTO ccbs_ai_sys_user (username, real_name, emp_no, status) VALUES (?,?,?,?)", "other", "其他人", "200001", 1);
        SysUserDao userDao = new SysUserDao(jdbc);
        importGate = new ReplayIssueImportGate();
        importService = new ReplayIssueImportService(new ReplayIssueExcelParser(), dao, importGate);
        ReplayIssueFullRefreshService fullRefreshService = new ReplayIssueFullRefreshService(
                new ReplayIssueFullRefreshExcelParser(), dao, userDao, importGate);
        properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        ReplayIssueEditService editService = new ReplayIssueEditService(dao, userDao);
        resolvedUser = new UserPrincipalResolver.Resolved("LDAP", "sunhy1", userDao.findByUsername("sunhy1"));
        UserPrincipalResolver resolver = new UserPrincipalResolver() {
            @Override public Resolved resolve(jakarta.servlet.http.HttpServletRequest request) {
                return resolvedUser;
            }
        };
        dailyDataDao = new ReplayDailyDataDao(jdbc);
        dailyReportService = new ReplayIssueDailyReportService(dailyDataDao, dao,
                new ReplayDailyReportCalculator(), new ReplayDailyReportWorkbookWriter(), jdbc);
        dailyReportMailService = mock(ReplayDailyReportMailService.class);
        weeklyReportService = mock(ReplayWeeklyReportService.class);
        weeklyReportMailService = mock(ReplayWeeklyReportMailService.class);
        reportMailBodyService = mock(ReplayReportMailBodyService.class);
        when(reportMailBodyService.preview(any())).thenReturn(new ReplayReportMailBodyPreview(
                "各位领导、老师：\n查询交易总交易10，本轮回放实发交易9，采集交易量100，实发交易量100。"));
        controller = new ReplayIssueController(importService, fullRefreshService, dao,
                properties, editService, resolver, dailyReportService,
                new ReplayIssueWeeklyTaskService(new ReplayIssueWeeklyTaskDao(jdbc)));
        ReplayIssueReviewProperties reviewProperties = new ReplayIssueReviewProperties();
        ReplayIssueReviewProperties.ReviewerGroup publicReviewers = new ReplayIssueReviewProperties.ReviewerGroup();
        publicReviewers.setEmpNos(List.of("100001"));
        reviewProperties.setReviewers(new LinkedHashMap<>(Map.of("公共组", publicReviewers)));
        ReflectionTestUtils.setField(controller, "reviewService",
                new ReplayIssueReviewService(dao, userDao, reviewProperties));
        ReplayIssuePlanDateProperties planDateProperties = new ReplayIssuePlanDateProperties();
        ReplayIssuePlanDateProperties.EditorGroup publicEditors = new ReplayIssuePlanDateProperties.EditorGroup();
        publicEditors.setEmpNos(List.of("100001"));
        planDateProperties.setEditors(new LinkedHashMap<>(Map.of("公共组", publicEditors)));
        ReplayIssuePlanDateProperties.EditorGroup publicAdvancedEditors = new ReplayIssuePlanDateProperties.EditorGroup();
        publicAdvancedEditors.setEmpNos(List.of("100001"));
        planDateProperties.setAdvancedEditors(new LinkedHashMap<>(Map.of("公共组", publicAdvancedEditors)));
        ReflectionTestUtils.setField(controller, "planDateService",
                new ReplayIssuePlanDateService(dao, userDao, new ReplayTransactionPersonDao(jdbc), planDateProperties));
        ReplayIssueDomainProperties domainProperties = new ReplayIssueDomainProperties();
        ReplayIssueDomainProperties.EditorGroup publicDomainEditors = new ReplayIssueDomainProperties.EditorGroup();
        publicDomainEditors.setEmpNos(List.of("100001"));
        domainProperties.setEditors(new LinkedHashMap<>(Map.of("公共组", publicDomainEditors)));
        ReplayIssueDomainProperties.EditorGroup publicAdvancedDomainEditors = new ReplayIssueDomainProperties.EditorGroup();
        publicAdvancedDomainEditors.setEmpNos(List.of("100001"));
        domainProperties.setAdvancedEditors(new LinkedHashMap<>(Map.of("公共组", publicAdvancedDomainEditors)));
        ReflectionTestUtils.setField(controller, "issueDomainService",
                new ReplayIssueDomainService(dao, userDao, domainProperties));
        ReflectionTestUtils.setField(controller, "dailyReportMailService", dailyReportMailService);
        ReflectionTestUtils.setField(controller, "weeklyReportService", weeklyReportService);
        ReflectionTestUtils.setField(controller, "weeklyReportMailService", weeklyReportMailService);
        ReflectionTestUtils.setField(controller, "reportMailBodyService", reportMailBodyService);
        mvc = MockMvcBuilders.standaloneSetup(controller, new ReplayIssueUserController(userDao)).build();
    }

    @Test
    void issueDomainPermissionsPatchAndHistoryAreExposed() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "domain")),
                LocalDateTime.of(2026, 8, 31, 9, 0));
        long issueId = dao.findCurrentByIssueKeyForUpdate("key-1").id();

        mvc.perform(get("/api/ai/parallel-replay/issues/issue-domain-permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editableDomains[0]").value("公共组"))
                .andExpect(jsonPath("$.data.transferLimitBypassDomains[0]").value("公共组"));

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}/issue-domain", issueId)
                        .contentType("application/json")
                        .content("{\"issueDomain\":\"平台组\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueDomain").value("平台组"))
                .andExpect(jsonPath("$.data.transferCount").value(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/issue-domain-transfers", issueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transferCount").value(1))
                .andExpect(jsonPath("$.data.items[0].fromDomain").value("公共组"))
                .andExpect(jsonPath("$.data.items[0].toDomain").value("平台组"));
    }

    @Test
    void planDatePermissionsAndPatchEnforceAuthenticationPermissionAndValidation() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", true, 1, "6208", "planned")),
                LocalDateTime.of(2026, 8, 26, 9, 0));
        long issueId = dao.findCurrentByIssueKeyForUpdate("key-1").id();
        jdbc.update("UPDATE dii_replay_issue SET first_occurrence_date='2026-08-20' WHERE id=?", issueId);

        mvc.perform(get("/api/ai/parallel-replay/issues/plan-date-permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editableGroups[0]").value("公共组"))
                .andExpect(jsonPath("$.data.dateLimitBypassGroups[0]").value("公共组"))
                .andExpect(jsonPath("$.data.editableTransactionCodes.length()").value(0));

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}/planned-completion-date", issueId)
                        .contentType("application/json")
                        .content("{\"plannedCompletionDate\":\"2026-08-26\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plannedCompletionDate").value("2026-08-26"))
                .andExpect(jsonPath("$.data.changeCount").value(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/planned-completion-date-changes", issueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].plannedCompletionDate").value("2026-08-26"))
                .andExpect(jsonPath("$.data.items[0].operatorUsername").value("sunhy1"));

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}/planned-completion-date", issueId)
                        .contentType("application/json")
                        .content("{\"plannedCompletionDate\":\"2026-09-30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plannedCompletionDate").value("2026-09-30"))
                .andExpect(jsonPath("$.data.changeCount").value(2));

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}/planned-completion-date", issueId)
                        .contentType("application/json")
                        .content("{\"plannedCompletionDate\":\"2026-08-32\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("填写日期格式不合法，请按 2026-08-26 格式填写"));

        resolvedUser = new UserPrincipalResolver.Resolved("LDAP", "other", userDao().findByUsername("other"));
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}/planned-completion-date", issueId)
                        .contentType("application/json")
                        .content("{\"plannedCompletionDate\":null}"))
                .andExpect(status().isForbidden());

        resolvedUser = new UserPrincipalResolver.Resolved("LDAP", "tester", userDao().findByUsername("tester"));
        jdbc.update("UPDATE dii_replay_issue SET defect_repair_date='2026-08-27' WHERE id=?", issueId);
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}/planned-completion-date", issueId)
                        .contentType("application/json")
                        .content("{\"plannedCompletionDate\":\"2026-08-28\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问题已有缺陷修复日期，计划验证日期不可修改"));

        resolvedUser = null;
        mvc.perform(get("/api/ai/parallel-replay/issues/plan-date-permissions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/planned-completion-date-changes", issueId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void plannedCompletionStatisticsExposeDatePointsDashboardAndDrillDown() throws Exception {
        dao.replaceAll(List.of(
                        ReplayIssueTestFixtures.row("公共组", false, 1, "6201", "已逾期"),
                        ReplayIssueTestFixtures.row("公共组", false, 2, "6202", "未到期")),
                LocalDateTime.of(2026, 8, 27, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET planned_completion_date='2026-08-22' WHERE issue_key='key-1'");
        jdbc.update("UPDATE dii_replay_issue SET planned_completion_date='2026-08-28' WHERE issue_key='key-2'");
        ReflectionTestUtils.setField(controller, "completionStatsService",
                new ReplayIssueCompletionStatsService(new ReplayIssueCompletionStatsDao(jdbc),
                        Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"))));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion/date-points"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datePoints.length()").value(4))
                .andExpect(jsonPath("$.data.defaultStartDate").value("2026-08-26"))
                .andExpect(jsonPath("$.data.defaultEndDate").value("2026-08-28"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveStartDate").value("2026-08-26"))
                .andExpect(jsonPath("$.data.effectiveEndDate").value("2026-08-28"))
                .andExpect(jsonPath("$.data.summary.plannedTotal").value(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion")
                        .param("startDate", "2026-08-22")
                        .param("endDate", "2026-08-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today").value("2026-08-27"))
                .andExpect(jsonPath("$.data.summary.plannedTotal").value(2))
                .andExpect(jsonPath("$.data.groups[0].groupName").value("公共组"))
                .andExpect(jsonPath("$.data.groups[0].overdueUnfinishedCount").value(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion/issues")
                        .param("startDate", "2026-08-22")
                        .param("endDate", "2026-08-28")
                        .param("groupName", "公共组")
                        .param("category", "OVERDUE_UNFINISHED")
                        .param("limit", "20")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].issueId").value("issue-1"));
    }

    @Test
    void plannedCompletionStatisticsRejectInvalidRangeCategoryAndPaging() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6201", "范围")),
                LocalDateTime.of(2026, 8, 27, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET planned_completion_date='2026-08-22' WHERE issue_key='key-1'");
        ReflectionTestUtils.setField(controller, "completionStatsService",
                new ReplayIssueCompletionStatsService(new ReplayIssueCompletionStatsDao(jdbc),
                        Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"))));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion")
                        .param("startDate", "2026/08/22")
                        .param("endDate", "2026-08-22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("计划验证日期范围不合法"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion/issues")
                        .param("groupName", "公共组")
                        .param("category", "UNKNOWN")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("完成情况分类不合法"));
    }

    @Test
    void supportsReplayTypeForPlannedCompletionEndpoints() throws Exception {
        dao.replaceAll(List.of(
                        ReplayIssueTestFixtures.row("公共组", false, 1, "RPT-ONLY", "rpt"),
                        ReplayIssueTestFixtures.row("公共组", false, 2, "DZ-ONLY", "dz")),
                LocalDateTime.of(2026, 8, 27, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET planned_completion_date='2026-08-27'");
        jdbc.update("DELETE FROM dii_replay_issue_occurrence_batch");
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'RPT20260827-001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='RPT-ONLY'",
                LocalDateTime.of(2026, 8, 27, 9, 0), LocalDateTime.of(2026, 8, 27, 9, 0),
                LocalDateTime.of(2026, 8, 27, 9, 0), LocalDateTime.of(2026, 8, 27, 9, 0));
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'DZ20260827-001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='DZ-ONLY'",
                LocalDateTime.of(2026, 8, 27, 9, 0), LocalDateTime.of(2026, 8, 27, 9, 0),
                LocalDateTime.of(2026, 8, 27, 9, 0), LocalDateTime.of(2026, 8, 27, 9, 0));
        ReflectionTestUtils.setField(controller, "completionStatsService",
                new ReplayIssueCompletionStatsService(new ReplayIssueCompletionStatsDao(jdbc),
                        Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"))));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion/date-points")
                        .param("replayType", "DZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datePoints[1].plannedCount").value(2));
        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion")
                        .param("startDate", "2026-08-27").param("endDate", "2026-08-27")
                        .param("replayType", "QUERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.plannedTotal").value(1));
        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion/issues")
                        .param("startDate", "2026-08-27").param("endDate", "2026-08-27")
                        .param("groupName", "公共组").param("category", "UNFINISHED")
                        .param("replayType", "DZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transactionCode").value("DZ-ONLY"));
        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion/date-points")
                        .param("replayType", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("回放交易类型不合法"));
    }

    @Test
    void plannedCompletionStatisticsUseTheSelectedGroupingForDashboardAndDrillDown() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6201", "所属领域统计")),
                LocalDateTime.of(2026, 8, 27, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_domain='平台组', planned_completion_date='2026-08-22' "
                + "WHERE issue_key='key-1'");
        ReflectionTestUtils.setField(controller, "completionStatsService",
                new ReplayIssueCompletionStatsService(new ReplayIssueCompletionStatsDao(jdbc),
                        Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"))));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion")
                        .param("startDate", "2026-08-22").param("endDate", "2026-08-22")
                        .param("groupBy", "issueDomain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].groupName").value("平台组"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion/issues")
                        .param("startDate", "2026-08-22").param("endDate", "2026-08-22")
                        .param("groupBy", "issueDomain")
                        .param("groupName", "平台组")
                        .param("category", "OVERDUE_UNFINISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].issueId").value("issue-1"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/planned-completion")
                        .param("groupBy", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("统计分组口径仅支持 domain 或 issueDomain"));
    }

    @Test
    void plannedCompletionDatePatchReturnsNotFoundForMissingIssue() throws Exception {
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}/planned-completion-date", 99999)
                        .contentType("application/json")
                        .content("{\"plannedCompletionDate\":\"2026-08-26\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("回放问题不存在"));

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/planned-completion-date-changes", 99999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("回放问题不存在"));
    }

    @Test
    void mailStatusRequiresAuthenticatedCurrentSender() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "mail")),
                LocalDateTime.of(2026, 8, 24, 10, 0));
        long issueId = dao.findCurrentByIssueKeyForUpdate("key-1").id();
        ReplayIssueMailService mailService = mock(ReplayIssueMailService.class);
        when(mailService.status(any(), any())).thenReturn(
                new ReplayIssueMailStatus("UNSENT", null, null, null));
        ReflectionTestUtils.setField(controller, "issueMailService", mailService);
        resolvedUser = null;

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/mail-status", issueId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void mailSendPassesCurrentLoggedInOperatorToMailService() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "mail")),
                LocalDateTime.of(2026, 8, 24, 10, 0));
        long issueId = dao.findCurrentByIssueKeyForUpdate("key-1").id();
        ReplayIssueMailService mailService = mock(ReplayIssueMailService.class);
        ReplayIssueOperator operator = new ReplayIssueOperator("sunhy1", "孙海英");
        when(mailService.requestSend(any(), eq(List.of("recipient@spdbdev.com")), eq(operator)))
                .thenReturn(new ReplayIssueMailStatus("SENDING", null, "recipient@spdbdev.com", null));
        ReflectionTestUtils.setField(controller, "issueMailService", mailService);

        mvc.perform(post("/api/ai/parallel-replay/issues/{id}/mail-send", issueId)
                        .contentType("application/json")
                        .content("{\"recipientEmails\":[\"recipient@spdbdev.com\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENDING"));

        verify(mailService).requestSend(any(), eq(List.of("recipient@spdbdev.com")), eq(operator));
    }

    @Test
    void reviewApproveIsIdempotentAtDocumentedEndpoint() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "review")),
                LocalDateTime.of(2026, 8, 21, 9, 0));
        long issueId = dao.findCurrentByIssueKeyForUpdate("key-1").id();
        jdbc.update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='PENDING' WHERE id=?", issueId);

        mvc.perform(post("/api/ai/parallel-replay/issues/{id}/review/approve", issueId)
                        .contentType("application/json")
                        .content("{\"reason\":\"首次审核原因\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("已审核"))
                .andExpect(jsonPath("$.data.reviewReason").value("首次审核原因"));
        mvc.perform(post("/api/ai/parallel-replay/issues/{id}/review/approve", issueId)
                        .contentType("application/json")
                        .content("{\"reason\":\"首次审核原因\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewerUsername").value("sunhy1"));

        assertEquals(1L, dao.countHistory("key-1"));
    }

    @Test
    void reviewApproveRejectsBlankReason() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "review")),
                LocalDateTime.of(2026, 8, 21, 9, 0));
        long issueId = dao.findCurrentByIssueKeyForUpdate("key-1").id();
        jdbc.update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='PENDING' WHERE id=?", issueId);

        mvc.perform(post("/api/ai/parallel-replay/issues/{id}/review/approve", issueId)
                        .contentType("application/json")
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请填写审核原因"));
    }

    @Test
    void reviewPermissionsExposeTechnologyOwnerTransactionCodes() throws Exception {
        jdbc.update("INSERT INTO dii_replay_transaction_person " +
                        "(domain,old_transaction_code,old_transaction_name,bank_owner,bank_owner_emp_nos,imported_at) " +
                        "VALUES (?,?,?,?,?,?)", "公共组", "6208", "测试交易", "孙海英", "100001",
                LocalDateTime.of(2026, 8, 21, 8, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues/review-permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewableGroups[0]").value("公共组"))
                .andExpect(jsonPath("$.data.reviewableTransactionCodes[0]").value("6208"));
    }

    @Test
    void reviewApproveRequiresSameGroupReviewer() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "review")),
                LocalDateTime.of(2026, 8, 21, 9, 0));
        long issueId = dao.findCurrentByIssueKeyForUpdate("key-1").id();
        jdbc.update("UPDATE dii_replay_issue SET issue_status='无需处理',issue_type='合理差异',review_status='PENDING' WHERE id=?", issueId);
        resolvedUser = new UserPrincipalResolver.Resolved("LDAP", "other",
                new SysUserDao(jdbc).findByUsername("other"));

        mvc.perform(post("/api/ai/parallel-replay/issues/{id}/review/approve", issueId)
                        .contentType("application/json")
                        .content("{\"reason\":\"无权限尝试\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("没有权限，请联系孙海英进行审核"));
    }

    @Test
    void validTokenImportsThenListReturnsRows() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(8));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("limit", "50")
                        .param("offset", "0")
                        .param("sandbox", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[0].is_sandbox").value(1));
    }

    @Test
    void listAcceptsIndependentDeveloperAndBankOwnerFilters() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-A", "first"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-B", "second")),
                LocalDateTime.of(2026, 8, 11, 10, 0));
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)",
                List.of(
                        new Object[] {"贷款组", "T-A", "交易A", "张开发", "刘科技", LocalDateTime.of(2026, 8, 11, 9, 0)},
                        new Object[] {"贷款组", "T-B", "交易B", "张开发", "王科技", LocalDateTime.of(2026, 8, 11, 9, 0)}));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("developer", "张开发")
                        .param("bankOwner", "刘科技"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("T-A"));
    }

    @Test
    void listHeaderOptionsAndExportAcceptIssueIdGroupAndSandboxFilters() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-1", "public-normal"),
                ReplayIssueTestFixtures.row("公共组", true, 2, "T-2", "public-sandbox"),
                ReplayIssueTestFixtures.row("贷款组", true, 3, "T-3", "loan-sandbox")),
                LocalDateTime.of(2026, 8, 24, 9, 0));
        jdbc.batchUpdate("UPDATE dii_replay_issue SET issue_id=? WHERE transaction_code=?", List.of(
                new Object[]{"ISSUE-ALPHA-001", "T-1"},
                new Object[]{"ISSUE-ALPHA-002", "T-2"},
                new Object[]{"ISSUE-BETA-003", "T-3"}));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("issueId", "ALPHA")
                        .param("groupNames", "公共组", "贷款组")
                        .param("sandboxes", "是"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("T-2"));

        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "sandbox")
                        .param("issueId", "ALPHA")
                        .param("groupNames", "公共组"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("否"))
                .andExpect(jsonPath("$.data[1]").value("是"));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("issueId", "ALPHA")
                        .param("groupNames", "公共组")
                        .param("sandboxes", "是"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertEquals(2, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("T-2", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void plannedCompletionDateHeaderFilterAppliesToListOptionsAndExport() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-EMPTY", "empty"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-26", "date-26"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-27", "date-27")),
                LocalDateTime.of(2026, 8, 26, 9, 0));
        jdbc.batchUpdate("UPDATE dii_replay_issue SET planned_completion_date=? WHERE transaction_code=?", List.of(
                new Object[]{java.time.LocalDate.of(2026, 8, 26), "T-26"},
                new Object[]{java.time.LocalDate.of(2026, 8, 27), "T-27"}));

        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "plannedCompletionDate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("空"))
                .andExpect(jsonPath("$.data[1]").value("2026-08-26"))
                .andExpect(jsonPath("$.data[2]").value("2026-08-27"));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("plannedCompletionDates", "空", "2026-08-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("plannedCompletionDates", "2026-08-27"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertEquals(2, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("T-27", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void issueDomainHeaderFilterEndpointUsesDomainFallbackCandidates() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-FALLBACK", "fallback"),
                ReplayIssueTestFixtures.row("存款组", false, 2, "T-PLATFORM", "platform")),
                LocalDateTime.of(2026, 8, 31, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_domain='' WHERE transaction_code='T-FALLBACK'");
        jdbc.update("UPDATE dii_replay_issue SET issue_domain='平台组' WHERE transaction_code='T-PLATFORM'");

        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-option-counts")
                        .param("field", "issueDomain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(2))
                .andExpect(jsonPath("$.data.matchedIssueCount").value(2))
                .andExpect(jsonPath("$.data.items[0].value").value("公共组"))
                .andExpect(jsonPath("$.data.items[1].value").value("平台组"));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("issueDomains", "平台组"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("T-PLATFORM"));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("issueDomains", "公共组", "平台组"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertEquals(3, workbook.getSheetAt(0).getPhysicalNumberOfRows());
        }
    }

    @Test
    void fourDetailHeaderFiltersSupportCandidateSearchCompositionAndExport() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-EMPTY", "empty"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-100", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 3, "T-200", "second")),
                LocalDateTime.of(2026, 8, 27, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_id='',serial_no='',global_serial_no='',defect_repair_date=NULL WHERE transaction_code='T-EMPTY'");
        jdbc.update("UPDATE dii_replay_issue SET issue_id='ISS-100',serial_no='SER-AAA-100',global_serial_no='GS-100',defect_repair_date='2026-08-20' WHERE transaction_code='T-100'");
        jdbc.update("UPDATE dii_replay_issue SET issue_id='ISS-200',serial_no='SER-BBB-200',global_serial_no='GS-200',defect_repair_date='2026-08-21' WHERE transaction_code='T-200'");

        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "issueId")
                        .param("keyword", "SS-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("ISS-200"));
        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "serialNo")
                        .param("keyword", "BBB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("SER-BBB-200"));
        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "globalSerialNo")
                        .param("keyword", "GS-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("GS-200"));
        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "defectRepairDate")
                        .param("keyword", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("2026-08-20"))
                .andExpect(jsonPath("$.data[1]").value("2026-08-21"));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("issueIds", "ISS-100", "ISS-200")
                        .param("serialNos", "SER-BBB-200")
                        .param("globalSerialNos", "GS-200")
                        .param("defectRepairDates", "2026-08-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("T-200"));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("issueIds", "ISS-100", "ISS-200")
                        .param("serialNos", "SER-BBB-200")
                        .param("globalSerialNos", "GS-200")
                        .param("defectRepairDates", "2026-08-21"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertEquals(2, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("T-200", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void fourLongTextHeaderFiltersSupportCandidateSearchCompositionAndExport() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-100", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-200", "second")),
                LocalDateTime.of(2026, 8, 30, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='账户查询',field_name='custName',issue_description='新老核心客户名称不一致',issue_key='TC001|custName' WHERE transaction_code='T-100'");
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='账户维护',field_name='accountStatus',issue_description='新老核心账户状态不一致',issue_key='TC002|accountStatus' WHERE transaction_code='T-200'");

        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "issueDescription")
                        .param("keyword", "账户状态")
                        .param("transactionNames", "账户维护"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("新老核心账户状态不一致"));

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("transactionNames", "账户查询", "账户维护")
                        .param("fieldNames", "accountStatus")
                        .param("issueDescriptions", "新老核心账户状态不一致")
                        .param("issueKeys", "TC002|accountStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("T-200"));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("transactionNames", "账户查询", "账户维护")
                        .param("fieldNames", "accountStatus")
                        .param("issueDescriptions", "新老核心账户状态不一致")
                        .param("issueKeys", "TC002|accountStatus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertEquals(2, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("T-200", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void postSearchEndpointsAcceptLongIssueDescriptionFiltersInJson() throws Exception {
        String longDescription = "超长问题描述".repeat(2000);

        mvc.perform(post("/api/ai/parallel-replay/issues")
                        .contentType("application/json")
                        .content("{\"query\":{\"limit\":50,\"offset\":0,\"issueDescriptions\":[\""
                                + longDescription + "\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mvc.perform(post("/api/ai/parallel-replay/issues/header-filter-option-counts")
                        .contentType("application/json")
                        .content("{\"field\":\"issueDescription\",\"keyword\":\"错误码\",\"query\":{"
                                + "\"issueDescriptions\":[\"" + longDescription + "\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(0))
                .andExpect(jsonPath("$.data.matchedIssueCount").value(0));
    }

    @Test
    void countedHeaderFilterEndpointPreservesLegacyCandidateResponse() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-1", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-2", "second"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "T-3", "third")),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='账户查询' WHERE transaction_code IN ('T-1','T-2')");
        jdbc.update("UPDATE dii_replay_issue SET transaction_name='贷款查询' WHERE transaction_code='T-3'");

        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-option-counts")
                        .param("field", "transactionName")
                        .param("keyword", "账户")
                        .param("groupNames", "公共组", "贷款组"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.matchedIssueCount").value(2))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.items[0].value").value("账户查询"))
                .andExpect(jsonPath("$.data.items[0].count").value(2));

        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-options")
                        .param("field", "transactionName")
                        .param("keyword", "账户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("账户查询"));
    }

    @Test
    void affectedTransactionCountOrderAppliesToListAndExport() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-2", "two"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-10", "ten")),
                LocalDateTime.of(2026, 8, 30, 11, 0));
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='2' WHERE transaction_code='T-2'");
        jdbc.update("UPDATE dii_replay_issue SET affected_transaction_count='10' WHERE transaction_code='T-10'");

        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("affectedTransactionCountOrder", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("T-10"))
                .andExpect(jsonPath("$.data.items[0].affected_transaction_count").value("10"));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("affectedTransactionCountOrder", "DESC"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (int index = 0; index < headerRow.getLastCellNum(); index++) {
                headers.add(headerRow.getCell(index).getStringCellValue());
            }
            assertFalse(headers.contains("上次出现日期"));
            assertEquals("出现笔数", headerRow.getCell(25).getStringCellValue());
            assertEquals("10", sheet.getRow(1).getCell(25).getStringCellValue());
        }
    }

    @Test
    void invalidAffectedTransactionCountOrderIsRejected() throws Exception {
        mvc.perform(get("/api/ai/parallel-replay/issues")
                        .param("affectedTransactionCountOrder", "SIDEWAYS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weeklyTaskConfigurationCanBeReadReplacedAndClearedWithToken() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "T-A", "first"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "T-B", "second")),
                LocalDateTime.of(2026, 8, 19, 10, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchNames.length()").value(0))
                .andExpect(jsonPath("$.data.availableBatchNames[0]").value("RPT20260820-142055-0001"));

        mvc.perform(put("/api/ai/parallel-replay/issues/weekly-task")
                        .header("X-DII-Trigger-Token", "secret")
                        .contentType("application/json")
                        .content("{\"batchNames\":[\"RPT20260820-142055-0002\",\"RPT20260820-142055-0001\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchNames[0]").value("RPT20260820-142055-0001"))
                .andExpect(jsonPath("$.data.issueCount").value(2));

        mvc.perform(get("/api/ai/parallel-replay/issues").param("weeklyTask", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].weekly_task").value(true));

        mvc.perform(put("/api/ai/parallel-replay/issues/weekly-task")
                        .header("X-DII-Trigger-Token", "secret")
                        .contentType("application/json")
                        .content("{\"batchNames\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueCount").value(0));
    }

    @Test
    void weeklyTaskReplacementRejectsWrongTokenAndUnknownBatchWithoutChangingCurrentSet() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "T-A", "first")),
                LocalDateTime.of(2026, 8, 19, 10, 0));
        jdbc.update("INSERT INTO dii_replay_weekly_task_batch(batch_name) VALUES ('BATCH-1')");

        mvc.perform(put("/api/ai/parallel-replay/issues/weekly-task")
                        .header("X-DII-Trigger-Token", "wrong")
                        .contentType("application/json")
                        .content("{\"batchNames\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/ai/parallel-replay/issues/weekly-task")
                        .header("X-DII-Trigger-Token", "secret")
                        .contentType("application/json")
                        .content("{\"batchNames\":[\"UNKNOWN\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("出现批次不存在：UNKNOWN"));
        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-task"))
                .andExpect(jsonPath("$.data.batchNames[0]").value("BATCH-1"));
    }

    @Test
    void weeklyTaskReplacementUsesPriorityTaskWordingOnUnexpectedFailure() throws Exception {
        ReplayIssueWeeklyTaskService failingService = mock(ReplayIssueWeeklyTaskService.class);
        when(failingService.replace(any())).thenThrow(new IllegalStateException("database unavailable"));
        ReflectionTestUtils.setField(controller, "weeklyTaskService", failingService);

        mvc.perform(put("/api/ai/parallel-replay/issues/weekly-task")
                        .header("X-DII-Trigger-Token", "secret")
                        .contentType("application/json")
                        .content("{\"batchNames\":[\"BATCH-1\"]}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("优先任务配置失败，原配置未改变"));
    }

    @Test
    void exportsAllRowsMatchingQueryFilters() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,bank_owner,imported_at) VALUES (?,?,?,?,?,?)",
                "公共组", "6208", "交易6208", "张开发", "刘科技", LocalDateTime.of(2026, 8, 11, 9, 0));
        long issueId = jdbc.queryForObject("SELECT id FROM dii_replay_issue WHERE group_name='公共组' AND is_sandbox=0",
                Long.class);
        dao.updatePlannedCompletionDate(issueId, LocalDate.of(2026, 8, 26));
        jdbc.update("UPDATE dii_replay_issue SET first_occurrence_date=?, last_occurrence_date=?, cooperation_person_username=?, cooperation_person_real_name=? WHERE id=?",
                "2026-07-28 00:00:00.0", "2026-07-31 00:00:00.0", "sunhy1", "孙海英", issueId);

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export")
                        .param("groupName", "公共组")
                        .param("sandbox", "false")
                        .param("issueStatus", "新建"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(body.length > 100, "导出文件应包含 Excel 内容");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(0);
            var dataRow = sheet.getRow(1);
            List<String> headers = new ArrayList<>();
            for (int index = 0; index < headerRow.getLastCellNum(); index++) {
                headers.add(headerRow.getCell(index).getStringCellValue());
            }
            assertEquals(2, sheet.getPhysicalNumberOfRows());
            assertFalse(headers.contains("批次"));
            assertFalse(headers.contains("导入时间"));
            assertFalse(headers.contains("登记时间"));
            assertFalse(headers.contains("历史出现次数"));
            assertFalse(headers.contains("上次出现日期"));
            assertEquals(headers.indexOf("问题类型") + 1, headers.indexOf("需协同人"));
            assertEquals(headers.indexOf("问题描述") + 1, headers.indexOf("优先任务"));
            assertEquals(headers.indexOf("优先任务") + 1, headers.indexOf("领域"));
            assertEquals(headers.indexOf("领域") + 1, headers.indexOf("问题所属领域"));
            assertEquals(headers.indexOf("问题所属领域") + 1, headers.indexOf("计划验证日期"));
            assertFalse(headers.contains("转组次数"));
            assertEquals("-", dataRow.getCell(headers.indexOf("优先任务")).getStringCellValue());
            assertEquals("公共组", dataRow.getCell(headers.indexOf("领域")).getStringCellValue());
            assertEquals("公共组", dataRow.getCell(headers.indexOf("问题所属领域")).getStringCellValue());
            assertEquals("否", dataRow.getCell(headers.indexOf("是否沙箱")).getStringCellValue());
            assertEquals("2026-08-26", dataRow.getCell(headers.indexOf("计划验证日期")).getStringCellValue());
            assertEquals("张开发", dataRow.getCell(headers.indexOf("开发负责人")).getStringCellValue());
            assertEquals("刘科技", dataRow.getCell(headers.indexOf("科技负责人")).getStringCellValue());
            assertEquals("孙海英(sunhy1)", dataRow.getCell(headers.indexOf("需协同人")).getStringCellValue());
            assertEquals("2026-07-28", dataRow.getCell(headers.indexOf("首次出现日期")).getStringCellValue());
            assertTrue(!dataRow.getCell(headers.indexOf("出现批次")).getStringCellValue().isBlank());
        }
    }

    @Test
    void exportPlacesPriorityAndDomainsBetweenDescriptionAndPlanDate() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "优先任务表头")),
                LocalDateTime.of(2026, 8, 27, 9, 0));

        byte[] body = mvc.perform(get("/api/ai/parallel-replay/issues/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            var headerRow = workbook.getSheetAt(0).getRow(0);
            List<String> headers = new ArrayList<>();
            for (int index = 0; index < headerRow.getLastCellNum(); index++) {
                headers.add(headerRow.getCell(index).getStringCellValue());
            }
            assertEquals("issue_id", headers.get(0));
            assertEquals(headers.indexOf("问题描述") + 1, headers.indexOf("优先任务"));
            assertEquals(headers.indexOf("优先任务") + 1, headers.indexOf("领域"));
            assertEquals(headers.indexOf("领域") + 1, headers.indexOf("问题所属领域"));
            assertEquals(headers.indexOf("问题所属领域") + 1, headers.indexOf("计划验证日期"));
            assertTrue(headers.contains("计划验证日期"));
            assertFalse(headers.contains("计划完成日期"));
        }
    }

    @Test
    void listsDatabaseBackedDailyReportBatches() throws Exception {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch("DZ20260901-01", LocalDateTime.of(2026, 9, 1, 10, 0), "DZ-FIRST");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "SELECTED");
        dailyDataDao.saveReportSnapshot(new ReplayDailyReportSnapshot("RPT20260902-01", "日报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1}, 1L,
                LocalDateTime.of(2026, 9, 2, 10, 30)));

        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].batchNo").value("RPT20260902-01"))
                .andExpect(jsonPath("$.data[0].family").value("RPT"))
                .andExpect(jsonPath("$.data[0].importedAt").exists())
                .andExpect(jsonPath("$.data[0].previousBatchNo").value("RPT20260901-01"))
                .andExpect(jsonPath("$.data[0].canGenerate").value(true))
                .andExpect(jsonPath("$.data[0].generated").value(true))
                .andExpect(jsonPath("$.data[0].generatedAt").value("2026-09-02T10:30:00"))
                .andExpect(jsonPath("$.data[1].batchNo").value("DZ20260901-01"))
                .andExpect(jsonPath("$.data[1].canGenerate").value(false))
                .andExpect(jsonPath("$.data[1].generated").value(false))
                .andExpect(jsonPath("$.data[1].generatedAt").value(nullValue()));
    }

    @Test
    void generatesDailyReportWithBusinessTypeAndBatchDateFilename() throws Exception {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "PREVIOUS");
        seedDailyBatch("RPT20260902-01", LocalDateTime.of(2026, 9, 2, 9, 0), "SELECTED");

        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report")
                        .param("batchNo", "RPT20260902-01"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        containsString("%E6%9F%A5%E8%AF%A2%E6%97%A5%E6%8A%A5-20260902.xlsx")));
    }

    @Test
    void dailyReportReturnsBadRequestForMalformedBatch() throws Exception {
        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report")
                        .param("batchNo", "BATCH-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("批次号格式错误"));
    }

    @Test
    void dailyReportReturnsNotFoundForMissingBatch() throws Exception {
        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report")
                        .param("batchNo", "RPT20260909-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("批次数据不存在"));
    }

    @Test
    void dailyReportMailConfigReturnsFixedRecipientsAndStatus() throws Exception {
        when(dailyReportMailService.configuration("RPT20260908-01"))
                .thenReturn(new ReplayDailyReportMailView("RPT20260908-01",
                        "对公分布式核心回放问题日报-20260908", List.of("to@example.com"),
                        List.of("cc@example.com"), "默认正文", "SENT", LocalDateTime.of(2026, 9, 8, 10, 30), null));

        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report/mail-config")
                        .param("batchNo", "RPT20260908-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("对公分布式核心回放问题日报-20260908"))
                .andExpect(jsonPath("$.data.toEmails[0]").value("to@example.com"))
                .andExpect(jsonPath("$.data.body").value("默认正文"))
                .andExpect(jsonPath("$.data.status").value("SENT"));
    }

    @Test
    void dailyReportMailSendRequiresTokenAndReturnsLatestStatus() throws Exception {
        ReplayDailyReportMailView sent = new ReplayDailyReportMailView("RPT20260908-01",
                "对公分布式核心回放问题日报-20260908", List.of("to@example.com"),
                List.of(), "请查收", "SENT", LocalDateTime.of(2026, 9, 8, 10, 30), null);
        ReplayDailyReportMailSendRequest request = new ReplayDailyReportMailSendRequest(
                "RPT20260908-01", "自定义标题", List.of("to@example.com"), List.of(), "请查收",
                List.of("DZ20260907-01"));
        when(dailyReportMailService.send(eq(request), anyList()))
                .thenReturn(sent);
        MockMultipartFile mail = new MockMultipartFile("mail", "", "application/json",
                "{\"batchNo\":\"RPT20260908-01\",\"subject\":\"自定义标题\",\"toEmails\":[\"to@example.com\"],\"ccEmails\":[],\"body\":\"请查收\",\"reportBatchNos\":[\"DZ20260907-01\"]}".getBytes());
        MockMultipartFile local = new MockMultipartFile("files", "补充.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2});

        mvc.perform(multipart("/api/ai/parallel-replay/issues/daily-report/mail-send")
                        .file(mail).file(local).header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/daily-report/mail-send")
                        .file(mail).file(local).header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));
        verify(dailyReportMailService).send(eq(request), eq(List.of(local)));
    }

    @Test
    void dailyReportAttachmentOptionsArePagedThroughService() throws Exception {
        ReplayIssueDailyReportService mockedService = mock(ReplayIssueDailyReportService.class);
        ReplayReportAttachmentOptionPage page = new ReplayReportAttachmentOptionPage(List.of(
                new ReplayReportAttachmentOption("DZ20260908-01", "DZ", "DZ日报.xlsx", 2048,
                        LocalDateTime.of(2026, 9, 8, 10, 0))), 1, 10, 21);
        when(mockedService.searchAttachmentOptions("日报", "DZ", 1, 10)).thenReturn(page);
        ReflectionTestUtils.setField(controller, "dailyReportService", mockedService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report/attachment-options")
                        .param("keyword", "日报").param("family", "DZ")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].batchNo").value("DZ20260908-01"))
                .andExpect(jsonPath("$.data.total").value(21));
        verify(mockedService).searchAttachmentOptions("日报", "DZ", 1, 10);
    }

    @Test
    void dailyReportMailMapsBusinessFailures() throws Exception {
        when(dailyReportMailService.configuration("RPT20260908-01"))
                .thenThrow(new ReplayDailyReportMailService.SnapshotNotFoundException());
        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report/mail-config")
                        .param("batchNo", "RPT20260908-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("日报尚未生成"));

        when(dailyReportMailService.configuration("RPT20260909-01"))
                .thenThrow(new ReplayDailyReportMailService.ConfigurationException());
        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report/mail-config")
                        .param("batchNo", "RPT20260909-01"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("日报邮件配置不完整"));

        when(dailyReportMailService.send(any(ReplayDailyReportMailSendRequest.class), anyList()))
                .thenThrow(new ReplayDailyReportMailService.MailSendException(new IllegalStateException("SMTP")));
        MockMultipartFile mail = new MockMultipartFile("mail", "", "application/json",
                "{\"batchNo\":\"RPT20260910-01\",\"subject\":\"标题\",\"toEmails\":[\"to@example.com\"],\"ccEmails\":[],\"body\":\"正文\"}".getBytes());
        mvc.perform(multipart("/api/ai/parallel-replay/issues/daily-report/mail-send")
                        .file(mail).header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("邮件发送失败"));

        when(dailyReportMailService.send(any(ReplayDailyReportMailSendRequest.class), anyList()))
                .thenThrow(new ReplayReportMailAttachmentService.AttachmentTooLargeException("超大.xlsx"));
        mvc.perform(multipart("/api/ai/parallel-replay/issues/daily-report/mail-send")
                        .file(mail).header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("单个附件不能超过20MB：超大.xlsx"));
    }

    @Test
    void weeklyReportOptionsAndDownloadAreExposed() throws Exception {
        when(weeklyReportService.options()).thenReturn(new ReplayWeeklyReportOptions(List.of(), List.of()));
        byte[] content = new byte[]{1, 2, 3};
        when(weeklyReportService.generate("RPT20260901-01", "RPT20260908-01"))
                .thenReturn(new ReplayWeeklyReportSnapshot(
                        "RPT20260901-01", "RPT20260908-01", "RPT20260908-01周报.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        content, content.length, LocalDateTime.of(2026, 9, 8, 10, 0)));

        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-report/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyBatches").isArray())
                .andExpect(jsonPath("$.data.weeklyReports").isArray());

        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-report")
                        .param("startBatchNo", "RPT20260901-01")
                        .param("endBatchNo", "RPT20260908-01"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content))
                .andExpect(header().string("Content-Disposition",
                        containsString("%E6%9F%A5%E8%AF%A2%E5%91%A8%E6%8A%A5%2820260901-20260908%29.xlsx")));
    }

    @Test
    void weeklyReportMailConfigAndSendAreExposed() throws Exception {
        ReplayWeeklyReportMailView view = new ReplayWeeklyReportMailView(
                "RPT20260901-01", "RPT20260908-01",
                "对公分布式核心回放问题周报-20260908", List.of("to@example.com"),
                List.of("cc@example.com"), "默认正文", "SENT",
                LocalDateTime.of(2026, 9, 8, 10, 30), null);
        when(weeklyReportMailService.configuration("RPT20260901-01", "RPT20260908-01"))
                .thenReturn(view);
        ReplayWeeklyReportMailSendRequest request = new ReplayWeeklyReportMailSendRequest(
                "RPT20260901-01", "RPT20260908-01", "自定义标题",
                List.of("to@example.com"), List.of(), "请查收");
        when(weeklyReportMailService.send(eq(request), anyList())).thenReturn(view);

        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-report/mail-config")
                        .param("startBatchNo", "RPT20260901-01")
                        .param("endBatchNo", "RPT20260908-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("对公分布式核心回放问题周报-20260908"));

        MockMultipartFile mail = new MockMultipartFile("mail", "", "application/json",
                "{\"startBatchNo\":\"RPT20260901-01\",\"endBatchNo\":\"RPT20260908-01\",\"subject\":\"自定义标题\",\"toEmails\":[\"to@example.com\"],\"ccEmails\":[],\"body\":\"请查收\",\"reportBatchNos\":[]}".getBytes());
        mvc.perform(multipart("/api/ai/parallel-replay/issues/weekly-report/mail-send")
                        .file(mail).header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));
        verify(weeklyReportMailService).send(request, List.of());
    }

    @Test
    void dailyReportRegenerationRequiresTokenAndReturnsWorkbook() throws Exception {
        ReplayIssueDailyReportService mockedService = mock(ReplayIssueDailyReportService.class);
        when(mockedService.regenerate("RPT20260908-01")).thenReturn(new byte[]{7, 8, 9});
        ReflectionTestUtils.setField(controller, "dailyReportService", mockedService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/ai/parallel-replay/issues/daily-report/regenerate")
                        .param("batchNo", "RPT20260908-01")
                        .header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));

        mvc.perform(post("/api/ai/parallel-replay/issues/daily-report/regenerate")
                        .param("batchNo", "RPT20260908-01")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{7, 8, 9}))
                .andExpect(header().string("Content-Disposition",
                        containsString("%E6%9F%A5%E8%AF%A2%E6%97%A5%E6%8A%A5-20260908.xlsx")));
        verify(mockedService).regenerate("RPT20260908-01");
    }

    @Test
    void weeklyReportRegenerationRequiresTokenAndMapsMissingSnapshot() throws Exception {
        ReplayWeeklyReportSnapshot snapshot = new ReplayWeeklyReportSnapshot(
                "RPT20260901-01", "RPT20260908-01", "RPT20260908-01周报.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{4, 5, 6}, 3, LocalDateTime.of(2026, 9, 9, 10, 0));
        when(weeklyReportService.regenerate("RPT20260901-01", "RPT20260908-01"))
                .thenReturn(snapshot);

        mvc.perform(post("/api/ai/parallel-replay/issues/weekly-report/regenerate")
                        .param("startBatchNo", "RPT20260901-01")
                        .param("endBatchNo", "RPT20260908-01")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{4, 5, 6}))
                .andExpect(header().string("Content-Disposition",
                        containsString("%E6%9F%A5%E8%AF%A2%E5%91%A8%E6%8A%A5%2820260901-20260908%29.xlsx")));

        when(weeklyReportService.regenerate("RPT20260902-01", "RPT20260909-01"))
                .thenThrow(new ReplayWeeklyReportService.SnapshotNotFoundException());
        mvc.perform(post("/api/ai/parallel-replay/issues/weekly-report/regenerate")
                        .param("startBatchNo", "RPT20260902-01")
                        .param("endBatchNo", "RPT20260909-01")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("周报尚未生成"));
    }

    @Test
    void weeklyReportMapsRangeSnapshotTokenAndSmtpFailures() throws Exception {
        when(weeklyReportService.generate("RPT20260908-01", "RPT20260901-01"))
                .thenThrow(new ReplayWeeklyReportService.InvalidRangeException());
        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-report")
                        .param("startBatchNo", "RPT20260908-01")
                        .param("endBatchNo", "RPT20260901-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("周报起止批次范围错误"));

        when(weeklyReportService.generate("RPT20260903-01", "RPT20260908-01"))
                .thenThrow(new ReplayWeeklyReportService.EndBatchAlreadyGeneratedException());
        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-report")
                        .param("startBatchNo", "RPT20260903-01")
                        .param("endBatchNo", "RPT20260908-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("结束批次周报已生成"));

        when(weeklyReportMailService.configuration("RPT20260901-01", "RPT20260908-01"))
                .thenThrow(new ReplayWeeklyReportMailService.SnapshotNotFoundException());
        mvc.perform(get("/api/ai/parallel-replay/issues/weekly-report/mail-config")
                        .param("startBatchNo", "RPT20260901-01")
                        .param("endBatchNo", "RPT20260908-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("周报尚未生成"));

        MockMultipartFile tokenMail = new MockMultipartFile("mail", "", "application/json",
                "{\"startBatchNo\":\"RPT20260901-01\",\"endBatchNo\":\"RPT20260908-01\"}".getBytes());
        mvc.perform(multipart("/api/ai/parallel-replay/issues/weekly-report/mail-send")
                        .file(tokenMail).header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));

        ReplayWeeklyReportMailSendRequest request = new ReplayWeeklyReportMailSendRequest(
                "RPT20260901-01", "RPT20260908-01", "标题",
                List.of("to@example.com"), List.of(), "正文");
        when(weeklyReportMailService.send(eq(request), anyList()))
                .thenThrow(new ReplayWeeklyReportMailService.MailSendException(new IllegalStateException("SMTP")));
        MockMultipartFile mail = new MockMultipartFile("mail", "", "application/json",
                "{\"startBatchNo\":\"RPT20260901-01\",\"endBatchNo\":\"RPT20260908-01\",\"subject\":\"标题\",\"toEmails\":[\"to@example.com\"],\"ccEmails\":[],\"body\":\"正文\",\"reportBatchNos\":[]}".getBytes());
        mvc.perform(multipart("/api/ai/parallel-replay/issues/weekly-report/mail-send")
                        .file(mail).header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("邮件发送失败"));
    }

    @Test
    void dailyReportReturnsConflictWithoutPreviousBatch() throws Exception {
        seedDailyBatch("RPT20260901-01", LocalDateTime.of(2026, 9, 1, 9, 0), "FIRST");

        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report")
                        .param("batchNo", "RPT20260901-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("没有上批次数据"));
    }

    @Test
    void dailyReportReturnsUnifiedMessageForUnexpectedFailure() throws Exception {
        ReplayIssueDailyReportService failingService = mock(ReplayIssueDailyReportService.class);
        when(failingService.generate("RPT20260902-01"))
                .thenThrow(new IllegalStateException("sensitive database detail"));
        ReflectionTestUtils.setField(controller, "dailyReportService", failingService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report")
                        .param("batchNo", "RPT20260902-01"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("日报生成失败"))
                .andExpect(content().string(not(containsString("sensitive database detail"))));
    }

    @Test
    void dailyReportTreatsDownstreamIllegalArgumentAsUnexpectedFailure() throws Exception {
        ReplayIssueDailyReportService failingService = mock(ReplayIssueDailyReportService.class);
        when(failingService.generate("RPT20260902-01"))
                .thenThrow(new IllegalArgumentException("invalid persisted row type"));
        ReflectionTestUtils.setField(controller, "dailyReportService", failingService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/ai/parallel-replay/issues/daily-report")
                        .param("batchNo", "RPT20260902-01"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("日报生成失败"))
                .andExpect(content().string(not(containsString("invalid persisted row type"))));
    }

    @Test
    void fullRefreshRequiresTokenAndExplicitConfirmation() throws Exception {
        MockMultipartFile file = validFullRefreshWorkbook();
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(file).param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(file).param("confirm", "wrong")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("FULL_REFRESH")));
    }

    @Test
    void fullRefreshAcceptsTokenOnlyAuthentication() throws Exception {
        resolvedUser = new UserPrincipalResolver.Resolved("UNKNOWN", "dii-token", null);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());

        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                10, 0, null, null, null, null, null)).get(0).get("id")).longValue();
        assertEquals("dii-token", dao.findHistoryByIssueId(id, 10).get(0).operatorUsername());
    }

    @Test
    void fullRefreshUsesSystemOperatorWhenSecurityIsDisabled() throws Exception {
        resolvedUser = new UserPrincipalResolver.Resolved("ANONYMOUS", null, null);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());

        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                10, 0, null, null, null, null, null)).get(0).get("id")).longValue();
        assertEquals("SYSTEM", dao.findHistoryByIssueId(id, 10).get(0).operatorUsername());
    }

    @Test
    void fullRefreshRejectsMissingFileWithSharedBadRequestEnvelope() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件为空"));
    }

    @Test
    void fullRefreshImportsOnlyFirstSheetAndRecordsLoggedInOperator() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(1))
                .andExpect(jsonPath("$.data.rowsBySheet.0731").value(1))
                .andExpect(jsonPath("$.data.rowsBySheet.0803").doesNotExist());

        List<Map<String, Object>> rows = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                10, 0, null, null, null, null, null));
        assertEquals(1, rows.size());
        long id = ((Number) rows.get(0).get("id")).longValue();
        assertEquals("sunhy1", dao.findHistoryByIssueId(id, 10).get(0).operatorUsername());
    }

    @Test
    void fullRefreshReturnsConflictWhenAnyImportIsRunning() throws Exception {
        ReflectionTestUtils.setField(importGate, "permit", new Semaphore(0));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已有导入任务正在执行"));
    }

    @Test
    void validLegacyXlsImportsAllTargetSheets() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(validLegacyWorkbook())
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(8));
    }

    @Test
    void configuredTokenRejectsMissingAndWrongValues() throws Exception {
        MockMultipartFile file = ReplayIssueTestFixtures.validWorkbook(1);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(file)
                        .header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void blankConfiguredTokenDisablesAuthentication() throws Exception {
        properties.getBatchTrigger().setToken("  ");

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(8));
    }

    @Test
    void blankFileReturnsBadRequest() throws Exception {
        MockMultipartFile blank = new MockMultipartFile("file", "replay-issues.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(blank)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void oversizedXlsxReturnsBadRequestInSharedEnvelope() throws Exception {
        MockMultipartFile oversized = new MockMultipartFile("file", "replay-issues.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[50 * 1024 * 1024 + 1]);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(oversized)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("文件不能超过 50MB"));
    }

    @Test
    void unsupportedExtensionReturnsBadRequest() throws Exception {
        MockMultipartFile csv = new MockMultipartFile("file", "replay-issues.csv", "text/csv",
                new byte[] {1});

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(csv)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void importRejectsUnknownReplayType() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .param("replayType", "OTHER")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("未知回放类型：OTHER"));
    }

    @Test
    void importWithoutDailyContextOrIssueRowsReturnsBadRequest() throws Exception {
        MockMultipartFile missingSheets = ReplayIssueTestFixtures.workbook(
                Map.of("公共组", List.of()), ReplayIssueTestFixtures.HEADERS, false);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(missingSheets)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("没有可导入数据")));
    }

    @Test
    void concurrentImportReturnsConflict() throws Exception {
        ReflectionTestUtils.setField(importGate, "permit", new Semaphore(0));

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void daoErrorReturnsInternalServerErrorWithoutExceptionLeakage() throws Exception {
        jdbc.execute("DROP TABLE dii_replay_issue");

        mvc.perform(get("/api/ai/parallel-replay/issues/stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("请求失败"))
                .andExpect(content().string(not(containsString("DII_REPLAY_ISSUE"))));
    }

    @Test
    void optionsAndStatsDescribeImportedSnapshot() throws Exception {
        importService.importFile(ReplayIssueTestFixtures.validWorkbook(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.length()").value(4))
                .andExpect(jsonPath("$.data.issueLevels[0]").value("交易级"))
                .andExpect(jsonPath("$.data.issueTypes[0]").value("迁移问题"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.groupCount").value(4))
                .andExpect(jsonPath("$.data.sandboxCount").value(4))
                .andExpect(jsonPath("$.data.importedAt").isNotEmpty());
    }

    @Test
    void groupSummaryEndpointReturnsPendingAndFixedSegmentsForFormalStatusesOnly() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-NEW", "new"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-OPEN", "open"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "T-REOPENED", "reopened"),
                ReplayIssueTestFixtures.row("贷款组", false, 4, "T-DEFERRED", "deferred"),
                ReplayIssueTestFixtures.row("贷款组", false, 5, "T-PENDING", "pending"),
                ReplayIssueTestFixtures.row("贷款组", false, 6, "T-NO-ACTION", "no-action"),
                ReplayIssueTestFixtures.row("贷款组", false, 7, "T-FIXED", "fixed"),
                ReplayIssueTestFixtures.row("贷款组", false, 8, "T-LEGACY", "legacy")),
                LocalDateTime.of(2026, 8, 11, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '新建' WHERE transaction_code = 'T-NEW'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '重新打开' WHERE transaction_code = 'T-REOPENED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '延后修复' WHERE transaction_code = 'T-DEFERRED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '修复待验证' WHERE transaction_code = 'T-PENDING'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '无需处理' WHERE transaction_code = 'T-NO-ACTION'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '已修复' WHERE transaction_code = 'T-FIXED'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '分析中' WHERE transaction_code = 'T-LEGACY'");

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].groupName").value("贷款组"))
                .andExpect(jsonPath("$.data[0].newCount").value(1))
                .andExpect(jsonPath("$.data[0].openCount").value(1))
                .andExpect(jsonPath("$.data[0].reopenedCount").value(1))
                .andExpect(jsonPath("$.data[0].deferredCount").value(1))
                .andExpect(jsonPath("$.data[0].pendingVerificationCount").value(1))
                .andExpect(jsonPath("$.data[0].pendingTotalCount").value(5))
                .andExpect(jsonPath("$.data[0].noActionCount").value(1))
                .andExpect(jsonPath("$.data[0].fixedCount").value(1))
                .andExpect(jsonPath("$.data[0].fixedTotalCount").value(2))
                .andExpect(jsonPath("$.data[0].totalCount").value(7));
    }

    @Test
    void statisticsEndpointsCanGroupByIssueDomainWithGroupNameFallback() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-PLATFORM", "platform"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-FALLBACK", "fallback")),
                LocalDateTime.of(2026, 8, 11, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_domain = '平台组', issue_status = '新建' WHERE transaction_code = 'T-PLATFORM'");
        jdbc.update("UPDATE dii_replay_issue SET issue_domain = NULL, issue_status = '打开' WHERE transaction_code = 'T-FALLBACK'");

        mvc.perform(get("/api/ai/parallel-replay/issues/stats").param("groupBy", "issueDomain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupCount").value(2))
                .andExpect(jsonPath("$.data.groupCounts.平台组.total").value(1))
                .andExpect(jsonPath("$.data.groupCounts.贷款组.total").value(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/groups").param("groupBy", "issueDomain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].groupName", hasItems("贷款组", "平台组")));
    }

    @Test
    void personRankingKeepsDynamicDeveloperWhenGroupedByIssueDomain() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("贷款组", false, 1, "T-PLATFORM", "platform")),
                LocalDateTime.of(2026, 8, 11, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_domain = '平台组', issue_status = '新建' WHERE transaction_code = 'T-PLATFORM'");
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                "贷款", "T-PLATFORM", "平台问题交易", "张三(c-zhangs3)", LocalDateTime.of(2026, 8, 11, 8, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking").param("groupBy", "issueDomain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].groupName").value("平台组"))
                .andExpect(jsonPath("$.data[0].developer").value("张三(c-zhangs3)"));
    }

    @Test
    void statisticsEndpointsRejectUnknownGroupingDimension() throws Exception {
        mvc.perform(get("/api/ai/parallel-replay/issues/stats").param("groupBy", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("统计分组口径仅支持 domain 或 issueDomain"));
    }

    @Test
    void acceptsReplayTypeAcrossListOptionsAndExport() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "RPT-ONLY", "rpt"),
                ReplayIssueTestFixtures.row("公共组", false, 2, "DZ-ONLY", "dz")),
                LocalDateTime.of(2026, 9, 2, 9, 0));
        jdbc.update("DELETE FROM dii_replay_issue_occurrence_batch");
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'RPT20260902-001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='RPT-ONLY'",
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0),
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0));
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'DZ20260902-001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='DZ-ONLY'",
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0),
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues").param("replayType", "DZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].transaction_code").value("DZ-ONLY"));
        mvc.perform(get("/api/ai/parallel-replay/issues/header-filter-option-counts")
                        .param("field", "occurrenceBatch").param("replayType", "QUERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].value").value("RPT20260902-001"));
        mvc.perform(get("/api/ai/parallel-replay/issues/export").param("replayType", "DZ"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidReplayType() throws Exception {
        mvc.perform(get("/api/ai/parallel-replay/issues").param("replayType", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("回放交易类型不合法"));
        mvc.perform(get("/api/ai/parallel-replay/issues/stats").param("replayType", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("回放交易类型不合法"));
    }

    @Test
    void filtersAllStatisticsEndpointsByReplayType() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("公共组", false, 1, "RPT-ONLY", "rpt"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "DZ-ONLY", "dz")),
                LocalDateTime.of(2026, 9, 2, 9, 0));
        jdbc.update("DELETE FROM dii_replay_issue_occurrence_batch");
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'RPT20260902-001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='RPT-ONLY'",
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0),
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0));
        jdbc.update("INSERT INTO dii_replay_issue_occurrence_batch"
                        + "(replay_issue_id,issue_key,batch_name,first_occurred_at,last_occurred_at,created_at,updated_at) "
                        + "SELECT id,issue_key,'DZ20260902-001',?,?,?,? FROM dii_replay_issue WHERE transaction_code='DZ-ONLY'",
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0),
                LocalDateTime.of(2026, 9, 2, 9, 0), LocalDateTime.of(2026, 9, 2, 9, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats").param("replayType", "QUERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/ai/parallel-replay/issues/stats/groups").param("replayType", "QUERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].groupName").value("公共组"));
        mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking").param("replayType", "DZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].groupName").value("贷款组"));
    }

    @Test
    void personRankingEndpointKeepsDeveloperCombinationAsOneRankingRow() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("贷款组", false, 1, "T-COMBINATION", "one"),
                ReplayIssueTestFixtures.row("贷款组", false, 2, "T-COMBINATION", "two"),
                ReplayIssueTestFixtures.row("贷款组", false, 3, "T-NO-ACTION", "no-action"),
                ReplayIssueTestFixtures.row("贷款组", false, 4, "T-FIXED", "fixed")),
                LocalDateTime.of(2026, 8, 11, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '无需处理' WHERE transaction_code = 'T-NO-ACTION'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status = '已修复' WHERE transaction_code = 'T-FIXED'");
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                "贷款", "T-COMBINATION", "组合交易", "张三(c-zhangs3)、李四(c-lisi)", LocalDateTime.of(2026, 8, 11, 8, 0));
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                "贷款", "T-NO-ACTION", "无需处理交易", "张三(c-zhangs3)、李四(c-lisi)", LocalDateTime.of(2026, 8, 11, 8, 0));
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                "贷款", "T-FIXED", "已修复交易", "张三(c-zhangs3)、李四(c-lisi)", LocalDateTime.of(2026, 8, 11, 8, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].groupName").value("贷款组"))
                .andExpect(jsonPath("$.data[0].developer").value("张三(c-zhangs3)、李四(c-lisi)"))
                .andExpect(jsonPath("$.data[0].pendingTotalCount").value(2))
                .andExpect(jsonPath("$.data[0].noActionCount").value(1))
                .andExpect(jsonPath("$.data[0].fixedCount").value(1))
                .andExpect(jsonPath("$.data[0].fixedTotalCount").value(2))
                .andExpect(jsonPath("$.data[0].totalCount").value(4));
    }

    @Test
    void personScheduleEndpointReturnsThreeStatusDateSummaryAndRejectsMissingDeveloper() throws Exception {
        dao.replaceAll(List.of(
                ReplayIssueTestFixtures.row("存款组", false, 1, "S-NEW", "schedule-new"),
                ReplayIssueTestFixtures.row("存款组", false, 2, "S-OPEN", "schedule-open"),
                ReplayIssueTestFixtures.row("存款组", false, 3, "S-REOPENED", "schedule-reopened")),
                LocalDateTime.of(2026, 8, 11, 9, 0));
        jdbc.update("UPDATE dii_replay_issue SET issue_status='新建', planned_completion_date='2026-07-02' WHERE transaction_code='S-NEW'");
        jdbc.update("UPDATE dii_replay_issue SET issue_status='重新打开', planned_completion_date='2026-07-01' WHERE transaction_code='S-REOPENED'");
        jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,developer,imported_at) VALUES (?,?,?,?,?)",
                List.of(
                        new Object[]{"存款组", "S-NEW", "新建交易", "张三(c-zhangs3)", LocalDateTime.of(2026, 8, 11, 8, 0)},
                        new Object[]{"存款组", "S-OPEN", "打开交易", "张三(c-zhangs3)", LocalDateTime.of(2026, 8, 11, 8, 0)},
                        new Object[]{"存款组", "S-REOPENED", "重新打开交易", "张三(c-zhangs3)", LocalDateTime.of(2026, 8, 11, 8, 0)}));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking/schedule")
                        .param("groupBy", "domain")
                        .param("replayType", "ALL")
                        .param("groupName", "存款组")
                        .param("developer", "张三(c-zhangs3)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleTotalCount").value(3))
                .andExpect(jsonPath("$.data.schedulePlannedCount").value(2))
                .andExpect(jsonPath("$.data.scheduleUnplannedCount").value(1))
                .andExpect(jsonPath("$.data.dateCounts[0].plannedCompletionDate").value("2026-07-01"))
                .andExpect(jsonPath("$.data.dateCounts[0].count").value(1))
                .andExpect(jsonPath("$.data.dateCounts[1].plannedCompletionDate").value("2026-07-02"));

        mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking/schedule")
                        .param("groupName", "存款组"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void roundEndpointsGroupImportResultAndAllManualChanges() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, null)).get(0).get("id")).longValue();

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"延后修复\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"第一次\",\"finalSolution\":\"方案一\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"修复待验证\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"第二次\",\"finalSolution\":\"方案二\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ai/parallel-replay/issues/rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].inputRows").value(8))
                .andExpect(jsonPath("$.data[0].operatorUsername").value("SYSTEM"))
                .andExpect(jsonPath("$.data[0].operatorRealName").value("系统"));

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].appeared").value(true))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(2))
                .andExpect(jsonPath("$.data[0].finalStatus").value("修复待验证"))
                .andExpect(jsonPath("$.data[0].manualEvents.length()").value(2));
    }

    @Test
    void roundTrackingMovesLaterManualEditsToTheNewIssueRound() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                        50, 0, null, null, null, null, "TRAN|6208|响应码|公共组|1"))
                .get(0).get("id")).longValue();

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"延后修复\",\"issueType\":\"代码问题\","
                                + "\"initialAnalysis\":\"第一轮分析\",\"finalSolution\":\"第一轮方案\","
                                + "\"cooperationPersonUsername\":null,\"remark\":\"第一轮修改\"}"))
                .andExpect(status().isOk());

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(validWorkbookForBatch("RPT20260821-142055-0001"))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"修复待验证\",\"issueType\":\"代码问题\","
                                + "\"initialAnalysis\":\"第二轮分析\",\"finalSolution\":\"第二轮方案\","
                                + "\"cooperationPersonUsername\":null,\"remark\":\"第二轮修改\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(1))
                .andExpect(jsonPath("$.data[0].manualEvents[0].remark").value("第二轮修改"))
                .andExpect(jsonPath("$.data[1].manualChangeCount").value(1))
                .andExpect(jsonPath("$.data[1].manualEvents[0].remark").value("第一轮修改"));
    }

    @Test
    void roundTrackingKeepsOriginalDataWhenOnlyExcludedFieldsChange() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        Map<String, Object> issue = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                        50, 0, null, null, null, null, "TRAN|6208|响应码|公共组|1"))
                .get(0);
        long id = ((Number) issue.get("id")).longValue();
        jdbc.update("UPDATE dii_replay_issue SET issue_description=?,issue_type=?,initial_analysis=?,final_solution=?,"
                        + "cooperation_person_username=?,cooperation_person_real_name=?,remark=? WHERE id=?",
                "待导入覆盖的旧问题描述", "代码问题", "人工分析", "人工方案", "alice", "艾丽丝", "人工备注", id);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(validWorkbookForBatch("RPT20260821-142055-0001"))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actionType").value("数据继承"))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(0))
                .andExpect(jsonPath("$.data[0].manualEvents.length()").value(0))
                .andExpect(jsonPath("$.data[0].inheritedEvents.length()").value(0))
                .andExpect(jsonPath("$.data[0].originalData[2].field").value("交易码"))
                .andExpect(jsonPath("$.data[0].originalData.length()").value(13));
    }

    @Test
    void roundTrackingHidesOperationsAlreadyShownByDedicatedViews() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        Map<String, Object> issue = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                        50, 0, null, null, null, null, "TRAN|6208|响应码|公共组|1"))
                .get(0);
        long id = ((Number) issue.get("id")).longValue();
        String issueKey = String.valueOf(issue.get("issue_key"));
        String batch = String.valueOf(issue.get("batch_no"));
        Long roundId = dao.findLatestIssueRoundId(id);
        ReplayIssueOperator operator = new ReplayIssueOperator("sunhy1", "孙海英");
        LocalDateTime baseTime = LocalDateTime.of(2026, 8, 20, 10, 0);

        dao.insertHistoryForRound(id, issueKey, "人工保存", baseTime.plusMinutes(1), operator,
                baseTime.toLocalDate(), null, null, null,
                "{\"issueStatus\":\"打开\"}", "{\"issueStatus\":\"修复待验证\"}", null, roundId, batch);
        dao.insertHistoryForRound(id, issueKey, "修改问题所属领域", baseTime.plusMinutes(2), operator,
                baseTime.toLocalDate(), null, null, null,
                "{\"domain\":\"公共组\"}", "{\"domain\":\"贷款组\"}", null, roundId, batch);
        dao.insertHistoryForRound(id, issueKey, "修改计划验证日期", baseTime.plusMinutes(3), operator,
                baseTime.toLocalDate(), null, null, null,
                "{\"plannedCompletionDate\":\"2026-08-20\"}",
                "{\"plannedCompletionDate\":\"2026-08-21\"}", null, roundId, batch);

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(1))
                .andExpect(jsonPath("$.data[0].manualEvents.length()").value(1))
                .andExpect(jsonPath("$.data[0].manualEvents[0].operationType").value("人工保存"))
                .andExpect(jsonPath("$.data[0].inheritedEvents[?(@.operationType == '修改问题所属领域')]").isEmpty())
                .andExpect(jsonPath("$.data[0].inheritedEvents[?(@.operationType == '修改计划验证日期')]").isEmpty());
    }

    @Test
    void roundTrackingHidesEventsWithoutTrackedFieldChanges() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        Map<String, Object> issue = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                        50, 0, null, null, null, null, "TRAN|6208|响应码|公共组|1"))
                .get(0);
        long id = ((Number) issue.get("id")).longValue();
        String issueKey = String.valueOf(issue.get("issue_key"));
        String batch = String.valueOf(issue.get("batch_no"));
        Long roundId = dao.findLatestIssueRoundId(id);
        ReplayIssueOperator operator = new ReplayIssueOperator("sunhy1", "孙海英");
        LocalDateTime baseTime = LocalDateTime.of(2026, 8, 20, 10, 0);

        dao.insertHistoryForRound(id, issueKey, "人工保存", baseTime.plusMinutes(1), operator,
                baseTime.toLocalDate(), null, null, null,
                "{\"issueDescription\":\"旧描述\"}", "{\"issueDescription\":\"新描述\"}", null, roundId, batch);
        dao.insertHistoryForRound(id, issueKey, "人工保存", baseTime.plusMinutes(2), operator,
                baseTime.toLocalDate(), null, null, null,
                "{\"issueStatus\":\"打开\",\"issueDescription\":\"旧描述\"}",
                "{\"issueStatus\":\"修复待验证\",\"issueDescription\":\"新描述\"}", null, roundId, batch);

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(1))
                .andExpect(jsonPath("$.data[0].manualEvents.length()").value(1))
                .andExpect(jsonPath("$.data[0].manualEvents[0].operationType").value("人工保存"))
                .andExpect(jsonPath("$.data[0].manualEvents[0].changes.length()").value(1))
                .andExpect(jsonPath("$.data[0].manualEvents[0].changes[0].field").value("问题状态"));
    }

    @Test
    void roundTrackingShowsAutoRepairInTheIssueBatchHistory() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 99, "MISSING-TX", "原基础数据")),
                LocalDateTime.of(2026, 8, 10, 9, 0));
        Map<String, Object> seeded = dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, "key-99")).get(0);
        long id = ((Number) seeded.get("id")).longValue();
        jdbc.update("UPDATE dii_replay_issue SET issue_type=?,initial_analysis=?,final_solution=?,"
                        + "cooperation_person_username=?,cooperation_person_real_name=?,remark=? WHERE id=?",
                "代码问题", "人工分析", "人工方案", "alice", "艾丽丝", "人工备注", id);

        mvc.perform(multipart("/api/ai/parallel-replay/issues/import")
                        .file(ReplayIssueTestFixtures.validWorkbook(1))
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoRepairedRows").value(1));

        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].appeared").value(false))
                .andExpect(jsonPath("$.data[0].actionType").value("问题自动修复"))
                .andExpect(jsonPath("$.data[0].originalData.length()").value(0))
                .andExpect(jsonPath("$.data[1].appeared").value(true));

        var history = dao.findHistoryByIssueId(id, 10).get(0);
        assertEquals("问题自动修复", history.operationType());
        assertTrue(history.afterSnapshot().contains("人工分析"));
        assertTrue(history.afterSnapshot().contains("人工备注"));
        org.junit.jupiter.api.Assertions.assertNull(history.incomingSnapshot());
        assertEquals(dao.findIssueRounds(id).get(0).roundId(), history.contextRoundId());
    }

    @Test
    void fullRefreshHistoryIsShownAsBaseDataWithoutCreatingRound() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/issues/full-refresh")
                        .file(validFullRefreshWorkbook())
                        .param("confirm", "FULL_REFRESH")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk());
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, null)).get(0).get("id")).longValue();

        mvc.perform(get("/api/ai/parallel-replay/issues/rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roundCode").value("基础数据"))
                .andExpect(jsonPath("$.data[0].manualChangeCount").value(1));
    }

    @Test
    void oversizedLimitIsClampedThroughObservableItemCount() throws Exception {
        List<ReplayIssueRow> rows = new ArrayList<>();
        for (int rowOrder = 1; rowOrder <= 201; rowOrder++) {
            rows.add(ReplayIssueTestFixtures.row("公共组", false, rowOrder,
                    "T-" + rowOrder, "issue-" + rowOrder));
        }
        dao.replaceAll(rows, LocalDateTime.of(2026, 8, 4, 12, 0));

        mvc.perform(get("/api/ai/parallel-replay/issues").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(201))
                .andExpect(jsonPath("$.data.items.length()").value(200));
    }

    @Test
    void patchAcceptsAllFiveFieldsAndRejectsSystemStatus() throws Exception {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")),
                LocalDateTime.of(2026, 8, 5, 9, 0));
        long id = ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(1, 0, null, null, null, null, null))
                .get(0).get("id")).longValue();
        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"修复待验证\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"分析\",\"finalSolution\":\"方案\",\"cooperationPersonUsername\":\"sunhy1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueStatus").value("修复待验证"))
                .andExpect(jsonPath("$.data.cooperationPersonRealName").value("孙海英"));
        var history = dao.findHistoryByIssueId(id, 10);
        assertEquals("sunhy1", history.get(0).operatorUsername());
        assertEquals("孙海英", history.get(0).operatorRealName());

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"已修复\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"\",\"finalSolution\":\"\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedPrincipalWithoutUserMappingCanSaveAndIsRecordedAsOperator() throws Exception {
        long id = seedIssue();
        resolvedUser = new UserPrincipalResolver.Resolved("UIAS", "A012345", null);

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"延后修复\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"分析\",\"finalSolution\":\"方案\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isOk());

        var history = dao.findHistoryByIssueId(id, 10);
        assertEquals(1, history.size());
        assertEquals("A012345", history.get(0).operatorUsername());
        assertEquals("A012345", history.get(0).operatorRealName());
    }

    @Test
    void anonymousPrincipalCannotSave() throws Exception {
        long id = seedIssue();
        resolvedUser = new UserPrincipalResolver.Resolved("ANONYMOUS", null, null);

        mvc.perform(patch("/api/ai/parallel-replay/issues/{id}", id)
                        .contentType("application/json")
                        .content("{\"issueStatus\":\"分析中\",\"issueType\":\"代码问题\",\"initialAnalysis\":\"分析\",\"finalSolution\":\"方案\",\"cooperationPersonUsername\":null}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));

        assertEquals(0, dao.findHistoryByIssueId(id, 10).size());
    }

    @Test
    void userLookupMatchesUsernameAndRealName() throws Exception {
        mvc.perform(get("/api/ai/parallel-replay/issues/users").param("keyword", "sunh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].displayName").value("孙海英(sunhy1)"));
        mvc.perform(get("/api/ai/parallel-replay/issues/users").param("keyword", "海英"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("sunhy1"));
    }

    private static MockMultipartFile validLegacyWorkbook() {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String sheetName : ReplayIssueTestFixtures.TARGET_SHEETS) {
                writeLegacySheet(workbook, sheetName);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "replay-issues.xls", "application/vnd.ms-excel",
                    output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create legacy test workbook", exception);
        }
    }

    private static MockMultipartFile validWorkbookForBatch(String batchNo) {
        return ReplayIssueTestFixtures.workbook(
                ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of("批次", batchNo)),
                ReplayIssueTestFixtures.HEADERS, true);
    }

    private MockMultipartFile validFullRefreshWorkbook() {
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("0731", List.of(fullRefreshRow("KEY-0731", "否")));
        sheets.put("0803", List.of(fullRefreshRow("KEY-0803", "是")));
        return ReplayIssueTestFixtures.fullRefreshWorkbook(sheets);
    }

    private Map<String, String> fullRefreshRow(String issueKey, String sandbox) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String header : ReplayIssueTestFixtures.FULL_REFRESH_HEADERS) {
            row.put(header, "");
        }
        row.put("领域", "公共组");
        row.put("问题描述", "基础数据");
        row.put("issue_id", issueKey.replace("KEY", "ISSUE"));
        row.put("issue_key", issueKey);
        row.put("问题状态", "打开");
        row.put("是否沙箱", sandbox);
        return row;
    }

    private SysUserDao userDao() {
        return new SysUserDao(jdbc);
    }

    private static void writeLegacySheet(HSSFWorkbook workbook, String sheetName) {
        Sheet sheet = workbook.createSheet(sheetName);
        Row headerRow = sheet.createRow(0);
        Row dataRow = sheet.createRow(1);
        for (int columnIndex = 0; columnIndex < ReplayIssueTestFixtures.HEADERS.size(); columnIndex++) {
            String header = ReplayIssueTestFixtures.HEADERS.get(columnIndex);
            headerRow.createCell(columnIndex).setCellValue(header);
            dataRow.createCell(columnIndex).setCellValue(legacyValue(header, sheetName));
        }
    }

    private static String legacyValue(String header, String sheetName) {
        return switch (header) {
            case "领域" -> sheetName.replace("沙箱-", "");
            case "序号" -> "1";
            case "批次" -> "RPT20260820-142055-9860";
            case "issue_key" -> "TRAN|6208|响应码|" + sheetName;
            default -> "value";
        };
    }

    private void seedDailyBatch(String batchNo, LocalDateTime importedAt, String marker) {
        dailyDataDao.replaceBatch(new ReplayDailyWorkbookData(batchNo,
                List.of(new ReplayDailySummaryRow(batchNo, "公共组", 10L, 100L,
                        2L, 80L, 1L, new BigDecimal("0.80"), 3L, 1, marker)),
                List.of(new ReplayInterfaceComparisonRow(batchNo, ReplayDailyRowType.DETAIL,
                        marker + "-IFACE", "S1", "交易", "开发", "行内", "公共组",
                        100L, 1L, 2L, 3L, 4L, 80L, 1L,
                        new BigDecimal("0.90"), new BigDecimal("0.80"),
                        new BigDecimal("1.25"), new BigDecimal("2.50"), 1, marker)),
                List.of(new ReplayCoverageSummaryRow(batchNo, ReplayDailyRowType.DETAIL,
                        marker + "-COVERAGE", 100L, 90L, 10L, 0L, 0L, 0L,
                        new BigDecimal("0.90"), 1, marker)),
                List.of(new ReplayCoverageDetailRow(batchNo, marker + "-COVERAGE", "交易", "公共组",
                        "S1", "", "是", LocalDate.of(2026, 9, 1), 90L,
                        "已发送", "", "开发", "行内", 1, marker))), importedAt);
    }

    private long seedIssue() {
        dao.replaceAll(List.of(ReplayIssueTestFixtures.row("公共组", false, 1, "6208", "old")),
                LocalDateTime.of(2026, 8, 5, 9, 0));
        return ((Number) dao.list(new com.axonlink.ai.replay.dto.ReplayIssueQuery(
                1, 0, null, null, null, null, null)).get(0).get("id")).longValue();
    }

    @Test
    void dailyReportImportEndpointIsRetired() throws Exception {
        MockMultipartFile file = twoSectionSummaryWorkbook();

        mvc.perform(multipart("/api/ai/parallel-replay/issues/daily-report/import").file(file))
                .andExpect(status().isNotFound());
    }

    private static MockMultipartFile twoSectionSummaryWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("汇总信息");
            writeSummarySection(sheet, 0, "BATCH-PREV", "存款组", 100);
            writeSummarySection(sheet, 5, "BATCH-CURR", "贷款组", 200);
            workbook.write(output);
            return new MockMultipartFile("file", "daily-summary.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private static void writeSummarySection(Sheet sheet, int startRow, String batch, String domain, long covered) {
        Row parent = sheet.createRow(startRow);
        String[] parentHeaders = {"批次", "领域", "覆盖528接口", "发送交易量", "交易核对分类统计"};
        for (int index = 0; index < parentHeaders.length; index++) {
            parent.createCell(index).setCellValue(parentHeaders[index]);
        }
        parent.createCell(11).setCellValue("接口成功率");
        parent.createCell(12).setCellValue("比对通过率");
        Row child = sheet.createRow(startRow + 1);
        String[] childHeaders = {"528成功/CCBS失败", "CCBS失败明细", "528失败/CCBS成功",
                "二者均失败响应码一致", "二者均失败响应码不一致", "二者均成功", "响应码忽略"};
        for (int index = 0; index < childHeaders.length; index++) {
            child.createCell(4 + index).setCellValue(childHeaders[index]);
        }
        Row data = sheet.createRow(startRow + 2);
        data.createCell(0).setCellValue(batch);
        data.createCell(1).setCellValue(domain);
        data.createCell(2).setCellValue(covered);
        data.createCell(3).setCellValue(covered * 10);
        for (int column = 4; column <= 10; column++) data.createCell(column).setCellValue(column);
        data.createCell(11).setCellValue("90%");
        data.createCell(12).setCellValue("80%");
        sheet.createRow(startRow + 3).createCell(1).setCellValue("合计");
    }
}
