package com.axonlink.ai.replay.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReplayReportFileNames {

    private static final Pattern BATCH = Pattern.compile("^(RPT|DZ)(\\d{8})-.+$");

    private ReplayReportFileNames() {
    }

    public static String daily(String batchNo) {
        BatchPart batch = parse(batchNo);
        return batch.label() + "日报-" + batch.date() + ".xlsx";
    }

    public static String weekly(String startBatchNo, String endBatchNo) {
        BatchPart start = parse(startBatchNo);
        BatchPart end = parse(endBatchNo);
        if (!start.family().equals(end.family())) {
            throw new IllegalArgumentException("周报起止批次类型必须一致");
        }
        return start.label() + "周报(" + start.date() + "-" + end.date() + ").xlsx";
    }

    private static BatchPart parse(String batchNo) {
        Matcher matcher = BATCH.matcher(batchNo == null ? "" : batchNo.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("批次号格式错误");
        }
        String family = matcher.group(1);
        return new BatchPart(family, matcher.group(2), "RPT".equals(family) ? "查询" : "账务");
    }

    private record BatchPart(String family, String date, String label) {
    }
}
