package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveCreateRequest;
import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConditionalRmoveRow;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayErrorCodeIgnoreRow;
import com.axonlink.ai.replay.dto.ReplaySortFieldCreateRequest;
import com.axonlink.ai.replay.dto.ReplaySortFieldRow;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreCreateRequest;
import com.axonlink.ai.replay.dto.ReplayUnconditionalIgnoreRow;
import com.axonlink.ai.replay.persistence.ReplayConditionalRmoveDao;
import com.axonlink.ai.replay.persistence.ReplayConfigReviewSnapshotDao;
import com.axonlink.ai.replay.persistence.ReplayErrorCodeIgnoreDao;
import com.axonlink.ai.replay.persistence.ReplaySortFieldDao;
import com.axonlink.ai.replay.persistence.ReplayUnconditionalIgnoreDao;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 忽略清单导出测试：只导已审核、固定四个 sheet、含审核人与领域等解析列。
 */
class ReplayConfigExportServiceTest {

    /** 与人员清单 bank_owner_emp_nos 匹配的审核人（fixtures 里种的是 c-lisi） */
    private static final ReplayConfigOperator REVIEWER = new ReplayConfigOperator("lisi", "李四", "c-lisi");
    private static final ReplayConfigOperator OPERATOR = new ReplayConfigOperator("zhangs3", "张三");
    private static final String REASON = "测试原因";

