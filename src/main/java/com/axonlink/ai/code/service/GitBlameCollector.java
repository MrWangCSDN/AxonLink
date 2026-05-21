package com.axonlink.ai.code.service;

import com.axonlink.ai.code.entity.CodeFileAuthorStat;
import com.axonlink.ai.code.entity.CodeRepoConfig;
import com.axonlink.ai.code.service.GitCommandExecutor.GitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Phase 8 / 08-01 核心采集：本地 clone + 原生 git，输出文件维度事实。
 *
 * <p>主指标 = git blame 存活行占比（{@code -w -M}，{@code -C} 可选，默认关——
 * 跨文件复制检测是 blame 最贵的开关，对"存活行占比"非必需）。numstat 增删/提交数为辅助列。
 *
 * <p>增量（性能核心，与全量重算逐字节等价）：
 * <ul>
 *   <li>HEAD 未变 → 直接短路</li>
 *   <li>有 last_sync_commit 且仍是 HEAD 祖先 → 只对两点 diff 的变更文件重跑 blame，
 *       未触碰文件复用上次入库行（blame 归属只随"动过该文件的提交"变化，数学恒等）</li>
 *   <li>last_sync_commit 不存在 / 非 HEAD 祖先（force-push / rebase / squash）→ 回退全量重采</li>
 * </ul>
 * 规则驱动的交易维度二次聚合属 08-02，本类不涉及。
 */
@Component
public class GitBlameCollector {

    private static final Logger log = LoggerFactory.getLogger(GitBlameCollector.class);

    private static final String COMMIT_PREFIX = ">>>COMMIT\t";
    private static final String LOG_FORMAT = "--format=" + ">>>COMMIT\t%H\t%an\t%ae\t%at";

    /** git blame --incremental 每组条目首行：<40hex sha> <origLine> <finalLine> <numLines>。 */
    private static final Pattern BLAME_ENTRY =
            Pattern.compile("^([0-9a-f]{40}) \\d+ \\d+ (\\d+)$");

    @Autowired
    private GitCommandExecutor git;

    @Value("${code-analysis.workspace:./.code-analysis-workspace}")
    private String workspace;

    @Value("${code-analysis.git-executable:git}")
    private String gitExecutable;

    /** 逐文件 blame 是唯一瓶颈：有界线程池并行，每个 blame 是独立 git 进程，近线性加速。 */
    @Value("${code-analysis.blame-parallel:4}")
    private int blameParallel;

    /** 是否开启 blame 跨文件复制检测 -C（最贵开关，默认关；主指标用 -w -M 已足够）。 */
    @Value("${code-analysis.blame-copy-detect:false}")
    private boolean blameCopyDetect;

    private final AtomicInteger blameThreadSeq = new AtomicInteger(0);

    public enum Mode { FULL, INCREMENTAL }

    public static class CollectResult {
        public String headSha;
        public boolean changed = true;
        public Mode mode = Mode.FULL;
        public int fileCount;   // FULL: 跟踪文件数；INCREMENTAL: 重 blame 文件数
        public int authorCount;
        /** INCREMENTAL: 需删除的旧行路径（变更∪删除∪改名旧名）；FULL: 空。 */
        public Set<String> pathsToDelete = new LinkedHashSet<>();
        public List<CodeFileAuthorStat> stats = new ArrayList<>();
        /** 本次采集使用的本地工作目录（供 XML 解析用，NO_CHANGE 时也有值）。 */
        public File localDir;
        /** git ls-files 全量结果（未经 includeExts 过滤，供 flowtrans XML 扫描用）。 */
        public List<String> allTrackedFiles = new ArrayList<>();
    }

    /** 单文件单作者累加器。 */
    private static class Acc {
        String authorName;
        int ownedLines;
        int addedLines;
        int deletedLines;
        int commitCount;
        Long firstEpoch;
        Long lastEpoch;
    }

    /** 单文件 blame 结果（并行任务返回，主线程合并，避免共享可变状态竞态）。 */
    private static class FileBlame {
        final String path;
        int total;
        final Map<String, Integer> ownedByEmail = new LinkedHashMap<>();
        final Map<String, String> nameByEmail = new LinkedHashMap<>();
        FileBlame(String path) { this.path = path; }
    }

