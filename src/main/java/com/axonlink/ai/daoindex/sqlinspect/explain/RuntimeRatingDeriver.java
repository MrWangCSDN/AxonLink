package com.axonlink.ai.daoindex.sqlinspect.explain;

import com.axonlink.ai.daoindex.sqlinspect.dto.ExplainResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 由 EXPLAIN 真实执行计划派生 {@code overall_rating}（「EXPLAIN 优先」管线唯一评级来源）。
 *
 * <h3>语义变更（v3）：取消「优良差」三档评级，只判「是否需整改」</h3>
 * <pre>
 * 1. EXPLAIN 失败 / 未跑                                      → null
 *      （看板靠 explain_error 归"报错"档；不送 LLM）
 * 2. 顶层 One-Time Filter:false（参数被当 NULL 短路）          → null
 *      （计划不代表真实执行，GaussDB 5 不支持 GENERIC_PLAN 的已知限制；保留含日志）
 * 3. 含任意 Seq Scan（全表扫描，ExplainResult.isHasSeqScan）   → POOR（需整改候选）
 *      （有全表扫描就送 LLM 解读，且应在看板里显著）
 * 4. 无 Seq Scan 但 EXPLAIN 估算扫描行数 ≥ 1000               → POOR（需整改候选）
 *      （命中索引但回表/扫描量大，仍需 LLM 解读）
 * 5. 无 Seq Scan 且估算扫描行数 &lt; 1000                       → EXCELLENT（无需整改）
 * </pre>
 *
 * <p><b>判定结果仅两类</b>：{@code POOR}=需整改候选（送 LLM）/ {@code EXCELLENT}=无需整改
 * （不送 LLM）。不再产出 {@code GOOD}（旧"良"档随本次语义变更弃用）。
 *
 * <p>"扫描行数"取 {@link ExplainResult#getTopPlanRows()}——EXPLAIN 优化器估算值，
 * 不带 ANALYZE（不实际执行 SQL）。
 */
@Component
public class RuntimeRatingDeriver {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRatingDeriver.class);

    /**
     * 「大扫描」阈值：无 Seq Scan 但 EXPLAIN 估算扫描行数 ≥ 此值即判需整改候选。
     * 此处的行数是 EXPLAIN 优化器估算行数（{@link ExplainResult#getTopPlanRows()}），
     * 不带 ANALYZE、不实际执行 SQL。
     */
    private static final long LARGE_SCAN_ROWS = 1000L;

    /**
     * 由 EXPLAIN 结果派生 overall_rating（是否需整改判定）。
     *
     * @param explain          EXPLAIN 执行结果
     * @param tableMetadataMap 涉及表的元数据 map（key 是 lower-case 表名）；
     *                         新口径下不再用它（保留入参仅为兼容调用方签名）
     * @return POOR=需整改候选 / EXCELLENT=无需整改；EXPLAIN 失败或短路时返回 {@code null}
     */
    public IndexRating derive(ExplainResult explain, Map<String, TableMetadata> tableMetadataMap) {
        if (explain == null || !explain.isSuccess()) {
            // EXPLAIN 失败/未跑：不评级，由调用方写 null（看板靠 explain_error 归档）
            return null;
        }
        // 顶层 One-Time Filter: false：优化器认为整条 SQL 恒假（参数替换成 NULL 导致），
        // 里层 Seq Scan / Index Scan 不代表真实执行。不派生评级，避免系统性误判。
        if (explain.isOneTimeFilterFalse()) {
            log.debug("[dii-rating] 顶层 One-Time Filter=false，跳过派生（参数替换导致的恒假条件）");
            return null;
        }

        // 全表扫描 → 需整改候选，触发 LLM 解读
        if (explain.isHasSeqScan()) {
            return IndexRating.POOR;
        }
        // 命中索引但 EXPLAIN 估算扫描行数偏大 → 同样判需整改候选，送 LLM
        if (explain.getTopPlanRows() >= LARGE_SCAN_ROWS) {
            return IndexRating.POOR;
        }
        // 命中索引且估算扫描行数较小 → 无需整改
        return IndexRating.EXCELLENT;
    }
}
