package com.axonlink.ai.daoindex.sqlinspect.explain;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
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
 * <h3>增强 v7（2026-05-27 回滚 v6）：任意全表扫描都送 LLM，不再看成本阈值</h3>
 * <pre>
 * 1. EXPLAIN 失败 / 未跑                                          → null
 *      （看板靠 explain_error 归"报错"档；不送 LLM）
 * 2. 顶层 One-Time Filter:false（参数被当 NULL 短路）              → null
 *      （计划不代表真实执行，GaussDB 5 不支持 GENERIC_PLAN 的已知限制；保留含日志）
 * 3. hasSeqScan = true（任意全表扫描）                             → POOR（需整改候选）
 *      （v7：无条件送 LLM——便宜小表全扫也送，让 LLM 自行判 NO_NEED；
 *        理由：成本估算偶有偏差/历史误判，宁可让 LLM 多看一次也别漏）
 * 4. EXPLAIN 估算 topPlanRows ≥ 1000（命中索引但大扫描）           → POOR（需整改候选）
 * 5. 其余（命中索引 + 小扫描）                                     → EXCELLENT（无需整改）
 * </pre>
 *
 * <p><b>v7 回滚 v6</b>：v6 曾引入「Seq Scan 但 topCost &lt; 阈值（默认 50）→ EXCELLENT」
 * 的小表豁免规则，目的是避免便宜小表全扫白跑 LLM。但用户反馈："只要全表扫描都要给 LLM 分析"——
 * 优化器成本估算受统计信息影响，部分场景偏低（如 ANALYZE 未跑、统计陈旧）会把"该送的"也豁免掉。
 * v7 撤回该豁免，回到 v3 的口径：任意 Seq Scan → POOR → LLM。
 * <p>{@link DaoIndexAnalysisProperties.Rating#getSeqScanCostMin()} 配置字段保留兼容
 * （默认值仍为 50.0，yml 也照旧绑定），但本类已不再读取——纯保留避免 yml 报"无法识别属性"。
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
     * 保留构造函数签名兼容 Spring 注入（v7 后实际不再读取 props）；删字段会破坏
     * existing yml 的 properties 解析或被注入框架视为变更，按宪法"最小爆炸半径"保留。
     */
    @SuppressWarnings("unused")
    private final DaoIndexAnalysisProperties props;

    public RuntimeRatingDeriver(DaoIndexAnalysisProperties props) {
        this.props = props;
    }

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

        // v7：任意全表扫描 → 需整改候选（无条件送 LLM，由 LLM 给出 NEED_FIX/NO_NEED）
        // 注意把 Seq Scan 判定前置，便宜小表全扫也命中此分支——这是与 v6 的关键区别。
        if (explain.isHasSeqScan()) {
            return IndexRating.POOR;
        }
        // 命中索引但 EXPLAIN 估算大扫描 → 需整改候选（回表 / 扫描量大，仍需 LLM 解读）
        if (explain.getTopPlanRows() >= LARGE_SCAN_ROWS) {
            return IndexRating.POOR;
        }
        // 命中索引 + 小扫描 → 无需整改
        return IndexRating.EXCELLENT;
    }
}
