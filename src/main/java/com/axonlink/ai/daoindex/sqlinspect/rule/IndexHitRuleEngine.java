package com.axonlink.ai.daoindex.sqlinspect.rule;

import com.axonlink.ai.daoindex.sqlinspect.dto.IndexMatchResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexMeta;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import com.axonlink.ai.daoindex.sqlinspect.dto.PredicateExtract;
import com.axonlink.ai.daoindex.sqlinspect.dto.TableRating;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 索引命中评级规则引擎（2a.1 版）。
 *
 * <h3>算法</h3>
 * <pre>
 * 输入：某表的所有 equality 谓词字段集合 P，该表上全部索引 indexes
 * 输出：一个 {@link TableRating}
 *
 * for 每个 index：
 *     按最左顺序遍历 index.columns：
 *         若当前列 ∈ P，matched++；继续
 *         否则 break
 *     记录 (index, matched)
 *
 * 选 matched 最大的一个作为 bestMatch；并列时优先选 总列数更少的（避免选了"大索引"的小前缀而显得命中不饱）
 *
 * 评级：
 *   bestMatch.matched == 0                                     → 差
 *   bestMatch.matched == bestMatch.index.columns.size()        → 优（完全覆盖）
 *   其它                                                        → 良
 * </pre>
 *
 * <p>说明：
 * <ul>
 *   <li>等值谓词参与最左匹配（2a.1 暂不区分等值和范围；范围在 2a.2 处理）。</li>
 *   <li>若某张表根本没有索引（包括主键），直接判"差"。</li>
 *   <li>若 SQL 对该表没有任何谓词（比如 {@code SELECT * FROM t}），也判"差"。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "dao-index-analysis", name = "enabled", havingValue = "true")
public class IndexHitRuleEngine {

    /**
     * 对单张表做评级。
     */
    public TableRating rateTable(PredicateExtract predicates, List<IndexMeta> indexes) {
        TableRating rating = new TableRating();
        rating.setTable(predicates.getTableName());
        rating.setPredicates(predicates);
        rating.setAvailableIndexes(indexes == null ? List.of() : indexes);

        Set<String> equalityCols = predicates.getEqualityColumns().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        // 没索引 / 没谓词 → 差
        if (indexes == null || indexes.isEmpty()) {
            rating.setRating(IndexRating.POOR);
            rating.setReason("表 " + predicates.getTableName() + " 未定义任何索引");
            return rating;
        }
        if (equalityCols.isEmpty()) {
            rating.setRating(IndexRating.POOR);
            rating.setReason("SQL 对表 " + predicates.getTableName() + " 未提供任何等值谓词（Seq Scan 风险）");
            return rating;
        }

        IndexMatch best = null;
        for (IndexMeta idx : indexes) {
            int matched = longestLeftmostMatch(idx.getColumns(), equalityCols);
            IndexMatch cur = new IndexMatch(idx, matched);
            if (best == null || isBetter(cur, best)) {
                best = cur;
            }
        }

        // 兜底：理论上 best 永远非 null（indexes 非空）
        if (best == null || best.matched == 0) {
            rating.setRating(IndexRating.POOR);
            rating.setReason("谓词字段 " + equalityCols + " 未匹配任何索引的最左列");
            return rating;
        }

        IndexMatchResult matchResult = buildMatchResult(best);

        // ORDER BY / GROUP BY 能否利用索引顺序（免额外 Sort / Aggregate 重组）
        boolean orderByCovered = orderBySequenceMatchesIndexSuffix(
                best.index.getColumns(), best.matched, predicates.getOrderByColumns());
        boolean groupByCovered = orderBySequenceMatchesIndexSuffix(
                best.index.getColumns(), best.matched, predicates.getGroupByColumns());
        matchResult.setOrderByCanUseIndex(orderByCovered);
        matchResult.setGroupByCanUseIndex(groupByCovered);

        rating.setMatchedIndex(matchResult);

        int total = best.index.getColumns().size();
        StringBuilder reason = new StringBuilder();
        if (best.matched == total) {
            rating.setRating(IndexRating.EXCELLENT);
            reason.append("完全命中索引 ").append(best.index.getIndexName())
                  .append(" 全部 ").append(total).append(" 列");
        } else {
            rating.setRating(IndexRating.GOOD);
            reason.append("命中索引 ").append(best.index.getIndexName())
                  .append(" 前 ").append(best.matched).append(" 列（共 ").append(total).append(" 列），剩余 ")
                  .append(matchResult.getUnusedSuffix()).append(" 未覆盖");
        }
        if (!predicates.getOrderByColumns().isEmpty()) {
            reason.append(orderByCovered
                    ? "；ORDER BY 可直接利用索引顺序（免 Sort）"
                    : "；ORDER BY " + predicates.getOrderByColumns() + " 未对齐索引，需要额外 Sort");
        }
        if (!predicates.getGroupByColumns().isEmpty()) {
            reason.append(groupByCovered
                    ? "；GROUP BY 可利用索引顺序"
                    : "；GROUP BY " + predicates.getGroupByColumns() + " 未对齐索引");
        }
        rating.setReason(reason.toString());
        return rating;
    }

