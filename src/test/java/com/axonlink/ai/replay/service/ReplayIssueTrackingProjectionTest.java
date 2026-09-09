package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueFieldChange;
import com.axonlink.ai.replay.dto.ReplayIssueOriginalDataItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueTrackingProjectionTest {

    @Test
    void returnsOnlyTrackedFieldsWithDifferentValues() {
        String before = """
                {"issueStatus":"打开","issueType":"代码问题","cooperationPersonUsername":"alice","cooperationPersonRealName":"艾丽丝","initialAnalysis":"旧分析","finalSolution":"旧方案","remark":"旧备注","issueDescription":"旧描述","reviewStatus":"待审核","plannedCompletionDate":"2026-08-20"}
                """;
        String after = """
                {"issueStatus":"修复待验证","issueType":"数据问题","cooperationPersonUsername":"bob","cooperationPersonRealName":"鲍勃","initialAnalysis":"新分析","finalSolution":"新方案","remark":"新备注","issueDescription":"新描述","reviewStatus":"已审核","plannedCompletionDate":"2026-08-21"}
                """;

        assertEquals(List.of(
                        new ReplayIssueFieldChange("问题状态", "打开", "修复待验证"),
                        new ReplayIssueFieldChange("问题类型", "代码问题", "数据问题"),
                        new ReplayIssueFieldChange("需协同人", "艾丽丝(alice)", "鲍勃(bob)"),
                        new ReplayIssueFieldChange("初步问题分析", "旧分析", "新分析"),
                        new ReplayIssueFieldChange("最终处理方案", "旧方案", "新方案"),
                        new ReplayIssueFieldChange("备注", "旧备注", "新备注")),
                ReplayIssueTrackingProjection.fieldChanges(before, after));
    }

    @Test
    void treatsNullAndBlankAsTheSameValueForDiffs() {
        String before = "{\"remark\":null,\"issueDescription\":\"描述\"}";
        String after = "{\"remark\":\"  \",\"issueDescription\":\"描述\"}";

        assertEquals(List.of(), ReplayIssueTrackingProjection.fieldChanges(before, after));
    }

    @Test
    void ignoresBatchNumberChanges() {
        String before = "{\"batchNo\":\"RPT20260820-001\",\"issueStatus\":\"打开\"}";
        String after = "{\"batchNo\":\"RPT20260821-001\",\"issueStatus\":\"打开\"}";

        assertEquals(List.of(), ReplayIssueTrackingProjection.fieldChanges(before, after));
    }

    @Test
    void keepsExcludedDisplayChangesEligibleForStoredHistory() {
        String before = "{\"issueDescription\":\"旧描述\"}";
        String after = "{\"issueDescription\":\"新描述\"}";

        assertTrue(ReplayIssueTrackingProjection.hasFieldChanges(before, after));
    }

    @Test
    void projectsTheThirteenOriginalDataFieldsInDisplayOrder() {
        String incoming = """
                {"issueId":"ISSUE-99","sandbox":false,"transactionCode":"TX001","transactionName":"客户查询","issueLevel":"高","fieldName":"响应码","serialNo":"SERIAL-1","globalSerialNo":"GLOBAL-1","issueDescription":"描述","domain":"贷款组","affectedTransactionCount":"8","issueKey":"internal","firstOccurrenceDate":"2026-08-01"}
                """;

        List<ReplayIssueOriginalDataItem> items = ReplayIssueTrackingProjection.originalData(incoming);

        assertEquals(List.of(
                new ReplayIssueOriginalDataItem("issue_id", "ISSUE-99"),
                new ReplayIssueOriginalDataItem("是否沙箱", "否"),
                new ReplayIssueOriginalDataItem("交易码", "TX001"),
                new ReplayIssueOriginalDataItem("交易名称", "客户查询"),
                new ReplayIssueOriginalDataItem("问题级别", "高"),
                new ReplayIssueOriginalDataItem("字段名", "响应码"),
                new ReplayIssueOriginalDataItem("流水号", "SERIAL-1"),
                new ReplayIssueOriginalDataItem("全局流水号", "GLOBAL-1"),
                new ReplayIssueOriginalDataItem("问题描述", "描述"),
                new ReplayIssueOriginalDataItem("领域", "贷款组"),
                new ReplayIssueOriginalDataItem("出现笔数", "8"),
                new ReplayIssueOriginalDataItem("issue_key", "internal"),
                new ReplayIssueOriginalDataItem("首次出现日期", "2026-08-01")), items);
    }

    @Test
    void returnsNoOriginalDataWithoutAnIncomingTheIssueWasMissingFromTheBatch() {
        assertEquals(List.of(), ReplayIssueTrackingProjection.originalData(null));
    }
}
