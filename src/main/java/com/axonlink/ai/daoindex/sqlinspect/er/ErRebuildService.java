package com.axonlink.ai.daoindex.sqlinspect.er;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErRelation;
import com.axonlink.ai.daoindex.sqlinspect.er.persistence.ErRelationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ER 关系重算编排：scan → infer → 落库（保留人工决策）。
 *
 * <p>同 env 重算防重入：内存 {@link #running} 标记，正在跑则抛 {@link IllegalStateException}
 * （controller 转 409 + 中文提示），避免两次重算并发写库。
 */
@Service
public class ErRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ErRebuildService.class);

    private final ErSchemaScanService scanService;
    private final ErInferenceService inferenceService;
    private final ErRelationDao dao;
    private final DaoIndexAnalysisProperties props;

    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();

    public ErRebuildService(ErSchemaScanService scanService,
                            ErInferenceService inferenceService,
                            ErRelationDao dao,
                            DaoIndexAnalysisProperties props) {
        this.scanService = scanService;
        this.inferenceService = inferenceService;
        this.dao = dao;
        this.props = props;
    }

    /**
     * 重算指定 env 的全部关系。
     * @return 统计 {scanned(表数), inferred(关系数), high, medium, low, deletedStale}
     */
    public Map<String, Object> rebuild(String env) {
        if (!props.getEr().isEnabled()) {
            throw new IllegalStateException("ER 推断已禁用（yml: er.enabled=false）");
        }
        String effEnv = (env == null || env.isBlank()) ? "" : env.trim();
        if (running.putIfAbsent(effEnv, Boolean.TRUE) != null) {
            throw new IllegalStateException("该 env 的 ER 重算正在进行中，请稍后再试");
        }
        long start = System.currentTimeMillis();
        try {
            // ① 扫描
            ErSchemaScanService.ScanResult scan = scanService.scan(effEnv);

            // ② 推断
            int distinctMax = props.getEr().getDistinctColumnMaxTables();
            Set<String> blacklist = props.getEr().getCommonColumns() == null ? Set.of()
                    : props.getEr().getCommonColumns().stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
            List<ErRelation> rels = inferenceService.infer(
                    scan.keySets, scan.tableColumns, scan.columnTableCount, distinctMax, blacklist);

            // ③ 落库（保留人工决策）+ 清理失效 AUTO
            Set<String> liveKeys = new LinkedHashSet<>();
            for (ErRelation r : rels) {
                dao.upsertKeepHumanDecision(effEnv, r);
                liveKeys.add(r.getFromTable() + "|" + r.getToTable() + "|" + r.joinColumnsCsv());
            }
            int deletedStale = dao.deleteStaleAuto(effEnv, liveKeys);

            // ④ 统计
            Map<String, Object> stat = dao.countByConfidence(effEnv);
            long high = lng(stat.get("high")), medium = lng(stat.get("medium")), low = lng(stat.get("low"));
            long elapsed = System.currentTimeMillis() - start;
            log.info("[er-rebuild] env={} 表={} 推断={} HIGH={} MEDIUM={} LOW={} 清理失效AUTO={} 耗时={}ms",
                    effEnv, scan.tableCount, rels.size(), high, medium, low, deletedStale, elapsed);

            return Map.of(
                    "env", effEnv.isEmpty() ? "(default)" : effEnv,
                    "scannedTables", scan.tableCount,
                    "inferred", rels.size(),
                    "high", high, "medium", medium, "low", low,
                    "deletedStale", deletedStale,
                    "elapsedMs", elapsed);
        } finally {
            running.remove(effEnv);
        }
    }

    private static long lng(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0L; }
    }
}
