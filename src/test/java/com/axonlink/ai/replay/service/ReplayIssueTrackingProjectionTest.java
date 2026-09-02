package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueFieldChange;
import com.axonlink.ai.replay.dto.ReplayIssueOriginalDataItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayIssueTrackingProjectionTest {

    @Test
    void returnsOnlyTrackedFieldsWithDifferentValues() {
        String before = """
                {"issueStatus":"打开","issueType":"代码问题","issueDescription":"旧描述","remark":"","id":1,"sourceRow":8}
                """;
        String after = """
                {"issueStatus":"修复待验证","issueType":"代码问题","issueDescription":"新描述","remark":"","id":1,"sourceRow":9}
                """;

        assertEquals(List.of(
                        new ReplayIssueFieldChange("问题状态", "打开", "修复待验证"),
                        new ReplayIssueFieldChange("问题描述", "旧描述", "新描述")),
                ReplayIssueTrackingProjection.fieldChanges(before, after));
    }

    @Test
    void treatsNullAndBlankAsTheSameValueForDiffs() {
        String before = "{\"remark\":null,\"issueDescription\":\"描述\"}";
        String after = "{\"remark\":\"  \",\"issueDescription\":\"描述\"}";

        assertEquals(List.of(), ReplayIssueTrackingProjection.fieldChanges(before, after));
    }

    @Test
    void projectsOnlyImportableBusinessFieldsFromIncomingSnapshot() {
        String incoming = """
                {"transactionCode":"TX001","transactionName":"客户查询","issueLevel":"高","issueDescription":"描述","issueKey":"internal","id":99}
                """;

        assertEquals(List.of(
                        new ReplayIssueOriginalDataItem("交易码", "TX001"),
                        new ReplayIssueOriginalDataItem("交易名称", "客户查询"),
                        new ReplayIssueOriginalDataItem("问题级别", "高"),
                        new ReplayIssueOriginalDataItem("问题描述", "描述")),
                ReplayIssueTrackingProjection.originalData(incoming));
    }
}
