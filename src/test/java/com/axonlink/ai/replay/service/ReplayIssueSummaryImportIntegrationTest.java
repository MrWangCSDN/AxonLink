package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueImportResult;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayIssueSummaryDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueSummaryImportIntegrationTest {

    private JdbcTemplate jdbc;
    private ReplayIssueDao dao;
    private ReplayIssueSummaryDao summaryDao;
    private ReplayIssueImportService service;

    @BeforeEach
    void setUp() {
        jdbc = ReplayIssueTestFixtures.newJdbc();
        ReplayIssueTestFixtures.createSchema(jdbc);
        dao = new ReplayIssueDao(jdbc);
        summaryDao = new ReplayIssueSummaryDao(jdbc);
        service = new ReplayIssueImportService(new ReplayIssueExcelParser(), dao,
                new ReplayIssueImportGate(new Semaphore(1)));
    }

    @Test
    void importFileDoesNotPersistLegacySummaryRowsForVerticalLayout() throws Exception {
        MockMultipartFile file = ReplayIssueTestFixtures.workbookWithVerticalSummary(
                ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of()), ReplayIssueTestFixtures.defaultSummaryValues());

        ReplayIssueImportResult result = service.importFile(file);

        assertTrue(summaryDao.findByRound(result.coverageRound()).isEmpty());
    }

    @Test
    void importFileDoesNotPersistLegacySummaryRowsForHorizontalLayout() throws Exception {
        MockMultipartFile file = ReplayIssueTestFixtures.workbookWithHorizontalSummary(
                ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of()), ReplayIssueTestFixtures.defaultSummaryValues());

        ReplayIssueImportResult result = service.importFile(file);

        assertTrue(summaryDao.findByRound(result.coverageRound()).isEmpty());
    }

    @Test
    void importFileSkipsSummaryWhenSheetMissing() throws Exception {
        MockMultipartFile file = ReplayIssueTestFixtures.validWorkbook(1);

        ReplayIssueImportResult result = service.importFile(file);

        assertTrue(summaryDao.findByRound(result.coverageRound()).isEmpty());
    }

    @Test
    void formalImportPersistsElevenSheetsWithoutLegacySummaryRows() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(jdbc);
        service = new ReplayIssueImportService(new ReplayIssueExcelParser(), dao,
                new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        ReplayIssueImportResult result = service.importFile(ReplayIssueTestFixtures.dailyWorkbook());

        assertEquals(8, result.totalRows(), "八个问题明细 Sheet 应同时入库");
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_issue", Integer.class));
        assertTrue(summaryDao.findByRound(result.coverageRound()).isEmpty(),
                "正式导入不应继续写入遗留汇总表");
        assertEquals(4, dailyDataDao.findSummaries("RPT20260904-094201-5355").size());
    }

    @Test
    void dzImportNormalizesDetailsOccurrencesAndStoredDailyRowsTogether() throws Exception {
        ReplayDailyDataDao dailyDataDao = new ReplayDailyDataDao(jdbc);
        service = new ReplayIssueImportService(new ReplayIssueExcelParser(), dao,
                new ReplayDailyWorkbookParser(), dailyDataDao,
                Clock.fixed(Instant.parse("2026-09-05T02:00:00Z"), ZoneOffset.UTC),
                new ReplayIssueImportGate(new Semaphore(1)));

        ReplayIssueImportResult result = service.importFile(
                ReplayIssueTestFixtures.dailyWorkbook(), ReplayIssueImportMode.DZ);

        assertEquals(8, result.totalRows());
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_issue WHERE batch_no=?",
                Integer.class, "DZ20260904-094201-5355"));
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_replay_issue_occurrence_batch WHERE batch_name=?",
                Integer.class, "DZ20260904-094201-5355"));
        assertEquals(4, dailyDataDao.findSummaries("DZ20260904-094201-5355").size());
    }

    private MockMultipartFile workbookWithDetailsAndTwoSectionSummary() throws Exception {
        return workbookWithDetailsAndTwoSectionSummary(
                "RPT20260819-142055-0001", "RPT20260820-142055-0002");
    }

    private MockMultipartFile workbookWithDetailsAndTwoSectionSummary(String previousBatch,
                                                                       String currentBatch) throws Exception {
        MockMultipartFile details = ReplayIssueTestFixtures.workbook(
                ReplayIssueTestFixtures.oneRowPerTargetSheet(Map.of("批次", currentBatch)),
                ReplayIssueTestFixtures.HEADERS, true);
        try (XSSFWorkbook workbook = new XSSFWorkbook(details.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet summary = workbook.createSheet("汇总信息");
            writeSummarySection(summary, 0, previousBatch, "存款组", 528, 1000, 63.05, 39.77);
            writeSummarySection(summary, 5, currentBatch, "公共组", 256, 2000, 69.54, 45.88);
            workbook.write(output);
            return new MockMultipartFile("file", "replay-issues-with-summary.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private void writeSummarySection(Sheet sheet, int startRow, String batch, String domain,
                                     long covered, long sent, double totalSuccess, double totalMatch) {
        Row parent = sheet.createRow(startRow);
        String[] headers = {"批次", "领域", "覆盖528接口", "发送交易量", "交易核对分类统计"};
        for (int index = 0; index < headers.length; index++) parent.createCell(index).setCellValue(headers[index]);
        parent.createCell(11).setCellValue("接口成功率");
        parent.createCell(12).setCellValue("比对通过率");
        Row child = sheet.createRow(startRow + 1);
        String[] detailHeaders = {"528成功/CCBS失败", "CCBS失败明细", "528失败/CCBS成功",
                "二者均失败响应码一致", "二者均失败响应码不一致", "二者均成功", "响应码忽略"};
        for (int index = 0; index < detailHeaders.length; index++) {
            child.createCell(index + 4).setCellValue(detailHeaders[index]);
        }
        Row data = sheet.createRow(startRow + 2);
        data.createCell(0).setCellValue(batch);
        data.createCell(1).setCellValue(domain);
        data.createCell(2).setCellValue(covered);
        data.createCell(3).setCellValue(sent);
        for (int col = 4; col <= 10; col++) data.createCell(col).setCellValue((col - 2) * 50L);
        data.createCell(11).setCellValue("95.5%");
        data.createCell(12).setCellValue("98.2%");
        Row total = sheet.createRow(startRow + 3);
        total.createCell(1).setCellValue("合计");
        total.createCell(11).setCellValue(totalSuccess / 100.0);
        total.getCell(11).setCellStyle(percentStyle(workbookOf(sheet)));
        total.createCell(12).setCellValue(totalMatch / 100.0);
        total.getCell(12).setCellStyle(percentStyle(workbookOf(sheet)));
    }

    private XSSFWorkbook workbookOf(Sheet sheet) {
        return (XSSFWorkbook) sheet.getWorkbook();
    }

    private org.apache.poi.ss.usermodel.CellStyle percentStyle(XSSFWorkbook workbook) {
        org.apache.poi.ss.usermodel.CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
        return style;
    }
}
