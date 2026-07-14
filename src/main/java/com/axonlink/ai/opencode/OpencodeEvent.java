package com.axonlink.ai.opencode;

/**
 * opencode /event SSE 事件的归一化表示。
 * 原始事件种类很多，网关层只关心四类：文本增量、工具动作、会话结束、错误；其余归为 OTHER。
 */
public class OpencodeEvent {

    public enum Kind { TEXT, TOOL, DONE, ERROR, OTHER }

    private final Kind kind;
    private final String sessionId;
    private final String text;
    private final String toolName;
    private final String toolStatus;
    private final String errorMessage;

    private OpencodeEvent(Kind kind, String sessionId, String text,
                          String toolName, String toolStatus, String errorMessage) {
        this.kind = kind;
        this.sessionId = sessionId;
        this.text = text;
        this.toolName = toolName;
        this.toolStatus = toolStatus;
        this.errorMessage = errorMessage;
    }

    /** 文本增量事件（源自 message.part.delta，text 为增量片段而非全量）。 */
    public static OpencodeEvent text(String sessionId, String text) {
        return new OpencodeEvent(Kind.TEXT, sessionId, text, null, null, null);
    }

    public static OpencodeEvent tool(String sessionId, String toolName, String toolStatus) {
        return new OpencodeEvent(Kind.TOOL, sessionId, null, toolName, toolStatus, null);
    }

    public static OpencodeEvent done(String sessionId) {
        return new OpencodeEvent(Kind.DONE, sessionId, null, null, null, null);
    }

    public static OpencodeEvent error(String sessionId, String message) {
        return new OpencodeEvent(Kind.ERROR, sessionId, null, null, null, message);
    }

    public static OpencodeEvent other() {
        return new OpencodeEvent(Kind.OTHER, null, null, null, null, null);
    }

    public Kind getKind() { return kind; }
    public String getSessionId() { return sessionId; }
    public String getText() { return text; }
    public String getToolName() { return toolName; }
    public String getToolStatus() { return toolStatus; }
    public String getErrorMessage() { return errorMessage; }

    /** 终止事件：收到后本次深度分析的事件消费循环应退出。 */
    public boolean isTerminal() { return kind == Kind.DONE || kind == Kind.ERROR; }

    /** 简洁单行，方便 Gateway 日志排障；text 过长时截断。 */
    @Override
    public String toString() {
        return "OpencodeEvent{kind=" + kind
                + ", sessionId=" + sessionId
                + ", text=" + truncate(text)
                + ", toolName=" + toolName
                + ", toolStatus=" + toolStatus
                + '}';
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}
