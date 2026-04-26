package com.axonlink.ai.daoindex.sqlinspect.explain;

import com.axonlink.ai.daoindex.sqlinspect.dto.ExplainResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 基于 EXPLAIN 真实计划派生运行时评级（{@code runtime_rating}），
 * 并对比规则评级，输出 {@code disagreement} 信号。
 *
 * <h3>派生规则</h3>
 * <pre>
 * 1. EXPLAIN 失败                                            → UNKNOWN（不参与 disagreement 计算）
 * 2. 涉及任何"大表"上的 Seq Scan（>= 1万 行）                 → POOR
 * 3. 仅"小表"上的 Seq Scan（< 1万 行）                        → GOOD（小表 Seq Scan 是合理的）
 * 4. 全部 Index Scan/Index Only Scan + 顶层 cost < 100         → EXCELLENT
 * 5. 其他                                                      → GOOD
 * </pre>
 *
 * <h3>disagreement 判定</h3>
 * <p>当 {@code runtime != UNKNOWN} 且 与 {@code rule} 不一致时为 true。
 * 输出原因便于审计："规则评 优，但 EXPLAIN 显示 大表 Seq Scan，建议人工 review"。
 */
@Component
public class RuntimeRatingDeriver {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRatingDeriver.class);

    /** 表行数 ≥ 这个阈值时，Seq Scan 视为严重问题。 */
    private static final long LARGE_TABLE_ROWS = 10_000L;
    /** 顶层 cost 低于此值才认为是"低成本计划"。 */
    private static final double LOW_COST_THRESHOLD = 100d;

    /**
     * 派生 runtime_rating。
     *
     * @param explain         EXPLAIN 执行结果
     * @param tableMetadataMap 涉及表的元数据 map（key 是 lower-case 表名）
     */
    public IndexRating derive(ExplainResult explain, Map<String, TableMetadata> tableMetadataMap) {
        if (explain == null || !explain.isSuccess()) {
            // EXPLAIN 失败/未跑：不评 runtime，由调用方决定怎么写库
            return null;
        }
        // 顶层 One-Time Filter: false 意味着 SQL 被优化器视为恒假（通常是参数替换为 NULL 导致），
        // 里层的 Seq Scan / Index Scan 不代表真实执行。不派生 runtime_rating，避免系统性误判。
        if (explain.isOneTimeFilterFalse()) {
            log.debug("[dii-runtime] 顶层 One-Time Filter=false，跳过派生（参数替换导致的恒假条件）");
            return null;
        }

        boolean hasLargeTableSeqScan = false;
        if (explain.isHasSeqScan()) {
            for (String table : explain.getScannedTables()) {
                TableMetadata md = tableMetadataMap == null ? null
                        : tableMetadataMap.get(table.toLowerCase(Locale.ROOT));
                long tuples = md != null && md.getLiveTuples() != null ? md.getLiveTuples() : Long.MAX_VALUE;
                // 没拿到行数时保守认为是"大表"，避免漏判
                if (tuples >= LARGE_TABLE_ROWS) {
                    hasLargeTableSeqScan = true;
                    break;
                }
            }
            if (hasLargeTableSeqScan) return IndexRating.POOR;
        }

        // 全部走索引 + 成本低 → 优
        boolean allIndexed = explain.isHasIndexScan() && !explain.isHasSeqScan();
        if (allIndexed && explain.getTopCost() < LOW_COST_THRESHOLD) {
            return IndexRating.EXCELLENT;
        }
        // Seq Scan 但都是小表 / 或其他正常情况 → 良
        return IndexRating.GOOD;
    }

    /**
     * 比对规则评级与 runtime 评级，得出分歧信号。
     *
     * @return 长度 2 数组：{@code [disagreementBoolean, reasonString]}（reason 可能为 null）
     */
    public Disagreement compare(IndexRating ruleRating, IndexRating runtimeRating, ExplainResult explain) {
        if (runtimeRating == null) {
            // EXPLAIN 失败：算不上分歧
            return new Disagreement(false, null);
        }
        if (ruleRating == runtimeRating) {
            return new Disagreement(false, null);
        }
        // 不一致，给"人话理由"
        String reason = buildReason(ruleRating, runtimeRating, explain);
        log.debug("[dii-runtime] disagreement rule={} runtime={} reason={}", ruleRating, runtimeRating, reason);
        return new Disagreement(true, reason);
    }

    private String buildReason(IndexRating rule, IndexRating runtime, ExplainResult explain) {
        StringBuilder sb = new StringBuilder();
        sb.append("规则评").append(label(rule)).append("，EXPLAIN 派生").append(label(runtime)).append("：");
        if (runtime == IndexRating.POOR) {
            if (explain.isHasSeqScan()) {
                sb.append("出现 Seq Scan on ").append(explain.getScannedTables());
            }
            sb.append("（顶层 cost=").append(String.format("%.1f", explain.getTopCost())).append("）");
            sb.append("。建议人工复核优化器选择，可能是统计信息过期或隐式类型转换。");
        } else if (runtime == IndexRating.EXCELLENT && rule != IndexRating.EXCELLENT) {
            sb.append("优化器自动选择了较优的索引组合，比规则推断更好。");
        } else {
            sb.append("评级落差较小，可参考 EXPLAIN 计划。");
        }
        return sb.toString();
    }

    private static String label(IndexRating r) {
        if (r == null) return "未知";
        return r.getLabel();
    }

    /** 比对结果：是否分歧 + 人话理由。 */
    public static class Disagreement {
        public final boolean disagree;
        public final String reason;

        public Disagreement(boolean disagree, String reason) {
            this.disagree = disagree;
            this.reason = reason;
        }
    }
}
