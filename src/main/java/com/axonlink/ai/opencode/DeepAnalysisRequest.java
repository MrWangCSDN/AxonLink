package com.axonlink.ai.opencode;

/** 深度分析请求体（前端传入）。 */
public class DeepAnalysisRequest {

    /** 用户问题；为空时用默认分析指令。 */
    private String question = "";

    /** 复用现有分析会话 id（可空，空则新生成）。 */
    private String sessionId;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
