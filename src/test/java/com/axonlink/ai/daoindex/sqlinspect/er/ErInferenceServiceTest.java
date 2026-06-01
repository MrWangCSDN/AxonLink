package com.axonlink.ai.daoindex.sqlinspect.er;

import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErKeySet;
import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErRelation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ErInferenceService} 纯算法单测——不依赖 DB / Spring。
 *
 * <p>覆盖 spec 算法节全部判定分支 + 置信度护栏：
 * 联合键 HIGH / 单列独特 MEDIUM / 单列通用 LOW / 黑名单强制 LOW /
 * 同实体 skip / 部分列命中 skip。
 */
@DisplayName("ErInferenceService —— 关系推断 + 置信度护栏")
class ErInferenceServiceTest {

    private final ErInferenceService svc = new ErInferenceService();

    private static List<String> cols(String... c) { return Arrays.asList(c); }
    private static Set<String> set(String... c) { return new HashSet<>(Arrays.asList(c)); }

    @Test
    @DisplayName("联合键(≥2列)全命中 → HIGH")
    void compositeKeyAllMatch_returnsHigh() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("a", List.of(new ErKeySet("PK", cols("a_id", "b_id"))));
        keySets.put("b", List.of(new ErKeySet("PK", cols("own_id"))));  // b 自身键不同
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("a", set("a_id", "b_id", "name"));
        tableCols.put("b", set("own_id", "a_id", "b_id", "amt"));        // b 含 a 的联合键两列
        Map<String, Integer> colCount = Map.of("a_id", 2, "b_id", 2);

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        assertEquals(1, rels.size());
        ErRelation r = rels.get(0);
        assertEquals("a", r.getFromTable());
        assertEquals("b", r.getToTable());
        assertEquals("HIGH", r.getConfidence());
        assertEquals(2, r.getKeyColCount());
    }

    @Test
    @DisplayName("单列键 + 列名独特(出现表数 ≤ 阈值) → MEDIUM")
    void singleDistinctColumn_returnsMedium() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("acct", List.of(new ErKeySet("PK", cols("acct_no"))));
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("acct", set("acct_no", "bal"));
        tableCols.put("txn", set("txn_id", "acct_no", "amt"));   // 含 acct_no（非 txn 键）
        Map<String, Integer> colCount = Map.of("acct_no", 3);     // 仅 3 张表 ≤ 5

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        assertEquals(1, rels.size());
        assertEquals("MEDIUM", rels.get(0).getConfidence());
    }

    @Test
    @DisplayName("单列键 + 列名通用(出现表数 > 阈值) → LOW")
    void singleCommonColumn_returnsLow() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("dict", List.of(new ErKeySet("PK", cols("id"))));
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("dict", set("id", "label"));
        tableCols.put("other", set("pk2", "id", "x"));
        Map<String, Integer> colCount = Map.of("id", 50);          // 50 张表 > 5

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        assertEquals(1, rels.size());
        assertEquals("LOW", rels.get(0).getConfidence());
    }

    @Test
    @DisplayName("单列键列名在黑名单 → 强制 LOW（即便出现表数少）")
    void blacklistColumn_forcesLow() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("t1", List.of(new ErKeySet("PK", cols("status"))));
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("t1", set("status", "x"));
        tableCols.put("t2", set("k2", "status", "y"));
        Map<String, Integer> colCount = Map.of("status", 2);       // 只 2 张表，本应 MEDIUM

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of("status"));
        assertEquals(1, rels.size());
        assertEquals("LOW", rels.get(0).getConfidence(), "黑名单列即便独特也降 LOW");
    }

    @Test
    @DisplayName("S 恰是 B 的 PK/UK → 同实体，不产出关系")
    void sameEntity_skipped() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("a", List.of(new ErKeySet("PK", cols("k"))));
        keySets.put("b", List.of(new ErKeySet("PK", cols("k"))));  // b 的 PK 也是 k
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("a", set("k", "x"));
        tableCols.put("b", set("k", "y"));
        Map<String, Integer> colCount = Map.of("k", 2);

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        assertTrue(rels.isEmpty(), "k 是 b 自己的主键 → 同实体，应 skip");
    }

    @Test
    @DisplayName("B 只含 S 的部分列 → 不产出关系")
    void partialColumnMatch_skipped() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("a", List.of(new ErKeySet("PK", cols("a_id", "b_id"))));
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("a", set("a_id", "b_id"));
        tableCols.put("b", set("own", "a_id"));    // 只含 a_id，缺 b_id
        Map<String, Integer> colCount = Map.of("a_id", 2, "b_id", 1);

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        assertTrue(rels.isEmpty(), "联合键要求全部列命中，部分命中应 skip");
    }
}
