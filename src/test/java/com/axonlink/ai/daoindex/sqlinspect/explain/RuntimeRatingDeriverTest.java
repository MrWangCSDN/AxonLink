package com.axonlink.ai.daoindex.sqlinspect.explain;

// 静态导入断言方法，断言时少写一层前缀
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.dto.ExplainResult;
import com.axonlink.ai.daoindex.sqlinspect.dto.IndexRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeRatingDeriver} 行为矩阵单元测试（增强 v6）。
 *
 * <p>覆盖 derive() 全部判序分支：
 * <ul>
 *   <li>explain=null / !isSuccess / oneTimeFilterFalse → null（不评级）</li>
 *   <li>topPlanRows ≥ 1000 → POOR（大扫描规则优先于 Seq Scan）</li>
 *   <li>hasSeqScan 且 topCost ≥ 阈值(默认 50.0) → POOR</li>
 *   <li>hasSeqScan 且 topCost &lt; 阈值且 rows &lt; 1000 → EXCELLENT（v6 核心新行为：便宜小表全扫不送 LLM）</li>
 *   <li>命中索引且小扫描 → EXCELLENT（cost 不参与非 seq 路径）</li>
 * </ul>
 * <p>纯逻辑、无需 Spring 上下文：props 直接 {@code new} 用默认阈值 50.0，
 * deriver 直接 {@code new RuntimeRatingDeriver(props)}。
 */
@DisplayName("RuntimeRatingDeriver —— EXPLAIN 派生评级行为矩阵（增强 v6）")
class RuntimeRatingDeriverTest {

    // 被测对象；@BeforeEach 中用默认配置（seqScanCostMin=50.0）重新构造，保证用例间互不影响
    private RuntimeRatingDeriver deriver;

    @BeforeEach
    void setUp() {
        // ConfigurationProperties POJO 直接 new，不走 Spring 绑定；getRating().getSeqScanCostMin() 默认 50.0
        DaoIndexAnalysisProperties props = new DaoIndexAnalysisProperties();
        // 构造注入：该类 v6 起有唯一构造函数 RuntimeRatingDeriver(DaoIndexAnalysisProperties)
        deriver = new RuntimeRatingDeriver(props);
    }

    /**
     * 构造一个"成功"的 ExplainResult 测试夹具。
     *
     * @param hasSeqScan 是否含 Seq Scan 节点
     * @param topPlanRows EXPLAIN 估算顶层返回行数
     * @param topCost EXPLAIN 估算顶层 Total Cost
     * @return success=true 的 ExplainResult（oneTimeFilterFalse 默认 false）
     */
    private ExplainResult ok(boolean hasSeqScan, long topPlanRows, double topCost) {
        // 无参构造 + setter 逐项赋值（ExplainResult 是普通 JavaBean，无 builder）
        ExplainResult r = new ExplainResult();
        r.setSuccess(true);
        r.setHasSeqScan(hasSeqScan);
        r.setTopPlanRows(topPlanRows);
        r.setTopCost(topCost);
        return r;
    }

    @Test
    @DisplayName("explain=null → null（不评级，由调用方写 null）")
    void nullExplain_returnsNull() {
        // 第二个入参 tableMetadataMap 新口径下不再使用，传 null 即可
        assertNull(deriver.derive(null, null));
    }

    @Test
    @DisplayName("!isSuccess → null（EXPLAIN 失败/未跑）")
    void notSuccess_returnsNull() {
        ExplainResult r = new ExplainResult();
        r.setSuccess(false);
        assertNull(deriver.derive(r, null));
    }

    @Test
    @DisplayName("oneTimeFilterFalse=true → null（参数被当 NULL 短路，计划不代表真实执行）")
    void oneTimeFilterFalse_returnsNull() {
        ExplainResult r = ok(true, 1L, 1.02);
        r.setOneTimeFilterFalse(true);
        assertNull(deriver.derive(r, null));
    }

    @Test
    @DisplayName("seqScan=true, rows=1, cost=1.02 → EXCELLENT（v6 核心：便宜小表全扫不送 LLM）")
    void cheapSmallTableSeqScan_returnsExcellent() {
        assertEquals(IndexRating.EXCELLENT, deriver.derive(ok(true, 1L, 1.02), null));
    }

    @Test
    @DisplayName("seqScan=true, rows=1, cost=50.0 → POOR（达阈值，边界含等号）")
    void seqScanAtCostThreshold_returnsPoor() {
        assertEquals(IndexRating.POOR, deriver.derive(ok(true, 1L, 50.0), null));
    }

    @Test
    @DisplayName("seqScan=true, rows=1, cost=120.0 → POOR（贵的全扫）")
    void expensiveSeqScan_returnsPoor() {
        assertEquals(IndexRating.POOR, deriver.derive(ok(true, 1L, 120.0), null));
    }

    @Test
    @DisplayName("seqScan=true, rows=5000, cost=1.0 → POOR（rows≥1000 规则优先，即便 cost 低）")
    void largeRowsSeqScanLowCost_returnsPoor() {
        assertEquals(IndexRating.POOR, deriver.derive(ok(true, 5000L, 1.0), null));
    }

    @Test
    @DisplayName("seqScan=false, rows=5000 → POOR（命中索引大扫描）")
    void indexScanLargeRows_returnsPoor() {
        assertEquals(IndexRating.POOR, deriver.derive(ok(false, 5000L, 0.0), null));
    }

    @Test
    @DisplayName("seqScan=false, rows=10, cost=80 → EXCELLENT（命中索引小扫描；cost 不参与非 seq 路径）")
    void indexScanSmallRows_returnsExcellent() {
        assertEquals(IndexRating.EXCELLENT, deriver.derive(ok(false, 10L, 80.0), null));
    }
}
