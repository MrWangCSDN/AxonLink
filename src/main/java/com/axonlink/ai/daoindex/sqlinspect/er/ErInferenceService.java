package com.axonlink.ai.daoindex.sqlinspect.er;

import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErKeySet;
import com.axonlink.ai.daoindex.sqlinspect.er.dto.ErRelation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表关系推断纯算法（无 DB / 无 Spring 依赖，可独立单测）。
 *
 * <p>规则（用户定义，v2 优化）：表 A 的某个键集合 S（PK 或 UK，单列或联合）的<b>全部列</b>
 * 出现在表 B 中即推断 A←B 关系（B 通过 S 引用 A）。
 * <p><b>v2 变更</b>：不再排除「S 也是 B 自身 PK/UK」的情形——这其实是合法的 1:1 关系
 * （如拆分表/扩展表共享主键），之前被当「同实体」误排除。现在一并保留。
 * 单列通用键造成的 PK-PK 组合噪声由置信度护栏兜底（落 LOW，前端默认隐藏）。
 *
 * <p>置信度（v4）：主键全覆盖即关系，<b>不分列数</b>，一律 HIGH。
 * 仅当单列主键命中 yml 黑名单（默认空）才降 LOW 隐藏——给运维的降噪逃生阀。
 */
@Service
public class ErInferenceService {

    /**
     * 推断全部关系。
     *
     * @param keySets         tableName → 该表的键集合列表（PK + 每个 UK）
     * @param tableColumns    tableName → 该表全部列集合（小写）
     * @param columnTableCount columnName → 出现该列的表数（算单列键独特性）
     * @param distinctMax     单列键独特性阈值（列出现表数 ≤ 此值 → 独特）
     * @param blacklist       通用列黑名单（小写）；命中则单列键强制 LOW
     * @return 推断关系列表
     */
    public List<ErRelation> infer(Map<String, List<ErKeySet>> keySets,
                                  Map<String, Set<String>> tableColumns,
                                  Map<String, Integer> columnTableCount,
                                  int distinctMax,
                                  Set<String> blacklist) {
        List<ErRelation> out = new ArrayList<>();
        if (keySets == null || tableColumns == null) return out;
        Set<String> bl = blacklist == null ? Set.of() : blacklist;
        Map<String, Integer> colCount = columnTableCount == null ? Map.of() : columnTableCount;

        for (Map.Entry<String, List<ErKeySet>> eA : keySets.entrySet()) {
            String tableA = eA.getKey();
            if (eA.getValue() == null) continue;
            for (ErKeySet s : eA.getValue()) {
                if (s.getColumns() == null || s.getColumns().isEmpty()) continue;
                Set<String> scol = new HashSet<>(s.getColumns());

                for (Map.Entry<String, Set<String>> eB : tableColumns.entrySet()) {
                    String tableB = eB.getKey();
                    if (tableB.equals(tableA)) continue;
                    Set<String> bCols = eB.getValue();
                    if (bCols == null || !bCols.containsAll(scol)) continue;   // 规则：B 含 S 全部列即成立
                    // v2：不再排除「S 也是 B 的 PK/UK」——那是合法 1:1（共享主键的拆分/扩展表），保留。

                    String conf = confidence(s, colCount, distinctMax, bl);
                    out.add(new ErRelation(tableA, tableB, s.getColumns(),
                            s.getKeyType(), s.getColumns().size(), conf));
                }
            }
        }
        return out;
    }

    /**
     * 置信度判定（v4 简化）。
     * <p>用户口径：只看主键，<b>不管几列</b>，只要主键全部列在对方表全包含 → 就是关系，
     * 一律 HIGH 展示（不再按列数分 HIGH/MEDIUM/LOW）。
     * <p>唯一保留的降噪：单列主键且该列在 yml 黑名单（{@code er.common-columns}，默认空）
     * → 降 LOW 隐藏。这是给运维的逃生阀，默认不生效。
     */
    private String confidence(ErKeySet s, Map<String, Integer> colCount,
                              int distinctMax, Set<String> blacklist) {
        if (s.getColumns().size() == 1 && blacklist.contains(s.getColumns().get(0))) {
            return "LOW";   // 显式标为噪声列 → 隐藏
        }
        return "HIGH";      // 主键全覆盖即关系，全部展示
    }
}
