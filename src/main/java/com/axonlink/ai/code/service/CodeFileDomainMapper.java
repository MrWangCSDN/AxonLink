package com.axonlink.ai.code.service;

import com.axonlink.ai.code.persistence.CodeDashboardDao;
import com.axonlink.common.DomainKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件 → 领域 映射。对 code_file_author_stat 的 DISTINCT file_path（仓库相对路径），
 * 切出 模块名 + 包名，复用 {@link DomainKeyResolver} 原样推导 domain_key，落 code_file_domain。
 *
 * <p>join 键 = 事实表自身 file_path，天然对齐，无 Phase② 路径对齐风险。
 * 领域口径与 flowtran/DomainSidebar 完全一致（同一个 DomainKeyResolver）。
 */
@Service
public class CodeFileDomainMapper {

    private static final Logger log = LoggerFactory.getLogger(CodeFileDomainMapper.class);

    /** 源码根标记：标记前一段=模块名(parentProject)，标记后到文件名=包路径。 */
    private static final String[] SRC_MARKERS = {
            "/src/main/java/", "/src/test/java/",
            "/target/gen/", "/target/generated-sources/annotations/"
    };

    @Autowired
    private CodeDashboardDao dao;

    /** 重建单仓库 file→domain 映射。 */
    public void rebuild(long repoId) {
        List<String> paths = dao.distinctFilePaths(repoId);
        List<Object[]> rows = new ArrayList<>(paths.size());
        for (String path : paths) {
            String[] pp = splitProjectAndPackage(path);
            String domainKey = DomainKeyResolver.resolveByProjectOrPackage(pp[0], pp[1]);
            rows.add(new Object[]{path, domainKey});
        }
        String snapshot = dao.latestSnapshotCommit(repoId);
        dao.replaceFileDomain(repoId, rows, snapshot);
        log.info("仓库[id={}] file→domain 映射重建：{} 文件", repoId, rows.size());
    }

    /**
     * 从仓库相对路径切出 [parentProject, packagePath]。
     * 形如 {@code ccbs-loan-impl/src/main/java/com/spdb/ccbs/loan/pbf/Foo.java}
     * → {@code ["ccbs-loan-impl", "com.spdb.ccbs.loan.pbf"]}。
     * 无源码根标记（非 java / 非常规布局）→ {@code [null, ""]} → DomainKeyResolver 归 public。
     */
    static String[] splitProjectAndPackage(String repoRelPath) {
        if (repoRelPath == null || repoRelPath.isBlank()) {
            return new String[]{null, ""};
        }
        String norm = "/" + repoRelPath.replace('\\', '/').replaceFirst("^/+", "");
        for (String marker : SRC_MARKERS) {
            int idx = norm.indexOf(marker);
            if (idx < 0) {
                continue;
            }
            String before = norm.substring(0, idx);          // 含前导 /
            String after = norm.substring(idx + marker.length());
            String project = null;
            int lastSlash = before.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash + 1 < before.length()) {
                project = before.substring(lastSlash + 1);    // src/ 前一段=模块名
            }
            int fileSlash = after.lastIndexOf('/');
            String pkgDir = fileSlash >= 0 ? after.substring(0, fileSlash) : "";
            String packagePath = pkgDir.replace('/', '.');
            return new String[]{project, packagePath};
        }
        return new String[]{null, ""};
    }
}
