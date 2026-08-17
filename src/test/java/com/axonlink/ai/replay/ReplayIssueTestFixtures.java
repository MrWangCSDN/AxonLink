package com.axonlink.ai.replay;

import com.axonlink.ai.replay.dto.ReplayIssueRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared H2 and workbook fixtures for replay issue tests. */
public final class ReplayIssueTestFixtures {

    public static final List<String> TARGET_SHEETS = List.of(
            "公共组", "存款组", "贷款组", "结算组",
            "沙箱-公共组", "沙箱-存款组", "沙箱-贷款组", "沙箱-结算组");

    public static final List<String> HEADERS = List.of(
            "领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期", "字段名",
            "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期",
            "需协同组", "解决人员", "流水号", "数据修复日期", "备注", "该问题出现在的交易笔数",
            "issue_id", "issue_key", "历史出现次数", "首次出现日期", "上次出现日期");

    public static final List<String> FULL_REFRESH_HEADERS = List.of(
            "领域", "批次", "交易码", "交易名称", "问题级别", "登记日期", "字段名", "问题描述",
            "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协同组",
            "协同人", "流水号", "数据修复日期", "备注", "该问题出现过的交易笔数",
            "issue_id", "issue_key", "历史出现次数", "首次出现日期", "上次出现日期", "问题状态", "是否沙箱");

    private ReplayIssueTestFixtures() {
    }

    public static JdbcTemplate newJdbc() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("replay_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        return new JdbcTemplate(dataSource);
    }

    public static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE dii_replay_import_round (id BIGINT AUTO_INCREMENT PRIMARY KEY, round_code VARCHAR(64) UNIQUE NOT NULL, imported_at DATETIME NOT NULL, operator_username VARCHAR(128), operator_real_name VARCHAR(128), input_rows INT NOT NULL DEFAULT 0, created_rows INT NOT NULL DEFAULT 0, updated_rows INT NOT NULL DEFAULT 0, ignored_rows INT NOT NULL DEFAULT 0, auto_repaired_rows INT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE dii_replay_issue_round (id BIGINT AUTO_INCREMENT PRIMARY KEY, round_id BIGINT NOT NULL, replay_issue_id BIGINT NOT NULL, issue_key VARCHAR(1024) NOT NULL, appeared TINYINT NOT NULL, status_before VARCHAR(32), status_after VARCHAR(32) NOT NULL, action_type VARCHAR(64) NOT NULL, source_sheet VARCHAR(64), source_row INT, recorded_at DATETIME NOT NULL, UNIQUE(round_id, issue_key))");
        jdbc.execute("CREATE TABLE dii_replay_issue_occurrence_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, replay_issue_id BIGINT NOT NULL, issue_key VARCHAR(1024) NOT NULL, batch_name VARCHAR(128) NOT NULL, first_occurred_at DATETIME NOT NULL, last_occurred_at DATETIME NOT NULL, last_status VARCHAR(32), created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, UNIQUE(replay_issue_id, batch_name))");
        jdbc.execute("CREATE TABLE dii_replay_transaction_person (id BIGINT AUTO_INCREMENT PRIMARY KEY, domain VARCHAR(64) NOT NULL, old_transaction_code VARCHAR(64) UNIQUE NOT NULL, old_transaction_name VARCHAR(256), developer VARCHAR(512), developer_usernames VARCHAR(512), bank_owner VARCHAR(512), bank_owner_emp_nos VARCHAR(512), imported_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE dii_replay_issue ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "source_sheet VARCHAR(64) NOT NULL, group_name VARCHAR(32) NOT NULL,"
                + "is_sandbox TINYINT NOT NULL, row_order INT NOT NULL,"
                + "domain VARCHAR(64), sequence_no VARCHAR(32), batch_no VARCHAR(128),"
                + "transaction_code VARCHAR(64), transaction_name VARCHAR(256), issue_level VARCHAR(64),"
                + "registered_date VARCHAR(64), field_name VARCHAR(512), issue_description MEDIUMTEXT,"
                + "transaction_owner VARCHAR(128), issue_type VARCHAR(128), initial_analysis MEDIUMTEXT,"
                + "final_solution MEDIUMTEXT, resolved_date VARCHAR(64), cooperation_group VARCHAR(256),"
                + "resolver VARCHAR(128), serial_no VARCHAR(512), global_serial_no VARCHAR(512), data_repair_date VARCHAR(64),"
                + "remark MEDIUMTEXT, affected_transaction_count VARCHAR(32), issue_id VARCHAR(64),"
                + "issue_key VARCHAR(1024) NOT NULL, historical_occurrence_count VARCHAR(32),"
                + "first_occurrence_date VARCHAR(64), last_occurrence_date VARCHAR(64),"
                + "imported_at DATETIME NOT NULL, coverage_round VARCHAR(64),"
                + "issue_status VARCHAR(32) NOT NULL DEFAULT '打开', import_date DATE,"
                + "defect_repair_date DATE, cooperation_person_username VARCHAR(128),"
                + "cooperation_person_real_name VARCHAR(128),"
                + "INDEX idx_replay_issue_key_lookup (issue_key), UNIQUE INDEX uq_dii_replay_issue_key (issue_key))");
        jdbc.execute("CREATE TABLE dii_replay_issue_history ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, replay_issue_id BIGINT,"
                + "issue_key VARCHAR(1024) NOT NULL, operation_type VARCHAR(64) NOT NULL,"
                + "operation_at DATETIME NOT NULL, operator_username VARCHAR(128),"
                + "operator_real_name VARCHAR(128), import_date DATE, coverage_round VARCHAR(64), context_round_id BIGINT, occurrence_batch_name VARCHAR(128), source_sheet VARCHAR(64),"
                + "source_row INT, before_snapshot MEDIUMTEXT, after_snapshot MEDIUMTEXT,"
                + "issue_status VARCHAR(32), issue_type VARCHAR(128), initial_analysis MEDIUMTEXT, final_solution MEDIUMTEXT,"
                + "cooperation_person_username VARCHAR(128), cooperation_person_real_name VARCHAR(128), remark MEDIUMTEXT, incoming_snapshot MEDIUMTEXT,"
                + "INDEX idx_replay_history_key_time (issue_key, operation_at, id),"
                + "INDEX idx_replay_history_issue_time (replay_issue_id, operation_at, id))");
    }