    public CollectResult collect(CodeRepoConfig repo) {
        File localDir = resolveLocalDir(repo);
        ensureRepo(repo, localDir);

        String headSha = single(git.exec(gitExecutable, localDir, "rev-parse", "HEAD").stdout);
        CollectResult result = new CollectResult();
        result.headSha = headSha;
        result.localDir = localDir;

        // 全量 ls-files（不过滤）：供 flowtrans XML 扫描用；blame 目标另行过滤
        List<String> rawTracked = git.exec(gitExecutable, localDir, "ls-files").stdout;
        result.allTrackedFiles = new ArrayList<>(rawTracked);

        if (headSha != null && headSha.equals(repo.getLastSyncCommit())) {
            result.changed = false;
            log.info("仓库[{}] HEAD 未变化（{}），跳过本次分析", repo.getRepoName(), shortSha(headSha));
            return result;
        }

        List<String> trackedFiles = filterFiles(rawTracked, repo);
        Set<String> trackedSet = new HashSet<>(trackedFiles);

        // ---- 模式判定：两守卫 ----
        String base = repo.getLastSyncCommit();
        Set<String> changedPaths = null;   // 需重 blame 的当前路径
        Set<String> removedPaths = new LinkedHashSet<>(); // D / 改名旧名 → 仅删行
        if (base != null && !base.isBlank()
                && commitExists(localDir, base) && isAncestor(localDir, base, headSha)) {
            result.mode = Mode.INCREMENTAL;
            changedPaths = new LinkedHashSet<>();
            parseDiff(localDir, base, headSha, repo, trackedSet, changedPaths, removedPaths);
        } else {
            result.mode = Mode.FULL;
            if (base != null && !base.isBlank()) {
                log.warn("仓库[{}] 基线 {} 不可达或非 HEAD 祖先（疑似 force-push/rebase），回退全量重采",
                        repo.getRepoName(), shortSha(base));
            }
        }

        // ---- 历史投入：git log --numstat；全量扫全仓，增量仅扫 changedPaths（all-time 但限文件集）----
        // changedPaths=null 在全量模式→全仓 log；非 null 在增量→只取变更文件的全量历史，性能 O(N×K) → O(N×k)
        Map<String, Map<String, Acc>> table = new LinkedHashMap<>();
        Map<String, Integer> fileTotalLines = new LinkedHashMap<>();
        collectHistory(localDir, table, changedPaths);

        // ---- 当前归属：并行逐文件 blame，目标集 = 全量(全部跟踪) / 增量(变更∩跟踪) ----
        List<String> blameTargets;
        if (result.mode == Mode.FULL) {
            blameTargets = trackedFiles;
        } else {
            blameTargets = new ArrayList<>();
            for (String p : changedPaths) {
                if (trackedSet.contains(p)) {
                    blameTargets.add(p);
                }
            }
        }
        blameAllParallel(localDir, blameTargets, table, fileTotalLines);

        // ---- 物化事实行 ----
        Date now = new Date();
        Set<String> authors = new HashSet<>();
        Collection<String> emitPaths = (result.mode == Mode.FULL) ? table.keySet() : blameTargets;
        for (String path : emitPaths) {
            if (!trackedSet.contains(path)) {
                continue; // 仅保留 HEAD 仍存在的文件，与 blame 当前归属语义一致
            }
            Map<String, Acc> byEmail = table.get(path);
            if (byEmail == null) {
                continue;
            }
            int total = fileTotalLines.getOrDefault(path, 0);
            for (Map.Entry<String, Acc> ae : byEmail.entrySet()) {
                String email = ae.getKey();
                Acc acc = ae.getValue();
                authors.add(email);
                CodeFileAuthorStat s = new CodeFileAuthorStat();
                s.setRepoId(repo.getId());
                s.setFilePath(path);
                s.setAuthorEmail(email);
                s.setAuthorName(acc.authorName);
                s.setUserId(null); // git 身份 -> 人员 别名映射属 Phase 3
                s.setOwnedLines(acc.ownedLines);
                s.setFileTotalLines(total);
                s.setAddedLines(acc.addedLines);
                s.setDeletedLines(acc.deletedLines);
                s.setCommitCount(acc.commitCount);
                s.setFirstCommitTime(acc.firstEpoch == null ? null : new Date(acc.firstEpoch * 1000));
                s.setLastCommitTime(acc.lastEpoch == null ? null : new Date(acc.lastEpoch * 1000));
                s.setSnapshotCommit(headSha);
                s.setSnapshotTime(now);
                result.stats.add(s);
            }
        }

        if (result.mode == Mode.INCREMENTAL) {
            // 变更文件先删后插；改名旧名/删除文件仅删
            result.pathsToDelete.addAll(blameTargets);
            result.pathsToDelete.addAll(removedPaths);
            result.fileCount = blameTargets.size();
        } else {
            result.fileCount = trackedFiles.size();
        }
        result.authorCount = authors.size();
        return result;
    }

