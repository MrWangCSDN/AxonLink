package com.axonlink.ai.replay.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 从只读映射表按内部核心交易码查找完整服务码。 */
@Service
public class ReplayConfigServiceCodeResolver {

    static final String SERVICE_MAPPING_TABLE = "znzx_service";

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
        List<String> codes = jdbc.queryForList(
                "SELECT DISTINCT esf_service_code FROM " + SERVICE_MAPPING_TABLE + " WHERE tran_code = ?",
                String.class, internalTransactionCode.trim());
        return new LinkedHashSet<>(codes);
    }
}
