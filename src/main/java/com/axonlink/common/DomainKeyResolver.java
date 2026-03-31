package com.axonlink.common;

import java.util.Locale;
import java.util.Map;

/**
 * 从工程名或 package_path 推断业务领域标识（domain_key）。
 *
 * <p>规则：
 * <ul>
 *   <li>优先根据工程名判断，例如 {@code ccbs-loan-impl -> loan}</li>
 *   <li>工程名无法识别时，再根据 {@code package_path} 兜底</li>
 * </ul>
 *
 * <p>兜底规则：若工程名和 {@code package_path} 都无法识别，统一返回 {@code "public"}。
 */
public final class DomainKeyResolver {

    /** package_path 关键字 → AxonLink domain_key */
    private static final Map<String, String> DOMAIN_MAP = Map.of(
        "dept", "deposit",
        "loan", "loan",
        "lntran", "loan",
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
     * 根据工程名优先、包路径兜底地解析领域。
     */
    public static String resolveByProjectOrPackage(String projectName, String packagePath) {
        String fromProject = resolveProject(projectName);
        if (fromProject != null) {
            return fromProject;
        }
        return resolve(packagePath);
    }

    /**
     * 根据工程名推断领域；无法识别时返回 null。
     */
    public static String resolveProject(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return null;
        }

        String normalized = projectName.trim().toLowerCase(Locale.ROOT);
        if (matchesProject(normalized, "loan")) return "loan";
        if (matchesProject(normalized, "dept")) return "deposit";
        if (matchesProject(normalized, "comm")) return "public";
        if (matchesProject(normalized, "sett")) return "settlement";
        if (matchesProject(normalized, "unvr")) return "unvr";
        if (matchesProject(normalized, "aggr")) return "aggr";
        if (matchesProject(normalized, "inbu")) return "inbu";
        if (matchesProject(normalized, "medu")) return "medu";
        if (matchesProject(normalized, "stmt")) return "stmt";
        if (normalized.equals("ap-parent") || normalized.startsWith("ccbs-ap-")) return "ap";
        return null;
    }

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

    private static boolean matchesProject(String normalizedProjectName, String token) {
        return normalizedProjectName.equals(token + "-parent")
            || normalizedProjectName.startsWith("ccbs-" + token + "-");
    }
}
