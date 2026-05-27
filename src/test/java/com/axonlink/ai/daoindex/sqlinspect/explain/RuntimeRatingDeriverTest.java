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
 * {@link RuntimeRatingDeriver} 行为矩阵单元测试（增强 v7）。
 *
 * <p>v7 回滚 v6：任意 Seq Scan 即 POOR（无条件送 LLM）。覆盖：
 * <ul>
 *   <li>explain=null / !isSuccess / oneTimeFilterFalse → null（不评级）</li>
 *   <li>hasSeqScan=true（不论 cost / rows）→ POOR（v7 核心）</li>
 *   <li>hasSeqScan=false 但 topPlanRows ≥ 1000 → POOR（命中索引但大扫描）</li>
 *   <li>hasSeqScan=false 且 topPlanRows &lt; 1000 → EXCELLENT</li>
 * </ul>
 * <p>纯逻辑、无需 Spring 上下文：props 直接 {@code new}（v7 不再读取，但构造签名保留）。
 */
@DisplayName("RuntimeRatingDeriver —— EXPLAIN 派生评级行为矩阵（增强 v7）")
class RuntimeRatingDeriverTest {

    // 被测对象；@BeforeEach 重新构造，保证用例间互不影响
    private RuntimeRatingDeriver deriver;

    @BeforeEach
    void setUp() {
        // ConfigurationProperties POJO 直接 new；v7 起 deriver 已不读取 seqScanCostMin
        DaoIndexAnalysisProperties props = new DaoIndexAnalysisProperties();
        deriver = new RuntimeRatingDeriver(props);
    }

    /**
     * 构造一个"成功"的 ExplainResult 测试夹具。
     */
    private ExplainResult ok(boolean hasSeqScan, long topPlanRows, double topCost) {
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
    @DisplayName("v7 核心：seqScan=true, rows=1, cost=1.02 → POOR（便宜小表全扫也送 LLM）")
    void cheapSmallTableSeqScan_returnsPoor_v7() {
        // v6 时此条返回 EXCELLENT；v7 改回 POOR（用户要求：只要全表扫描就给 LLM 分析）
        assertEquals(IndexRating.POOR, deriver.derive(ok(true, 1L, 1.02), null));
    }

    @Test
    @DisplayName("v7：seqScan=true, rows=1, cost=0.0 → POOR（成本为 0 的 Seq Scan 也送）")
    void zeroCostSeqScan_returnsPoor() {
        // 极端边界：cost=0、rows=1 这种"看起来最便宜"的 Seq Scan 在 v7 下也判 POOR
        assertEquals(IndexRating.POOR, deriver.derive(ok(true, 1L, 0.0), null));
    }

    @Test
    @DisplayName("v7：seqScan=true, rows=1, cost=120.0 → POOR（贵的全扫，v6/v7 行为一致）")
    void expensiveSeqScan_returnsPoor() {
        assertEquals(IndexRating.POOR, deriver.derive(ok(true, 1L, 120.0), null));
    }

    @Test
    @DisplayName("v7：seqScan=true, rows=5000, cost=1.0 → POOR（任意 SeqScan 一律命中第一条规则）")
    void largeRowsSeqScanLowCost_returnsPoor() {
        // 注意：v6 时这条由"rows≥1000"规则触发；v7 把 SeqScan 判前置，由第一条规则触发，
        // 结果相同（仍 POOR），但语义路径变了——这是 v7 把 SeqScan 提前的副效应，可接受。
        assertEquals(IndexRating.POOR, deriver.derive(ok(true, 5000L, 1.0), null));
    }

    @Test
    @DisplayName("seqScan=false, rows=5000 → POOR（命中索引但大扫描）")
    void indexScanLargeRows_returnsPoor() {
        assertEquals(IndexRating.POOR, deriver.derive(ok(false, 5000L, 0.0), null));
    }

    @Test
    @DisplayName("seqScan=false, rows=10, cost=80 → EXCELLENT（命中索引小扫描；cost 不参与非 seq 路径）")
    void indexScanSmallRows_returnsExcellent() {
        assertEquals(IndexRating.EXCELLENT, deriver.derive(ok(false, 10L, 80.0), null));
    }

    @Test
    @DisplayName("seqScan=false, rows=999 → EXCELLENT（命中索引、阈值前一格）")
    void indexScanJustBelowThreshold_returnsExcellent() {
        assertEquals(IndexRating.EXCELLENT, deriver.derive(ok(false, 999L, 0.0), null));
    }

    @Test
    @DisplayName("seqScan=false, rows=1000 → POOR（命中索引、阈值边界含等号）")
    void indexScanAtThreshold_returnsPoor() {
        assertEquals(IndexRating.POOR, deriver.derive(ok(false, 1000L, 0.0), null));
    }
}