    private JdbcTemplate jdbc;
    private ReplayUnconditionalIgnoreService unconditionalService;
    private ReplayConditionalRmoveService conditionalService;
    private ReplayErrorCodeIgnoreService errorCodeService;
    private ReplaySortFieldService sortFieldService;
    private ReplayConfigExportService exportService;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO znzx_service "
                        + "(application_name,esf_service_code,flow_id,tran_code,function_desc,group_name) "
                        + "VALUES (?,?,?,?,?,?)",
                "app", "S1", "flow", "Y444", "描述", "公共组");
        jdbc.update("INSERT INTO dii_replay_transaction_person "
                        + "(domain,old_transaction_code,old_transaction_name,developer,developer_usernames,"
                        + "bank_owner,bank_owner_emp_nos,imported_at) VALUES (?,?,?,?,?,?,?,?)",
                "公共组", "Y444", "客户信息查询", "张三", "c-zhangs", "李四", "c-lisi",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));

        ReplayConfigPersonResolver personResolver = new ReplayConfigPersonResolver(jdbc);
        unconditionalService = new ReplayUnconditionalIgnoreService(
                new ReplayUnconditionalIgnoreDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc), personResolver);
        conditionalService = new ReplayConditionalRmoveService(
                new ReplayConditionalRmoveDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc), personResolver);
        errorCodeService = new ReplayErrorCodeIgnoreService(
                new ReplayErrorCodeIgnoreDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc), personResolver);
        sortFieldService = new ReplaySortFieldService(
                new ReplaySortFieldDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc), personResolver);

        exportService = new ReplayConfigExportService(
                new ReplayUnconditionalIgnoreDao(jdbc), new ReplayConditionalRmoveDao(jdbc),
                new ReplayErrorCodeIgnoreDao(jdbc), new ReplaySortFieldDao(jdbc),
                personResolver, new ReplayConfigReviewSnapshotDao(jdbc));
    }

    @Test
    void exportsOnlyReviewedRowsWithReviewerAndDomain() throws Exception {
        // 无条件忽略：一条已审核 + 一条未审核
        ReplayUnconditionalIgnoreRow reviewedUnconditional = unconditionalService.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "accountNo", REASON), OPERATOR);
        unconditionalService.review(reviewedUnconditional.id(), 0, REVIEWER);
        unconditionalService.create(
                new ReplayUnconditionalIgnoreCreateRequest("S1&sop", "unreviewedField", REASON), OPERATOR);

        // 有条件忽略：已审核（字段标识=2 → 导出中文）
        ReplayConditionalRmoveRow conditional = conditionalService.create(
                new ReplayConditionalRmoveCreateRequest("S1&sop", "items", 2, "type=1", "type=2", REASON),
                OPERATOR);
        conditionalService.review(conditional.id(), conditional.version(), REVIEWER);

        // 错误码忽略：已审核
        ReplayErrorCodeIgnoreRow errorCode = errorCodeService.create(
                new ReplayErrorCodeIgnoreCreateRequest("S1&sop", "E001", "N001", REASON), OPERATOR);
        errorCodeService.review(errorCode.id(), errorCode.version(), REVIEWER);

        // 排序字段：一条请求展开成 3 条，审核其中一条
        List<ReplaySortFieldRow> sortRows = sortFieldService.create(
                new ReplaySortFieldCreateRequest("Y444", "A.B", "A(C,D)", REASON), OPERATOR);
        sortFieldService.review(sortRows.get(0).id(), sortRows.get(0).version(), REVIEWER);

        byte[] bytes = exportService.exportReviewedConfigs();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(4, wb.getNumberOfSheets(), "固定导出四个 sheet");
            assertEquals(List.of("无条件忽略", "有条件忽略", "错误码忽略", "排序字段"),
                    List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2), wb.getSheetName(3)));

            // ---- 无条件忽略 ----
            Sheet un = wb.getSheet("无条件忽略");
            assertEquals(11, un.getRow(0).getLastCellNum(), "无条件忽略应为 11 列");
            List<String> header = rowValues(un, 0);
            assertEquals(List.of("交易码", "内部核心交易码", "领域", "忽略字段", "忽略原因",
                    "审核人", "审核通过时间", "开发人员", "行方负责人", "最后更新时间", "创建时间"), header);
            assertEquals(1, un.getLastRowNum(), "只导已审核：应只有 1 行数据");
            List<String> line = rowValues(un, 1);
            assertEquals("S1&sop", line.get(0));
            assertEquals("Y444", line.get(1));
            assertEquals("公共组", line.get(2));
            assertEquals("accountNo", line.get(3));
            assertEquals(REASON, line.get(4));
            assertEquals("李四（lisi）", line.get(5), "审核人取最后一次 REVIEW 的姓名（账号）");
            assertFalse(line.get(6).isEmpty(), "审核通过时间应有值");
            assertEquals("张三", line.get(7));
            assertEquals("李四", line.get(8));
            assertFalse(line.get(9).isEmpty(), "最后更新时间应有值");
            assertFalse(line.get(10).isEmpty(), "创建时间应有值");

            // ---- 有条件忽略 ----
            Sheet cond = wb.getSheet("有条件忽略");
            assertEquals(List.of("交易码", "内部核心交易码", "领域", "忽略字段", "字段索引", "字段标识",
                            "主系统字段忽略条件", "备系统字段忽略条件", "忽略原因", "审核人", "审核通过时间",
                            "开发人员", "行方负责人", "最后更新时间", "创建时间"),
                    rowValues(cond, 0));
            assertEquals(1, cond.getLastRowNum());
            List<String> condLine = rowValues(cond, 1);
            assertEquals("items", condLine.get(3));
            assertEquals("1", condLine.get(4), "字段索引");
            assertEquals("对象或数组", condLine.get(5), "字段标识应导出中文");
            assertEquals("type=1", condLine.get(6));
            assertEquals("type=2", condLine.get(7));

            // ---- 错误码忽略 ----
            Sheet err = wb.getSheet("错误码忽略");
            assertEquals(List.of("交易码", "内部核心交易码", "领域", "老核心错误码", "新核心错误码", "忽略原因",
                    "审核人", "审核通过时间", "开发人员", "行方负责人", "最后更新时间", "创建时间"),
                    rowValues(err, 0));
            assertEquals(1, err.getLastRowNum());
            assertEquals("E001", rowValues(err, 1).get(3));
            assertEquals("N001", rowValues(err, 1).get(4));

            // ---- 排序字段 ----
            Sheet sort = wb.getSheet("排序字段");
            assertEquals(List.of("交易码", "内部核心交易码", "领域", "对象/数组名称", "排序字段", "忽略原因",
                    "审核人", "审核通过时间", "开发人员", "行方负责人", "最后更新时间", "创建时间"),
                    rowValues(sort, 0));
            assertEquals(1, sort.getLastRowNum(), "3 条里只导已审核的 1 条");
            List<String> sortLine = rowValues(sort, 1);
            assertEquals("S1&sop", sortLine.get(0));
            assertEquals("A", sortLine.get(3), "对象/数组名称");
            assertEquals("B", sortLine.get(4), "排序字段");
        }
    }

    @Test
    void emptyTablesStillExportFourSheetsWithHeadersOnly() throws Exception {
        byte[] bytes = exportService.exportReviewedConfigs();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(4, wb.getNumberOfSheets());
            for (int i = 0; i < 4; i++) {
                Sheet sheet = wb.getSheetAt(i);
                assertEquals(0, sheet.getLastRowNum(), sheet.getSheetName() + " 应只有表头行");
                assertTrue(rowValues(sheet, 0).get(0).equals("交易码"), "首列应为交易码");
            }
        }
    }

    private static List<String> rowValues(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        List<String> values = new java.util.ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            values.add(cell == null ? "" : cell.getStringCellValue());
        }
        return values;
    }
}
