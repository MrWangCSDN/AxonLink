package com.axonlink.ai.daoindex.sqlinspect.batch;

import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionRequest;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlInspectionResult;
import com.axonlink.ai.daoindex.sqlinspect.persistence.DiiSqlPoolDao;
import com.axonlink.ai.daoindex.sqlinspect.service.SqlInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SQL 池行批量 EXPLAIN 巡检（V16+ 新增）。
 *
 * <p>承担用户需求 1+2+3：
 * <ol>
 *   <li>白名单池行（{@code is_whitelist=1}）→ 跳过 EXPLAIN + LLM</li>
 *   <li>非白名单池行 → 跑 EXPLAIN：
 *     <ul>
 *       <li>无 SeqScan → <b>物理 DELETE 池行</b>（池本是"失败命中索引"候选，EXPLAIN 否认就该出局）</li>
 *       <li>有 SeqScan → 保留 + 写回 EXPLAIN + 标 LLM PENDING</li>
 *       <li>EXPLAIN 失败 → 保留 + 记 explain_error（不删，避免误杀）</li>
 *     </ul>
 *   </li>
 *   <li>EXPLAIN 复用 {@link SqlInspectionService#inspect}（persist=false）—— 池 SQL 是具体 SQL
 *       不含占位符，ExplainExecutor 直接跑；与 odb 路径完全兼容</li>
 * </ol>
 *
 * <p>注意：本类<b>不动 task 表的 total_sqls</b>（池总数由 listBatchTasks 的 LEFT JOIN pool_count 单独算）。
 */
@Component
public class PoolBatchInspector {

    private static final Logger log = LoggerFactory.getLogger(PoolBatchInspector.class);

    private final DiiSqlPoolDao poolDao;
    private final SqlInspectionService inspectionService;

    public PoolBatchInspector(DiiSqlPoolDao poolDao,
                              SqlInspectionService inspectionService) {
        this.poolDao = poolDao;
        this.inspectionService = inspectionService;
    }

    /**
     * 跑一次池巡检。返回三态计数。
     *
     * @param env 限定 env；为空则全量扫
     * @param maxItems 单次跑上限（防爆）；超出留到下次
     */
    public PoolInspectionSummary run(String env, int maxItems) {
        long startMs = System.currentTimeMillis();
        List<Map<String, Object>> rows = poolDao.listForInspection(env, maxItems);
        if (rows.isEmpty()) {
            log.info("[pool-batch] 没有需巡检的池行 env={}", env);
            return new PoolInspectionSummary(0, 0, 0, 0);
        }
        log.info("[pool-batch] 待巡检池行 {} 条 env={}", rows.size(), env);

        int deleted = 0;     // 非 seqscan 物理删除
        int kept = 0;        // 命中 seqscan 保留 + 标 LLM
        int failed = 0;      // EXPLAIN 失败保留 + 记错
        int skippedWhitelist = 0; // 已被列表过滤；保留计数位（兜底防御性 0）

        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String sqlText = String.valueOf(row.get("sql_text"));
            String sqlHash = String.valueOf(row.get("sql_hash"));
            String namedSql = String.valueOf(row.get("named_sql"));
            String rowEnv = String.valueOf(row.get("env"));
            // 白名单兜底：listForInspection 已过滤，但配置漂移防御
            Object wl = row.get("is_whitelist");
            if (wl instanceof Number n && n.intValue() == 1) {
                skippedWhitelist++;
                continue;
            }

            try {
                SqlInspectionRequest req = new SqlInspectionRequest();
                req.setSql(sqlText);
                req.setEnv(rowEnv != null && !"null".equals(rowEnv) ? rowEnv : env);
                // 关键：persist=false——不写 item 表，由本类自行写回池表
                SqlInspectionResult result = inspectionService.inspect(req, false);

                // EXPLAIN 失败：保留池行 + 写回 explain_error
                if (result.getExplainError() != null && !result.getExplainError().isEmpty()) {
                    poolDao.updateInspectionFields(id, result);
                    failed++;
                    log.debug("[pool-batch] EXPLAIN 失败保留 poolId={} sqlHash={} err={}",
                            id, sqlHash, result.getExplainError());
                    continue;
                }

                // 是否全表扫描——核心判定：用户需求 2
                Boolean hasSeq = result.getExplainHasSeqScan();
                if (hasSeq == null || !hasSeq) {
                    // 非 SeqScan → 物理 DELETE（即便 INSERT / NOT_APPLICABLE 也清，因为本不该入池）
                    int aff = poolDao.deleteById(id);
                    deleted += aff > 0 ? 1 : 0;
                    log.info("[pool-batch] 非全表扫描物理删除 poolId={} named={} sqlHash={}",
                            id, namedSql, sqlHash);
                } else {
                    // SeqScan → 写回 EXPLAIN + 标 LLM PENDING
                    poolDao.updateInspectionFields(id, result);
                    poolDao.markLlmPending(id);
                    kept++;
                    log.debug("[pool-batch] SeqScan 保留并标 LLM PENDING poolId={} sqlHash={}",
                            id, sqlHash);
                }
            } catch (Throwable t) {
                failed++;
                log.error("[pool-batch] 单条 SQL 失败 poolId={} sqlHash={}: {}",
                        id, sqlHash, t.getMessage(), t);
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("[pool-batch] 完成 env={} 总 {} 条 删除={} 保留={} 失败={} 白名单={} 耗时={}ms",
                env, rows.size(), deleted, kept, failed, skippedWhitelist, elapsed);
        return new PoolInspectionSummary(deleted, kept, failed, skippedWhitelist);
    }

    /** 跑批结果计数 DTO。 */
    public static class PoolInspectionSummary {
        public final int deleted;
        public final int kept;
        public final int failed;
        public final int skippedWhitelist;

        public PoolInspectionSummary(int deleted, int kept, int failed, int skippedWhitelist) {
            this.deleted = deleted;
            this.kept = kept;
            this.failed = failed;
            this.skippedWhitelist = skippedWhitelist;
        }
    }
}
