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
 * <p>覆盖 spec 算法节全部判定分支（v4 口径）：
 * 主键全覆盖一律 HIGH（不分列数）/ 黑名单单列强制 LOW（降噪逃生阀）/
 * 共享主键双向保留 / 部分列命中 skip。
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
    @DisplayName("v4：单列主键全覆盖 → HIGH（不再按列数降级）")
    void singleColumnKey_fullCover_returnsHigh() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("acct", List.of(new ErKeySet("PK", cols("acct_no"))));
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("acct", set("acct_no", "bal"));
        tableCols.put("txn", set("txn_id", "acct_no", "amt"));   // 含 acct_no（非 txn 键）
        Map<String, Integer> colCount = Map.of("acct_no", 3);

        // 用户口径：只看主键全覆盖，不管几列 → HIGH
        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        assertEquals(1, rels.size());
        assertEquals("HIGH", rels.get(0).getConfidence());
    }

    @Test
    @DisplayName("v4：单列主键即便出现在很多表 → 仍 HIGH（列数/普遍度不再降级）")
    void singleColumnKey_commonColumn_stillHigh() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        keySets.put("dict", List.of(new ErKeySet("PK", cols("id"))));
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("dict", set("id", "label"));
        tableCols.put("other", set("pk2", "id", "x"));
        Map<String, Integer> colCount = Map.of("id", 50);          // 50 张表也不降级

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        assertEquals(1, rels.size());
        assertEquals("HIGH", rels.get(0).getConfidence());
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
    @DisplayName("v2：S 恰是 B 的 PK/UK → 不再排除，保留为合法 1:1 关系（双向）")
    void sharedKey_nowKept() {
        Map<String, List<ErKeySet>> keySets = new HashMap<>();
        // a、b 都用联合键 (m,n)（独特，命中 HIGH）；互为共享主键的拆分表
        keySets.put("a", List.of(new ErKeySet("PK", cols("m", "n"))));
        keySets.put("b", List.of(new ErKeySet("PK", cols("m", "n"))));
        Map<String, Set<String>> tableCols = new HashMap<>();
        tableCols.put("a", set("m", "n", "x"));
        tableCols.put("b", set("m", "n", "y"));
        Map<String, Integer> colCount = Map.of("m", 2, "n", 2);

        List<ErRelation> rels = svc.infer(keySets, tableCols, colCount, 5, Set.of());
        // 不再排除 → a←b 与 b←a 双向都保留（共享主键 1:1）
        assertEquals(2, rels.size(), "共享主键的拆分表应保留双向关系，不再当同实体排除");
        assertTrue(rels.stream().allMatch(r -> "HIGH".equals(r.getConfidence())));
        assertTrue(rels.stream().anyMatch(r -> r.getFromTable().equals("a") && r.getToTable().equals("b")));
        assertTrue(rels.stream().anyMatch(r -> r.getFromTable().equals("b") && r.getToTable().equals("a")));
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
