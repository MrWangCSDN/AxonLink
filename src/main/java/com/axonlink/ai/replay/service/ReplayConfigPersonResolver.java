package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigPersonInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 配置服务码到「全量交易人员清单」的映射解析。
 *
 * <p>链路：配置最终服务码去后缀得 base → 匹配 znzx_service 去点号的 esf_service_code →
 * 取 znzx_service.tran_code → 匹配 dii_replay_transaction_person.old_transaction_code →
 * 取老核心交易码、开发人员、行方负责人。映射不到时返回 {@code null}。
 */
@Service
public class ReplayConfigPersonResolver {

    private static final Pattern SUFFIX_PATTERN = Pattern.compile("&(sop|soap|bzjson)$");

    private final JdbcTemplate jdbc;

    public ReplayConfigPersonResolver(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /** 按最终服务码批量解析人员信息；返回 key 为传入的原始服务码。 */
    public Map<String, ReplayConfigPersonInfo> resolveByServiceCodes(Collection<String> finalServiceCodes) {
        Map<String, ReplayConfigPersonInfo> result = new LinkedHashMap<>();
        if (finalServiceCodes == null || finalServiceCodes.isEmpty()) {
            return result;
        }
        Set<String> bases = new LinkedHashSet<>();
        for (String code : finalServiceCodes) {
            if (code == null) {
                continue;
            }
            String base = stripSuffix(code.trim());
            if (!base.isBlank()) {
                bases.add(base);
            }
        }
        Map<String, ReplayConfigPersonInfo> byBase = new HashMap<>();
        if (!bases.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(bases.size(), "?"));
            List<Object> args = new ArrayList<>(bases);
            jdbc.query("SELECT REPLACE(z.esf_service_code,'.','') AS service_base, "
                            + "p.old_transaction_code AS person_code, p.developer, p.bank_owner, p.bank_owner_emp_nos "
                            + "FROM znzx_service z "
                            + "LEFT JOIN dii_replay_transaction_person p ON p.old_transaction_code = z.tran_code "
                            + "WHERE REPLACE(z.esf_service_code,'.','') IN (" + placeholders + ")",
                    rs -> {
                        String base = rs.getString("service_base");
                        String personCode = rs.getString("person_code");
                        if (base == null || personCode == null || byBase.containsKey(base)) {
                            return;
                        }
                        byBase.put(base, new ReplayConfigPersonInfo(personCode, rs.getString("developer"),
                                rs.getString("bank_owner"), rs.getString("bank_owner_emp_nos")));
                    }, args.toArray());
        }
        for (String code : finalServiceCodes) {
            if (code == null) {
                continue;
            }
            result.put(code, byBase.get(stripSuffix(code.trim())));
        }
        return result;
    }

    /**
     * 解析某工号作为行方负责人可审核的全部最终服务码集合。
     *
     * <p>路径：人员清单 bank_owner_emp_nos 命中该工号 → old_transaction_code →
     * znzx_service.tran_code → esf_service_code 去点号 → 追加三种后缀。
     */
    public Set<String> findReviewableServiceCodes(String empNo) {
        if (empNo == null || empNo.isBlank()) {
            return Set.of();
        }
        String target = empNo.trim();
        List<String> transactionCodes = jdbc.query(
                        "SELECT old_transaction_code, bank_owner_emp_nos FROM dii_replay_transaction_person "
                                + "WHERE TRIM(COALESCE(bank_owner_emp_nos,'')) <> ''",
                        (rs, rowNum) -> new String[]{rs.getString("old_transaction_code"),
                                rs.getString("bank_owner_emp_nos")})
                .stream()
                .filter(row -> splitEmpNos(row[1]).contains(target))
                .map(row -> row[0])
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        if (transactionCodes.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", Collections.nCopies(transactionCodes.size(), "?"));
        List<String> esfCodes = jdbc.queryForList(
                "SELECT esf_service_code FROM znzx_service WHERE tran_code IN (" + placeholders + ")",
                String.class, transactionCodes.toArray());
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

    /** 服务码集合求交；任一侧为 {@code null} 表示不限制。 */
    public static Set<String> intersect(Set<String> left, Set<String> right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    /** 去掉最终服务码的 &sop / &soap / &bzjson 后缀。 */
    public static String stripSuffix(String code) {
        if (code == null) {
            return null;
        }
        return SUFFIX_PATTERN.matcher(code.trim()).replaceFirst("");
    }

    /** 登录人工号是否命中行方负责人工号列表。 */
    public static boolean matchesBankOwner(ReplayConfigPersonInfo info, ReplayConfigOperator operator) {
        if (info == null || operator == null || operator.empNo() == null || operator.empNo().isBlank()) {
            return false;
        }
        return splitEmpNos(info.bankOwnerEmpNos()).contains(operator.empNo().trim());
    }

    /**
     * 审核按钮的禁用原因；返回 {@code null} 表示可审核。
     */
    public static String reviewDisabledReason(ReplayConfigPersonInfo info, ReplayConfigOperator operator,
                                              int reviewStatus) {
        if (info == null) {
            return "无审核人";
        }
        if (reviewStatus == 1) {
            return "已审核";
        }
        return matchesBankOwner(info, operator) ? null : reviewForbiddenMessage(info);
    }

    /** 行方负责人姓名（去掉工号括号、按分隔符拆分去重），用于“请联系X进行审核”。 */
    public static String reviewerContactNames(ReplayConfigPersonInfo info) {
        if (info == null || info.bankOwner() == null || info.bankOwner().isBlank()) {
            return "";
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : info.bankOwner().split("[、,，;；]")) {
            String name = part.replaceAll("[（(][^）)]*[）)]", "").trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join("、", names);
    }

    /** 无审核权限提示：带上行方负责人姓名。 */
    public static String reviewForbiddenMessage(ReplayConfigPersonInfo info) {
        String names = reviewerContactNames(info);
        return names.isEmpty() ? "没有审核权限" : "没有权限，请联系" + names + "进行审核";
    }

    private static List<String> splitEmpNos(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[、,，;；]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
