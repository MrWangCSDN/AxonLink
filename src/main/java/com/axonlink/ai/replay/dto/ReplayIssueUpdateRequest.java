package com.axonlink.ai.replay.dto;

/** The six fields saved together by the replay issue editor. */
public record ReplayIssueUpdateRequest(
        ReplayIssueStatus issueStatus,
        String issueType,
        String initialAnalysis,
        String finalSolution,
        String cooperationPersonUsername,
        String remark,
        boolean sendMail) {

    public ReplayIssueUpdateRequest(ReplayIssueStatus issueStatus, String issueType, String initialAnalysis,
                                    String finalSolution, String cooperationPersonUsername) {
        this(issueStatus, issueType, initialAnalysis, finalSolution, cooperationPersonUsername, "", false);
    }

    public ReplayIssueUpdateRequest(ReplayIssueStatus issueStatus, String issueType, String initialAnalysis,
                                    String finalSolution, String cooperationPersonUsername, String remark) {
        this(issueStatus, issueType, initialAnalysis, finalSolution, cooperationPersonUsername, remark, false);
    }

    public ReplayIssueUpdateRequest {
        validateTextLength("初步问题分析", initialAnalysis);
        validateTextLength("最终处理方案", finalSolution);
        validateTextLength("备注", remark);
    }

    public void validateTextLengths() {
        validateTextLength("初步问题分析", initialAnalysis);
        validateTextLength("最终处理方案", finalSolution);
    }

    private static void validateTextLength(String label, String value) {
        if (value != null && value.length() > 500) {
            throw new IllegalArgumentException(label + "不能超过500个字符");
        }
    }
}