    // ---- 仓库准备：clone 或 fetch+reset（凭证仅在 URL 内临时注入，不写入 .git/config） ----

    private void ensureRepo(CodeRepoConfig repo, File localDir) {
        // 本地只读扫描：只校验是 git 仓库，绝不 clone/fetch/reset（不动用户工作副本）。
        // 下游 rev-parse/ls-files/log/blame 全部只读，安全。
        if (repo.isLocalScan()) {
            if (localDir == null || !localDir.isDirectory()
                    || !new File(localDir, ".git").exists()) {
                throw new GitCommandExecutor.GitException(
                        "本地扫描路径不是 git 仓库（缺少 .git）: "
                                + (localDir == null ? "null" : localDir.getAbsolutePath()));
            }
            log.info("本地只读扫描[{}] {}（不 clone/fetch/reset）",
                    repo.getRepoName(), localDir.getAbsolutePath());
            return;
        }
        String branch = repo.getBranch() == null || repo.getBranch().isBlank() ? "master" : repo.getBranch();
        String url = buildAuthedUrl(repo);
        // 提取 token 以便在异常消息中脱敏（token 仅内存中，永不入库）
        String ref = repo.getCredentialRef();
        String token = (ref != null && !ref.isBlank()) ? System.getenv(ref) : null;
        File gitDir = new File(localDir, ".git");
        try {
            if (!gitDir.exists()) {
                File parent = localDir.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                log.info("克隆仓库[{}] {} 分支 {} -> {}", repo.getRepoName(),
                        repo.getRepoUrl(), branch, localDir.getAbsolutePath());
                // 全量 clone：blame/log 需要完整历史，不能 --depth 浅克隆
                git.exec(gitExecutable, null, "clone", "--single-branch", "--branch", branch,
                        url, localDir.getAbsolutePath());
            } else {
                log.info("更新仓库[{}] 分支 {}", repo.getRepoName(), branch);
                git.exec(gitExecutable, localDir, "fetch", "--prune", url, branch);
                git.exec(gitExecutable, localDir, "reset", "--hard", "FETCH_HEAD");
            }
        } catch (GitCommandExecutor.GitException e) {
            if (token != null && !token.isBlank()) {
                throw new GitCommandExecutor.GitException(e.getMessage().replace(token, "***"));
            }
            throw e;
        }
    }

    private String buildAuthedUrl(CodeRepoConfig repo) {
        String url = repo.getRepoUrl();
        String ref = repo.getCredentialRef();
        if (ref == null || ref.isBlank()) {
            return url;
        }
        String token = System.getenv(ref);
        if (token == null || token.isBlank()) {
            log.warn("仓库[{}] 配置了 credential_ref={} 但环境变量为空，将以匿名方式访问",
                    repo.getRepoName(), ref);
            return url;
        }
        int idx = url.indexOf("://");
        if (idx < 0) {
            return url; // SSH 等非 http(s) 形式，凭证由系统 SSH 配置负责
        }
        return url.substring(0, idx + 3) + "oauth2:" + token + "@" + url.substring(idx + 3);
    }

    private File resolveLocalDir(CodeRepoConfig repo) {
        String lp = repo.getLocalPath();
        if (lp != null && !lp.isBlank()) {
            return new File(lp);
        }
        String safe = repo.getRepoName() == null ? ("repo-" + repo.getId())
                : repo.getRepoName().replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(new File(workspace), safe);
    }

    // ---- 守卫：基线提交存在 + 是 HEAD 祖先 ----

    private boolean commitExists(File localDir, String sha) {
        return git.execAllowFail(gitExecutable, localDir,
                Arrays.asList("cat-file", "-e", sha + "^{commit}")).ok();
    }

    private boolean isAncestor(File localDir, String ancestor, String head) {
        // exit 0 = 是祖先；exit 1 = 不是；其它 = 错误（一律按"不是"处理 → 回退全量，安全）
        return git.execAllowFail(gitExecutable, localDir,
                Arrays.asList("merge-base", "--is-ancestor", ancestor, head)).exitCode == 0;
    }

