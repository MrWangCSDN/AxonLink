package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportPeriod;

import java.util.Comparator;
import java.util.List;

public class ReplayReportMailBodyComposer {

    public String compose(ReplayReportPeriod period, List<FamilyMetrics> metrics) {
        if (period == null) throw new IllegalArgumentException("邮件报告周期不能为空");
        List<FamilyMetrics> ordered = metrics == null ? List.of() : metrics.stream()
                .sorted(Comparator.comparingInt(value -> familyOrder(value.family())))
                .toList();
        if (ordered.isEmpty()) throw new IllegalArgumentException("邮件报告附件不能为空");

        StringBuilder body = new StringBuilder("各位领导、老师：\n");
        if (period == ReplayReportPeriod.WEEKLY) {
            body.append("本周回放比对主要内容如下，请查阅，谢谢。\n");
        }
        for (int index = 0; index < ordered.size(); index++) {
            FamilyMetrics value = ordered.get(index);
            body.append(familyLabel(value.family()))
                    .append("交易总交易").append(value.expectedTransactions())
                    .append("，本轮回放实发交易").append(value.actualTransactions())
                    .append("，采集交易量").append(value.collectedTransactions())
                    .append("，实发交易量").append(value.collectedTransactions())
                    .append(index == ordered.size() - 1 ? "。" : "；");
            if (index < ordered.size() - 1) body.append('\n');
        }
        return body.toString();
    }

    private static int familyOrder(String family) {
        return switch (family) {
            case "RPT" -> 0;
            case "DZ" -> 1;
            default -> throw new IllegalArgumentException("报告批次族不支持：" + family);
        };
    }

    private static String familyLabel(String family) {
        return switch (family) {
            case "RPT" -> "查询";
            case "DZ" -> "账务";
            default -> throw new IllegalArgumentException("报告批次族不支持：" + family);
        };
    }

    public record FamilyMetrics(String family, long expectedTransactions,
                                long actualTransactions, long collectedTransactions) {
    }
}
