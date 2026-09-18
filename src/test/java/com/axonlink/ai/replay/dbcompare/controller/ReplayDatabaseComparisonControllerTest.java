package com.axonlink.ai.replay.dbcompare.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseTableOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareAuditGroupPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptValidationError;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareHeaderFilterResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportError;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareImportResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareListPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbComparePrimaryKeySyncResult;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareQuery;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSaveRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareMetadataStatus;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionGateError;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionPage;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionSummary;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareVersionTablePage;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseDatabaseUnavailableException;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseMetadataService;
import com.axonlink.ai.replay.dbcompare.service.ReplayBasePrimaryKeyMissingException;
import com.axonlink.ai.replay.dbcompare.service.ReplayBasePrimaryKeysRequiredException;
import com.axonlink.ai.replay.dbcompare.service.ReplayBaseTableNotFoundException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonImportService;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonConfigScriptService;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonService;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonVersionConflictException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonGenerationException;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonScopeException;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScopeValidationError;
import com.axonlink.ai.replay.dbcompare.service.ReplayDatabaseComparisonVersionService;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.security.UserPrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayDatabaseComparisonControllerTest {

    private ReplayDatabaseComparisonService service;
    private ReplayBaseMetadataService metadataService;
    private ReplayDatabaseComparisonImportService importService;
    private ReplayDatabaseComparisonVersionService versionService;
    private ReplayDatabaseComparisonConfigScriptService configScriptService;
    private ReplayDatabaseComparisonProperties properties;
    private DaoIndexAnalysisProperties daoProperties;
    private UserPrincipalResolver resolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ReplayDatabaseComparisonService.class);
        metadataService = mock(ReplayBaseMetadataService.class);
        importService = mock(ReplayDatabaseComparisonImportService.class);
        versionService = mock(ReplayDatabaseComparisonVersionService.class);
        configScriptService = mock(ReplayDatabaseComparisonConfigScriptService.class);
        resolver = mock(UserPrincipalResolver.class);
        properties = new ReplayDatabaseComparisonProperties();
        daoProperties = new DaoIndexAnalysisProperties();
        daoProperties.getBatchTrigger().setToken("secret");
        mvc = MockMvcBuilders.standaloneSetup(new ReplayDatabaseComparisonController(
                        service, metadataService, importService, versionService, configScriptService,
                        resolver, properties, daoProperties))
                .build();
    }

    @Test
    void searchReturnsFilteredAndGlobalTotalsSeparately() throws Exception {
        when(service.search(any(ReplayDbCompareQuery.class)))
                .thenReturn(new ReplayDbCompareListPage(List.of(), 0, 50, 3, 166, 271));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":0,\"size\":50,\"domains\":[\"结算组\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.globalTableCount").value(166))
                .andExpect(jsonPath("$.data.globalFieldCount").value(271));
    }

    @Test
    void bindsExactMultiValueHeaderFilters() throws Exception {
        when(service.search(any(ReplayDbCompareQuery.class)))
                .thenReturn(new ReplayDbCompareListPage(List.of(), 0, 50, 0, 0, 0));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"page":0,"size":50,
                                 "tableNames":["acct_a","acct_b"],
                                 "fieldNames":["acct_no","customer_no"],
                                 "registeredDates":["2026-09-10","2026-09-11"]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ReplayDbCompareQuery> captor = ArgumentCaptor.forClass(ReplayDbCompareQuery.class);
        verify(service).search(captor.capture());
        assertEquals(List.of("acct_a", "acct_b"), captor.getValue().tableNames());
        assertEquals(List.of("acct_no", "customer_no"), captor.getValue().fieldNames());
        assertEquals(List.of(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11)),
                captor.getValue().registeredDates());
    }

    @Test
    void exposesConfigScriptStatusAndBothDownloadContractsWithoutAToken() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        String versionNo = "20260914-172637";
        String fileName = "replay-db-compare-config-20260914-172637.sql";
        String sha256 = "a".repeat(64);
        byte[] sql = "SELECT 1;".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(configScriptService.status(versionNo)).thenReturn(new ReplayDbCompareConfigScriptStatus(
                true, fileName, (long) sql.length, sha256, "editor", "编辑人",
                LocalDateTime.of(2026, 9, 14, 18, 35, 26, 318_000_000)));
        when(configScriptService.generate(eq(versionNo), any())).thenReturn(
                new ReplayDatabaseComparisonConfigScriptService.ScriptFile(fileName, sha256, sql));
        when(configScriptService.download(versionNo)).thenReturn(
                new ReplayDatabaseComparisonConfigScriptService.ScriptFile(fileName, sha256, sql));

        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/versions/"
                        + versionNo + "/config-script"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generated").value(true))
                .andExpect(jsonPath("$.data.fileName").value(fileName))
                .andExpect(jsonPath("$.data.sha256").value(sha256));
        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/"
                        + versionNo + "/config-script"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/sql;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''" + fileName))
                .andExpect(header().string("X-Content-SHA256", sha256))
                .andExpect(content().bytes(sql));
        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/versions/"
                        + versionNo + "/config-script/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-SHA256", sha256))
                .andExpect(content().bytes(sql));

        verify(configScriptService).generate(eq(versionNo), any(ReplayIssueOperator.class));
        verify(configScriptService).download(versionNo);
    }

    @Test
    void requiresAuthenticationForConfigScriptGenerationOnly() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/"
                        + "20260914-172637/config-script"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(configScriptService);
    }

    @Test
    void allowsAuthenticatedPrincipalWithoutUserMappingToGenerateConfigScript() throws Exception {
        when(resolver.resolve(any())).thenReturn(
                new UserPrincipalResolver.Resolved("UIAS", "A012345", null));
        String versionNo = "20260914-172637";
        byte[] sql = "SELECT 1;".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(configScriptService.generate(versionNo, new ReplayIssueOperator("A012345", "A012345")))
                .thenReturn(new ReplayDatabaseComparisonConfigScriptService.ScriptFile(
                        "replay-db-compare-config-20260914-172637.sql", "a".repeat(64), sql));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/"
                        + versionNo + "/config-script"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(sql));

        verify(configScriptService).generate(
                versionNo, new ReplayIssueOperator("A012345", "A012345"));
    }

    @Test
    void returnsCompleteConfigScriptValidationErrors() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        String versionNo = "20260914-172637";
        when(configScriptService.generate(eq(versionNo), any())).thenThrow(
                new ReplayDatabaseComparisonGenerationException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "CONFIG_SCRIPT_VALIDATION_FAILED",
                        "2 项配置无法生成生产脚本",
                        Map.of("errors", List.of(
                                new ReplayDbCompareConfigScriptValidationError(
                                        "bad-table", null, "表英文名不是合法数据库标识符"),
                                new ReplayDbCompareConfigScriptValidationError(
                                        "acct_master", "bad field", "字段英文名不是合法数据库标识符")))));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/"
                        + versionNo + "/config-script"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("CONFIG_SCRIPT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors.length()").value(2))
                .andExpect(jsonPath("$.data.errors[1].fieldName").value("bad field"));
    }

    @Test
    void generatesAndQueriesVersionHistoryContracts() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        ReplayDbCompareVersionSummary summary = new ReplayDbCompareVersionSummary(
                "20260914-142530", "editor", "编辑人",
                LocalDateTime.of(2026, 9, 14, 14, 25, 30, 318_000_000),
                2, 5, true);
        when(versionService.generate(eq("secret"), eq("secret"), any())).thenReturn(summary);
        when(versionService.latest()).thenReturn(null);
        when(versionService.versions(0, 20))
                .thenReturn(new ReplayDbCompareVersionPage(List.of(summary), 0, 20, 1));
        when(versionService.searchVersion(eq("20260914-142530"), any()))
                .thenReturn(new ReplayDbCompareVersionTablePage(List.of(), 0, 50, 0));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/generate")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value("20260914-142530"))
                .andExpect(jsonPath("$.data.tableCount").value(2));
        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/versions/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/versions")
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].latest").value(true));
        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/20260914-142530/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":0,\"size\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        verify(versionService).generate(
                "secret", "secret", new ReplayIssueOperator("editor", "编辑人"));
    }

    @Test
    void returnsStableGenerationErrorWithCompleteGateData() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        ReplayDbCompareVersionGateError gateError = new ReplayDbCompareVersionGateError(
                "base_schema", "acct_master", "账户主表",
                "100", "zhangsan", "张三", null, null,
                ReplayDbCompareMetadataStatus.MISSING_FIELDS,
                List.of("legacy_id"), "比对字段母库中不存在：legacy_id");
        when(versionService.generate(eq("secret"), eq("secret"), any()))
                .thenThrow(new ReplayDatabaseComparisonGenerationException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "VERSION_GATE_BLOCKED",
                        "1 张表未通过版本生成门禁",
                        Map.of("errors", List.of(gateError))));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/generate")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("VERSION_GATE_BLOCKED"))
                .andExpect(jsonPath("$.data.errors[0].tableName").value("acct_master"))
                .andExpect(jsonPath("$.data.errors[0].missingFieldNames[0]").value("legacy_id"))
                .andExpect(jsonPath("$.data.errors[0].groupOwnerEmpNo").doesNotExist());
    }

    @Test
    void allowsTokenOnlyVersionGenerationAsSystem() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("UNKNOWN", "dii-token", null));
        ReplayDbCompareVersionSummary summary = new ReplayDbCompareVersionSummary(
                "20260915-103000", "SYSTEM", "系统",
                LocalDateTime.of(2026, 9, 15, 10, 30), 1, 2, true);
        when(versionService.generate("secret", "secret", ReplayIssueOperator.system()))
                .thenReturn(summary);

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/versions/generate")
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generatedBy").value("SYSTEM"))
                .andExpect(jsonPath("$.data.generatedName").value("系统"));

        verify(versionService).generate("secret", "secret", ReplayIssueOperator.system());
    }

    @Test
    void rejectsUnauthenticatedWrites() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));

        mvc.perform(put("/api/ai/parallel-replay/database-comparison-fields/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableName":"acct_master","domainName":"存款组",
                                 "groupOwnerEmpNo":"101","fieldNames":["acct_no"],"version":0}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户未登录"));
    }

    @Test
    void tokenIdentityCannotPerformManualRegistrationWritesAndGetsAnActionableForbiddenError() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("UNKNOWN", "dii-token", null));
        mvc.perform(put("/api/ai/parallel-replay/database-comparison-fields/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableName":"kapb_busi_log","domainName":"平台组",
                                 "groupOwnerEmpNo":"101","fieldNames":["id"],"version":1}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("HUMAN_LOGIN_REQUIRED"))
                .andExpect(jsonPath("$.message").value("当前为操作口令身份，请退出后使用人员账号登录再保存"));
        verifyNoInteractions(service);
    }

    @Test
    void allowsAnyAuthenticatedUserToUpdateAndMapsVersionConflict() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        when(service.update(eq(7L), any(ReplayDbCompareSaveRequest.class), any(ReplayIssueOperator.class)))
                .thenThrow(new ReplayDatabaseComparisonVersionConflictException());

        mvc.perform(put("/api/ai/parallel-replay/database-comparison-fields/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableName":"acct_master","domainName":"存款组",
                                 "groupOwnerEmpNo":"101","fieldNames":["acct_no"],"version":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("登记数据已被其他用户修改，请刷新后重试"));
    }

    @Test
    void returnsCompleteComparisonScopeValidationErrors() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        when(service.update(eq(7L), any(ReplayDbCompareSaveRequest.class), any(ReplayIssueOperator.class)))
                .thenThrow(new ReplayDatabaseComparisonScopeException(List.of(
                        new ReplayDbCompareScopeValidationError(
                                "whereCondition.groups[0].conditions[1]",
                                "条件字段 legacy_status 在 BASE 母库中不存在"),
                        new ReplayDbCompareScopeValidationError(
                                "compareLimit", "比对条数必须在 1～10000000 之间"))));

        mvc.perform(put("/api/ai/parallel-replay/database-comparison-fields/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableName":"acct_master","domainName":"存款组",
                                 "groupOwnerEmpNo":"101","fieldNames":["acct_no"],"version":0,
                                 "compareLimit":10000001}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("COMPARISON_SCOPE_INVALID"))
                .andExpect(jsonPath("$.data.errors.length()").value(2))
                .andExpect(jsonPath("$.data.errors[0].path")
                        .value("whereCondition.groups[0].conditions[1]"))
                .andExpect(jsonPath("$.data.errors[1].path").value("compareLimit"));
    }

    @Test
    void returnsPrimaryKeyMetadataAndSanitizesUnavailableError() throws Exception {
        when(metadataService.listColumns("acct_master", "acct"))
                .thenReturn(List.of(new ReplayBaseColumnOption("acct_no", "账号", 1, true, 2)));

        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/metadata/tables/acct_master/columns")
                        .param("keyword", "acct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].columnName").value("acct_no"))
                .andExpect(jsonPath("$.data[0].primaryKey").value(true))
                .andExpect(jsonPath("$.data[0].primaryKeyOrder").value(2));

        when(metadataService.listColumns("broken", null))
                .thenThrow(new ReplayBaseDatabaseUnavailableException("BASE 母库未配置或暂不可用"));

        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/metadata/tables/broken/columns"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("BASE 母库未配置或暂不可用"))
                .andExpect(jsonPath("$.data.errorCode").value("BASE_DATABASE_UNAVAILABLE"));
    }

    @Test
    void synchronizesPrimaryKeysBeforeListLoading() throws Exception {
        when(service.synchronizePrimaryKeys())
                .thenReturn(new ReplayDbComparePrimaryKeySyncResult(200, 3, 4, 1));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/metadata/primary-keys/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scannedCount").value(200))
                .andExpect(jsonPath("$.data.updatedCount").value(3))
                .andExpect(jsonPath("$.data.addedFieldCount").value(4))
                .andExpect(jsonPath("$.data.conflictCount").value(1));
    }

    @Test
    void returnsExplicitNotFoundContractWhenBaseTableIsMissing() throws Exception {
        when(metadataService.listColumns("removed_table", null))
                .thenThrow(new ReplayBaseTableNotFoundException("removed_table"));

        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/metadata/tables/removed_table/columns"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("BASE 母库中不存在表：removed_table"))
                .andExpect(jsonPath("$.data.errorCode").value("BASE_TABLE_NOT_FOUND"))
                .andExpect(jsonPath("$.data.tableName").value("removed_table"));
    }

    @Test
    void returnsCompletePrimaryKeyRequirementContract() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        when(service.update(eq(7L), any(ReplayDbCompareSaveRequest.class), any(ReplayIssueOperator.class)))
                .thenThrow(new ReplayBasePrimaryKeysRequiredException(
                        "acct_master", List.of("b", "f")));

        mvc.perform(put("/api/ai/parallel-replay/database-comparison-fields/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableName":"acct_master","domainName":"存款组",
                                 "groupOwnerEmpNo":"101","fieldNames":["a","c","d"],"version":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("BASE_PRIMARY_KEYS_REQUIRED"))
                .andExpect(jsonPath("$.data.tableName").value("acct_master"))
                .andExpect(jsonPath("$.data.missingPrimaryKeyNames[0]").value("b"))
                .andExpect(jsonPath("$.data.missingPrimaryKeyNames[1]").value("f"));
    }

    @Test
    void returnsMissingPrimaryKeyContract() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        when(service.update(eq(7L), any(ReplayDbCompareSaveRequest.class), any(ReplayIssueOperator.class)))
                .thenThrow(new ReplayBasePrimaryKeyMissingException("acct_master"));

        mvc.perform(put("/api/ai/parallel-replay/database-comparison-fields/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableName":"acct_master","domainName":"存款组",
                                 "groupOwnerEmpNo":"101","fieldNames":["acct_no"],"version":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("该表没有主键，请联系 DBA 创建表主键"))
                .andExpect(jsonPath("$.data.errorCode").value("BASE_PRIMARY_KEY_MISSING"))
                .andExpect(jsonPath("$.data.tableName").value("acct_master"));
    }

    @Test
    void exposesHeaderOptionsAndRegistrationAuditEndpoints() throws Exception {
        when(service.headerFilterOptions(any())).thenReturn(new ReplayDbCompareHeaderFilterResult(
                List.of(new ReplayDbCompareHeaderFilterOption("存款组", "存款组", 12)), 1, 12, false));
        when(service.auditEvents(eq(7L), eq(0), eq(50)))
                .thenReturn(new ReplayDbCompareAuditPage(List.of(), 0, 50, 0));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/header-filter-options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetColumn":"domainName","page":0,"size":50}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options[0].value").value("存款组"))
                .andExpect(jsonPath("$.data.matchedRegistrationCount").value(12));

        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/7/audits")
                        .param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0));
    }

    @Test
    void exposesGroupedAuditSearch() throws Exception {
        when(service.searchGroupedAudits(any())).thenReturn(
                new ReplayDbCompareAuditGroupPage(List.of(), 0, 20, 0, 0));

        mvc.perform(post("/api/ai/parallel-replay/database-comparison-fields/audits/grouped-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":0,\"size\":20,\"tableKeyword\":\"账户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void enrichesBaseTableSearchWithRegistrationState() throws Exception {
        when(metadataService.searchTables("acct", 20))
                .thenReturn(List.of(new ReplayBaseTableOption("acct_master", "账户主表")));
        when(service.registrationState("acct_master"))
                .thenReturn(new ReplayDatabaseComparisonService.RegistrationState("ACTIVE", 7L, 3L));

        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/metadata/tables")
                        .param("keyword", "acct").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tableName").value("acct_master"))
                .andExpect(jsonPath("$.data[0].registrationStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].registrationId").value(7))
                .andExpect(jsonPath("$.data[0].registrationVersion").value(3));
    }

    @Test
    void returnsUnprocessableEntityWithCompleteImportValidationErrors() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        when(importService.importFile(any(), any())).thenReturn(new ReplayDbCompareImportResult(
                false, 1, 0, 0, 0, List.of(new ReplayDbCompareImportError(
                "存款", 28, "acct_master", "missing_field", "张三",
                "BASE 母库中不存在该字段"))));
        MockMultipartFile file = new MockMultipartFile(
                "file", "初始化.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3});

        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(file)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("初始化导入校验失败，未写入任何数据"))
                .andExpect(jsonPath("$.data.errors[0].sheetName").value("存款"))
                .andExpect(jsonPath("$.data.errors[0].rowNumber").value(28))
                .andExpect(jsonPath("$.data.errors[0].tableName").value("acct_master"))
                .andExpect(jsonPath("$.data.errors[0].fieldName").value("missing_field"))
                .andExpect(jsonPath("$.data.errors[0].reviserInput").value("张三"))
                .andExpect(jsonPath("$.data.errors[0].reason").value("BASE 母库中不存在该字段"));
    }

    @Test
    void rejectsWrongImportTokenBeforeParsingWorkbook() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("editor", "编辑人", "200"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "初始化.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3});

        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(file)
                        .header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));

        verifyNoInteractions(importService);
    }

    @Test
    void allowsAnyAuthenticatedUserToImportWithCorrectToken() throws Exception {
        when(resolver.resolve(any())).thenReturn(authenticated("creator", "创建人", "100"));
        when(importService.importFile(any(), any())).thenReturn(new ReplayDbCompareImportResult(
                true, 1, 1, 1, 0, List.of()));
        MockMultipartFile file = new MockMultipartFile(
                "file", "初始化.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3});

        mvc.perform(get("/api/ai/parallel-replay/database-comparison-fields/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importEnabled").value(true))
                .andExpect(jsonPath("$.data.canImport").value(true));

        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(file)
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        verify(importService).importFile(any(), eq(new ReplayIssueOperator("creator", "创建人")));
    }

    @Test
    void importsWithCorrectTokenWhenNoLoginContextExists() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));
        when(importService.importFile(any(), eq(ReplayIssueOperator.system())))
                .thenReturn(new ReplayDbCompareImportResult(true, 1, 1, 1, 0, List.of()));

        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(initialImportFile())
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        verify(importService).importFile(any(), eq(ReplayIssueOperator.system()));
    }

    @Test
    void importsWithTokenPrincipalWithoutUserMapping() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("UNKNOWN", "dii-token", null));
        when(importService.importFile(any(), eq(ReplayIssueOperator.system())))
                .thenReturn(new ReplayDbCompareImportResult(true, 1, 1, 1, 0, List.of()));

        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(initialImportFile())
                        .header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        verify(importService).importFile(any(), eq(ReplayIssueOperator.system()));
    }

    @Test
    void rejectsWrongOrMissingTokenWithoutLoginBeforeImporting() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));
        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(initialImportFile()).header("X-DII-Trigger-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));
        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(initialImportFile()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("口令错误"));
        verifyNoInteractions(importService);
    }

    @Test
    void disabledImportRejectsCorrectTokenWithoutLogin() throws Exception {
        properties.setImportEnabled(false);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));
        mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                        .file(initialImportFile()).header("X-DII-Trigger-Token", "secret"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(importService);
    }

    @Test
    void unconfiguredTokenDoesNotAuthorizeAnonymousImport() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));
        for (String token : new String[]{null, "", "   "}) {
            daoProperties.getBatchTrigger().setToken(token);
            mvc.perform(multipart("/api/ai/parallel-replay/database-comparison-fields/import")
                            .file(initialImportFile()).header("X-DII-Trigger-Token", "anything"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(importService);
    }

    private MockMultipartFile initialImportFile() {
        return new MockMultipartFile("file", "初始化.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
    }

    private UserPrincipalResolver.Resolved authenticated(String username, String name, String empNo) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(name);
        user.setEmpNo(empNo);
        user.setStatus(1);
        return new UserPrincipalResolver.Resolved("LDAP", username, user);
    }

}
