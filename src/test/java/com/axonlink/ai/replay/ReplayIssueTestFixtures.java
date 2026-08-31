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
import java.util.ArrayList;
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
        jdbc.execute("CREATE TABLE dii_replay_issue_summary (id BIGINT AUTO_INCREMENT PRIMARY KEY, round_code VARCHAR(64) NOT NULL, batch_no VARCHAR(128), domain VARCHAR(64), covered_interface_count BIGINT, sent_transaction_count BIGINT, c528_success_ccbs_fail BIGINT, ccbs_failure_detail BIGINT, c528_fail_ccbs_success BIGINT, both_fail_same_code BIGINT, both_fail_diff_code BIGINT, both_success BIGINT, code_ignored BIGINT, success_rate DECIMAL(10,4), match_pass_rate DECIMAL(10,4), raw_json CLOB, imported_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE dii_replay_issue_round (id BIGINT AUTO_INCREMENT PRIMARY KEY, round_id BIGINT NOT NULL, replay_issue_id BIGINT NOT NULL, issue_key VARCHAR(1024) NOT NULL, appeared TINYINT NOT NULL, status_before VARCHAR(32), status_after VARCHAR(32) NOT NULL, action_type VARCHAR(64) NOT NULL, source_sheet VARCHAR(64), source_row INT, recorded_at DATETIME NOT NULL, UNIQUE(round_id, issue_key))");
        jdbc.execute("CREATE TABLE dii_replay_issue_occurrence_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, replay_issue_id BIGINT NOT NULL, issue_key VARCHAR(1024) NOT NULL, batch_name VARCHAR(128) NOT NULL, first_occurred_at DATETIME NOT NULL, last_occurred_at DATETIME NOT NULL, last_status VARCHAR(32), created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, UNIQUE(replay_issue_id, batch_name))");
        jdbc.execute("CREATE TABLE dii_replay_weekly_task_batch (batch_name VARCHAR(128) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE dii_replay_transaction_person (id BIGINT AUTO_INCREMENT PRIMARY KEY, domain VARCHAR(64) NOT NULL, old_transaction_code VARCHAR(64) UNIQUE NOT NULL, old_transaction_name VARCHAR(256), developer VARCHAR(512), developer_usernames VARCHAR(512), bank_owner VARCHAR(512), bank_owner_emp_nos VARCHAR(512), imported_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE dii_replay_issue ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "source_sheet VARCHAR(64) NOT NULL, group_name VARCHAR(32) NOT NULL, issue_domain VARCHAR(32),"
                + "is_sandbox TINYINT NOT NULL, row_order INT NOT NULL,"
                + "domain VARCHAR(64), sequence_no VARCHAR(32), batch_no VARCHAR(128),"
                + "transaction_code VARCHAR(64), transaction_name VARCHAR(256), issue_level VARCHAR(64),"
                + "registered_date VARCHAR(64), field_name VARCHAR(512), issue_description MEDIUMTEXT, planned_completion_date DATE,"
                + "transaction_owner VARCHAR(128), issue_type VARCHAR(128), initial_analysis MEDIUMTEXT,"
                + "final_solution MEDIUMTEXT, resolved_date VARCHAR(64), cooperation_group VARCHAR(256),"
                + "resolver VARCHAR(128), serial_no VARCHAR(512), global_serial_no VARCHAR(512), data_repair_date VARCHAR(64),"
                + "remark MEDIUMTEXT, affected_transaction_count VARCHAR(32), issue_id VARCHAR(64),"
                + "issue_key VARCHAR(1024) NOT NULL, historical_occurrence_count VARCHAR(32),"
                + "first_occurrence_date VARCHAR(64), last_occurrence_date VARCHAR(64),"
                + "imported_at DATETIME NOT NULL, coverage_round VARCHAR(64),"
                + "issue_status VARCHAR(32) NOT NULL DEFAULT '打开', import_date DATE,"
                + "defect_repair_date DATE, cooperation_person_username VARCHAR(128),"
                + "cooperation_person_real_name VARCHAR(128), review_status VARCHAR(16), reviewer_username VARCHAR(128),"
                + "reviewer_real_name VARCHAR(128), reviewed_at DATETIME,"
                + "INDEX idx_replay_issue_key_lookup (issue_key), UNIQUE INDEX uq_dii_replay_issue_key (issue_key))");
        jdbc.execute("CREATE TABLE dii_replay_issue_domain_transfer ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, replay_issue_id BIGINT NOT NULL, issue_key VARCHAR(1024) NOT NULL,"
                + "from_domain VARCHAR(32) NOT NULL, to_domain VARCHAR(32) NOT NULL, operator_username VARCHAR(128),"
                + "operator_real_name VARCHAR(128), transferred_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE dii_replay_issue_plan_date_change ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, replay_issue_id BIGINT NOT NULL, issue_key VARCHAR(1024) NOT NULL,"
                + "planned_completion_date DATE, operator_username VARCHAR(128), operator_real_name VARCHAR(128),"
                + "changed_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE dii_replay_issue_history ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, replay_issue_id BIGINT,"
                + "issue_key VARCHAR(1024) NOT NULL, operation_type VARCHAR(64) NOT NULL,"
                + "operation_at DATETIME NOT NULL, operator_username VARCHAR(128),"
                + "operator_real_name VARCHAR(128), import_date DATE, coverage_round VARCHAR(64), context_round_id BIGINT, occurrence_batch_name VARCHAR(128), source_sheet VARCHAR(64),"
                + "source_row INT, before_snapshot MEDIUMTEXT, after_snapshot MEDIUMTEXT,"
                + "issue_status VARCHAR(32), issue_type VARCHAR(128), initial_analysis MEDIUMTEXT, final_solution MEDIUMTEXT,"
                + "cooperation_person_username VARCHAR(128), cooperation_person_real_name VARCHAR(128), remark MEDIUMTEXT, incoming_snapshot MEDIUMTEXT,"
                + "review_status VARCHAR(16), reviewer_username VARCHAR(128), reviewer_real_name VARCHAR(128), reviewed_at DATETIME,"
                + "INDEX idx_replay_history_key_time (issue_key, operation_at, id),"
                + "INDEX idx_replay_history_issue_time (replay_issue_id, operation_at, id))");
    }

    public static ReplayIssueRow row(String groupName, boolean sandbox, int rowOrder,
                                     String transactionCode, String issueDescription) {
        String sourceSheet = sandbox ? "沙箱-" + groupName : groupName;
        return new ReplayIssueRow(null, sourceSheet, groupName, sandbox, rowOrder,
                groupName, String.valueOf(rowOrder), "RPT20260820-142055-" + String.format("%04d", rowOrder), transactionCode,
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

    /** 「汇总信息」页签 · 竖排布局（字段名同列、值在右侧）。 */
    public static MockMultipartFile workbookWithVerticalSummary(Map<String, List<Map<String, String>>> rowsBySheet,
                                                                Map<String, String> summaryValues) {
        return workbookWithSummary(rowsBySheet, summaryValues, false);
    }

    /** 「汇总信息」页签 · 横排布局（字段名同行、值在下方）。 */
    public static MockMultipartFile workbookWithHorizontalSummary(Map<String, List<Map<String, String>>> rowsBySheet,
                                                                  Map<String, String> summaryValues) {
        return workbookWithSummary(rowsBySheet, summaryValues, true);
    }

    private static MockMultipartFile workbookWithSummary(Map<String, List<Map<String, String>>> rowsBySheet,
                                                         Map<String, String> summaryValues, boolean horizontal) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String targetSheet : TARGET_SHEETS) {
                writeSheet(workbook, targetSheet, HEADERS, rowsBySheet.getOrDefault(targetSheet, List.of()));
            }
            Sheet summary = workbook.createSheet("汇总信息");
            if (horizontal) {
                writeHorizontalSummary(summary, summaryValues);
            } else {
                writeVerticalSummary(summary, summaryValues);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "replay-issues.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create test workbook with summary sheet", e);
        }
    }

    private static void writeVerticalSummary(Sheet summary, Map<String, String> values) {
        List<String> labels = new ArrayList<>(values.keySet());
        int rowIndex = 0;
        for (String label : labels) {
            Row row = summary.createRow(rowIndex++);
            row.createCell(0).setCellValue(label);
            String value = values.get(label);
            if (value != null) {
                row.createCell(1).setCellValue(value);
            }
        }
    }

    private static void writeHorizontalSummary(Sheet summary, Map<String, String> values) {
        List<String> labels = new ArrayList<>(values.keySet());
        Row headerRow = summary.createRow(0);
        for (int i = 0; i < labels.size(); i++) {
            headerRow.createCell(i).setCellValue(labels.get(i));
        }
        Row dataRow = summary.createRow(1);
        for (int i = 0; i < labels.size(); i++) {
            String value = values.get(labels.get(i));
            if (value != null) {
                dataRow.createCell(i).setCellValue(value);
            }
        }
    }

    /** 默认的汇总信息值（竖排/横排共用）。 */
    public static Map<String, String> defaultSummaryValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("批次", "20260815-01");
        values.put("领域", "存款组");
        values.put("覆盖528接口", "528");
        values.put("发送交易量", "12,345");
        // 用带括号的真实表头名（与 Excel 一致），验证别名匹配
        values.put("528成功/CCBS失败(本批笔数/总笔数)", "100");
        values.put("CCBS失败明细", "150");
        values.put("528失败/CCBS成功", "200");
        values.put("二者均失败响应码一致", "300");
        values.put("二者均失败响应码不一致", "400");
        values.put("二者均成功", "500");
        values.put("响应码忽略", "600");
        // Excel 里实际叫"接口成功率"，验证别名映射到 successRate
        values.put("接口成功率", "95.5%");
        values.put("比对通过率", "98.2");
        return values;
    }

    /**
     * 真实场景：横排 header + 多领域数据行 + 同批次合并单元格 + 末尾"合计"行 + "问题清单"等注释行。
     * <p>模拟用户 Excel 的真实结构（批次列合并、6 子项、合计行、说明字段）。
     */
    public static MockMultipartFile workbookWithRealisticHorizontalSummary(
            Map<String, List<Map<String, String>>> rowsBySheet,
            List<List<String>> dataRows,
            List<String> trailingRows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String targetSheet : TARGET_SHEETS) {
                writeSheet(workbook, targetSheet, HEADERS, rowsBySheet.getOrDefault(targetSheet, List.of()));
            }
            Sheet summary = workbook.createSheet("汇总信息");
            // 与真实 Excel「汇总信息」表头一致（12 列；不含"528失败/CCBS成功"，该字段只在右侧小表）
            List<String> labels = List.of(
                    "批次", "领域", "覆盖528接口", "发送交易量",
                    "528成功/CCBS失败(本批笔数/总笔数)", "CCBS失败明细",
                    "二者均失败响应码一致", "二者均失败响应码不一致",
                    "二者均成功", "响应码忽略", "接口成功率", "比对通过率");
            Row headerRow = summary.createRow(0);
            for (int i = 0; i < labels.size(); i++) {
                headerRow.createCell(i).setCellValue(labels.get(i));
            }
            // 数据行：合并"批次"列（同批次多领域）
            int nextRowIndex = 1;
            for (List<String> dataRow : dataRows) {
                Row row = summary.createRow(nextRowIndex++);
                for (int colIndex = 0; colIndex < labels.size(); colIndex++) {
                    String value = colIndex < dataRow.size() ? dataRow.get(colIndex) : null;
                    if (value != null) {
                        row.createCell(colIndex).setCellValue(value);
                    }
                }
            }
            // 把连续同批次行的"批次"列做合并（POI 行为）
            int start = 1;
            while (start < nextRowIndex) {
                Row startRow = summary.getRow(start);
                String startBatch = startRow == null ? null : startRow.getCell(0) == null
                        ? null : startRow.getCell(0).getStringCellValue();
                int end = start;
                while (end + 1 < nextRowIndex) {
                    Row next = summary.getRow(end + 1);
                    String nextBatch = next == null ? null : next.getCell(0) == null
                            ? null : next.getCell(0).getStringCellValue();
                    if (startBatch != null && startBatch.equals(nextBatch)) {
                        end++;
                    } else {
                        break;
                    }
                }
                if (end > start) {
                    summary.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(start, end, 0, 0));
                    // 合并区域中除左上角外的 cell 设为 null，模拟 POI 真实读取
                    for (int r = start + 1; r <= end; r++) {
                        Row mergedRow = summary.getRow(r);
                        if (mergedRow != null) {
                            mergedRow.removeCell(mergedRow.getCell(0));
                        }
                    }
                }
                start = end + 1;
            }
            // 末尾追加"合计"行 + "问题清单"等说明行
            for (String trailing : trailingRows) {
                Row row = summary.createRow(nextRowIndex++);
                row.createCell(0).setCellValue(trailing);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "replay-issues.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create realistic summary workbook", e);
        }
    }

    private static Map<String, String> defaultWorkbookRow(String sheet, int rowOrder) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("领域", sheet.replace("沙箱-", ""));
        row.put("序号", String.valueOf(rowOrder));
        row.put("批次", "RPT20260820-142055-" + String.format("%04d", rowOrder));
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
