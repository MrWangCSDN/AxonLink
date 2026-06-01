package com.axonlink.ai.code.service;

import com.axonlink.ai.code.entity.CodeRepoConfig;
import com.axonlink.ai.code.persistence.CodeFileAuthorStatDao;
import com.axonlink.ai.code.persistence.CodeRepoConfigDao;
import com.axonlink.ai.code.persistence.CodeRepoDailyStatDao;
import com.axonlink.ai.code.persistence.CodeDashboardDao;
import com.axonlink.ai.code.service.GitBlameCollector.CollectResult;
import com.axonlink.ai.code.service.GitBlameCollector.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 8 / 08-01 编排：拉取启用的仓库 -> 采集 -> 刷新文件维度事实表。
 *
 * <p>同步状态直接回写 {@code code_repo_config} 的 last_sync_* 三列（不引入任务表，
 * 本工程无 com.spdb SysTask 基础设施；系统级定时任务无真实用户上下文）。
 * 全局开关 {@code code-analysis.enabled} 关闭时整体 no-op。
 * 规则驱动的交易维度二次聚合属 08-02。
 */
@Service
public class CodeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalysisService.class);

    @Autowired
    private CodeRepoConfigDao repoDao;

    @Autowired
    private CodeFileAuthorStatDao statDao;

    @Autowired
    private GitBlameCollector collector;

    @Autowired
    private CodeDashboardAggregator aggregator;

    @Autowired
    private CodeRepoDailyStatDao dailyStatDao;

    @Autowired
    private CodeDashboardDao dashboardDao;

    @Value("${code-analysis.enabled:false}")
    private boolean globalEnabled;

    /**
     * project.workspace-roots：逗号分隔的本地工程根目录，每个根目录下的直接子目录
     * 若含 .git 则视为 git 仓库，自动纳入每日扫描（保留增量基线，支持增量模式）。
     * 空字符串 = 不启用 workspace-roots 扫描。
     */
    @Value("${project.workspace-roots:}")
    private String workspaceRoots;

    /** 调度入口：异步跑，避免阻塞调度线程；逐仓库隔离失败。 */
    @Async
    public void runAllAsync() {
        if (!globalEnabled) {
            log.info("code-analysis.enabled=false，跳过源码提交分析");
            return;
        }
        // ① enabled=1 的远程/显式配置仓库
        List<CodeRepoConfig> repos = repoDao.selectEnabled();
        if (!repos.isEmpty()) {
            log.info("开始源码提交分析，启用仓库数={}", repos.size());
            for (CodeRepoConfig repo : repos) {
                try {
                    syncRepo(repo);
                } catch (Exception e) {
                    log.error("仓库[{}] 源码提交分析失败", repo.getRepoName(), e);
                }
            }
        }
        // ② workspace-roots 自动发现的本地仓库
        scanWorkspaceRoots();
    }

    /**
     * 扫描 project.workspace-roots 下的直接子目录，含 .git 的视为 git 仓库，
     * get-or-create code_repo_config（enabled=0），保留 lastSyncCommit 支持增量模式，
     * 不 clone / fetch / reset。
     */
    private void scanWorkspaceRoots() {
        if (workspaceRoots == null || workspaceRoots.isBlank()) return;
        for (String part : workspaceRoots.split(",")) {
            String path = part.trim();
            if (path.isEmpty()) continue;
            File root = new File(path);
            if (!root.isDirectory()) {
                log.warn("workspace-roots 路径不存在或非目录，跳过：{}", path);
                continue;
            }
            File[] children = root.listFiles(File::isDirectory);
            if (children == null || children.length == 0) continue;
            log.info("workspace-roots[{}] 扫描中，发现子目录 {} 个", path, children.length);
            for (File dir : children) {
                if (!new File(dir, ".git").exists()) continue;
                try {
                    syncLocalRepo(dir);
                } catch (Exception e) {
                    log.error("workspace-roots 仓库[{}] 扫描失败", dir.getName(), e);
                }
            }
        }
    }

    /**
     * workspace-roots 发现的单仓库本地同步：
     * <ul>
     *   <li>get-or-create code_repo_config（enabled=0，定时任务不通过 enabled=1 处理）</li>
     *   <li>路径变化时自动更新 local_path</li>
     *   <li>不清 lastSyncCommit，首次=全量；后续=增量（与远程仓库同等对待）</li>
     * </ul>
     */
    private void syncLocalRepo(File dir) {
        String repoName = dir.getName();
        String localPath = dir.getAbsolutePath();

        CodeRepoConfig cfg = repoDao.findByRepoName(repoName);
        if (cfg == null) {
            cfg = new CodeRepoConfig();
            cfg.setRepoName(repoName);
            cfg.setRepoUrl("local:" + localPath);
            cfg.setLocalPath(localPath);
            cfg.setEnabled(0);
            long id = repoDao.insert(cfg);
            cfg.setId(id);
            log.info("workspace-roots 新增仓库配置[{}] id={}", repoName, id);
        } else if (!localPath.equals(cfg.getLocalPath())) {
            cfg.setLocalPath(localPath);
            cfg.setRepoUrl("local:" + localPath);
            repoDao.updateLocalConfig(cfg);
            log.info("workspace-roots 仓库[{}] 路径已更新 -> {}", repoName, localPath);
        }
        cfg.setLocalScan(true);   // 只读模式：不 clone/fetch/reset
        // 不清 lastSyncCommit，保留增量基线
        doSync(cfg);
    }

    /** 单仓库同步：采集 -> 全量替换 / 增量删改 -> 回写仓库同步状态。 */
    public void syncRepo(CodeRepoConfig repo) {
        doSync(repo);
    }

    /** 采集 + 落库 + 聚合，返回采集结果（供 /scan 汇总）。 */
    private CollectResult doSync(CodeRepoConfig repo) {
        try {
            CollectResult res = collector.collect(repo);

            if (!res.changed) {
                updateRepoState(repo, repo.getLastSyncCommit(), "NO_CHANGE");
                log.info("仓库[{}] 无变更：HEAD 未变化 {}", repo.getRepoName(), shortSha(res.headSha));
                return res;
            }

            int n = res.stats.size();
            if (res.mode == Mode.FULL) {
                statDao.deleteByRepoId(repo.getId());
                statDao.batchInsert(res.stats);
            } else {
                int del = statDao.deleteByRepoIdAndPaths(repo.getId(), res.pathsToDelete);
                statDao.batchInsert(res.stats);
                log.info("仓库[{}] 增量：删旧 {} 行，重插 {} 行（变更/删除文件 {} 个）",
                        repo.getRepoName(), del, n, res.pathsToDelete.size());
            }
            updateRepoState(repo, res.headSha, "SUCCESS");
            log.info("仓库[{}] 分析完成（{}）：{} 文件 {} 作者 {} 条事实，HEAD={}",
                    repo.getRepoName(), res.mode, res.fileCount, res.authorCount, n,
                    shortSha(res.headSha));
            // 采集成功 -> 扫描 flowtrans XML + 重建大屏 summary（聚合失败不影响采集结果）
            aggregator.rebuild(repo.getId(), res.localDir, res.allTrackedFiles, res.headSha);

            // 写入每日快照（用于折线图）
            writeDailyStat(repo.getId(), res);

            return res;
        } catch (Exception e) {
            updateRepoState(repo, repo.getLastSyncCommit(), "FAILED");
            throw e;
        }
    }

    /**
     * 手动本地只读扫描（测试用）：指定本地 git 工程路径，原地只读分析（不 clone/fetch/reset），
     * 落库 + 聚合后返回汇总。get-or-create 一行 code_repo_config（enabled=0，定时任务永不碰它）。
     *
     * @param repoName     仓库展示名（uk，重复则复用并更新接入参数）
     * @param localPath    本地 git 仓库根目录（须含 .git）
     * @param branch       仅记录用；本地只读不切分支，可空
     * @param includeExts  逗号分隔扩展名白名单，空=全部（建议 "java"）
     * @param excludePaths 逗号分隔排除路径前缀
     */
    public Map<String, Object> runLocalScan(String repoName, String localPath,
                                            String branch, String includeExts, String excludePaths) {
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("repoName 不能为空");
        }
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalArgumentException("localPath 不能为空");
        }
        File dir = new File(localPath);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("localPath 不是有效目录: " + localPath);
        }
        if (!new File(dir, ".git").exists()) {
            throw new IllegalArgumentException("localPath 不是 git 仓库（缺少 .git）: " + localPath);
        }

        CodeRepoConfig cfg = repoDao.findByRepoName(repoName);
        boolean created = false;
        if (cfg == null) {
            cfg = new CodeRepoConfig();
            cfg.setRepoName(repoName);
            cfg.setRepoUrl("local:" + localPath);
            cfg.setBranch(branch);
            cfg.setLocalPath(localPath);
            cfg.setIncludeExts(includeExts);
            cfg.setExcludePaths(excludePaths);
            cfg.setEnabled(0); // 定时任务不挑（避免对本地工作副本 fetch/reset）
            long id = repoDao.insert(cfg);
            cfg.setId(id);
            created = true;
        } else {
            cfg.setRepoUrl("local:" + localPath);
            cfg.setBranch(branch);
            cfg.setLocalPath(localPath);
            cfg.setIncludeExts(includeExts);
            cfg.setExcludePaths(excludePaths);
            repoDao.updateLocalConfig(cfg);
        }

        cfg.setLocalScan(true);          // 只读模式：不 clone/fetch/reset
        cfg.setLastSyncCommit(null);     // 每次都全量重算，结果可预期

        long t0 = System.currentTimeMillis();
        CollectResult res = doSync(cfg);
        long ms = System.currentTimeMillis() - t0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repoId", cfg.getId());
        out.put("repoName", repoName);
        out.put("localPath", localPath);
        out.put("repoCreated", created);
        out.put("mode", res.changed ? String.valueOf(res.mode) : "NO_CHANGE");
        out.put("headCommit", res.headSha);
        out.put("fileCount", res.fileCount);
        out.put("authorCount", res.authorCount);
        out.put("statRows", res.stats.size());
        out.put("elapsedMs", ms);
        out.put("hint", "前端大屏选择仓库「" + repoName + "」查看结果（/api/code/dashboard/overview?repoId=" + cfg.getId() + "）");
        return out;
    }

    private void updateRepoState(CodeRepoConfig repo, String lastSyncCommit, String status) {
        repo.setLastSyncCommit(lastSyncCommit);
        repo.setLastSyncTime(new Date());
        repo.setLastSyncStatus(status);
        repoDao.updateSyncState(repo);
    }

    private String shortSha(String sha) {
        return sha == null ? "?" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }

    private void writeDailyStat(long repoId, CollectResult res) {
        try {
            long totalOwned = 0, staffOwned = 0, vendorOwned = 0;
            int authorCount = 0;
            int fileCount = res.stats.stream()
                    .map(s -> s.getFilePath())
                    .distinct()
                    .mapToInt(x -> 1)
                    .sum();

            // 按 person_type 聚合行数
            for (var stat : res.stats) {
                totalOwned += stat.getOwnedLines();
            }

            // 从 summary 表查行员/厂商拆分（rebuild 后数据已就绪）
            List<Map<String, Object>> byType = dashboardDao.sumByPersonType(repoId);
            for (Map<String, Object> row : byType) {
                String pt = String.valueOf(row.get("person_type"));
                long owned = ((Number) row.get("owned_lines")).longValue();
                if ("VENDOR".equals(pt)) {
                    vendorOwned = owned;
                } else {
                    staffOwned = owned;
                }
            }

            // 如果 summary 还没聚合完成，用 0 值写入（容错）
            if (totalOwned == 0 && !res.stats.isEmpty()) {
                totalOwned = res.stats.stream().mapToLong(com.axonlink.ai.code.entity.CodeFileAuthorStat::getOwnedLines).sum();
            }

            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            dailyStatDao.upsert(repoId, today, totalOwned, staffOwned, vendorOwned,
                    res.authorCount, res.fileCount, res.headSha);
            log.info("仓库[id={}] 每日快照写入完成：total={} staff={} vendor={}",
                    repoId, totalOwned, staffOwned, vendorOwned);
        } catch (Exception e) {
            log.error("仓库[id={}] 每日快照写入失败（不影响采集结果）", repoId, e);
        }
    }
}
