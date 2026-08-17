package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueRow;
import com.axonlink.ai.replay.dto.ReplayIssueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueFullRefreshExcelParserTest {

    private ReplayIssueFullRefreshExcelParser parser;

    @BeforeEach
    void setUp() {
        parser = new ReplayIssueFullRefreshExcelParser();
    }

    @Test
    void importsOnlyTheFirstSheetRegardlessOfItsNameAndIgnoresRepairDates() throws Exception {
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("任意批次", List.of(row("贷款组", "ISSUE-FIRST", "KEY-FIRST", "延后修复", "否",
                "编辑后类型", "编辑后分析", "编辑后方案", "编辑后备注")));
        sheets.put("0803", List.of(row("公共组", "ISSUE-0803", "KEY-0803", "修复待验证", "是",
                "沙箱类型", "沙箱分析", "沙箱方案", "沙箱备注")));

        ReplayIssueFullRefreshExcelParser.ParsedWorkbook parsed = parser.parse(
                ReplayIssueTestFixtures.fullRefreshWorkbook(sheets));

        assertEquals(List.of("任意批次"), new ArrayList<>(parsed.rowsBySheet().keySet()));
        assertEquals(Map.of("任意批次", 1), parsed.rowsBySheet());
        assertEquals(1, parsed.rows().size());
        ReplayIssueRow first = parsed.rows().get(0);
        assertEquals("任意批次", first.sourceSheet());
        assertEquals("贷款组", first.domain());
        assertEquals("贷款组", first.groupName());
        assertEquals("编辑后类型", first.issueType());
        assertEquals("编辑后分析", first.initialAnalysis());
        assertEquals("编辑后方案", first.finalSolution());
        assertEquals("编辑后备注", first.remark());
        assertEquals(ReplayIssueStatus.DEFERRED, first.issueStatus());
        assertEquals("夏燕", first.cooperationPersonRealName());
        assertNull(first.cooperationPersonUsername());
        assertNull(first.dataRepairDate());
        assertNull(first.defectRepairDate());
        assertEquals(0, parsed.sandboxRows());
        assertEquals(1, parsed.nonSandboxRows());
    }

    @Test
    void preservesEachBlankIdentityForTransactionalAllocation() throws Exception {
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("基础数据", List.of(
                row("贷款组", "", "KEY-A", "", "否", "", "", "", ""),
                row("贷款组", "ID-B", "", "打开", "否", "", "", "", ""),
                row("贷款组", "", "", "打开", "否", "", "", "", ""),
                row("贷款组", "EXISTING-ID", "EXISTING-KEY", "打开", "否", "", "", "", "")));
        sheets.put("不会处理", List.of(
                row("公共组", "SECOND-ID", "SECOND-KEY", "打开", "是", "", "", "", "")));

        ReplayIssueFullRefreshExcelParser.ParsedWorkbook parsed = parser.parse(
                ReplayIssueTestFixtures.fullRefreshWorkbook(sheets));

        assertEquals("", parsed.rows().get(0).issueId());
        assertEquals("KEY-A", parsed.rows().get(0).issueKey());
        assertEquals("ID-B", parsed.rows().get(1).issueId());
        assertEquals("", parsed.rows().get(1).issueKey());
        assertEquals("", parsed.rows().get(2).issueId());
        assertEquals("", parsed.rows().get(2).issueKey());
        assertEquals("EXISTING-ID", parsed.rows().get(3).issueId());
        assertEquals("EXISTING-KEY", parsed.rows().get(3).issueKey());
        assertEquals(3, parsed.generatedIdentityRows());
        assertEquals(4, parsed.rows().size());
    }

    @Test
    void reportsMissingKeyHeaderWithSheetAndHeaderName() {
        List<String> headers = new ArrayList<>(ReplayIssueTestFixtures.FULL_REFRESH_HEADERS);
        headers.remove("issue_key");
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("基础数据", List.of(row("贷款组", "I-1", "K-1", "打开", "否", "", "", "", "")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(ReplayIssueTestFixtures.workbook(sheets, headers, false)));

        assertTrue(exception.getMessage().contains("基础数据"));
        assertTrue(exception.getMessage().contains("issue_key"));
    }

    @Test
    void rejectsInvalidStatusAndSandbox() {
        assertInvalidRow(row("公共组", "I-1", "K-1", "不存在", "否", "", "", "", ""), "问题状态");
        assertInvalidRow(row("公共组", "I-1", "K-1", "打开", "", "", "", "", ""), "是否沙箱");
    }

    @Test
    void rejectsDuplicateFinalKeysWithBothLocations() {
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("基础数据", List.of(
                row("贷款组", "I-1", "DUP", "打开", "否", "", "", "", ""),
                row("公共组", "I-2", "DUP", "打开", "是", "", "", "", "")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(ReplayIssueTestFixtures.fullRefreshWorkbook(sheets)));

        assertTrue(exception.getMessage().contains("第 2 行"));
        assertTrue(exception.getMessage().contains("第 3 行"));
    }

    private void assertInvalidRow(Map<String, String> invalid, String expectedMessage) {
        Map<String, List<Map<String, String>>> sheets = new LinkedHashMap<>();
        sheets.put("基础数据", List.of(invalid));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(ReplayIssueTestFixtures.fullRefreshWorkbook(sheets)));
        assertTrue(exception.getMessage().contains(expectedMessage));
    }

    private Map<String, String> row(String domain, String issueId, String issueKey, String status,
                                    String sandbox, String issueType, String analysis, String solution,
                                    String remark) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("领域", domain);
        row.put("批次", "20260731");
        row.put("交易码", "6208");
        row.put("交易名称", "对公贷款还款计划查询");
        row.put("问题级别", "交易级");
        row.put("登记日期", "2026-07-31");
        row.put("字段名", "响应码");
        row.put("问题描述", "响应不一致");
        row.put("交易负责人", "张三");
        row.put("问题类型", issueType);
        row.put("初步问题分析", analysis);
        row.put("最终处理方案", solution);
        row.put("需协同组", "公共组");
        row.put("协同人", " 夏燕 ");
        row.put("流水号", "001012213710102");
        row.put("数据修复日期", "2026-08-07");
        row.put("备注", remark);
        row.put("该问题出现过的交易笔数", "2");
        row.put("issue_id", issueId);
        row.put("issue_key", issueKey);
        row.put("历史出现次数", "1");
        row.put("首次出现日期", "2026-07-31");
        row.put("上次出现日期", "2026-08-03");
        row.put("问题状态", status);
        row.put("是否沙箱", sandbox);
        return row;
    }
}