    /** 两点 tree 对 tree（{@code git diff A B} 等价 {@code A..B}）枚举每条变更路径。 */
    private void parseDiff(File localDir, String base, String head, CodeRepoConfig repo,
                           Set<String> trackedSet,
                           Set<String> changedPaths, Set<String> removedPaths) {
        GitResult r = git.exec(gitExecutable, localDir,
                "diff", "--name-status", "-M", base, head);
        for (String line : r.stdout) {
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\t", -1);
            String status = p[0];
            char c = status.isEmpty() ? '?' : status.charAt(0);
            if (c == 'R' && p.length >= 3) {
                addIfIncluded(p[2], repo, trackedSet, changedPaths); // 新名重 blame
                removedPaths.add(p[1]);                               // 旧名删行
            } else if (c == 'C' && p.length >= 3) {
                addIfIncluded(p[2], repo, trackedSet, changedPaths); // 复制出的新文件
            } else if (c == 'D' && p.length >= 2) {
                removedPaths.add(p[1]);
            } else if (p.length >= 2) {                               // A / M / T
                addIfIncluded(p[1], repo, trackedSet, changedPaths);
            }
        }
    }

    private void addIfIncluded(String path, CodeRepoConfig repo,
                               Set<String> trackedSet, Set<String> changedPaths) {
        String t = path.trim();
        if (t.isEmpty() || !trackedSet.contains(t)) {
            return; // 不在当前 HEAD 跟踪集（或被 include/exclude 过滤）则不重 blame
        }
        changedPaths.add(t);
    }

    // ---- 历史投入：git log --numstat；limitPaths=null 全仓，否则仅列出涉及这些文件的提交 ----

    private void collectHistory(File localDir, Map<String, Map<String, Acc>> table,
                                Collection<String> limitPaths) {
        List<String> logArgs = new ArrayList<>(Arrays.asList("log", "--no-merges", "--numstat", LOG_FORMAT));
        if (limitPaths != null && !limitPaths.isEmpty()) {
            logArgs.add("--");
            logArgs.addAll(limitPaths);
        }
        GitResult r = git.exec(gitExecutable, localDir, logArgs);
        String name = null;
        String email = null;
        Long epoch = null;
        for (String line : r.stdout) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(COMMIT_PREFIX)) {
                String[] h = line.substring(COMMIT_PREFIX.length()).split("\t", -1);
                if (h.length >= 4) {
                    name = h[1];
                    email = h[2];
                    try {
                        epoch = Long.parseLong(h[3].trim());
                    } catch (NumberFormatException e) {
                        epoch = null;
                    }
                }
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            if ("-".equals(parts[0]) || "-".equals(parts[1])) {
                continue; // 二进制文件 numstat 为 "-"
            }
            int added;
            int deleted;
            try {
                added = Integer.parseInt(parts[0].trim());
                deleted = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String path = normalizeRenamePath(parts[2]);
            if (email == null) {
                continue;
            }
            Acc acc = table.computeIfAbsent(path, k -> new LinkedHashMap<>())
                    .computeIfAbsent(email, k -> new Acc());
            if (acc.authorName == null) {
                acc.authorName = name;
            }
            acc.addedLines += added;
            acc.deletedLines += deleted;
            acc.commitCount += 1;
            if (epoch != null) {
                if (acc.firstEpoch == null || epoch < acc.firstEpoch) acc.firstEpoch = epoch;
                if (acc.lastEpoch == null || epoch > acc.lastEpoch) acc.lastEpoch = epoch;
            }
        }
    }

    /** numstat 改名形态：花括号块或 "old =&gt; new"，取改名后的新路径。 */
    private String normalizeRenamePath(String raw) {
        String p = raw.trim();
        if (!p.contains("=>")) {
            return p;
        }
        int lb = p.indexOf('{');
        int rb = p.indexOf('}');
        if (lb >= 0 && rb > lb) {
            String inside = p.substring(lb + 1, rb);
            String after = inside.contains("=>")
                    ? inside.substring(inside.indexOf("=>") + 2).trim()
                    : inside.trim();
            String result = p.substring(0, lb) + after + p.substring(rb + 1);
            return result.replace("//", "/").trim();
        }
        return p.substring(p.indexOf("=>") + 2).trim();
    }

    // ---- 当前归属：并行 git blame --incremental ----

