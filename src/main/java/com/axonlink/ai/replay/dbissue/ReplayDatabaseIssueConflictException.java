package com.axonlink.ai.replay.dbissue;
public class ReplayDatabaseIssueConflictException extends RuntimeException {
    public ReplayDatabaseIssueConflictException(){super("记录已更新，请刷新后重新编辑");}
}
