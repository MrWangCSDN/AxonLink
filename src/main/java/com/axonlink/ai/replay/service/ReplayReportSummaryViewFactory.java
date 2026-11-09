package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayDailySummaryCalculatedRow;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.dto.ReplayReportSummaryColumn;
import com.axonlink.ai.replay.dto.ReplayReportSummaryColumn.ValueType;
import com.axonlink.ai.replay.dto.ReplayReportSummaryRow;
import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.axonlink.ai.replay.service.ReplayDailyReportCalculator.CalculatedReport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReplayReportSummaryViewFactory {

    private static final String TRANSACTION_GROUP = "交易核对分类统计";
    private static final String UNRESOLVED_GROUP = "上一批次未解决问题分类统计";
    private static final List<ReplayReportSummaryColumn> COLUMNS = List.of(
            column("batchNo", "批次", null, ValueType.TEXT, 0),
            column("domain", "领域", null, ValueType.TEXT, 1),
            column("coveredInterfaceCount", "覆盖528接口", null, ValueType.INTEGER, 2),
            column("sentTransactionCount", "发送交易量", null, ValueType.INTEGER, 3),
            column("c528SuccessCcbsFail", "528成功/CCBS失败", TRANSACTION_GROUP, ValueType.INTEGER, 4),
            column("c528FailCcbsSuccess", "528失败/CCBS成功", TRANSACTION_GROUP, ValueType.INTEGER, 5),
            column("bothFailSameCode", "二者均失败响应码一致", TRANSACTION_GROUP, ValueType.INTEGER, 6),
            column("bothFailDiffCode", "二者均失败响应码不一致", TRANSACTION_GROUP, ValueType.INTEGER, 7),
            column("bothSuccess", "二者均成功", TRANSACTION_GROUP, ValueType.INTEGER, 8),
            column("noAction", "无需处理", TRANSACTION_GROUP, ValueType.INTEGER, 9),
            column("codeIgnored", "响应码忽略", TRANSACTION_GROUP, ValueType.INTEGER, 10),
            column("successRate", "成功率", null, ValueType.PERCENT, 11),
            column("matchPassRate", "比对通过率", null, ValueType.PERCENT, 12),
            column("issueTotal", "问题总数", null, ValueType.INTEGER, 13),
            column("previousUnresolvedTotal", "上一批次未解决问题数量", null, ValueType.INTEGER, 14),
            column("previousResolutionRate", "上一批次问题解决率", null, ValueType.PERCENT, 15),
            column("unanalyzed", "未分析", UNRESOLVED_GROUP, ValueType.INTEGER, 16),
            column("analyzedPendingFix", "已分析待修复", UNRESOLVED_GROUP, ValueType.INTEGER, 17),
            column("notFullyFixed", "未完全修复", UNRESOLVED_GROUP, ValueType.INTEGER, 18),
            column("dataMigrationIssue", "数据迁移问题", UNRESOLVED_GROUP, ValueType.INTEGER, 19),
            column("codeNotReleased", "代码未发版", UNRESOLVED_GROUP, ValueType.INTEGER, 20));

    public List<ReplayReportSummaryColumn> columns() {
        return COLUMNS;
    }

    public ReplayReportSummaryView create(ReplayReportPeriod period,
                                          String startBatchNo,
                                          String endBatchNo,
                                          CalculatedReport report) {
        return create(period, startBatchNo, endBatchNo, report, null);
    }

    public ReplayReportSummaryView create(ReplayReportPeriod period, String startBatchNo, String endBatchNo,
                                          CalculatedReport report, ReplayReportCalculationContext context) {
        String family = endBatchNo != null && endBatchNo.startsWith("DZ") ? "DZ" : "RPT";
        String reportName = reportName(period, family, startBatchNo, endBatchNo);
        boolean previousAvailable = (period != ReplayReportPeriod.DAILY || hasPreviousHistory(report))
                && (context == null || context.previous().issueStatisticsAvailable());
        return new ReplayReportSummaryView(
                1,
                period,
                family,
                startBatchNo,
                endBatchNo,
                reportName,
                COLUMNS,
                report.currentRows().stream().map(row -> row(row, false, previousAvailable, context)).toList(),
                row(report.currentTotal(), true, previousAvailable, context));
    }

    static boolean hasPreviousHistory(CalculatedReport report) {
        return !report.previousRows().isEmpty() || (report.previousTotal() != null
                && report.previousTotal().batchNo() != null && !report.previousTotal().batchNo().isBlank());
    }

    private static String reportName(ReplayReportPeriod period, String family,
                                     String startBatchNo, String endBatchNo) {
        try {
            return (period == ReplayReportPeriod.WEEKLY
                    ? ReplayReportFileNames.weekly(startBatchNo, endBatchNo)
                    : ReplayReportFileNames.daily(endBatchNo)).replaceFirst("\\.xlsx$", "");
        } catch (IllegalArgumentException exception) {
            String label = "RPT".equals(family) ? "查询" : "账务";
            return label + (period == ReplayReportPeriod.WEEKLY ? "周报" : "日报") + "-" + endBatchNo;
        }
    }

    private static ReplayReportSummaryRow row(ReplayDailySummaryCalculatedRow value, boolean total,
                                              boolean previousAvailable, ReplayReportCalculationContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("batchNo", total ? "" : value.batchNo());
        values.put("domain", total ? "合计" : value.domain());
        values.put("coveredInterfaceCount", value.coveredInterfaceCount());
        values.put("sentTransactionCount", value.sentTransactionCount());
        values.put("c528SuccessCcbsFail", value.c528SuccessCcbsFail());
        values.put("c528FailCcbsSuccess", value.c528FailCcbsSuccess());
        values.put("bothFailSameCode", value.bothFailSameCode());
        values.put("bothFailDiffCode", value.bothFailDiffCode());
        values.put("bothSuccess", value.bothSuccess());
        values.put("noAction", value.noAction());
        values.put("codeIgnored", value.codeIgnored());
        values.put("successRate", value.successRate());
        values.put("matchPassRate", value.matchPassRate());
        values.put("issueTotal", value.issueTotal());
        values.put("previousUnresolvedTotal", previousAvailable ? value.previousUnresolvedTotal() : null);
        values.put("previousResolutionRate", previousAvailable ? value.previousResolutionRate() : null);
        values.put("unanalyzed", previousAvailable ? value.unanalyzed() : null);
        values.put("analyzedPendingFix", previousAvailable ? value.analyzedPendingFix() : null);
        values.put("notFullyFixed", previousAvailable ? value.notFullyFixed() : null);
        values.put("dataMigrationIssue", previousAvailable ? value.dataMigrationIssue() : null);
        values.put("codeNotReleased", previousAvailable ? value.codeNotReleased() : null);
        if (context != null) {
            var side = context.current();
            if (!side.issueStatisticsAvailable()) values.put("issueTotal", null);
            if (side.hideBreakdown(value.domain(), total)) {
                for (String key : List.of("c528SuccessCcbsFail", "c528FailCcbsSuccess", "bothFailDiffCode")) values.put(key, null);
            }
            if (side.hideNoAction(value.domain(), total)) {
                values.put("noAction", null);
            }
            if (side.hideSuccessRate(value.domain(), total)) values.put("successRate", null);
            if (side.hideMatchRate(value.domain(), total)) values.put("matchPassRate", null);
            if (total) values.put("calculationContext", context.toMap());
        }
        return new ReplayReportSummaryRow(total ? "合计" : value.domain(), total ? "TOTAL" : "DETAIL", values);
    }

    private static ReplayReportSummaryColumn column(String key, String label, String group,
                                                    ValueType valueType, int order) {
        return new ReplayReportSummaryColumn(key, label, group, valueType, order);
    }
}
