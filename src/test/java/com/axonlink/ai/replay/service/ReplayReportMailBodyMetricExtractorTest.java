package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayReportMailBodyMetricExtractorTest {

    private final ReplayReportMailBodyMetricExtractor extractor = new ReplayReportMailBodyMetricExtractor();

    @Test
    void prefersGroupCoverageTotalAndUsesSummaryCollectedTotal() throws IOException {
        byte[] workbook = workbookWithCoverageTotals(new long[]{120, 100}, new long[]{120, 100});

        ReplayReportMailBodyMetricExtractor.Metrics metrics =
                extractor.extract(workbook, summary("RPT", 900L), "DAILY||RPT20260916-01");

        assertThat(metrics).isEqualTo(new ReplayReportMailBodyMetricExtractor.Metrics(120, 100, 900));
    }

    @Test
    void fallsBackToDomainCoverageTotalWhenGroupTotalIsMissing() throws IOException {
        byte[] workbook = workbookWithCoverageTotals(new long[]{88, 80}, null);

        assertThat(extractor.extract(workbook, summary("DZ", 700L), "DAILY||DZ20260916-01"))
                .isEqualTo(new ReplayReportMailBodyMetricExtractor.Metrics(88, 80, 700));
    }

    @Test
    void rejectsDifferentDomainAndGroupTotals() throws IOException {
        byte[] workbook = workbookWithCoverageTotals(new long[]{120, 100}, new long[]{121, 100});

        assertThatThrownBy(() -> extractor.extract(workbook, summary("RPT", 900L), "report"))
                .isInstanceOf(ReplayReportMailBodyMetricExtractor.BodyMetricUnavailableException.class)
                .hasMessageContaining("覆盖汇总合计不一致");
    }

    @Test
    void rejectsMissingCollectedTransactionTotal() throws IOException {
        byte[] workbook = workbookWithCoverageTotals(new long[]{120, 100}, new long[]{120, 100});
        ReplayReportSummaryView summary = summary("RPT", null);

        assertThatThrownBy(() -> extractor.extract(workbook, summary, "report"))
                .isInstanceOf(ReplayReportMailBodyMetricExtractor.BodyMetricUnavailableException.class)
                .hasMessageContaining("发送交易量合计缺失");
    }

    private static byte[] workbookWithCoverageTotals(long[] domainTotal, long[] groupTotal) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("回放交易覆盖情况");
            int rowIndex = 0;
            rowIndex = writeSection(sheet, rowIndex, "按业务领域汇总", domainTotal);
            writeSection(sheet, rowIndex, "按大组汇总", groupTotal);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static int writeSection(Sheet sheet, int rowIndex, String title, long[] total) {
        sheet.createRow(rowIndex++).createCell(0).setCellValue(title);
        Row header = sheet.createRow(rowIndex++);
        header.createCell(0).setCellValue("业务领域");
        header.createCell(1).setCellValue("全量清单交易数");
        header.createCell(2).setCellValue("本次已发送");
        if (total != null) {
            Row totalRow = sheet.createRow(rowIndex++);
            totalRow.createCell(0).setCellValue("合计");
            totalRow.createCell(1).setCellValue(total[0]);
            totalRow.createCell(2).setCellValue(total[1]);
        }
        return rowIndex;
    }

    private static ReplayReportSummaryView summary(String family, Long collected) {
        Map<String, Object> values = collected == null
                ? Map.of()
                : Map.of("sentTransactionCount", collected);
        return new ReplayReportSummaryView(1, ReplayReportPeriod.DAILY, family, null,
                family + "20260916-01", "报告", List.of(), List.of(),
                new ReplayReportSummaryRow("合计", "TOTAL", values));
    }
}
