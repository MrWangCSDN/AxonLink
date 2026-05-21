package com.axonlink.ai.code.service;

import com.axonlink.ai.code.persistence.CodeDashboardDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

/**
 * 大屏 summary 物化聚合编排。每日采集成功后对该仓库重建 summary（每日快照口径）。
 *
 * <p>聚合失败不回退采集结果：事实表已落库，summary 陈旧是非致命的，记日志即可。
 */
@Service
public class CodeDashboardAggregator {

    private static final Logger log = LoggerFactory.getLogger(CodeDashboardAggregator.class);

    @Autowired
    private CodeDashboardDao dao;

    @Autowired
    private CodeFileDomainMapper fileDomainMapper;

    @Autowired
    private CodeTxXmlScanner txXmlScanner;

    /**
     * 完整重建：先扫描 flowtrans XML 填充 code_tx_file_map，再重建所有 summary 表。
     * 每次采集后调用此版本。
     *
     * @param localDir        仓库本地工作目录（来自 CollectResult.localDir）
     * @param allTrackedFiles git ls-files 全量列表（未过滤扩展名，供 XML 扫描用）
     * @param headSha         本次同步的 HEAD commit sha
     */
    public void rebuild(long repoId, File localDir, List<String> allTrackedFiles, String headSha) {
        // 1. flowtrans XML 扫描 → 填充 code_tx_file_map
        try {
            List<String[]> txMappings = txXmlScanner.scan(localDir, allTrackedFiles);
            dao.replaceTxFileMap(repoId, txMappings, headSha);
            log.info("仓库[id={}] flowtrans XML 扫描完成：交易映射 {} 条", repoId, txMappings.size());
        } catch (Exception e) {
            log.error("仓库[id={}] flowtrans XML 扫描失败（summary 中人员×交易维度将陈旧）", repoId, e);
        }
        // 2. 重建所有 summary
        rebuild(repoId);
    }

    /**
     * 重建单仓库全部 summary（工程 / 领域 / 交易 / 人员×交易）。
     * 不含 flowtrans XML 扫描——code_tx_file_map 须已是最新数据。
     */
    public void rebuild(long repoId) {
        try {
            int authors = dao.rebuildRepoAuthorStat(repoId);
            fileDomainMapper.rebuild(repoId);
            int dp = dao.rebuildDomainPersonStat(repoId);
            int da = dao.rebuildDomainAuthorStat(repoId);
            int tx = dao.rebuildTxPersonStat(repoId);
            int pt = dao.rebuildPersonTxStat(repoId);
            log.info("仓库[id={}] 大屏聚合完成：作者行 {}，领域×类型 {}，领域×作者 {}，" +
                            "交易×类型 {}，人员×交易 {}",
                    repoId, authors, dp, da, tx, pt);
        } catch (Exception e) {
            log.error("仓库[id={}] 大屏聚合失败（采集结果不受影响，summary 将陈旧）", repoId, e);
        }
    }
}