    private void blameAllParallel(File localDir, List<String> targets,
                                  Map<String, Map<String, Acc>> table,
                                  Map<String, Integer> fileTotalLines) {
        if (targets.isEmpty()) {
            return;
        }
        int pool = Math.max(1, blameParallel);
        ExecutorService es = Executors.newFixedThreadPool(pool, runnable -> {
            Thread t = new Thread(runnable, "code-blame-" + blameThreadSeq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<FileBlame>> futures = new ArrayList<>(targets.size());
            for (String path : targets) {
                futures.add(es.submit(blameTask(localDir, path)));
            }
            for (Future<FileBlame> f : futures) {
                FileBlame fb;
                try {
                    fb = f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GitCommandExecutor.GitException("blame 被中断");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw (cause instanceof RuntimeException re) ? re
                            : new GitCommandExecutor.GitException("blame 失败: " + cause);
                }
                if (fb == null) {
                    continue; // 二进制 / 无历史，跳过
                }
                fileTotalLines.put(fb.path, fb.total);
                Map<String, Acc> byEmail =
                        table.computeIfAbsent(fb.path, k -> new LinkedHashMap<>());
                for (Map.Entry<String, Integer> oe : fb.ownedByEmail.entrySet()) {
                    Acc acc = byEmail.computeIfAbsent(oe.getKey(), k -> new Acc());
                    acc.ownedLines += oe.getValue();
                    if (acc.authorName == null || acc.authorName.isBlank()) {
                        acc.authorName = fb.nameByEmail.get(oe.getKey());
                    }
                }
            }
        } finally {
            es.shutdownNow();
        }
    }

    private Callable<FileBlame> blameTask(File localDir, String path) {
        return () -> {
            List<String> args = new ArrayList<>();
            args.add("blame");
            args.add("--incremental");
            args.add("-w");
            args.add("-M");
            if (blameCopyDetect) {
                args.add("-C");
            }
            args.add("--");
            args.add(path);
            GitResult r = git.execAllowFail(gitExecutable, localDir, args);
            if (!r.ok()) {
                log.debug("跳过 blame 文件 {}（exit={}）", path, r.exitCode);
                return null;
            }
            FileBlame fb = new FileBlame(path);
            // sha -> [name, email]，--incremental 仅首次出现该 sha 时输出 author 块
            Map<String, String[]> shaMeta = new HashMap<>();
            String curSha = null;
            int curGroupLines = 0;
            for (String line : r.stdout) {
                var m = BLAME_ENTRY.matcher(line);
                if (m.matches()) {
                    curSha = m.group(1);
                    curGroupLines = Integer.parseInt(m.group(2));
                    continue;
                }
                if (curSha == null) {
                    continue;
                }
                if (line.startsWith("author ")) {
                    shaMeta.computeIfAbsent(curSha, k -> new String[2])[0] = line.substring(7).trim();
                } else if (line.startsWith("author-mail ")) {
                    String e = line.substring(12).trim();
                    if (e.startsWith("<") && e.endsWith(">") && e.length() >= 2) {
                        e = e.substring(1, e.length() - 1);
                    }
                    shaMeta.computeIfAbsent(curSha, k -> new String[2])[1] = e;
                } else if (line.startsWith("filename ")) {
                    // 每组以 filename 行收尾；此时该 sha 的 author 必已记录
                    String[] meta = shaMeta.get(curSha);
                    String email = meta == null ? null : meta[1];
                    if (email != null) {
                        fb.total += curGroupLines;
                        fb.ownedByEmail.merge(email, curGroupLines, Integer::sum);
                        if (meta[0] != null) {
                            fb.nameByEmail.putIfAbsent(email, meta[0]);
                        }
                    }
                    curSha = null;
                    curGroupLines = 0;
                }
            }
            return fb;
        };
    }

    // ---- 文件过滤 ----

    private List<String> filterFiles(List<String> all, CodeRepoConfig repo) {
        Set<String> includeExts = toLowerSet(repo.getIncludeExts());
        // xml 全量纳入 blame：flowtrans/pbs/pcs 等 XML 须有行归属数据，人员×交易维度才能正确关联
        if (!includeExts.isEmpty()) {
            includeExts.add("xml");
        }
        List<String> excludePrefixes = toList(repo.getExcludePaths());
        List<String> result = new ArrayList<>();
        for (String f : all) {
            String path = f.trim();
            if (path.isEmpty()) {
                continue;
            }
            boolean excluded = false;
            for (String ex : excludePrefixes) {
                if (path.startsWith(ex)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) {
                continue;
            }
            if (!includeExts.isEmpty()) {
                int dot = path.lastIndexOf('.');
                String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
                if (!includeExts.contains(ext)) {
                    continue;
                }
            }
            result.add(path);
        }
        return result;
    }

    private Set<String> toLowerSet(String csv) {
        Set<String> set = new HashSet<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim().toLowerCase();
                if (!t.isEmpty()) set.add(t);
            }
        }
        return set;
    }

    private List<String> toList(String csv) {
        List<String> list = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) list.add(t);
            }
        }
        return list;
    }

    private String single(List<String> lines) {
        return lines.isEmpty() ? null : lines.get(0).trim();
    }

    private String shortSha(String sha) {
        return sha == null ? "?" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }
}
