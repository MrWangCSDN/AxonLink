package com.axonlink.ai.replay.dbcompare.controller;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseMetadataSnapshot;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonScriptConfigurationDao;
import com.axonlink.ai.replay.dbcompare.service.*;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.security.DiiTokenBypassFilter;
import com.axonlink.security.UserPrincipalResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReplayDatabaseComparisonScriptConfigurationControllerTest {
    private static final String BASE = "/api/ai/parallel-replay/database-comparison-fields/script-configs";
    private JdbcTemplate jdbc;
    private ReplayBaseMetadataService metadata;
    private UserPrincipalResolver resolver;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/daoindex/V95__replay_named_script_configuration.sql"), new ClassPathResource(
                "db/daoindex/V97__replay_script_config_datasource.sql")).execute(jdbc.getDataSource());
        metadata = mock(ReplayBaseMetadataService.class);
        when(metadata.inspectTables(anyCollection())).thenReturn(Map.of("accounts", new ReplayBaseMetadataSnapshot(
                true, "accounts", "账户", List.of(
                new ReplayBaseColumnOption("account_id", "账户", "varchar", 1, true, 2),
                new ReplayBaseColumnOption("tenant_id", "法人", "varchar", 2, true, 1),
                new ReplayBaseColumnOption("mnt_trid", "维护交易", "varchar", 3, false, null),
                new ReplayBaseColumnOption("balance", "余额", "numeric", 4, false, null)))));
        resolver = mock(UserPrincipalResolver.class);
        login("c-wangsh8");
        var service = new ReplayDatabaseComparisonScriptConfigurationService(
                new ReplayDatabaseComparisonScriptConfigurationDao(jdbc), metadata,
                new ReplayDatabaseComparisonConfigScriptGenerator(), new ReplayDatabaseComparisonTablesExcelParser(),
                new ReplayScriptConfigDataSource(), new ReplayScriptConfigPasswordCipher(new ReplayDatabaseComparisonProperties()));
        mvc = MockMvcBuilders.standaloneSetup(
                new ReplayDatabaseComparisonScriptConfigurationController(service, resolver, json)).build();
    }

    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    private void login(String username) {
        var user = new SysUser();
        user.setUsername(username);
        user.setRealName("操作人");
        user.setStatus(1);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP", username, user));
    }

    private String body(String name, Number cap, Long revision, List<String> ignored) throws Exception {
        return json.writeValueAsString(Map.of("name", name, "revision", revision,
                "globalIgnoredFields", List.of("tenant_id", "mnt_trid"), "tables", List.of(Map.of(
                        "tableName", "accounts", "tableComment", "账户", "domainName", "存款组",
                        "ownerName", "张三", "maxPartitionNum", cap, "ignoredFields", ignored))));
    }

    private JsonNode create() throws Exception {
        var result = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(body("主键比对", 256, 0L, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.fieldCount").value(3))
                .andExpect(jsonPath("$.data.updatedBy").value("c-wangsh8"))
                .andReturn().getResponse();
        return json.readTree(result.getContentAsByteArray()).path("data");
    }

    @Test void namedConfigurationWorksWithoutHistoryAndDownloadsFrozenPrimaryKeySql() throws Exception {
        long id = create().path("id").asLong();
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.items[0].id").value(id));
        mvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.data.tables[0].fields.length()").value(4))
                .andExpect(jsonPath("$.data.tables[0].maxPartitionNum").value(256));
        when(metadata.inspectTables(anyCollection())).thenThrow(new IllegalStateException("BASE unavailable"));
        var response = mvc.perform(get(BASE + "/" + id + "/download").param("revision", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/sql;charset=UTF-8"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().exists("X-Content-SHA256"))
                .andReturn().getResponse();
        String script = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(script.contains("bcomp_max_partition_num"));
        assertTrue(script.contains("tenant_id"));
        assertTrue(script.contains("account_id"));
        assertFalse(script.contains("mnt_trid"));
        mvc.perform(get(BASE + "/" + id + "/download").param("revision", "2"))
                .andExpect(status().isConflict());
    }

    @Test void duplicateNameAndStaleEditCannotOverwriteTheSavedWinner() throws Exception {
        long id = create().path("id").asLong();
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(body("主键比对", 64, 0L, List.of())))
                .andExpect(status().isConflict());
        mvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("新名称", 100, 1L, List.of())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        mvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("过期编辑", 64, 1L, List.of())))
                .andExpect(status().isConflict());
        mvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("$.data.name").value("新名称"));
    }

    @Test void downloadRequiresAnExplicitPositiveRevision() throws Exception {
        long id = create().path("id").asLong();
        mvc.perform(get(BASE + "/" + id + "/download")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + id + "/download").param("revision", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + id + "/download").param("revision", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test void primaryKeyIgnoreFractionalCapAndInvalidNamesNeverWritePartialConfigurations() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(body("错误主键", 64, 0L, List.of("tenant_id"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errors").isArray());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(body("分数上限", 64.5, 0L, List.of())))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(body(" ", 64, 0L, List.of())))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test void createRejectsFractionalAndOverflowRevisionsBeforeMetadataLookup() throws Exception {
        var payload = (ObjectNode) json.readTree(body("修订号校验", 64, 0L, List.of()));
        for (String revision : List.of("0.9", "9223372036854775808")) {
            payload.set("revision", json.readTree(revision));
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(metadata);
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test void rejectsMissingCustomPasswordAndFractionalPortWithoutSavingABaseScript() throws Exception {
        var payload = (ObjectNode) json.readTree(body("独立数据源", 64, 0L, List.of()));
        var connection = payload.putObject("connection")
                .put("mode", "CUSTOM").put("host", "db.example.test").put("port", 5432)
                .put("database", "migration").put("schema", "public").put("username", "compare_reader");

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isBadRequest());

        connection.put("port", 5432.5).put("password", "test-only-password");
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isBadRequest());

        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.items.length()").value(0));
        verifyNoInteractions(metadata);
    }

    @Test void previewsExistingBaseConfigurationWithoutCheckingDraftIgnoresOrChangingIt() throws Exception {
        long id = create().path("id").asLong();
        var payload = (ObjectNode) json.readTree(body("主键比对", 256, 1L, List.of("removed_field")));
        payload.put("configurationId", id);

        mvc.perform(post(BASE + "/preview-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tables[0].ignoredFields.length()").value(0))
                .andExpect(jsonPath("$.data.tables[0].fields.length()").value(4));

        payload.put("revision", 2L);
        payload.putObject("connection").put("mode", "CUSTOM").put("host", "db.example.test")
                .put("database", "Migration").put("schema", "staging").put("username", "Reader")
                .put("password", "new-password");
        mvc.perform(post(BASE + "/preview-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isConflict());
        payload.remove("revision");
        mvc.perform(post(BASE + "/preview-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isBadRequest());
        payload.put("revision", 1.1);
        mvc.perform(post(BASE + "/preview-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.connection.mode").value("BASE"));
    }

    private MockMultipartFile excelFile() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("tables");
            var header = sheet.createRow(0);
            String[] labels = {"表名", "表中文名", "分组", "负责人", "最大hash分区数"};
            for (int i=0; i<labels.length; i++) header.createCell(i).setCellValue(labels[i]);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("accounts");
            row.createCell(1).setCellValue("账户");
            row.createCell(2).setCellValue("存款组");
            row.createCell(3).setCellValue("张三");
            row.createCell(4).setCellValue(256);
            workbook.write(out);
            return new MockMultipartFile("file", "tables.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    @Test void importPreviewsRealWorkbookWithFieldsButDoesNotPersist() throws Exception {
        mvc.perform(multipart(BASE + "/import-tables").file(excelFile()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tables[0].maxPartitionNum").value(256))
                .andExpect(jsonPath("$.data.tables[0].fields.length()").value(4));
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test void importAcceptsAnOrdinaryConnectionTextPartAndProtectsParseErrors() throws Exception {
        long id = create().path("id").asLong();
        var workbook = excelFile();
        // The saved CUSTOM row is from an installation whose original encryption key is unavailable.
        jdbc.update("UPDATE dii_replay_db_compare_script_config SET connection_json=?,password_ciphertext=? WHERE id=?",
                """
                {"mode":"CUSTOM","host":"db.example.test","port":5432,"database":"Migration",
                 "schema":"staging","username":"Reader"}
                """, "v1:unreadable-sentinel", id);
        byte[] source = """
                {"mode":"CUSTOM","host":"db.example.test","port":5432,"database":"Migration",
                 "schema":"staging","username":"Reader","password":""}
                """.getBytes(StandardCharsets.UTF_8);
        var part = new MockPart("connection", source);

        mvc.perform(multipart(BASE + "/import-tables").file(workbook).part(part)
                        .param("configurationId", Long.toString(id)).param("revision", "2"))
                .andExpect(status().isConflict());
        var reentry = mvc.perform(multipart(BASE + "/import-tables").file(workbook).part(part)
                        .param("configurationId", Long.toString(id)).param("revision", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("SCRIPT_CONFIG_PASSWORD_REENTRY_REQUIRED"))
                .andReturn().getResponse();
        assertFalse(reentry.getContentAsString().contains("unreadable-sentinel"));

        byte[] malformed = "{\"password\":\"sentinel-password\"".getBytes(StandardCharsets.UTF_8);
        var response = mvc.perform(multipart(BASE + "/import-tables").file(workbook)
                        .part(new MockPart("connection", malformed)))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("sentinel-password"));
        mvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("$.data.revision").value(1));
    }

    @Test void savedPlainPasswordStaysOutOfHttpListDetailAndFrozenDownload() throws Exception {
        long id = create().path("id").asLong();
        String password = "sentinel-plain-password";
        jdbc.update("UPDATE dii_replay_db_compare_script_config SET connection_json=?,password_ciphertext=? WHERE id=?",
                """
                {"mode":"CUSTOM","host":"db.example.test","port":5432,"database":"Migration",
                 "schema":"staging","username":"Reader"}
                """, "plain:v1:" + password, id);

        var detail = mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection.passwordConfigured").value(true))
                .andExpect(jsonPath("$.data.connection.password").doesNotExist())
                .andReturn().getResponse();
        var list = mvc.perform(get(BASE)).andExpect(status().isOk()).andReturn().getResponse();
        var download = mvc.perform(get(BASE + "/" + id + "/download").param("revision", "1"))
                .andExpect(status().isOk()).andReturn().getResponse();
        for (var response : List.of(list, detail, download)) {
            assertFalse(response.getContentAsString().contains(password));
            assertFalse(response.getContentAsString().contains("plain:v1:"));
        }
    }

    @Test void writesRequireHumanLoginAndRejectOversizedOrWrongFormatUploads() throws Exception {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("ANONYMOUS", null, null));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(body("未登录", 64, 0L, List.of())))
                .andExpect(status().isUnauthorized());
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved(
                "UNKNOWN", DiiTokenBypassFilter.DII_PRINCIPAL, null));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(body("口令", 64, 0L, List.of())))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/preview-metadata").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        login("c-wangsh8");
        mvc.perform(multipart(BASE + "/import-tables").file(
                        new MockMultipartFile("file", "tables.txt", "text/plain", new byte[]{1})))
                .andExpect(status().isBadRequest());
        var oversized = new MockMultipartFile("file", "tables.xlsx", "application/octet-stream", new byte[]{1}) {
            @Override public long getSize() { return 10 * 1024 * 1024L + 1; }
        };
        mvc.perform(multipart(BASE + "/import-tables").file(oversized))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get(BASE + "/999999")).andExpect(status().isNotFound());
    }
}
