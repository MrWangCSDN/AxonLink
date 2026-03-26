package com.axonlink.common;

import java.util.Map;

/**
 * 从 serviceType/component/flowtran 的 package_path 推断业务领域标识（domain_key）。
 *
 * <p>约定格式：{@code com.spdb.ccbs.{领域}.{层}.{模块}.{功能}}
 * <br>取 index=3（第4段）作为领域关键字，静态映射到 AxonLink 的 domain_key。
 *
 * <p>兜底规则：若 package_path 为空、分段不足4段、或关键字未在映射表中，统一返回 {@code "public"}。
 */
public final class DomainKeyResolver {

    /** package_path 第4段关键字 → AxonLink domain_key */
    private static final Map<String, String> DOMAIN_MAP = Map.of(
        "dept", "deposit",
        "loan", "loan",
        "sett", "settlement",
        "comm", "public",
        "unvr", "unvr",
        "aggr", "aggr",
        "inbu", "inbu",
        "medu", "medu",
        "stmt", "stmt"
    );

    private DomainKeyResolver() {}

    /**
     * 从 package_path 推断领域标识。
     *
     * @param packagePath serviceType/flowtran 的包路径，如 {@code com.spdb.ccbs.dept.pbf.trans.qryMnt}
     * @return AxonLink domain_key，如 {@code deposit}；无法识别时返回 {@code "public"}
     */
    public static String resolve(String packagePath) {
        if (packagePath == null || packagePath.isBlank()) return "public";
        String[] parts = packagePath.split("\\.");
        if (parts.length < 4) return "public";
        return DOMAIN_MAP.getOrDefault(parts[3], "public");
    }
}
