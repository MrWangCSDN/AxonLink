package com.axonlink.ai.code.entity;

import java.util.Date;

/**
 * 文件维度提交事实。粒度：仓库 × 文件 × 作者邮箱。
 * 生成型批量数据，不继承 BaseEntity（参照 LeaveQuotaTransaction）。每次同步对一个仓库全量刷新。
 */
public class CodeFileAuthorStat {

    private Long id;
    private Long repoId;
    private String filePath;
    private String authorName;
    private String authorEmail;
    private Long userId;
    private Integer ownedLines;
    private Integer fileTotalLines;
    private Integer addedLines;
    private Integer deletedLines;
    private Integer commitCount;
    private Date firstCommitTime;
    private Date lastCommitTime;
    private String snapshotCommit;
    private Date snapshotTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String authorEmail) { this.authorEmail = authorEmail; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getOwnedLines() { return ownedLines; }
    public void setOwnedLines(Integer ownedLines) { this.ownedLines = ownedLines; }
    public Integer getFileTotalLines() { return fileTotalLines; }
    public void setFileTotalLines(Integer fileTotalLines) { this.fileTotalLines = fileTotalLines; }
    public Integer getAddedLines() { return addedLines; }
    public void setAddedLines(Integer addedLines) { this.addedLines = addedLines; }
    public Integer getDeletedLines() { return deletedLines; }
    public void setDeletedLines(Integer deletedLines) { this.deletedLines = deletedLines; }
    public Integer getCommitCount() { return commitCount; }
    public void setCommitCount(Integer commitCount) { this.commitCount = commitCount; }
    public Date getFirstCommitTime() { return firstCommitTime; }
    public void setFirstCommitTime(Date firstCommitTime) { this.firstCommitTime = firstCommitTime; }
    public Date getLastCommitTime() { return lastCommitTime; }
    public void setLastCommitTime(Date lastCommitTime) { this.lastCommitTime = lastCommitTime; }
    public String getSnapshotCommit() { return snapshotCommit; }
    public void setSnapshotCommit(String snapshotCommit) { this.snapshotCommit = snapshotCommit; }
    public Date getSnapshotTime() { return snapshotTime; }
    public void setSnapshotTime(Date snapshotTime) { this.snapshotTime = snapshotTime; }
}
