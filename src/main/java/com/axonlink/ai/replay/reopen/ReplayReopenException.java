package com.axonlink.ai.replay.reopen;

/** Business failure with a stable HTTP and machine-readable code. */
public class ReplayReopenException extends RuntimeException {
    private final int status;
    private final String errorCode;

    public ReplayReopenException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public int status() { return status; }
    public String errorCode() { return errorCode; }

    public static ReplayReopenException invalid(String message) {
        return new ReplayReopenException(400, "VALIDATION", message);
    }
    public static ReplayReopenException unauthenticated() {
        return new ReplayReopenException(401, "UNAUTHENTICATED", "请使用有效账号登录后操作");
    }
    public static ReplayReopenException forbidden(String message) {
        return new ReplayReopenException(403, "FORBIDDEN", message);
    }
    public static ReplayReopenException notFound(String message) {
        return new ReplayReopenException(404, "NOT_FOUND", message);
    }
    public static ReplayReopenException conflict(String message) {
        return new ReplayReopenException(409, "CONFLICT", message);
    }
}
