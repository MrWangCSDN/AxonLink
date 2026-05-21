package com.axonlink.ai.code.entity;

import java.util.Date;

/**
 * 代码仓库配置。Phase 8 / 08-01：GitLab 源码提交分析文件维度基线。
 * enabled 默认 0，提供仓库接入参数前不参与定时分析。
 * 纯 POJO：本工程无 com.spdb BaseEntity，审计字段对系统级定时任务无意义，故不继承。
 */
public class CodeRepoConfig {

    private Long id;
    private String repoName;
    private String repoUrl;
    private String branch;
    private String localPath;
    private String credentialRef;
    private String includeExts;
    private String excludePaths;
    private String lastSyncCommit;
    private Date lastSyncTime;
    private String lastSyncStatus;
    private Integer enabled;

    /**
     * 本地只读扫描模式（非持久化，仅手动 /scan 接口临时置位）。
     * 为 true 时 GitBlameCollector 跳过 clone/fetch/reset，直接只读分析 localPath 的现有 git 仓库，
     * 绝不改动用户工作副本。持久化的 code_repo_config 行不含此标志。
     */
    private boolean localScan;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef; }
    public String getIncludeExts() { return includeExts; }
    public void setIncludeExts(String includeExts) { this.includeExts = includeExts; }
    public String getExcludePaths() { return excludePaths; }
    public void setExcludePaths(String excludePaths) { this.excludePaths = excludePaths; }
    public String getLastSyncCommit() { return lastSyncCommit; }
    public void setLastSyncCommit(String lastSyncCommit) { this.lastSyncCommit = lastSyncCommit; }
    public Date getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(Date lastSyncTime) { this.lastSyncTime = lastSyncTime; }
    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public boolean isLocalScan() { return localScan; }
    public void setLocalScan(boolean localScan) { this.localScan = localScan; }
}
