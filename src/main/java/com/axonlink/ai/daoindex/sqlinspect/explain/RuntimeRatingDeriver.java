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
 * <h3>派生规则（新口径）</h3>
 * <pre>
 * 1. EXPLAIN 失败 / 未跑                                      → null
 *      （看板靠 explain_error 归"报错"档；不送 LLM）
 * 2. 顶层 One-Time Filter:false（参数被当 NULL 短路）          → null
 *      （计划不代表真实执行，GaussDB 5 不支持 GENERIC_PLAN 的已知限制）
 * 3. 含任意 Seq Scan（不分大小表，ExplainResult.isHasSeqScan）→ POOR
 *      （有全表扫描就送 LLM 解读，且应在看板里显著）
 * 4. 无 Seq Scan 且全 Index Scan 且顶层 cost 低               → EXCELLENT
 * 5. 其它（无 Seq Scan 但成本不低 / 含 Sort 等）              → GOOD
 * </pre>
 *
 * <p>与旧版差异：旧版「仅大表 Seq Scan→POOR、小表 Seq Scan→GOOD」；新版
 * <b>任意 Seq Scan 一律 POOR</b>，与「有全表扫描才送 LLM」口径对齐。
 * 旧的「规则评级 vs runtime 评级」对比（disagreement）随规则引擎下线一并移除。
 */
@Component
public class RuntimeRatingDeriver {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRatingDeriver.class);

    /** 顶层 cost 低于此值才认为是"低成本计划"。 */
    private static final double LOW_COST_THRESHOLD = 100d;

    /**
     * 由 EXPLAIN 结果派生 overall_rating。
     *
     * @param explain          EXPLAIN 执行结果
     * @param tableMetadataMap 涉及表的元数据 map（key 是 lower-case 表名）；
     *                         新口径下不再用它区分大小表，保留入参仅为兼容调用方
     * @return POOR / GOOD / EXCELLENT；EXPLAIN 失败或短路时返回 {@code null}
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

        // 任意 Seq Scan（不分大小表）→ POOR，触发 LLM 解读
        if (explain.isHasSeqScan()) {
            return IndexRating.POOR;
        }

        // 无 Seq Scan + 全走索引 + 成本低 → EXCELLENT
        boolean allIndexed = explain.isHasIndexScan() && !explain.isHasSeqScan();
        if (allIndexed && explain.getTopCost() < LOW_COST_THRESHOLD) {
            return IndexRating.EXCELLENT;
        }
        // 无 Seq Scan 但成本不低 / 含 Sort 等 → GOOD
        return IndexRating.GOOD;
    }
}