    public static ReplayIssueRow row(String groupName, boolean sandbox, int rowOrder,
                                     String transactionCode, String issueDescription) {
        String sourceSheet = sandbox ? "沙箱-" + groupName : groupName;
        return new ReplayIssueRow(null, sourceSheet, groupName, sandbox, rowOrder,
                groupName, String.valueOf(rowOrder), "BATCH-" + rowOrder, transactionCode,
                "交易" + transactionCode, "交易级", "2026-08-04", "响应码", issueDescription,
                "张三", "数据差异", "初步分析", "处理方案", "", "", "", "001" + rowOrder,
                "", "", "1", "issue-" + rowOrder, "key-" + rowOrder, "0", "", "", null);
    }

    public static MockMultipartFile validWorkbook(int rowsPerSheet) {
        return validWorkbook(rowsPerSheet, false);
    }

    public static MockMultipartFile validWorkbook(int rowsPerSheet, boolean withAuxiliarySheet) {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        for (String sheet : TARGET_SHEETS) {
            java.util.ArrayList<Map<String, String>> sheetRows = new java.util.ArrayList<>();
            for (int i = 1; i <= rowsPerSheet; i++) {
                sheetRows.add(defaultWorkbookRow(sheet, i));
            }
            rows.put(sheet, sheetRows);
        }
        MockMultipartFile file = workbook(rows, HEADERS, true);
        return withAuxiliarySheet ? appendAuxiliarySheet(file) : file;
    }

    public static Map<String, List<Map<String, String>>> oneRowPerTargetSheet(Map<String, String> values) {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        for (String sheet : TARGET_SHEETS) {
            Map<String, String> row = defaultWorkbookRow(sheet, 1);
            row.putAll(values);
            rows.put(sheet, List.of(row));
        }
        return rows;
    }

    /**
     * When {@code includeMissingTargetSheets} is true, absent target entries become empty sheets.
     * Tests that remove a target sheet pass false and omit that key.
     */
    public static MockMultipartFile workbook(Map<String, List<Map<String, String>>> rowsBySheet,
                                             List<String> headers,
                                             boolean includeMissingTargetSheets) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (includeMissingTargetSheets) {
                for (String targetSheet : TARGET_SHEETS) {
                    writeSheet(workbook, targetSheet, headers, rowsBySheet.getOrDefault(targetSheet, List.of()));
                }
            }
            for (Map.Entry<String, List<Map<String, String>>> entry : rowsBySheet.entrySet()) {
                if (workbook.getSheet(entry.getKey()) == null) {
                    writeSheet(workbook, entry.getKey(), headers, entry.getValue());
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "replay-issues.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create test workbook", e);
        }
    }

    public static MockMultipartFile fullRefreshWorkbook(Map<String, List<Map<String, String>>> rowsBySheet) {
        return workbook(rowsBySheet, FULL_REFRESH_HEADERS, false);
    }

    private static Map<String, String> defaultWorkbookRow(String sheet, int rowOrder) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("领域", sheet.replace("沙箱-", ""));
        row.put("序号", String.valueOf(rowOrder));
        row.put("批次", "BATCH-" + rowOrder);
        row.put("交易码", "6208");
        row.put("交易名称", "对公贷款还款计划查询");
        row.put("问题级别", "交易级");
        row.put("问题描述", "CCBS响应不一致");
        row.put("问题类型", "数据差异");
        row.put("流水号", "001012213710102");
        row.put("issue_id", "000845");
        row.put("issue_key", "TRAN|6208|响应码|" + sheet + "|" + rowOrder);
        return row;
    }

    private static MockMultipartFile appendAuxiliarySheet(MockMultipartFile file) {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(workbook, "总信息", HEADERS, List.of(defaultWorkbookRow("总信息", 1)));
            workbook.write(out);
            return new MockMultipartFile("file", "replay-issues.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not append auxiliary test sheet", e);
        }
    }

    private static void writeSheet(Workbook workbook, String name, List<String> headers,
                                   List<Map<String, String>> rows) {
        Sheet sheet = workbook.createSheet(name);
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            headerRow.createCell(i).setCellValue(headers.get(i));
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row dataRow = sheet.createRow(rowIndex + 1);
            Map<String, String> values = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                String value = values.get(headers.get(columnIndex));
                if (value != null) {
                    dataRow.createCell(columnIndex).setCellValue(value);
                }
            }
        }
    }
}
