package com.axonlink.ai.replay.dto;

/**
 * 一行「汇总信息」页签解析结果：某 group+sandbox 在某段（半部分）的统计快照。
 *
 * <p>part 标识该行属于上半部分（UPPER）还是下半部分（LOWER）。
 * 上半部分：上一批次的统计；下半部分：本批次的统计。
 *
 * <p>该 DTO 只保存 Excel 给的交易核对分类原始子项、接口成功率和比对通过率等静态列；
 * 日报新增的“合理差异”不进入该 DTO，而是按区域批次从问题清单实时计算。
 * "问题总数"、"按 issue_type 分列"、"按 status 5 子项分类"、"上轮问题解决率"、"排查进度/解决进度"等
 * 动态指标由日报层在生成时按 occurrence 表实时算（不依赖 Excel 给的值）。
 */
public record ReplayIssueSummaryRow(
        String batchNo,
        String domain,
        Long coveredInterfaceCount,
        Long sentTransactionCount,
        Long c528SuccessCcbsFail,
        Long ccbsFailureDetail,
        Long c528FailCcbsSuccess,
        Long bothFailSameCode,
        Long bothFailDiffCode,
        Long bothSuccess,
        Long codeIgnored,
        Double successRate,
        Double matchPassRate,
        Part part,
        String rawJson) {

    /** 上半/下半部分标记。 */
    public enum Part {
        UPPER, LOWER
    }

    /** 兼容旧调用方：未提供 ccbsFailureDetail / part / rawJson（默认 UPPER）。 */
    public ReplayIssueSummaryRow(String batchNo, String domain, Long coveredInterfaceCount,
                                 Long sentTransactionCount, Long c528SuccessCcbsFail,
                                 Long c528FailCcbsSuccess, Long bothFailSameCode,
                                 Long bothFailDiffCode, Long bothSuccess, Long codeIgnored,
                                 Double successRate, Double matchPassRate) {
        this(batchNo, domain, coveredInterfaceCount, sentTransactionCount, c528SuccessCcbsFail, null,
                c528FailCcbsSuccess, bothFailSameCode, bothFailDiffCode, bothSuccess, codeIgnored,
                successRate, matchPassRate, Part.UPPER, null);
    }

    /** 兼容旧调用方：未提供 ccbsFailureDetail / part（含 rawJson）。 */
    public ReplayIssueSummaryRow(String batchNo, String domain, Long coveredInterfaceCount,
                                 Long sentTransactionCount, Long c528SuccessCcbsFail,
                                 Long c528FailCcbsSuccess, Long bothFailSameCode,
                                 Long bothFailDiffCode, Long bothSuccess, Long codeIgnored,
                                 Double successRate, Double matchPassRate, String rawJson) {
        this(batchNo, domain, coveredInterfaceCount, sentTransactionCount, c528SuccessCcbsFail, null,
                c528FailCcbsSuccess, bothFailSameCode, bothFailDiffCode, bothSuccess, codeIgnored,
                successRate, matchPassRate, Part.UPPER, rawJson);
    }

    /** 兼容旧调用方：未提供 part（含 ccbsFailureDetail + rawJson）。 */
    public ReplayIssueSummaryRow(String batchNo, String domain, Long coveredInterfaceCount,
                                 Long sentTransactionCount, Long c528SuccessCcbsFail,
                                 Long ccbsFailureDetail, Long c528FailCcbsSuccess,
                                 Long bothFailSameCode,
                                 Long bothFailDiffCode, Long bothSuccess, Long codeIgnored,
                                 Double successRate, Double matchPassRate, Part part) {
        this(batchNo, domain, coveredInterfaceCount, sentTransactionCount, c528SuccessCcbsFail,
                ccbsFailureDetail, c528FailCcbsSuccess, bothFailSameCode, bothFailDiffCode, bothSuccess,
                codeIgnored, successRate, matchPassRate, part, null);
    }

    /** 是否为全空行（无任何有效字段）。 */
    public boolean isEmpty() {
        return isBlank(batchNo) && isBlank(domain)
                && coveredInterfaceCount == null && sentTransactionCount == null
                && c528SuccessCcbsFail == null && ccbsFailureDetail == null
                && c528FailCcbsSuccess == null && bothFailSameCode == null
                && bothFailDiffCode == null && bothSuccess == null && codeIgnored == null
                && successRate == null && matchPassRate == null;
    }

    /** 是否为可写入日报的领域静态指标行。 */
    public boolean isStaticDataRow() {
        return !isBlank(domain)
                && (coveredInterfaceCount != null || sentTransactionCount != null
                || c528SuccessCcbsFail != null || ccbsFailureDetail != null
                || c528FailCcbsSuccess != null || bothFailSameCode != null
                || bothFailDiffCode != null || bothSuccess != null || codeIgnored != null
                || successRate != null || matchPassRate != null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