    /**
     * 计算 SQL 谓词对单个索引的最左连续匹配长度。
     *
     * @param indexColumns  索引列（顺序敏感）
     * @param equalityCols  SQL 的等值谓词字段集合（小写）
     * @return 从最左起连续命中的列数，范围 {@code [0, indexColumns.size()]}
     */
    private int longestLeftmostMatch(List<String> indexColumns, Set<String> equalityCols) {
        if (indexColumns == null || indexColumns.isEmpty()) return 0;
        int matched = 0;
        for (String col : indexColumns) {
            String normalized = col == null ? "" : col.toLowerCase(Locale.ROOT);
            if (equalityCols.contains(normalized)) {
                matched++;
            } else {
                break; // 最左匹配：一断就全断
            }
        }
        return matched;
    }

    /**
     * 多索引候选比较：
     * <ol>
     *   <li>matched 大的优先</li>
     *   <li>matched 相同，列总数小的优先（更"贴合"，避免大索引的浅前缀掩盖真正全覆盖的小索引）</li>
     *   <li>再相同，主键 / 唯一索引优先（可读性更好）</li>
     * </ol>
     */
    private boolean isBetter(IndexMatch cur, IndexMatch best) {
        if (cur.matched != best.matched) {
            return cur.matched > best.matched;
        }
        int curTotal = cur.index.getColumns().size();
        int bestTotal = best.index.getColumns().size();
        if (curTotal != bestTotal) {
            return curTotal < bestTotal;
        }
        // 主键 > 唯一 > 普通
        int curRank = rankOfIndexType(cur.index);
        int bestRank = rankOfIndexType(best.index);
        return curRank > bestRank;
    }

    private int rankOfIndexType(IndexMeta idx) {
        if (idx.isPrimary()) return 2;
        if (idx.isUnique()) return 1;
        return 0;
    }

    private IndexMatchResult buildMatchResult(IndexMatch best) {
        IndexMatchResult r = new IndexMatchResult();
        r.setIndexName(best.index.getIndexName());
        r.setIndexColumns(new ArrayList<>(best.index.getColumns()));
        r.setMatchedColumnCount(best.matched);
        r.setTotalColumnCount(best.index.getColumns().size());
        List<String> suffix = best.matched < best.index.getColumns().size()
                ? new ArrayList<>(best.index.getColumns().subList(best.matched, best.index.getColumns().size()))
                : List.of();
        r.setUnusedSuffix(suffix);
        return r;
    }

    /**
     * 判定 {@code ORDER BY} 字段序列是否刚好等于索引 matched 之后的连续列，
     * 从而可以利用索引顺序、免掉 Sort 步骤。
     *
     * <p>例如索引 {@code (a,b,c,d)}：
     * <ul>
     *   <li>{@code WHERE a=? ORDER BY b,c} → matched=1，ORDER BY=[b,c]，等于 suffix[0..2] 前缀 ✅</li>
     *   <li>{@code WHERE a=? ORDER BY c}  → ORDER BY=[c] ≠ suffix 前缀 [b] ❌</li>
     *   <li>{@code WHERE a=?,b=? ORDER BY c} → matched=2，ORDER BY=[c]=suffix[0..1] 前缀 ✅</li>
     * </ul>
     */
    private boolean orderBySequenceMatchesIndexSuffix(List<String> indexColumns,
                                                      int matchedCount,
                                                      List<String> orderBy) {
        if (orderBy == null || orderBy.isEmpty()) return false;
        if (indexColumns == null || matchedCount > indexColumns.size()) return false;
        // 剩余可用的索引列（matched 之后的顺序列）
        List<String> suffix = indexColumns.subList(matchedCount, indexColumns.size());
        if (orderBy.size() > suffix.size()) return false;
        for (int i = 0; i < orderBy.size(); i++) {
            String expect = suffix.get(i).toLowerCase(Locale.ROOT);
            String actual = orderBy.get(i) == null ? "" : orderBy.get(i).toLowerCase(Locale.ROOT);
            if (!expect.equals(actual)) return false;
        }
        return true;
    }

    /** 内部中间态：一个索引的匹配结果。 */
    private record IndexMatch(IndexMeta index, int matched) {}
}
