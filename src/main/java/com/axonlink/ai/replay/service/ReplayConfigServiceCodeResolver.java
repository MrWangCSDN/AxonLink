package com.axonlink.ai.replay.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内部核心交易码到最终服务码集合的映射解析。
 *
 * <p>解析规则：精确查询只读表 {@code znzx_service.tran_code}，读取全部
 * {@code esf_service_code}，去除其中所有点号后分别追加 {@code &sop}、{@code &soap}、
 * {@code &bzjson}，合并去重。
 */
@Service
public class ReplayConfigServiceCodeResolver {

    private final JdbcTemplate jdbc;

    public ReplayConfigServiceCodeResolver(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /**
     * @param internalTransactionCode 内部核心交易码；为空表示不按服务码筛选
     * @return 空集合表示无映射（应返回空分页）；{@code null} 表示不筛选
     */
    public Set<String> resolveFinalServiceCodes(String internalTransactionCode) {
        if (internalTransactionCode == null || internalTransactionCode.isBlank()) {
            return null;
        }
        List<String> esfCodes = jdbc.queryForList(
                "SELECT esf_service_code FROM znzx_service WHERE tran_code = ?",
                String.class, internalTransactionCode.trim());
        Set<String> result = new LinkedHashSet<>();
        for (String esfCode : esfCodes) {
            if (esfCode == null) {
                continue;
            }
            String base = esfCode.replace(".", "");
            if (base.isBlank()) {
                continue;
            }
            result.add(base + "&sop");
            result.add(base + "&soap");
            result.add(base + "&bzjson");
        }
        return result;
    }
}
