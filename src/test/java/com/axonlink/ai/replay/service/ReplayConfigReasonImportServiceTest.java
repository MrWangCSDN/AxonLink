package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayConfigReasonImportServiceTest {
    private final ReplayConfigOperator operator = new ReplayConfigOperator("dev", "开发");
    private JdbcTemplate jdbc;
    private ReplayConfigReasonImportService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        service = new ReplayConfigReasonImportService(jdbc, new ReplayUnconditionalIgnoreReasonExcelParser());
    }

    @Test
    void conditionalImportUsesFieldIndexAndPreservesApproval() throws IOException {
        jdbc.update("INSERT INTO dii_replay_conditional_rmove "
                + "(orig_trcd,field_rmove_name,field_file_indx,field_file_flag,review_status,created_at,updated_at) "
                + "VALUES ('S1&sop','amount',1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "('S1&sop','amount',2,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var result = service.importReasons("conditional-ignores",
                excel(new String[]{"S1&sop", "amount", "2", "补充原因"}), operator);
        assertEquals(1, result.updatedRows());
        assertNull(jdbc.queryForObject("SELECT ignore_reason FROM dii_replay_conditional_rmove "
                + "WHERE field_file_indx=1", String.class));
        assertEquals("补充原因", jdbc.queryForObject("SELECT ignore_reason FROM dii_replay_conditional_rmove "
                + "WHERE field_file_indx=2 AND review_status=1 AND version=1", String.class));
        assertEquals("EXCEL", jdbc.queryForObject("SELECT operation_source "
                + "FROM dii_replay_conditional_rmove_operation WHERE new_ignore_reason='补充原因'", String.class));
    }

    @Test
    void errorCodeImportMatchesNullAndSkipsAmbiguousRows() throws IOException {
        jdbc.update("INSERT INTO dii_replay_error_code_ignore_config "
                + "(service_code,old_resp_code,new_resp_code,created_at,updated_at) VALUES "
                + "('S1&sop',NULL,'E001',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "('S1&sop',NULL,'E002',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "('S1&sop',NULL,'E002',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var result = service.importReasons("error-code-ignores", excel(
                new String[]{"S1&sop", "", "E001", "匹配空值"},
                new String[]{"S1&sop", "", "E001", "不覆盖"},
                new String[]{"S1&sop", "", "E002", "多条不写"}), operator);
        assertEquals(1, result.updatedRows());
        assertEquals(1, result.skippedExistingReasonRows());
        assertEquals(1, result.ambiguousRows());
        assertEquals("匹配空值", jdbc.queryForObject("SELECT ignore_reason "
                + "FROM dii_replay_error_code_ignore_config WHERE new_resp_code='E001'", String.class));
    }

    @Test
    void invalidSortRowPreventsEarlierValidRowFromBeingWritten() throws IOException {
        jdbc.update("INSERT INTO dii_replay_sort_field "
                + "(orig_trcd,orig_arry_name,orig_field_name,created_at,updated_at) "
                + "VALUES ('S1&sop','items','amount',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var file = excel(new String[]{"S1&sop", "items", "amount", "排序原因"},
                new String[]{"S1&sop", "items", "amount", ""});
        assertThrows(IllegalArgumentException.class, () -> service.importReasons("sort-fields", file, operator));
        assertNull(jdbc.queryForObject("SELECT ignore_reason FROM dii_replay_sort_field", String.class));
    }

    private static MockMultipartFile excel(String[]... rows) throws IOException {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            workbook.write(bytes);
            return new MockMultipartFile("file", "reasons.xlsx", null, bytes.toByteArray());
        }
    }
}
